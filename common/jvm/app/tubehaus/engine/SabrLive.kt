package app.tubehaus.engine

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live streams over SABR, for when no client gives a live HLS playlist. There is no file to fill: one SABR
 * conversation per picture quality carries picture and sound together near the live edge, the newest minute of
 * segments is kept in memory, and [Sabr]'s server on 127.0.0.1 writes a live HLS playlist (fMP4) from them, so the
 * players play it as any live stream. Sound is one track per video, fed by whichever conversation runs.
 */
internal object SabrLive {
    private const val KEEP = 60
    private const val IDLE_MS = 60_000L

    private class Seg(val seq: Long, val start: Long, var dur: Double, val b: ByteArray)

    private class Track {
        @Volatile var init: ByteArray? = null
        val segs = java.util.TreeMap<Long, Seg>()
        val lock = Object()
        fun push(init: Boolean, seq: Long, start: Long, data: ByteArray) = synchronized(lock) {
            var b = data
            if (init) { if (this.init == null) this.init = data; return@synchronized }
            val cut = mediaAt(data)
            if (cut > 0) { if (this.init == null) this.init = data.copyOfRange(0, cut); b = data.copyOfRange(cut, data.size) }
            if (segs.containsKey(seq)) return@synchronized
            segs.lastEntry()?.value?.let { if (seq <= it.seq - KEEP) return@synchronized }
            // a live segment's header has no duration: the next one's start tells it
            val before = segs.lowerEntry(seq)?.value
            if (before != null && before.seq == seq - 1 && start > before.start && before.start >= 0) before.dur = (start - before.start) / 1000.0
            segs[seq] = Seg(seq, start, before?.dur ?: 2.0, b)
            while (segs.size > KEEP) segs.pollFirstEntry()
            lock.notifyAll()
        }
        fun ready() = init != null && segs.size >= 2
    }

    private class Conv(val vid: String, val index: Int) {
        @Volatile var runV = 0
        @Volatile var runA = 0
        @Volatile var running = false
        @Volatile var used = System.currentTimeMillis()
        @Volatile var fails = 0
        @Volatile var failed = false
        val picture = Track()
    }

    private val convs = ConcurrentHashMap<String, Conv>()
    private val sound = ConcurrentHashMap<String, Track>()
    private val byRun = ConcurrentHashMap<Int, Pair<Conv, Boolean>>()     // run -> (conversation, picture?)
    private val ids = AtomicInteger(1 shl 28)

    /** the live master playlist of video [id] on this device, or null when SABR can't play it live */
    fun master(id: String): String? {
        val h = Sabr.host ?: return null
        if (!h.live || !Sabr.available) return null
        return "http://127.0.0.1:${Sabr.port()}/live/master.m3u8?v=$id"
    }

    // ------------------------------------------------------------------ from the page

    fun piece(run: Int, at: Long, init: Boolean, ms: Long, data: ByteArray): Boolean {
        val (c, pic) = byRun[run] ?: return false
        (if (pic) c.picture else soundOf(c.vid)).push(init, at, ms, data)
        if (pic) c.fails = 0
        return true
    }

    fun end(run: Int, bad: Boolean, error: String?): Boolean {
        val (c, pic) = byRun.remove(run) ?: return false
        if (!pic) return true
        synchronized(c) {
            if (c.runV != run) return true
            c.running = false
            if (bad || error != null || c.picture.segs.isEmpty()) c.fails++
            if (c.fails >= 6) c.failed = true
        }
        synchronized(c.picture.lock) { c.picture.lock.notifyAll() }
        soundOf(c.vid).let { synchronized(it.lock) { it.lock.notifyAll() } }
        // still watched: carry on with a new conversation
        if (!c.failed && watched(c)) Thread {
            Thread.sleep(1000L * maxOf(1, c.fails)); run(c)
        }.apply { isDaemon = true }.start()
        return true
    }

    private fun watched(c: Conv) = System.currentTimeMillis() - c.used < IDLE_MS
    private fun soundOf(vid: String) = sound.getOrPut(vid) { Track() }

    private fun run(c: Conv) {
        val h = Sabr.host ?: return
        synchronized(c) {
            if (c.running || c.failed) return
            c.running = true
            c.runV = ids.incrementAndGet(); c.runA = ids.incrementAndGet()
            byRun[c.runV] = c to true; byRun[c.runA] = c to false
        }
        h.startLive(c.runV, c.runA, c.vid, c.index)
    }

    private fun stop(c: Conv) {
        synchronized(c) {
            if (!c.running) return
            c.running = false
            byRun.remove(c.runV); byRun.remove(c.runA)
        }
        Sabr.host?.stop(c.runV)
    }

    /** stops the conversations nobody has asked for in a minute (from [Sabr]'s sweep) */
    fun sweep() {
        val now = System.currentTimeMillis()
        for ((k, c) in convs) {
            if (now - c.used > IDLE_MS) stop(c)
            if (now - c.used > 5 * IDLE_MS) convs.remove(k)
        }
        val live = convs.values.map { it.vid }.toSet()
        sound.keys.removeIf { it !in live }
    }

    // ------------------------------------------------------------------ the formats

    private class Fmt(val index: Int, val itag: Int, val mime: String, val codecs: String, val w: Int, val h: Int, val fps: Int, val bitrate: Long)

