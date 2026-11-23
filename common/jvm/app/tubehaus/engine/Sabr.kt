package app.tubehaus.engine

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * SABR: how YouTube's web player gets its video. No stream address, but a conversation with the video server,
 * and a web token without which it stops after about a minute. Used when no client with plain addresses plays
 * a video (see [YouTube]).
 *
 * The players here (ExoPlayer, mpv) play addresses with byte ranges, so each format is made a file again:
 * every SABR piece says where in YouTube's file it belongs, and is written to that place in a file in the
 * cache (sparse: only what came takes room). A small web server on 127.0.0.1 answers ranges from it, and
 * where a range is missing, asks the page for SABR from the time that byte plays, so a jump ahead does not
 * wait for everything before it. It reads at most [AHEAD] past what a player asked for.
 */
object Sabr {
    @Volatile var host: SabrHost? = null
    /** where the formats are kept while they play; set by the app (a cache folder) */
    @Volatile var dir: File? = null
    /** every video the SABR way (a test build) */
    @Volatile var only = false
    val available get() = host != null && dir != null
    /** when the page last gave a playable answer: while it is recent the host is up and every video goes this way first */
    @Volatile var okAt = 0L
    val warm get() = available && System.currentTimeMillis() - okAt < 30 * 60_000L
    /** the host closed its page (idle): fetches on their way there are over, and start again when wanted */
    fun hostGone() {
        cool()
        for (run in runs.keys.toList()) end(run, false, null)
        SabrLive.hostGone()
    }
    /** a SABR fetch failed for good: the usual clients first again until the page answers well */
    fun cool() { okAt = 0 }

    private const val AHEAD = 24L shl 20
    private const val NEAR = 6L shl 20
    private const val IDLE_MS = 20 * 60_000L
    private const val DISK_MAX = 2L shl 30

    private class Run(val id: Int, val from: Long, @Volatile var head: Long, val back: Int) {
        @Volatile var media = 0
    }

    private class Track(val video: String, val index: Int, val length: Long, val durationMs: Long, val file: File) {
        val ranges = ArrayList<LongArray>()          // on disk: [start, end) sorted, merged
        val points = ArrayList<LongArray>()          // [byte, ms] of the pieces seen
        var headEnd = 0L
        /** where each segment starts, from the header piece (it carries the index): exact times, no guessing */
        @Volatile var toc: SabrIndex? = null
        @Volatile var run: Run? = null
        @Volatile var want = 0L
        var fails = 0
        var back = 0
        @Volatile var failed = false
        @Volatile var used = System.currentTimeMillis()
        val ch: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
        val lock = Object()

        fun onDisk(n: Long): Long { for (r in ranges) { if (n < r[0]) return 0; if (n < r[1]) return r[1] }; return 0 }
        fun add(a: Long, b: Long) {
            ranges.add(longArrayOf(a, b)); ranges.sortBy { it[0] }
            val out = ArrayList<LongArray>()
            for (r in ranges) { val l = out.lastOrNull(); if (l != null && r[0] <= l[1]) l[1] = max(l[1], r[1]) else out.add(longArrayOf(r[0], r[1])) }
            ranges.clear(); ranges.addAll(out)
        }
        fun size() = ranges.sumOf { it[1] - it[0] }
        /** the start time of the piece holding byte [n]: from the pieces seen around it, else the average bitrate */
        fun timeOf(n: Long): Long {
            if (n <= headEnd) return 0
            toc?.let { return it.timeOf(n) }
            var lo: LongArray? = null; var hi: LongArray? = null
            for (p in points) { if (p[0] <= n && (lo == null || p[0] > lo[0])) lo = p; if (p[0] > n && (hi == null || p[0] < hi[0])) hi = p }
            val a = lo ?: longArrayOf(headEnd, 0); val b = hi ?: longArrayOf(max(length, 1), durationMs)
            val ms = a[1] + (b[1] - a[1]) * ((n - a[0]).toDouble() / max(1, b[0] - a[0]))
            return max(0, ms.toLong() - 2000)
        }
    }

