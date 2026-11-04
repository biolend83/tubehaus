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
    /** YouTube's storyboard spec: the small pictures shown over the seek bar while you drag it */
    val storyboard: String? = null,
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
    /** "Top" and "Newest", each with the token that loads the comments in that order */
    val commentSorts: List<Pair<String, String>> = emptyList(),
    val commentsCount: String = "",
    val chapters: List<Chapter> = emptyList(),
)

/** A chapter of a video: its title, where it starts and its picture. */
data class Chapter(val title: String, val startMs: Long, val thumb: String = "")

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
    /** the start of the channel's description, as under its name on YouTube */
    val description: String = "",
    /** for [YouTube.channelAbout]: what "...more" opens */
    val aboutToken: String? = null,
    /** the tabs this channel has, in YouTube's order (Home, Videos, Shorts, Live, Courses, Playlists, Posts...) */
    val tabs: List<ChannelTab> = emptyList(),
    /** whether YouTube offers a search inside this channel */
    val searchable: Boolean = false,
    /** the link line under the description: its first link and "and 4 more links" */
    val linkText: String = "",
    val linkMore: String = "",
    /** the Home tab: the featured video (with the start of its description) and the shelves under it */
    val featured: Video? = null,
    val featuredText: String = "",
    val shelves: Page<Shelf> = Page(emptyList()),
    /** the Posts tab */
    val posts: Page<Post> = Page(emptyList()),
)

/** One tab of a channel: its name as YouTube shows it, what to ask for it, and what it is ("videos", "posts"...). */
data class ChannelTab(val title: String, val params: String, val kind: String)

/** A row on a channel's Home tab: a title and its videos, shorts or playlists, and where "Play all" goes. */
data class Shelf(val title: String, val items: List<Item>, val playlistId: String? = null, val tabParams: String? = null) {
    val id: String get() = title + (items.firstOrNull()?.id ?: "")
}

/** A post on a channel's Posts tab. [links] mark the parts of [text] that are links. */
data class Post(
    val id: String,
    val author: String,
    val avatar: String,
    val published: String,
    val text: String,
    val links: List<TextLink> = emptyList(),
    val likes: String = "",
    val replies: String = "",
    val images: List<String> = emptyList(),
    val video: Video? = null,
    val playlist: Playlist? = null,
    val poll: List<String> = emptyList(),
    val pollVotes: String = "",
)

/** characters [start] until [end] of a text are a link to [url] */
data class TextLink(val start: Int, val end: Int, val url: String)

/** A channel's "more about this channel": the whole description, its links and the numbers. */
data class ChannelAbout(
    val description: String,
    val links: List<ChannelLink>,
    val url: String,
    val country: String,
    val joinedText: String,
    val viewsText: String,
    val subsText: String,
    val videoCountText: String,
)

/** a link the channel lists: its title, the address as shown, where it goes, and the site's icon */
data class ChannelLink(val title: String, val text: String, val url: String, val icon: String)

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