    private fun formats(id: String): Pair<List<Fmt>, Fmt?> {
        val d = Sabr.session(id) ?: return emptyList<Fmt>() to null
        val all = d.at("streamingData.adaptiveFormats").arr.mapIndexed { i, f ->
            val mime = f.at("mimeType").str ?: ""
            Fmt(i, f.at("itag").int ?: 0, mime.substringBefore(';'), Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1) ?: "",
                f.at("width").int ?: 0, f.at("height").int ?: 0, f.at("fps").int ?: 30, f.at("bitrate").long ?: 0)
        }.filter { it.mime.endsWith("/mp4") }
        // the best avc1 one per height (the players and HLS all know it)
        val video = all.filter { it.mime.startsWith("video/") && it.codecs.startsWith("avc1") && it.h > 0 }
            .sortedWith(compareBy({ it.h }, { it.bitrate })).associateBy { it.h }.values.toList()
        val audio = all.filter { it.mime.startsWith("audio/") && it.codecs.startsWith("mp4a") }.maxByOrNull { it.bitrate }
        return video to audio
    }

    // ------------------------------------------------------------------ the playlists

    fun serve(out: OutputStream, path: String, q: Map<String, String>, reply: (Int, String, ByteArray) -> Unit) {
        val id = q["v"] ?: ""
        if (!Regex("[\\w-]{11}").matches(id)) return reply(400, "text/plain", ByteArray(0))
        val (video, audio) = formats(id)
        if (video.isEmpty() || audio == null) return reply(404, "text/plain", "no mp4 formats".toByteArray())
        val m3u = "application/vnd.apple.mpegurl"
        val part = path.substringBefore('?').removePrefix("/live/")
        if (part == "master.m3u8") {
            val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"media.m3u8?v=$id&i=a\"\n")
            // highest first: a player that takes the first one gets the best
            for (f in video.reversed())
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=${maxOf(f.bitrate, 500_000) + maxOf(audio.bitrate, 128_000)},RESOLUTION=${f.w}x${f.h},FRAME-RATE=${f.fps},CODECS=\"${f.codecs},${audio.codecs}\",AUDIO=\"a\"\n")
                    .append("media.m3u8?v=$id&i=${f.index}\n")
            return reply(200, m3u, sb.toString().toByteArray())
        }
        val which = q["i"] ?: ""
        val t: Track
        if (which == "a") {
            t = soundOf(id)
            // sound asked alone: some picture conversation must run for it (the lowest when none does)
            val any = convs.values.filter { it.vid == id && watched(it) }
            if (any.isEmpty()) conv(id, video.first().index) else any.forEach { if (!it.running) run(it) }
        } else {
            val f = video.firstOrNull { it.index.toString() == which } ?: return reply(404, "text/plain", ByteArray(0))
            t = conv(id, f.index).picture
        }
        val c = if (which == "a") null else convs["$id|$which"]
        val mime = if (which == "a") audio.mime else "video/mp4"
        when (part) {
            "media.m3u8" -> {
                if (!waitReady(t, c, id)) return reply(502, "text/plain", "live SABR failed".toByteArray())
                val segs = synchronized(t.lock) { t.segs.values.toList() }
                val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
                sb.append("#EXT-X-TARGETDURATION:${Math.ceil(segs.maxOf { it.dur }).toInt()}\n#EXT-X-MEDIA-SEQUENCE:${segs.first().seq}\n")
                sb.append("#EXT-X-MAP:URI=\"seg.mp4?v=$id&i=$which&s=init\"\n")
                for (s in segs) sb.append("#EXTINF:${"%.3f".format(java.util.Locale.ROOT, s.dur)},\nseg.mp4?v=$id&i=$which&s=${s.seq}\n")
                reply(200, m3u, sb.toString().toByteArray())
            }
            "seg.mp4" -> {
                val s = q["s"] ?: ""
                if (s == "init") {
                    if (!waitReady(t, c, id)) return reply(502, "text/plain", ByteArray(0))
                    return reply(200, mime, t.init!!)
                }
                val seq = s.toLongOrNull() ?: return reply(400, "text/plain", ByteArray(0))
                // a segment just past the newest one is waited for (a player a little ahead of the list)
                val until = System.currentTimeMillis() + 15_000
                val got = synchronized(t.lock) {
                    var x = t.segs[seq]
                    while (x == null && System.currentTimeMillis() < until && t.segs.isNotEmpty() && seq > t.segs.lastKey()) {
                        t.lock.wait(2_000); x = t.segs[seq]
                    }
                    x
                } ?: return reply(404, "text/plain", "gone".toByteArray())
                reply(200, mime, got.b)
            }
            else -> reply(404, "text/plain", ByteArray(0))
        }
    }

    private fun conv(id: String, index: Int): Conv {
        val c = convs.getOrPut("$id|$index") { Conv(id, index) }
        c.used = System.currentTimeMillis()
        if (!c.running && !c.failed) run(c)
        return c
    }

    /** waits up to half a minute for a track to have its init and two segments */
    private fun waitReady(t: Track, c: Conv?, id: String): Boolean {
        val until = System.currentTimeMillis() + 30_000
        synchronized(t.lock) {
            while (!t.ready()) {
                if (System.currentTimeMillis() > until) return false
                if (c?.failed == true || (c == null && convs.values.filter { it.vid == id }.let { l -> l.isNotEmpty() && l.all { it.failed } })) return false
                t.lock.wait(1_000)
            }
        }
        return true
    }

    /** where the media boxes start in an fMP4 piece that carries its own init (ftyp + moov), else 0 */
    private fun mediaAt(b: ByteArray): Int {
        var o = 0
        while (o + 8 <= b.size) {
            var size = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
            val type = String(b, o + 4, 4, Charsets.ISO_8859_1)
            if (type in MEDIA_BOX) return o
            if (size == 1L && o + 16 <= b.size) { size = 0; for (i in 8 until 16) size = (size shl 8) or (b[o + i].toLong() and 0xff) }
            if (size < 8) break
            o += size.toInt()
        }
        return 0
    }
    private val MEDIA_BOX = setOf("moof", "styp", "sidx", "emsg", "prft")
}
