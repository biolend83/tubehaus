package app.tubehaus.engine

import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Videos through the connected TubeHaus server. With a server set in Settings the player's
 * streams come from it, not from YouTube on this device: the server asks YouTube from its own
 * address (WARP on the GitHub version) and passes the video through its Invidious, so a device
 * whose network YouTube wants a sign-in on (a VPN, a data-centre address) still plays.
 *
 * It reads the same /api/v1/videos the web player uses (and its cache), and points every
 * stream at the server's proxy, the one the web's DASH manifest uses too. The
 * server's password goes only to the server: [authFor] answers for its own addresses.
 */
object ServerVideo {
    @Volatile private var base = ""
    @Volatile private var auth: String? = null

    val on get() = base.isNotEmpty()

    /** from Settings: the server's address and its login (empty when it has none) */
    @OptIn(ExperimentalEncodingApi::class)
    fun set(server: String, user: String, pass: String) {
        base = server.trim().trimEnd('/')
        auth = if (user.isNotEmpty()) "Basic " + Base64.encode("$user:$pass".encodeToByteArray()) else null
    }

    /** the Authorization header for [url] when it is on the server, else null */
    fun authFor(url: String): String? {
        val b = base
        if (b.isEmpty() || !url.startsWith(b, ignoreCase = true)) return null
        return if (url.length == b.length || url[b.length] == '/' || url[b.length] == '?') auth else null
    }

    private fun headers() = buildMap {
        put("Accept", "application/json")
        auth?.let { put("Authorization", it) }
    }

    suspend fun stream(id: String): Stream {
        val b = base.ifEmpty { throw YtError("No server") }
        val text = try {
            Net.get("$b/api/v1/videos/$id", headers())
        } catch (e: HttpError) {
            throw YtError(serverError(e.code), if (e.code == 404) YtError.Kind.UNAVAILABLE else YtError.Kind.NETWORK)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw YtError("The server didn't answer", YtError.Kind.NETWORK)
        }
        val d = runCatching { parse(text) }.getOrNull() ?: throw YtError("The server sent something unreadable", YtError.Kind.NETWORK)
        d.at("error").str?.let { throw YtError(it, YtError.Kind.UNAVAILABLE) }
        val live = d.at("liveNow").bool
        val hls = d.at("hlsUrl").str?.let { local(b, it) }
        val formats = d.at("adaptiveFormats").arr.mapNotNull { format(b, it) }
        if (formats.isEmpty() && hls == null) throw YtError("No playable streams", YtError.Kind.UNAVAILABLE)
        val expire = formats.firstNotNullOfOrNull { Regex("[?&]expire=(\\d+)").find(it.url)?.groupValues?.get(1)?.toLongOrNull() }
        return Stream(
            id = id,
            title = d.at("title").str ?: "",
            author = d.at("author").str ?: "",
            channelId = d.at("authorId").str ?: "",
            lengthSec = d.at("lengthSeconds").int ?: 0,
            views = d.at("viewCount").long ?: 0,
            description = d.at("description").str ?: "",
            keywords = d.at("keywords").arr.mapNotNull { it.str },
            live = live,
            hls = hls,
            video = formats.filter { it.isVideo },
            audio = formats.filter { !it.isVideo },
            captions = d.at("captions").arr.mapNotNull { c ->
                val url = c.at("url").str ?: return@mapNotNull null
                val label = c.at("label").str ?: ""
                Caption(c.at("language_code").str ?: c.at("languageCode").str ?: "", label, captions(b, url), label.contains("auto-generated", true))
            },
            expiresAt = expire ?: (Sys.nowMs() / 1000 + 18000),
            client = "server",
        )
    }

    /**
     * For Apple's player, which plays HLS only: the stream with the server's HLS list of it
     * (/api/p/hls, made from the same MP4 streams). A server without that list (older than
     * the app) counts as failing, so the video comes from the device instead.
     */
    suspend fun streamHls(id: String): Stream {
        val s = stream(id)
        if (s.live) return s
        val b = base
        val url = "$b/api/p/hls/$id.m3u8"
        val ok = runCatching { Net.get(url, headers()).startsWith("#EXTM3U") }.getOrDefault(false)
        if (!ok) throw YtError("The server has no HLS for this video", YtError.Kind.NETWORK)
        return s.copy(hls = url)
    }