    private class Session(val d: kotlinx.serialization.json.JsonElement, val at: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val tracks = ConcurrentHashMap<String, Track>()
    private val runs = ConcurrentHashMap<Int, Track>()
    private val runIds = AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null

    // ------------------------------------------------------------------ the video answer

    /** the watch page's player answer, its formats' addresses on this device's server; null when SABR can't play it */
    suspend fun prepare(id: String): kotlinx.serialization.json.JsonElement? {
        val h = host ?: return null
        if (dir == null) return null
        sessions[id]?.takeIf { System.currentTimeMillis() - it.at < 4 * 3600_000L }?.let { return it.d }
        val text = h.prepare(id, InnerTube.hl, InnerTube.gl) ?: return null
        val d = parse(text)
        if (d.at("playabilityStatus.status").str == "OK") okAt = System.currentTimeMillis()
        sessions[id] = Session(d, System.currentTimeMillis())
        if (sessions.size > 40) sessions.entries.minByOrNull { it.value.at }?.let { sessions.remove(it.key) }
        return d
    }

    /** the address of format [index] of video [id] on this device */
    fun url(id: String, index: Int) = "http://127.0.0.1:${port()}/sabr?v=$id&i=$index"

    /** a caption address of a SABR video, through this device (the page adds the token) */
    fun captionUrl(id: String, url: String) = "http://127.0.0.1:${port()}/api/timedtext?th=1&v=$id&u=" + URLEncoder.encode(url, "UTF-8")

    // ------------------------------------------------------------------ pieces from the page

    /** a piece of fetch [run]: [data] belongs at byte [at] of its format ([init]: the header at the start) */
    fun piece(run: Int, at: Long, init: Boolean, ms: Long, data: ByteArray) {
        if (SabrLive.piece(run, at, init, ms, data)) return
        val t = runs[run] ?: run { host?.stop(run); return }
        synchronized(t.lock) {
            val r = t.run
            if (r == null || r.id != run) { host?.stop(run); return }
            if (init) {
                t.headEnd = max(t.headEnd, data.size.toLong())
                if (t.toc == null) t.toc = SabrIndex.parse(data)
            }
            else {
                if (ms >= 0) t.points.add(longArrayOf(at, ms))
                if (r.media++ == 0 && at > max(r.from, t.headEnd)) {
                    // it started after the byte wanted (the time was guessed late): again, earlier
                    t.back = if (r.back == 0) 20 else r.back * 3
                    end(t, r)
                    ensure(t, r.from)
                    return
                }
            }
            t.ch.write(ByteBuffer.wrap(data), at)
            t.add(at, at + data.size)
            if (!init) { r.head = at + data.size; t.back = 0; t.fails = 0 }
            t.lock.notifyAll()
            // caught up with bytes already on disk: this fetch has nothing new to give
            if (!init && (t.onDisk(r.head) - r.head > NEAR || (t.length > 0 && t.onDisk(0) >= t.length))) end(t, r)
        }
    }

    /** fetch [run] is over; [bad]: YouTube turned its token down (the page makes a new one) */
    fun end(run: Int, bad: Boolean, error: String?) {
        if (SabrLive.end(run, bad, error)) return
        val t = runs.remove(run) ?: return
        synchronized(t.lock) {
            if (t.run?.id == run) t.run = null
            if (bad || error != null) {
                t.fails++
                if (t.fails >= 6) { t.failed = true; cool() }
                if (t.fails == 3) sessions.remove(t.video)
            }
            t.lock.notifyAll()
        }
    }

    private fun end(t: Track, r: Run) {
        if (t.run === r) t.run = null
        runs.remove(r.id)
        host?.stop(r.id)
    }

    /** byte [n] is wanted: a fetch that will bring it, unless one is on its way (call holding t.lock) */
    private fun ensure(t: Track, n: Long) {
        if (t.onDisk(n) > 0 || t.failed) return
        val h = host ?: return
        val r = t.run
        // a fetch started a little earlier than its byte is still on its way there while it catches up
        if (r != null && r.from <= n && n - max(r.head, r.from) < NEAR) return
        if (r != null) end(t, r)
        val id = runIds.incrementAndGet()
        val run = Run(id, n, n, t.back)
        t.run = run
        runs[id] = t
        h.start(id, t.video, t.index, max(0, t.timeOf(n) - t.back * 1000L))
        h.allow(id, max(t.want, n) + AHEAD)
    }

    private fun want(t: Track, n: Long) {
        // told again only when it moved a megabyte on, or back
        if (n >= t.want && n - t.want < (1L shl 20)) return
        t.want = n
        t.run?.let { host?.allow(it.id, n + AHEAD) }
    }

    /** waits for byte [n] to be on disk; a minute and a half with nothing arriving is a failure */
    private fun until(t: Track, n: Long) {
        synchronized(t.lock) {
            var quiet = System.currentTimeMillis()
            var seen = t.size()
            while (t.onDisk(n) == 0L) {
                if (t.failed) throw IOException("SABR failed")
                ensure(t, n)
                t.lock.wait(5_000)
                val now = t.size()
                if (now != seen) { seen = now; quiet = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - quiet > 90_000) throw IOException("SABR stalled")
            }
        }
    }

    private fun track(id: String, index: Int): Track? {
        val key = "$id|$index"
        tracks[key]?.let {
            if (!it.failed) { it.used = System.currentTimeMillis(); return it }
            // it failed earlier: played again, it starts over
            tracks.remove(key, it); runCatching { it.ch.close() }; it.file.delete()
        }
        val d = sessions[id]?.d ?: return null
        val f = d.at("streamingData.adaptiveFormats").arr.getOrNull(index) ?: return null
        val base = dir ?: return null
        base.mkdirs()
        val t = Track(id, index, f.at("contentLength").long ?: f.at("contentLength").str?.toLongOrNull() ?: 0,
            f.at("approxDurationMs").str?.toLongOrNull() ?: f.at("approxDurationMs").long ?: 0, File(base, "$id-$index.part").apply { delete() })
        return tracks.putIfAbsent(key, t) ?: t
    }

    // ------------------------------------------------------------------ the server on 127.0.0.1

    /** the watch page's answer already asked for video [id] */
    internal fun session(id: String) = sessions[id]?.d

    @Synchronized internal fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        // 127.0.0.1 itself: Android's loopback address is ::1, which the addresses here don't name
        val s = ServerSocket(0, 32, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = s
        thread(isDaemon = true, name = "sabr-server") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true, name = "sabr-serve") { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        thread(isDaemon = true, name = "sabr-sweep") { while (!s.isClosed) { Thread.sleep(60_000); sweep() } }
        return s.localPort
    }

    private fun sweep() {
        SabrLive.sweep()
        val now = System.currentTimeMillis()
        var total = tracks.values.sumOf { it.size() }
        // at most 2 GB, and never more than a tenth of the free room on the disk
        val cap = min(DISK_MAX, ((dir?.usableSpace ?: DISK_MAX) + total) / 10)
        for ((k, t) in tracks.entries.sortedBy { it.value.used }) {
            if (now - t.used > IDLE_MS || total > cap) {
                total -= t.size()
                tracks.remove(k)
                synchronized(t.lock) { t.run?.let { end(t, it) }; t.failed = true; t.lock.notifyAll() }
                runCatching { t.ch.close() }
                t.file.delete()
            }
        }
        sessions.entries.removeIf { now - it.value.at > 4 * 3600_000L }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 120_000
        val inp = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        while (true) {
            val line = readLine(inp) ?: return
            val parts = line.split(' ')
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(inp) ?: return
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val path = parts[1]
            val q = query(path)
            when {
                path.startsWith("/sabr?") -> serveFormat(out, parts[0] == "HEAD", q, headers["range"])
                path.startsWith("/api/timedtext?") -> serveCaption(out, q)
                path.startsWith("/live/") -> SabrLive.serve(out, path, q) { code, type, body -> reply(out, code, type, body) }
                else -> reply(out, 404, "text/plain", "not found".toByteArray())
            }
            out.flush()
            if (headers["connection"].equals("close", true)) return
        }
    }

    private fun serveFormat(out: OutputStream, head: Boolean, q: Map<String, String>, range: String?) {
        val id = q["v"] ?: ""; val index = q["i"]?.toIntOrNull() ?: -1
        val t = track(id, index) ?: return reply(out, 404, "text/plain", "no such format".toByteArray())
        val len = t.length
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0
        var end = m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1)
        if (len > 0 && end >= len) end = len - 1
        if (len > 0 && start >= len) return reply(out, 416, "text/plain", ByteArray(0), "Content-Range: bytes */$len\r\n")
        val mime = sessions[id]?.d?.at("streamingData.adaptiveFormats")?.arr?.getOrNull(index)?.at("mimeType")?.str?.substringBefore(';') ?: "application/octet-stream"
        synchronized(t.lock) { want(t, start) }
        try { until(t, start) } catch (e: IOException) { return reply(out, 502, "text/plain", (e.message ?: "").toByteArray()) }
        val sb = StringBuilder()
        sb.append(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
        if (m != null) sb.append("Content-Range: bytes $start-$end/$len\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (head) return
        var pos = start
        val buf = ByteBuffer.allocate(256 * 1024)
        while (pos <= end) {
            t.used = System.currentTimeMillis()
            synchronized(t.lock) { want(t, pos) }
            until(t, pos)
            val n = min(end + 1, synchronized(t.lock) { t.onDisk(pos) }) - pos
            buf.clear(); buf.limit(min(n, buf.capacity().toLong()).toInt())
            val got = t.ch.read(buf, pos)
            if (got <= 0) continue
            out.write(buf.array(), 0, got)
            pos += got
        }
    }

    private fun serveCaption(out: OutputStream, q: Map<String, String>) {
        val id = q["v"] ?: ""; val u = q["u"] ?: ""
        if (!u.startsWith("https://www.youtube.com/api/timedtext?")) return reply(out, 400, "text/plain", ByteArray(0))
        val full = u + (q["fmt"]?.let { "&fmt=$it" } ?: "") + (q["tlang"]?.let { "&tlang=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val text = runCatching { runBlocking { host?.caption(id, full) } }.getOrNull()
        if (text.isNullOrEmpty()) return reply(out, 502, "text/plain", ByteArray(0))
        reply(out, 200, "text/vtt; charset=utf-8", text.toByteArray())
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 416 to "Range Not Satisfiable", 502 to "Bad Gateway")[code] ?: "Error"
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray())
        out.write(body)
    }

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()

    private fun readLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
    }
}
