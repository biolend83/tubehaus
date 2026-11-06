package app.tubehaus.cast

import app.tubehaus.engine.Net
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * A small web server on the local network that the TV plays from. The TV can't fetch the
 * streams itself: YouTube gave them to this device's address, a connected TubeHaus server
 * wants its login, saved videos are files here, and the TV's player (a web page) needs CORS
 * headers YouTube doesn't send. So the TV gets addresses on this device, and this fetches
 * each piece for it (in 10 MB ranges, which YouTube serves at full speed).
 *
 * Every address is a random-looking key (a hash of what it stands for), so nothing else on
 * the network can guess one; they are forgotten when casting stops.
 */
internal object CastServer {
    private const val CHUNK = 10L shl 20

    private class Item(val url: String? = null, val text: String? = null, val type: String = "application/octet-stream")

    private val items = ConcurrentHashMap<String, Item>()
    @Volatile private var server: ServerSocket? = null

    /** http://<this device on the TV's network>:<port>, the start of every address handed to [device] */
    fun base(device: String): String {
        val ip = DatagramSocket().use { it.connect(InetAddress.getByName(device), 8009); it.localAddress.hostAddress }
        return "http://$ip:${port()}".also { currentBase = it }
    }

    /** an address on [base] that serves [url] (YouTube, the TubeHaus server, or a file: of a saved video) */
    fun proxy(base: String, url: String): String {
        val k = key(url)
        items[k] = Item(url = url)
        return "$base/u/$k"
    }

    /** an address on [base] that serves [text] as [type]; [ext] only makes the address readable */
    fun text(base: String, text: String, type: String, ext: String): String {
        val k = key(text)
        items[k] = Item(text = text, type = type)
        return "$base/t/$k.$ext"
    }

