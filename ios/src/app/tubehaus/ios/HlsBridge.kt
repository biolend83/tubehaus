package app.tubehaus.ios

import app.tubehaus.engine.Format
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.originalFirst
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.cinterop.BetaInteropApi
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * YouTube's own HLS list for a video can be broken while its MP4 files are fine (seen on fresh
 * uploads: the audio lists answer 404, and AVPlayer plays nothing but HLS). This makes the same
 * thing on the device from the video's MP4 streams, as the TubeHaus server's /api/p/hls does: one
 * list per quality, each piece a byte range of the MP4 file, read from the file's own index (sidx).
 * The pieces come straight from YouTube; the lists are handed to AVPlayer by [Lists] under a
 * made-up scheme, because AVPlayer won't start a master list from a file whose pieces are on the web.
 */
object HlsBridge {
    /** the lists for [s] (file name to text, "master.m3u8" among them), or null when the video has no H.264/AAC MP4 with an index */
    suspend fun build(s: Stream): Map<String, String>? {
        val video = s.video.filter { usable(it) && it.codecs.startsWith("avc1") }
            .groupBy { it.height }.values.map { same -> same.maxBy { it.bitrate } }.sortedBy { it.bitrate }
        val audio = originalFirst(s.audio.filter { usable(it) && it.codecs.startsWith("mp4a") && !it.drc })
            .groupBy { it.audioLang }.values.map { same -> same.maxBy { it.bitrate } }
        if (video.isEmpty() || audio.isEmpty()) return null
        val lists = coroutineScope { (audio + video).map { f -> async { name(f) to media(f) } }.awaitAll() }
        return lists.toMap() + (MASTER to master(video, audio))
    }

    const val MASTER = "master.m3u8"
    private const val SCHEME = "tubehaus-hls"

    /** an asset that plays [lists]; keep the returned loader as long as the asset plays (AVFoundation holds it weakly) */
    fun asset(id: String, lists: Map<String, String>): Pair<AVURLAsset, Lists> {
        val asset = AVURLAsset(uRL = NSURL(string = "$SCHEME://$id/$MASTER")!!, options = null)
        val loader = Lists(lists)
        asset.resourceLoader.setDelegate(loader, queue = dispatch_get_main_queue())
        return asset to loader
    }

    /** answers AVPlayer's requests for the lists (tubehaus-hls://<id>/<name>) from memory */
    class Lists(private val lists: Map<String, String>) : NSObject(), AVAssetResourceLoaderDelegateProtocol {
        @OptIn(BetaInteropApi::class)
        override fun resourceLoader(
            resourceLoader: AVAssetResourceLoader,
            shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
        ): Boolean {
            val req = shouldWaitForLoadingOfRequestedResource
            val url = req.request.URL ?: return false
            if (url.scheme != SCHEME) return false
            val text = lists[url.lastPathComponent ?: ""] ?: return false
            val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
            req.contentInformationRequest?.let {
                it.contentType = "public.m3u-playlist"
                it.contentLength = data.length.toLong()
                it.byteRangeAccessSupported = false
            }
            req.dataRequest?.respondWithData(data)
            req.finishLoading()
            return true
        }
    }

    private fun usable(f: Format) = f.mime.endsWith("/mp4") && f.initRange != null && f.indexRange != null

    private fun name(f: Format) = (if (f.isVideo) "v" else "a") + f.itag + (f.audioLang?.let { "-$it" } ?: "") + ".m3u8"

    private fun master(video: List<Format>, audio: List<Format>): String {
        val rows = mutableListOf("#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS")
        val main = audio.firstOrNull { it.audioDefault } ?: audio.first()
        for (a in audio) {
            // the same wording as YouTube's own lists, so IosPlayer reads the languages the same way
            val lang = a.audioLang?.let { ",LANGUAGE=\"$it\"" } ?: ""
            val label = (a.audioTrackName ?: "Default") + if (a.audioContent == "original") " - original" else ""
            val default = if (a === main) "YES" else "NO"
            rows += "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"$label\"$lang,DEFAULT=$default,AUTOSELECT=$default,URI=\"${name(a)}\""
        }
        for (v in video) {
            val fps = if (v.fps > 0) ",FRAME-RATE=${v.fps}" else ""
            rows += "#EXT-X-STREAM-INF:BANDWIDTH=${v.bitrate + main.bitrate},CODECS=\"${v.codecs},${main.codecs}\"," +
                "RESOLUTION=${v.width}x${v.height}$fps,AUDIO=\"a\""
            rows += name(v)
        }
        return rows.joinToString("\n", postfix = "\n")
    }

    /** one quality's list: the init part, then every piece the index names */
    private suspend fun media(f: Format): String {
        val idx = f.indexRange!!
        val init = f.initRange!!
        val (status, box) = Net.bytes(f.url, mapOf("Range" to "bytes=${idx.first}-${idx.last}"))
        if (status !in 200..299) error("HTTP $status for the index of itag ${f.itag}")
        val (pieces, firstAt) = sidx(box)
        var pos = idx.first + firstAt
        val rows = mutableListOf(
            "#EXTM3U", "#EXT-X-VERSION:7",
            "#EXT-X-TARGETDURATION:${maxOf(1, kotlin.math.ceil(pieces.maxOf { it.second }).toInt())}",
            "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MAP:URI=\"${f.url}\",BYTERANGE=\"${init.last + 1}@0\"",
        )
        for ((size, secs) in pieces) {
            rows += "#EXTINF:${(secs * 1000).toLong() / 1000.0},"
            rows += "#EXT-X-BYTERANGE:$size@$pos"
            rows += f.url
            pos += size
        }
        rows += "#EXT-X-ENDLIST"
        return rows.joinToString("\n", postfix = "\n")
    }
}

/**
 * The pieces of an MP4 file from its sidx box: (bytes, seconds) each, and where the first piece
 * starts, counted from the start of [b] (the box's own end plus its first_offset).
 */
internal fun sidx(b: ByteArray): Pair<List<Pair<Long, Double>>, Long> {
    fun u(at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }
    val i = (4 until b.size - 4).firstOrNull { b[it] == 's'.code.toByte() && b[it + 1] == 'i'.code.toByte() && b[it + 2] == 'd'.code.toByte() && b[it + 3] == 'x'.code.toByte() }
        ?: error("no sidx box")
    val boxEnd = i - 4 + u(i - 4, 4)
    var p = i + 4
    val n = if (b[p].toInt() == 0) 4 else 8         // version 0: 32-bit times, 1: 64-bit
    p += 8                                          // version+flags, reference_ID
    val scale = u(p, 4).toDouble(); p += 4
    p += n                                          // earliest_presentation_time
    val first = u(p, n); p += n
    p += 2                                          // reserved
    val count = u(p, 2).toInt(); p += 2
    val pieces = (0 until count).map { k ->
        val at = p + k * 12
        (u(at, 4) and 0x7FFFFFFF) to u(at + 4, 4) / scale
    }
    return pieces to boxEnd + first
}
