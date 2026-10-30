package app.tubehaus.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything the app asks YouTube, on iOS: the same calls as the Kotlin engine of the other
 * platforms (common/jvm/.../YouTube.kt), answered by the JavaScript engine (Engine, engine.js)
 * and turned into the app's own types here.
 */
object YouTube {

    enum class Filter(val params: String?) {
        ALL(null),
        VIDEOS("EgIQAQ%3D%3D"),
        CHANNELS("EgIQAg%3D%3D"),
        PLAYLISTS("EgIQAw%3D%3D"),
        SHORT("EgIYAQ%3D%3D"),
    }

    enum class Tab(val params: String) {
        VIDEOS("EgZ2aWRlb3PyBgQKAjoA"),
        SHORTS("EgZzaG9ydHPyBgUKA5oBAA%3D%3D"),
        LIVE("EgdzdHJlYW1z8gYECgJ6AA%3D%3D"),
        PLAYLISTS("EglwbGF5bGlzdHPyBgQKAkIA"),
    }

    private fun args(vararg a: Any?) = JsonArray(a.map {
        when (it) {
            null -> JsonNull
            is Number -> JsonPrimitive(it)
            is Boolean -> JsonPrimitive(it)
            else -> JsonPrimitive(it.toString())
        }
    })

    private suspend fun run(op: String, vararg a: Any?) = Engine.call(op, args(*a))

    // ------------------------------------------------------------------ the calls
    suspend fun search(query: String, filter: Filter = Filter.ALL): Page<Item> = itemPage(run("search", query, filter.params))
    suspend fun searchMore(token: String): Page<Item> = itemPage(run("searchMore", token))
    suspend fun suggestions(q: String): List<String> = if (q.isBlank()) emptyList() else run("suggestions", q).arr.mapNotNull { it.str }
    /**
     * Through the connected TubeHaus server when there is one (its HLS list, see
     * [ServerVideo.streamHls]), from this device when there isn't or the server can't.
     */
    suspend fun stream(id: String): Stream {
        if (!ServerVideo.on) return stream(run("stream", id))
        return try {
            ServerVideo.streamHls(id)
        } catch (e: YtError) {
            try { stream(run("stream", id)) } catch (x: Exception) { if (x is kotlinx.coroutines.CancellationException) throw x; throw e }
        }
    }
    suspend fun details(id: String): Details = details(run("details", id))
    suspend fun relatedMore(token: String): Page<Item> = itemPage(run("relatedMore", token))
    suspend fun browseMore(token: String): Page<Item> = itemPage(run("browseMore", token))

    suspend fun comments(token: String): Page<Comment> {
        val d = run("comments", token)
        return Page(d.at("items").arr.map(::comment), d.at("next").str)
    }

    suspend fun channel(id: String, tab: Tab = Tab.VIDEOS): ChannelPage {
        val d = run("channel", id, tab.params)
        return ChannelPage(
            id = d.at("id").str ?: id, name = d.at("name").str ?: "", avatar = d.at("avatar").str ?: "",
            banner = d.at("banner").str ?: "", handle = d.at("handle").str ?: "", subsText = d.at("subsText").str ?: "",
            videoCountText = d.at("videoCountText").str ?: "", items = itemPage(d.at("items")),
        )
    }

    suspend fun playlist(id: String): PlaylistPage {
        val d = run("playlist", id)
        return PlaylistPage(
            id = d.at("id").str ?: id, title = d.at("title").str ?: "", author = d.at("author").str ?: "",
            countText = d.at("countText").str ?: "", items = itemPage(d.at("items")),
        )
    }

    suspend fun resolve(url: String): Pair<String, String>? {
        val d = run("resolve", url).arr
        val kind = d.getOrNull(0).str ?: return null
        val id = d.getOrNull(1).str ?: return null
        return kind to id
    }

    suspend fun feed(channelId: String): List<Video> = run("feed", channelId).arr.mapNotNull { item(it) as? Video }

