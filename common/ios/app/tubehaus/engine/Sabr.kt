@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.tubehaus.engine

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.AF_INET
import kotlinx.cinterop.IntVar
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.mkdir
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.unlink
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * The iPad's way to play what only plays as YouTube's web player does (SABR, see the JVM twin in common/jvm),
 * and what comes without an HLS list (YouTube Kids, the TV client): AVPlayer plays only HLS, so a small web
 * server on 127.0.0.1 gives it HLS lists made from each mp4 format's index ([Hls]), and the bytes behind them:
 * from the SABR pieces kept in a file in the cache (where each belongs in YouTube's file), or from YouTube's
 * address of the format.
 */
object Sabr {
    @Volatile var host: SabrHost? = null
    /** the folder the formats are kept in while they play; set by the app (in Caches) */
    @Volatile var dir: String? = null
    /** every video the SABR way (a test copy) */
    @Volatile var only = false
    val available get() = host != null && dir != null

    private const val AHEAD = 24L shl 20
    private const val NEAR = 6L shl 20
    private const val IDLE_MS = 20 * 60_000L
    private const val DISK_MAX = 2L shl 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = NSLock()
    private inline fun <T> locked(f: () -> T): T { lock.lock(); try { return f() } finally { lock.unlock() } }

    // ------------------------------------------------------------------ what plays: a stream and its formats

    /** a format's bytes: SABR pieces on disk ([video], [index]), or YouTube's address ([url]) */
    private class Source(val format: Format, val video: String, val index: Int, val url: String?, val ua: String?)

    /** a stream served here (by key): its formats, and their media lists once made */
    private class Served(val stream: Stream, val sources: List<Source>, val at: Long) {
        val lists = HashMap<Int, String>()
    }

    private val served = HashMap<String, Served>()
    private val sessions = HashMap<String, Pair<JsonElement, Long>>()

    /** the stream of video [id] the SABR way (the watch page's answer, its formats served here); its [Stream.hls] is the master list */
    suspend fun stream(id: String): Stream {
        val h = host ?: throw YtError("No SABR", YtError.Kind.UNAVAILABLE)
        if (dir == null) throw YtError("No SABR", YtError.Kind.UNAVAILABLE)
        val cached = locked { sessions[id]?.takeIf { Sys.nowMs() - it.second < 4 * 3600_000L }?.first }
        val d = cached ?: (h.prepare(id, InnerTube.hl, InnerTube.gl) ?: throw YtError("No SABR", YtError.Kind.UNAVAILABLE)).let { parse(it) }
            .also { d -> locked { sessions[id] = d to Sys.nowMs(); if (sessions.size > 40) sessions.entries.minByOrNull { it.value.second }?.let { sessions.remove(it.key) } } }
        val ps = d.at("playabilityStatus")
        if (ps.at("status").str != "OK") {
            val reason = ps.at("reason").str ?: "This video is unavailable"
            throw YtError(reason, if (ps.at("status").str == "LOGIN_REQUIRED" && reason.contains("age", true)) YtError.Kind.AGE else YtError.Kind.UNAVAILABLE)
        }
        val raw = d.at("streamingData.adaptiveFormats").arr
        val formats = raw.mapIndexedNotNull { i, f -> format(f, "sabr:$i")?.let { i to it } }.filter { !it.second.drc }
        val vd = d.at("videoDetails")
        val s = Stream(
            id = id, title = vd.at("title").str ?: "", author = vd.at("author").str ?: "", channelId = vd.at("channelId").str ?: "",
            lengthSec = vd.at("lengthSeconds").int ?: 0, views = vd.at("viewCount").long ?: 0, description = vd.at("shortDescription").str ?: "",
            keywords = vd.at("keywords").arr.mapNotNull { it.str }, live = vd.at("isLive").bool, hls = null,
            video = formats.map { it.second }.filter { it.isVideo }, audio = originalFirst(formats.map { it.second }.filter { !it.isVideo }),
            captions = d.at("captions.playerCaptionsTracklistRenderer.captionTracks").arr.mapNotNull { t ->
                val url = t.at("baseUrl").str ?: return@mapNotNull null
                Caption(t.at("languageCode").str ?: "", t.at("name").text ?: "", "http://127.0.0.1:${port()}/api/timedtext?th=1&v=$id&u=" + urlEncode(url), t.at("kind").str == "asr")
            },
            expiresAt = Sys.nowMs() / 1000 + 4 * 3600, client = Clients.WEB_SABR.name,
            storyboard = d.at("storyboards.playerStoryboardSpecRenderer.spec").str,
        )
        if (!Hls.playable(s)) throw YtError("No stream the iPad can play", YtError.Kind.UNAVAILABLE)
        val sources = formats.map { (i, f) -> Source(f, id, i, null, null) }
        return serve(s, sources)
    }

    /** [s] with an HLS list made here from its formats, when it has none (YouTube Kids, the TV client); else [s] */
    fun withHls(s: Stream): Stream {
        if (s.hls != null || !Hls.playable(s)) return s
        val ua = listOf(Clients.KIDS, Clients.TV, Clients.ANDROID_VR, Clients.VISIONOS, Clients.WEB).firstOrNull { it.name == s.client }?.userAgent
        return serve(s, (s.video + s.audio).map { Source(it, s.id, -1, it.url, ua) })
    }

    private fun serve(s: Stream, sources: List<Source>): Stream {
        val key = s.id + "-" + Sys.nowMs().toString(36)
        locked {
            served[key] = Served(s, sources, Sys.nowMs())
            if (served.size > 20) served.entries.minByOrNull { it.value.at }?.let { served.remove(it.key) }
        }
        val base = "http://127.0.0.1:${port()}"
        // the formats' own addresses here too (the stats screen and the like show them)
        fun local(f: Format) = "$base/f?k=$key&i=${sources.indexOfFirst { it.format === f }}"
        return s.copy(hls = "$base/hls?k=$key", video = s.video.map { it.copy(url = local(it)) }, audio = s.audio.map { it.copy(url = local(it)) })
    }

    private fun format(f: JsonElement, url: String): Format? {
        val mimeFull = f.at("mimeType").str ?: return null
        fun range(e: JsonElement?): LongRange? { val a = e.at("start").long ?: return null; val b = e.at("end").long ?: return null; return a..b }
        return Format(
            itag = f.at("itag").int ?: 0, url = url, mime = mimeFull.substringBefore(';').trim(),
            codecs = mimeFull.substringAfter("codecs=\"", "").substringBefore('"'),
            width = f.at("width").int ?: 0, height = f.at("height").int ?: 0, fps = f.at("fps").int ?: 0, bitrate = f.at("bitrate").int ?: 0,
            contentLength = f.at("contentLength").long ?: 0, initRange = range(f.at("initRange")), indexRange = range(f.at("indexRange")),
            qualityLabel = f.at("qualityLabel").str ?: "", sampleRate = f.at("audioSampleRate").int ?: 0, channels = f.at("audioChannels").int ?: 2,
            audioTrack = f.at("audioTrack.id").str, audioTrackName = f.at("audioTrack.displayName").str,
            audioDefault = f.at("audioTrack.audioIsDefault").str?.let { it == "true" } ?: true, drc = f.at("isDrc").bool,
        )
    }

    // ------------------------------------------------------------------ SABR pieces on disk

    private class Run(val id: Int, val from: Long, var head: Long, val back: Int) { var media = 0 }

    private class Track(val video: String, val index: Int, val length: Long, val durationMs: Long, val path: String) {
        val ranges = ArrayList<LongArray>()
        val points = ArrayList<LongArray>()
        var headEnd = 0L
        var run: Run? = null
        var want = 0L
        var fails = 0
        var back = 0
        var failed = false
        var used = Sys.nowMs()
        val fd = open(path, O_RDWR or O_CREAT, 420)
        val cond = NSCondition()

        fun onDisk(n: Long): Long { for (r in ranges) { if (n < r[0]) return 0; if (n < r[1]) return r[1] }; return 0 }
        fun add(a: Long, b: Long) {
            ranges.add(longArrayOf(a, b)); ranges.sortBy { it[0] }
            val out = ArrayList<LongArray>()
            for (r in ranges) { val l = out.lastOrNull(); if (l != null && r[0] <= l[1]) l[1] = max(l[1], r[1]) else out.add(longArrayOf(r[0], r[1])) }
            ranges.clear(); ranges.addAll(out)
        }
        fun size() = ranges.sumOf { it[1] - it[0] }
        fun timeOf(n: Long): Long {
            if (n <= headEnd) return 0
            var lo: LongArray? = null; var hi: LongArray? = null
            for (p in points) { if (p[0] <= n && (lo == null || p[0] > lo[0])) lo = p; if (p[0] > n && (hi == null || p[0] < hi[0])) hi = p }
            val a = lo ?: longArrayOf(headEnd, 0); val b = hi ?: longArrayOf(max(length, 1), durationMs)
            val ms = a[1] + (b[1] - a[1]) * ((n - a[0]).toDouble() / max(1, b[0] - a[0]))
            return max(0, ms.toLong() - 2000)
        }
        inline fun <T> with(f: () -> T): T { cond.lock(); try { return f() } finally { cond.unlock() } }
    }

    private val tracks = HashMap<String, Track>()
    private val runs = HashMap<Int, Track>()
    private var runIds = 0

    private fun track(video: String, index: Int): Track? = locked {
        val key = "$video|$index"
        tracks[key]?.let {
            if (!it.failed) { it.used = Sys.nowMs(); return@locked it }
            tracks.remove(key); close(it.fd); unlink(it.path)
        }
        val f = sessions[video]?.first?.at("streamingData.adaptiveFormats").arr.getOrNull(index) ?: return@locked null
        val base = dir ?: return@locked null
        mkdir(base, 493.convert())
        val path = "$base/$video-$index.part"
        unlink(path)
        Track(video, index, f.at("contentLength").long ?: 0, f.at("approxDurationMs").long ?: 0, path).also { tracks[key] = it }
    }

    /** a piece of fetch [run]: [data] belongs at byte [at] of its format ([init]: the header at its start) */
    fun piece(run: Int, at: Long, init: Boolean, ms: Long, data: ByteArray) {
        val t = locked { runs[run] } ?: run { host?.stop(run); return }
        t.with {
            val r = t.run
            if (r == null || r.id != run) { host?.stop(run); return }
            if (init) t.headEnd = max(t.headEnd, data.size.toLong())
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
            if (data.isNotEmpty()) data.usePinned { pwrite(t.fd, it.addressOf(0), data.size.convert(), at) }
            t.add(at, at + data.size)
            if (!init) { r.head = at + data.size; t.back = 0; t.fails = 0 }
            t.cond.broadcast()
            if (!init && (t.onDisk(r.head) - r.head > NEAR || (t.length > 0 && t.onDisk(0) >= t.length))) end(t, r)
        }
    }

    /** fetch [run] is over; [bad]: YouTube turned its token down (the page makes a new one) */
    fun end(run: Int, bad: Boolean, error: String?) {
        val t = locked { runs.remove(run) } ?: return
        t.with {
            if (t.run?.id == run) t.run = null
            if (bad || error != null) {
                t.fails++
                if (t.fails >= 6) t.failed = true
                if (t.fails == 3) locked { sessions.remove(t.video) }
            }
            t.cond.broadcast()
        }
    }

    private fun end(t: Track, r: Run) {
        if (t.run === r) t.run = null
        locked { runs.remove(r.id) }
        host?.stop(r.id)
    }

    /** byte [n] is wanted: a fetch that brings it, unless one is on its way (holding t.cond); [ahead]: how far it may go */
    private fun ensure(t: Track, n: Long, ahead: Long = AHEAD) {
        if (t.onDisk(n) > 0 || t.failed) return
        val h = host ?: return
        val r = t.run
        if (r != null && r.from <= n && n - max(r.head, r.from) < NEAR) return
        if (r != null) end(t, r)
        val id = locked { ++runIds }
        val run = Run(id, n, n, t.back)
        t.run = run
        locked { runs[id] = t }
        h.start(id, t.video, t.index, max(0, t.timeOf(n) - t.back * 1000L))
        h.allow(id, max(t.want, n) + ahead)
    }

    private fun want(t: Track, n: Long) {
        if (n >= t.want && n - t.want < (1L shl 20)) return
        t.want = n
        t.run?.let { host?.allow(it.id, n + AHEAD) }
    }

    /** waits for byte [n] on disk; a minute and a half with nothing arriving is a failure */
    private fun until(t: Track, n: Long, ahead: Long = AHEAD): Boolean = t.with {
        var quiet = Sys.nowMs()
        var seen = t.size()
        while (t.onDisk(n) == 0L) {
            if (t.failed) return@with false
            ensure(t, n, ahead)
            t.cond.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(5.0))
            val now = t.size()
            if (now != seen) { seen = now; quiet = Sys.nowMs() }
            else if (Sys.nowMs() - quiet > 90_000) return@with false
        }
        true
    }

    /** bytes [a, b] of a SABR format, waiting for them */
    private fun sabrBytes(t: Track, a: Long, b: Long, ahead: Long = AHEAD): ByteArray? {
        val out = ByteArray((b - a + 1).toInt())
        var pos = a
        while (pos <= b) {
            t.used = Sys.nowMs()
            t.with { want(t, pos) }
            if (!until(t, pos, ahead)) return null
            val n = (min(b + 1, t.with { t.onDisk(pos) }) - pos).toInt()
            val got = out.usePinned { pread(t.fd, it.addressOf((pos - a).toInt()), n.convert(), pos) }.toInt()
            if (got <= 0) return null
            pos += got
        }
        return out
    }

    // ------------------------------------------------------------------ the server on 127.0.0.1

    @Volatile private var listening = -1
    @Volatile private var listenPort = 0

    private fun port(): Int = locked {
        if (listening >= 0) return@locked listenPort
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            val one = alloc<IntVar>(); one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = 0x0100007Fu                     // 127.0.0.1, in network order
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, 32) != 0) { close(fd); return@locked 0 }
            val len = alloc<socklen_tVar>(); len.value = sizeOf<sockaddr_in>().convert()
            getsockname(fd, addr.ptr.reinterpret(), len.ptr)
            val p = addr.sin_port.toInt()
            listenPort = ((p and 0xff) shl 8) or ((p shr 8) and 0xff)
            listening = fd
            scope.launch { acceptLoop(fd) }
            scope.launch { while (true) { delay(60_000); sweep() } }
        }
        listenPort
    }

    private fun acceptLoop(fd: Int) {
        while (true) {
            val c = accept(fd, null, null)
            if (c < 0) {
                // the system closed it (the app was away): the next address made opens a new one
                locked { if (listening == fd) listening = -1 }
                close(fd)
                return
            }
            memScoped {
                val one = alloc<IntVar>(); one.value = 1
                setsockopt(c, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())   // a player that hangs up doesn't end the app
            }
            scope.launch { try { Conn(c).serve() } catch (_: Throwable) {} finally { close(c) } }
        }
    }

    private fun sweep() {
        val now = Sys.nowMs()
        // the ones to drop, chosen under the lock; dropped outside it (a track's own lock comes first elsewhere)
        val gone = locked {
            var total = tracks.values.sumOf { it.size() }
            val out = ArrayList<Track>()
            for ((k, t) in tracks.entries.sortedBy { it.value.used }.toList()) {
                if (now - t.used > IDLE_MS || total > DISK_MAX) { total -= t.size(); tracks.remove(k); out.add(t) }
            }
            served.entries.removeAll { now - it.value.at > 6 * 3600_000L }
            sessions.entries.removeAll { now - it.value.second > 4 * 3600_000L }
            out
        }
        for (t in gone) {
            val r = t.with { val r = t.run; t.run = null; t.failed = true; t.cond.broadcast(); r }
            if (r != null) { locked { runs.remove(r.id) }; host?.stop(r.id) }
            close(t.fd); unlink(t.path)
        }
    }

    private class Conn(val fd: Int) {
        private val buf = ByteArray(16384)
        private var have = 0
        private var pos = 0

        private fun byte(): Int {
            if (pos == have) {
                val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), 0) }.toInt()
                if (n <= 0) return -1
                have = n; pos = 0
            }
            return buf[pos++].toInt() and 0xff
        }

        private fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = byte()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 16384) return null
            }
        }

        fun write(b: ByteArray): Boolean {
            var off = 0
            while (off < b.size) {
                val n = b.usePinned { send(fd, it.addressOf(off), (b.size - off).convert(), 0) }.toInt()
                if (n <= 0) return false
                off += n
            }
            return true
        }

        fun serve() {
            while (true) {
                val req = line() ?: return
                val parts = req.split(' ')
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val h = line() ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val path = parts[1]
                val q = path.substringAfter('?', "").split('&').mapNotNull { kv ->
                    val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to urlDecode(kv.substring(i + 1))
                }.toMap()
                val ok = when {
                    path.startsWith("/hls?") -> master(q)
                    path.startsWith("/m?") -> mediaList(q)
                    path.startsWith("/f?") -> bytes(q, headers["range"], parts[0] == "HEAD")
                    path.startsWith("/api/timedtext?") -> caption(q)
                    else -> reply(404, "text/plain", "not found".encodeToByteArray())
                }
                if (!ok || headers["connection"].equals("close", true)) return
            }
        }

        private fun reply(code: Int, type: String, body: ByteArray, extra: String = ""): Boolean {
            val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "Bad Gateway" }
            return write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$extra\r\n".encodeToByteArray()) && write(body)
        }

        private fun servedOf(q: Map<String, String>) = locked { served[q["k"] ?: ""] }

        private fun master(q: Map<String, String>): Boolean {
            val sv = servedOf(q) ?: return reply(404, "text/plain", ByteArray(0))
            val k = q["k"]!!
            val text = Hls.master(sv.stream) { f -> "/m?k=$k&i=" + sv.sources.indexOfFirst { it.format.itag == f.itag && it.format.audioTrack == f.audioTrack } }
            return reply(200, "application/vnd.apple.mpegurl", text.encodeToByteArray())
        }

        private fun mediaList(q: Map<String, String>): Boolean {
            val sv = servedOf(q) ?: return reply(404, "text/plain", ByteArray(0))
            val i = q["i"]?.toIntOrNull() ?: -1
            val src = sv.sources.getOrNull(i) ?: return reply(404, "text/plain", ByteArray(0))
            val text = locked { sv.lists[i] } ?: run {
                val r = src.format.indexRange ?: return reply(502, "text/plain", ByteArray(0))
                // the index alone: a SABR fetch for it goes no further than a little past it
                val sidx = read(src, r.first, r.last, 2L shl 20) ?: return reply(502, "text/plain", ByteArray(0))
                val segs = Hls.segments(src.format, sidx) ?: return reply(502, "text/plain", ByteArray(0))
                Hls.media(src.format, "/f?k=${q["k"]}&i=$i", segs).also { t -> locked { sv.lists[i] = t } }
            }
            return reply(200, "application/vnd.apple.mpegurl", text.encodeToByteArray())
        }

        private fun bytes(q: Map<String, String>, range: String?, head: Boolean): Boolean {
            val sv = servedOf(q) ?: return reply(404, "text/plain", ByteArray(0))
            val src = sv.sources.getOrNull(q["i"]?.toIntOrNull() ?: -1) ?: return reply(404, "text/plain", ByteArray(0))
            val len = src.format.contentLength
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0
            var end = m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1)
            if (len > 0 && end >= len) end = len - 1
            if (len > 0 && start >= len) return reply(416, "text/plain", ByteArray(0), "Content-Range: bytes */$len\r\n")
            val type = src.format.mime
            if (head) return write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n\r\n".encodeToByteArray())
            // in pieces of up to 4 MB, so a long range starts flowing at once
            var pos = start
            var first = true
            while (pos <= end) {
                val to = min(end, pos + (4L shl 20) - 1)
                val data = read(src, pos, to) ?: return if (first) reply(502, "text/plain", ByteArray(0)) else false
                if (first) {
                    val h = (if (m != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/$len\r\n" else "HTTP/1.1 200 OK\r\n") +
                        "Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n\r\n"
                    if (!write(h.encodeToByteArray())) return false
                    first = false
                }
                if (!write(data)) return false
                pos = to + 1
            }
            return true
        }

        private fun caption(q: Map<String, String>): Boolean {
            val u = q["u"] ?: ""
            if (!u.startsWith("https://www.youtube.com/api/timedtext?")) return reply(404, "text/plain", ByteArray(0))
            val full = u + (q["fmt"]?.let { "&fmt=$it" } ?: "") + (q["tlang"]?.let { "&tlang=" + urlEncode(it) } ?: "")
            val text = runCatching { runBlocking { host?.caption(q["v"] ?: "", full) } }.getOrNull()
            if (text.isNullOrEmpty()) return reply(502, "text/plain", ByteArray(0))
            return reply(200, "text/vtt; charset=utf-8", text.encodeToByteArray())
        }
    }

    /** bytes [a, b] of a source: from the SABR file, or from YouTube's address */
    private fun read(src: Source, a: Long, b: Long, ahead: Long = AHEAD): ByteArray? {
        if (src.url == null) {
            val t = track(src.video, src.index) ?: return null
            return sabrBytes(t, a, b, ahead)
        }
        return runCatching {
            runBlocking {
                val h = buildMap { put("Range", "bytes=$a-$b"); src.ua?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) } }
                val (code, data) = Net.bytes(src.url, h)
                if (code == 200 || code == 206) data else null
            }
        }.getOrNull()
    }
}