    /** the request headers Apple's player sends for [url]: the server's login on its own addresses */
    fun headersFor(url: String): Map<String, String>? = authFor(url)?.let { mapOf("Authorization" to it) }

    private fun serverError(code: Int) = when (code) {
        401, 403 -> "The server didn't accept the login (Settings → Server)"
        429 -> "The server says too many requests. Try again in a little while"
        503 -> "The server is busy right now. Try again in a few minutes"
        404 -> "This video is unavailable"
        else -> "The server answered HTTP $code"
    }

    /**
     * A caption track from the companion, which fetches it through WARP; Invidious' own
     * /api/v1/captions asks YouTube from the server's address and comes back empty.
     */
    private fun captions(b: String, url: String): String {
        val path = url.replace(Regex("^https?://[^/]+"), "")
        return b + if (path.startsWith("/api/v1/captions/")) "/companion$path" else path
    }

    /**
     * The address of [c] for the player when it is one of the server's, else null. The
     * companion doesn't translate, so a translation comes from the server's own
     * (/api/p/sub, the one the web uses).
     */
    fun captionUrl(c: Caption, to: String?): String? {
        val b = base
        if (b.isEmpty() || !c.url.startsWith("$b/")) return null
        val lang = to?.takeIf { it != c.lang.substringBefore('-') } ?: return c.url
        val id = Regex("/captions/([A-Za-z0-9_-]{11})").find(c.url)?.groupValues?.get(1) ?: return c.url
        return "$b/api/p/sub/$id.vtt?tl=$lang"
    }

    /** a path on the server ("/api/...", or a full address of the server's own) as an address the app can open */
    private fun local(b: String, url: String) = b + url.replace(Regex("^https?://[^/]+"), "")

    /**
     * YouTube's address of a stream turned into the server's proxy for it, the link the web's
     * DASH manifest has: /companion/videoplayback?<the same query>&host=<YouTube's host>. The
     * companion fetches it through WARP, the address YouTube gave the stream to (a stream is
     * locked to the address that asked for it).
     */
    private fun proxied(b: String, url: String): String {
        if (url.startsWith("/")) return b + url
        val host = url.substringAfter("://").substringBefore('/')
        if (!host.endsWith(".googlevideo.com")) return local(b, url)
        val query = url.substringAfter('?', "")
        return "$b/companion/videoplayback?$query&host=$host"
    }

    private fun range(s: String?): LongRange? {
        val (a, z) = s?.split('-')?.takeIf { it.size == 2 } ?: return null
        return (a.toLongOrNull() ?: return null)..(z.toLongOrNull() ?: return null)
    }

    private fun format(b: String, f: JsonElement): Format? {
        val url = f.at("url").str ?: return null
        val type = f.at("type").str ?: return null
        val mime = type.substringBefore(';').trim()
        val size = f.at("size").str?.split('x')
        return Format(
            itag = f.at("itag").int ?: 0,
            url = proxied(b, url),
            mime = mime,
            codecs = type.substringAfter("codecs=\"", "").substringBefore('"'),
            width = size?.getOrNull(0)?.toIntOrNull() ?: 0,
            height = size?.getOrNull(1)?.toIntOrNull() ?: 0,
            fps = f.at("fps").int ?: 0,
            bitrate = f.at("bitrate").int ?: 0,
            contentLength = f.at("clen").long ?: 0,
            initRange = range(f.at("init").str),
            indexRange = range(f.at("index").str),
            qualityLabel = f.at("qualityLabel").str ?: "",
            sampleRate = f.at("audioSampleRate").int ?: 0,
            channels = f.at("audioChannels").int ?: 2,
            audioTrack = f.at("audioTrack.id").str,
            audioTrackName = f.at("audioTrack.displayName").str,
            audioDefault = f.at("audioTrack.isDefault").str?.let { it == "true" } ?: true,
            drc = "drc" in (url.substringAfter("xtags=", "").substringBefore('&')),
        )
    }
}