    /** "3 days ago" / "3일 전" → seconds; null when it can't be read */
    fun ago(text: String): Long? {
        val m = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year|초|분|시간|일|주|개월|달|년)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "second", "초" -> 1L
            "minute", "분" -> 60L
            "hour", "시간" -> 3600L
            "day", "일" -> 86400L
            "week", "주" -> 7 * 86400L
            "month", "개월", "달" -> 30 * 86400L
            else -> 365 * 86400L
        }
        return n * unit
    }

    /** WebVTT text of a caption track, optionally machine-translated to [to]. */
    fun captionUrl(c: Caption, to: String? = null): String =
        ServerVideo.captionUrl(c, to) ?: c.url + "&fmt=vtt" + (to?.takeIf { it != c.lang.substringBefore('-') }?.let { "&tlang=$it" } ?: "")

    // ------------------------------------------------------------------ JSON → the app's types
    private fun itemPage(e: JsonElement?) = Page(e.at("items").arr.mapNotNull(::item), e.at("next").str)

    private fun item(e: JsonElement): Item? {
        val id = e.at("id").str ?: return null
        return when (e.at("type").str) {
            "video" -> Video(
                id = id, title = e.at("title").str ?: "", author = e.at("author").str ?: "", channelId = e.at("channelId").str ?: "",
                lengthSec = e.at("lengthSec").int ?: 0, viewsText = e.at("viewsText").str ?: "", views = e.at("views").long ?: 0,
                publishedText = e.at("publishedText").str ?: "", publishedAt = e.at("publishedAt").long ?: 0,
                live = e.at("live").bool, short = e.at("short").bool, avatar = e.at("avatar").str ?: "",
            )
            "channel" -> Channel(id, e.at("name").str ?: "", e.at("avatar").str ?: "", e.at("subsText").str ?: "", e.at("handle").str ?: "")
            "playlist" -> Playlist(id, e.at("title").str ?: "", e.at("thumb").str ?: "", e.at("countText").str ?: "", e.at("author").str ?: "")
            else -> null
        }
    }

    private fun range(e: JsonElement?): LongRange? {
        val a = e.arr
        val s = a.getOrNull(0).long ?: return null
        val t = a.getOrNull(1).long ?: return null
        return s..t
    }

    private fun format(f: JsonElement) = Format(
        itag = f.at("itag").int ?: 0, url = f.at("url").str ?: "", mime = f.at("mime").str ?: "", codecs = f.at("codecs").str ?: "",
        width = f.at("width").int ?: 0, height = f.at("height").int ?: 0, fps = f.at("fps").int ?: 0, bitrate = f.at("bitrate").int ?: 0,
        contentLength = f.at("contentLength").long ?: 0, initRange = range(f.at("initRange")), indexRange = range(f.at("indexRange")),
        qualityLabel = f.at("qualityLabel").str ?: "", sampleRate = f.at("sampleRate").int ?: 0, channels = f.at("channels").int ?: 2,
        audioTrack = f.at("audioTrack").str, audioTrackName = f.at("audioTrackName").str,
        audioDefault = f.at("audioDefault").str?.let { it == "true" } ?: true, drc = f.at("drc").bool,
    )

    private fun stream(d: JsonElement) = Stream(
        id = d.at("id").str ?: "", title = d.at("title").str ?: "", author = d.at("author").str ?: "", channelId = d.at("channelId").str ?: "",
        lengthSec = d.at("lengthSec").int ?: 0, views = d.at("views").long ?: 0, description = d.at("description").str ?: "",
        keywords = d.at("keywords").arr.mapNotNull { it.str }, live = d.at("live").bool, hls = d.at("hls").str,
        video = d.at("video").arr.map(::format), audio = d.at("audio").arr.map(::format),
        captions = d.at("captions").arr.map { Caption(it.at("lang").str ?: "", it.at("name").str ?: "", it.at("url").str ?: "", it.at("auto").bool) },
        expiresAt = d.at("expiresAt").long ?: (Sys.nowMs() / 1000 + 18000), client = d.at("client").str ?: "",
    )

    private fun details(d: JsonElement) = Details(
        likesText = d.at("likesText").str ?: "", subsText = d.at("subsText").str ?: "", avatar = d.at("avatar").str ?: "",
        publishedText = d.at("publishedText").str ?: "", viewsText = d.at("viewsText").str ?: "",
        related = d.at("related").arr.mapNotNull(::item), relatedNext = d.at("relatedNext").str, commentsToken = d.at("commentsToken").str,
    )

    private fun comment(c: JsonElement) = Comment(
        id = c.at("id").str ?: "", author = c.at("author").str ?: "", avatar = c.at("avatar").str ?: "", text = c.at("text").str ?: "",
        likes = c.at("likes").str ?: "", replies = c.at("replies").int ?: 0, published = c.at("published").str ?: "",
        channelId = c.at("channelId").str ?: "", repliesToken = c.at("repliesToken").str,
        byCreator = c.at("byCreator").bool, pinned = c.at("pinned").bool,
    )
}
