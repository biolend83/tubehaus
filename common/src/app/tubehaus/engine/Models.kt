package app.tubehaus.engine

/** Something in a list: a video, a channel or a playlist. */
sealed interface Item { val id: String }

data class Video(
    override val id: String,
    val title: String,
    val author: String = "",
    val channelId: String = "",
    val lengthSec: Int = 0,
    val viewsText: String = "",
    val views: Long = 0,
    val publishedText: String = "",
    /** epoch seconds when known exactly (RSS feeds, history), else 0 */
    val publishedAt: Long = 0,
    val live: Boolean = false,
    val short: Boolean = false,
    val avatar: String = "",
) : Item {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val thumbHigh: String get() = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
}

data class Channel(
    override val id: String,
    val name: String,
    val avatar: String = "",
    val subsText: String = "",
    val handle: String = "",
) : Item

data class Playlist(
    override val id: String,
    val title: String,
    val thumb: String = "",
    val countText: String = "",
    val author: String = "",
) : Item

data class Page<T>(val items: List<T>, val next: String? = null)

/** One downloadable stream from the player response. */
data class Format(
    val itag: Int,
    val url: String,
    val mime: String,          // "video/webm"
    val codecs: String,        // "vp9", "avc1.640028", "opus"
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val contentLength: Long = 0,
    val initRange: LongRange? = null,
    val indexRange: LongRange? = null,
    val qualityLabel: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 2,
    /** audio language track (for videos with dubbing); null when there is only one */
    val audioTrack: String? = null,
    val audioTrackName: String? = null,
    val audioDefault: Boolean = true,
    val drc: Boolean = false,
) {
    val isVideo get() = mime.startsWith("video")
    /** the audio track's language ("ko", "en-US"), from its id ("ko.4"); null when the video has one track */
    val audioLang: String? get() = audioTrack?.substringBefore('.')?.takeIf { it.isNotEmpty() }
    /** what YouTube says the audio track is: "original", "dubbed-auto", "dubbed", "descriptive"... (xtags in the stream URL) */
    val audioContent: String? get() = Regex("acont(?:=|%3D)([a-z-]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
    val codecFamily: String
        get() = when {
            codecs.startsWith("av01") -> "av1"
            codecs.startsWith("vp9") || codecs.startsWith("vp09") -> "vp9"
            codecs.startsWith("avc1") -> "h264"
            codecs.startsWith("opus") -> "opus"
            codecs.startsWith("mp4a") -> "aac"
            else -> codecs
        }
}

/**
 * A video's audio tracks with the original language as the default. YouTube marks the dub in
 * the request's language (English) as its default, so a Korean video started in an auto-dubbed
 * English; the stream URLs say which track is the original.
 */
fun originalFirst(audio: List<Format>): List<Format> =
    if (audio.none { it.audioContent == "original" }) audio
    else audio.map { it.copy(audioDefault = it.audioContent == "original") }

data class Caption(val lang: String, val name: String, val url: String, val auto: Boolean)

/** What the player needs. URLs expire after a few hours ([expiresAt], epoch seconds). */
data class Stream(
    val id: String,
    val title: String,
    val author: String,
    val channelId: String,
    val lengthSec: Int,
    val views: Long,
    val description: String,
    val keywords: List<String>,
    val live: Boolean,
    val hls: String?,
    val video: List<Format>,
    val audio: List<Format>,
    val captions: List<Caption>,
    val expiresAt: Long,
    val client: String,
) {
    fun asVideo() = Video(id, title, author, channelId, lengthSec, views = views, live = live)
}

/** The watch page around a video: owner, likes, related videos, the comments' entry point. */
data class Details(
    val likesText: String = "",
    val subsText: String = "",
    val avatar: String = "",
    val publishedText: String = "",
    val viewsText: String = "",
    val related: List<Item> = emptyList(),
    val relatedNext: String? = null,
    val commentsToken: String? = null,
)

data class Comment(
    val id: String,
    val author: String,
    val avatar: String,
    val text: String,
    val likes: String,
    val replies: Int,
    val published: String,
    val channelId: String,
    val repliesToken: String?,
    val byCreator: Boolean = false,
    val pinned: Boolean = false,
)

data class ChannelPage(
    val id: String,
    val name: String,
    val avatar: String,
    val banner: String,
    val handle: String,
    val subsText: String,
    val videoCountText: String,
    val items: Page<Item>,
)

data class PlaylistPage(
    val id: String,
    val title: String,
    val author: String,
    val countText: String,
    val items: Page<Item>,
)

class YtError(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { UNAVAILABLE, AGE, LOGIN, NETWORK, OTHER }
}
