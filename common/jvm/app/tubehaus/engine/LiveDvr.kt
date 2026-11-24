package app.tubehaus.engine

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Going back in a live stream as far as YouTube's own player does. YouTube's live HLS playlist lists only the last
 * dozen minutes or so, but its segments are numbered from the stream's start (/sq/N/ in their addresses) and the
 * older ones are still there, hours back. So the app serves the playlist itself on 127.0.0.1 ([Sabr]'s server): the
 * real playlist with [BACK_MS] more of segments in front, numbered before the first, whose addresses are YouTube's
 * with the number changed. They go through here (short addresses keep the playlist small: it is read every couple
 * of seconds), the rest point to YouTube as they were.
 */
object LiveDvr {
    /** how far back: the player's seek bar then spans this much */
    private const val BACK_MS = 2 * 3600_000L

    private class Media(val url: String) {
        @Volatile var template: String? = null   // a segment's address, its /sq/N/ to change
        @Volatile var used = System.currentTimeMillis()
    }
    private val media = ConcurrentHashMap<String, Media>()
    private val SQ = Regex("/sq/(\\d+)/")

    /** the live master playlist [url] as one on this device whose media lists go back hours; null to play [url] as it is */
    suspend fun master(url: String): String? = runCatching {
        if (!url.contains("googlevideo.com") && !url.contains("youtube.com/api/manifest")) return null
        val text = Net.get(url)
        if (!text.startsWith("#EXTM3U")) return null
        val port = Sabr.port()
        val out = StringBuilder()
        for (line in text.lines()) {
            out.append(
                if (line.startsWith("http")) {
                    val k = Integer.toHexString(line.hashCode()) + Integer.toHexString(line.length)
                    media.getOrPut(k) { Media(line) }.used = System.currentTimeMillis()
                    "http://127.0.0.1:$port/dvr/m?k=$k"
                } else line,
            ).append('\n')
        }
        sweep()
        val k = "master" + Integer.toHexString(url.hashCode())
        masters[k] = out.toString()
        "http://127.0.0.1:$port/dvr/master?k=$k"
    }.getOrNull()
    private val masters = ConcurrentHashMap<String, String>()

    private fun sweep() {
        val old = System.currentTimeMillis() - 6 * 3600_000L
        media.entries.removeIf { it.value.used < old }
        if (masters.size > 20) masters.clear()
    }

    /** answers /dvr/... of [Sabr]'s server */
    internal fun serve(out: OutputStream, path: String, q: Map<String, String>, reply: (Int, String, ByteArray) -> Unit) {
        when {
            path.startsWith("/dvr/master") -> masters[q["k"]]?.let { reply(200, "application/vnd.apple.mpegurl", it.toByteArray()) }
                ?: reply(404, "text/plain", ByteArray(0))
            path.startsWith("/dvr/m") -> {
                val m = media[q["k"]] ?: return reply(404, "text/plain", ByteArray(0))
                m.used = System.currentTimeMillis()
                val text = runCatching { kotlinx.coroutines.runBlocking { Net.get(m.url) } }.getOrNull()
                    ?: return reply(502, "text/plain", ByteArray(0))
                reply(200, "application/vnd.apple.mpegurl", longer(m, text, q["k"]!!).toByteArray())
            }
            // /dvr/s/<k>/<n>/seg.ts: a file name ending in .ts, which some players look for
            path.startsWith("/dvr/s/") -> path.split('/').let { p -> segment(out, media[p.getOrNull(3)], p.getOrNull(4)?.toLongOrNull(), reply) }
            else -> reply(404, "text/plain", ByteArray(0))
        }
    }

    /** the media playlist with older segments in front */
    private fun longer(m: Media, text: String, k: String): String {
        val lines = text.lines()
        val firstUrl = lines.firstOrNull { it.startsWith("http") } ?: return text
        val first = SQ.find(firstUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return text
        m.template = firstUrl
        val seq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.trim()?.toLongOrNull()
        // the numbers in the addresses must be the playlist's own (YouTube's live lists number from the stream's
        // start); a list that counts from 0 by itself cannot be lengthened this way
        if (seq == null || seq != first) return text
        val dur = lines.firstOrNull { it.startsWith("#EXTINF:") }?.substringAfter(':')?.substringBefore(',')?.toDoubleOrNull() ?: return text
        val back = minOf(first, (BACK_MS / (dur * 1000)).toLong())
        if (back <= 0) return text
        val sb = StringBuilder()
        var inHeader = true
        for (line in lines) {
            if (inHeader && (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("http"))) {
                inHeader = false
                val d = "%.3f".format(java.util.Locale.ROOT, dur)
                for (n in first - back until first) sb.append("#EXTINF:").append(d).append(",\n/dvr/s/").append(k).append('/').append(n).append("/seg.ts\n")
            }
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first - back).append('\n')
                // times of day and ad marks name the real list's part only; without them the longer list stays one timeline
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-DATERANGE") -> {}
                line.isNotEmpty() -> sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    /** an older segment: YouTube's address with its number, passed through */
    private fun segment(out: OutputStream, m: Media?, n: Long?, reply: (Int, String, ByteArray) -> Unit) {
        val t = m?.template
        if (t == null || n == null || n < 0) return reply(404, "text/plain", ByteArray(0))
        m.used = System.currentTimeMillis()
        val url = t.replace(SQ, "/sq/$n/")
        val r = runCatching { Net.client.newCall(okhttp3.Request.Builder().url(url).build()).execute() }.getOrNull()
            ?: return reply(502, "text/plain", ByteArray(0))
        r.use {
            val body = it.body
            if (!it.isSuccessful || body == null) return reply(if (it.code == 404) 404 else 502, "text/plain", ByteArray(0))
            val bytes = body.bytes()
            reply(200, it.header("Content-Type") ?: "video/mp2t", bytes)
        }
    }

}