    fun forget() = items.clear()

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(("tubehaus-cast:" + s).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    @Synchronized private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 64)
        server = s
        thread(isDaemon = true, name = "cast-server") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true, name = "cast-serve") { serve(c) }
            }
        }
        return s.localPort
    }

    // ------------------------------------------------------------------ HTTP

    private fun serve(c: Socket) {
        try {
            c.use {
                c.soTimeout = 30_000
                c.tcpNoDelay = true
                val inp = BufferedInputStream(c.getInputStream())
                val first = line(inp) ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val l = line(inp) ?: return
                    if (l.isEmpty()) break
                    val colon = l.indexOf(':')
                    if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
                }
                val out = c.getOutputStream().buffered(64 * 1024)
                handle(method, path, headers, out)
                out.flush()
            }
        } catch (_: IOException) {
            // the TV closed the connection (a seek, another quality): nothing to do
        }
    }

    private fun line(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>, extra: Map<String, String> = emptyMap()) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun handle(method: String, path: String, h: Map<String, String>, out: OutputStream) {
        if (method == "OPTIONS") {
            // the TV's player asks before sending a Range header from its page
            head(out, 204, listOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to (h["access-control-request-headers"] ?: "Range, Content-Type"),
                "Access-Control-Max-Age" to "86400",
                "Content-Length" to "0",
            ))
            return
        }
        val k = path.substringAfterLast('/').substringBefore('.')
        val item = items[k]
        if (item == null || (method != "GET" && method != "HEAD")) { head(out, 404, listOf("Content-Length" to "0")); return }
        val headOnly = method == "HEAD"
        when {
            item.text != null -> {
                val b = item.text.toByteArray(Charsets.UTF_8)
                head(out, 200, listOf("Content-Type" to item.type, "Content-Length" to b.size.toString(), "Cache-Control" to "no-cache"))
                if (!headOnly) out.write(b)
            }
            item.url!!.startsWith("file:") -> file(File(URI(item.url)), h["range"], headOnly, out)
            isPlaylist(item.url) -> playlist(item.url, headOnly, out)
            else -> remote(item.url, h["range"], headOnly, out)
        }
    }

    /** "bytes=a-b" as (a, b or null); null for none, or the rare "bytes=-n" (taken as the whole) */
    private fun range(r: String?): Pair<Long, Long?>? {
        val v = r?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val a = v.substringBefore('-').toLongOrNull() ?: return null
        return a to v.substringAfter('-', "").toLongOrNull()
    }

    private fun typeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "m4a" -> "audio/mp4"; "webm" -> "video/webm"; "weba" -> "audio/webm"
        "vtt" -> "text/vtt"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "application/octet-stream"
    }

    private fun file(f: File, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        if (!f.isFile) { head(out, 404, listOf("Content-Length" to "0")); return }
        val total = f.length()
        val r = range(rangeHeader)
        val from = r?.first ?: 0L
        if (from >= total && total > 0) { head(out, 416, listOf("Content-Range" to "bytes */$total", "Content-Length" to "0")); return }
        val end = min(r?.second ?: (total - 1), total - 1)
        val hs = mutableListOf("Content-Type" to typeOf(f.name), "Accept-Ranges" to "bytes", "Content-Length" to (end - from + 1).toString())
        if (r != null) hs += "Content-Range" to "bytes $from-$end/$total"
        head(out, if (r != null) 206 else 200, hs)
        if (headOnly) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(256 * 1024)
            var left = end - from + 1
            while (left > 0) {
                val n = raf.read(buf, 0, min(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun fetch(url: String, from: Long, to: Long): Response {
        var last: IOException? = null
        repeat(3) {
            try {
                val r = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=$from-$to").build()).execute()
                if (r.code == 206 || r.code == 200) return r
                r.close()
                last = IOException("HTTP ${r.code}")
                if (r.code in 400..499) throw last!!
            } catch (e: IOException) { last = e; if (e.message?.startsWith("HTTP 4") == true) throw e }
        }
        throw last ?: IOException("fetch failed")
    }

    /** YouTube (or the TubeHaus server), in 10 MB pieces handed on as one response */
    private fun remote(url: String, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        val r = range(rangeHeader)
        val from = r?.first ?: 0L
        var piece = try { fetch(url, from, if (r?.second != null) min(r.second!!, from + CHUNK - 1) else from + CHUNK - 1) } catch (e: IOException) {
            head(out, if (e.message == "HTTP 416") 416 else 404, listOf("Content-Length" to "0")); return
        }
        val whole = piece.code == 200          // the other end ignored the range: this is the whole file
        val total = if (whole) piece.body?.contentLength()?.takeIf { it >= 0 }
            else piece.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        val end = min(r?.second ?: Long.MAX_VALUE, (total ?: Long.MAX_VALUE) - 1)
        val type = piece.header("Content-Type") ?: "application/octet-stream"
        val hs = mutableListOf("Content-Type" to type, "Accept-Ranges" to "bytes")
        if (total != null) hs += "Content-Length" to (end - from + 1).toString()
        if (r != null && total != null) hs += "Content-Range" to "bytes $from-$end/$total"
        head(out, if (r != null && total != null) 206 else 200, hs)
        if (headOnly) { piece.close(); return }
        val buf = ByteArray(256 * 1024)
        var pos = from
        while (true) {
            piece.use { p ->
                val inp = p.body!!.byteStream()
                if (whole && from > 0) inp.skipNBytesCompat(from)
                while (pos <= end) {
                    val n = inp.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            if (whole || pos > end || total == null) break
            piece = fetch(url, pos, min(pos + CHUNK - 1, end))
        }
    }

    private fun InputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) return; left-- } else left -= s
        }
    }

    private fun isPlaylist(url: String) = ".m3u8" in url || "/hls_playlist/" in url || "/hls_variant/" in url

    /** a live stream's HLS playlist, its every address turned into one of ours */
    private fun playlist(url: String, headOnly: Boolean, out: OutputStream) {
        val text = try {
            Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string() ?: ""
            }
        } catch (_: IOException) { head(out, 404, listOf("Content-Length" to "0")); return }
        val base = currentBase ?: run { head(out, 404, listOf("Content-Length" to "0")); return }
        val src = URI(url)
        val rewritten = text.lineSequence().joinToString("\n") { l ->
            when {
                l.isBlank() -> l
                l.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(l) { m -> "URI=\"" + proxy(base, src.resolve(m.groupValues[1]).toString()) + "\"" }
                else -> proxy(base, src.resolve(l.trim()).toString())
            }
        }
        val b = rewritten.toByteArray()
        head(out, 200, listOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to b.size.toString(), "Cache-Control" to "no-cache"))
        if (!headOnly) out.write(b)
    }

    /** the [base] last handed out: a playlist's addresses are rewritten onto it */
    @Volatile var currentBase: String? = null
}
