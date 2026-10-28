package app.tubehaus.data

import app.tubehaus.engine.Video
import kotlinx.serialization.Serializable

// The whole library lives in one JSON file on this device. Its shape matches the TubeHaus
// server's ytlib.json, so a "tubehaus-backup" file moves between the app and any TubeHaus
// server (or its public instances) unchanged.

@Serializable
data class Meta(
    val id: String,
    val title: String = "",
    val author: String = "",
    val authorId: String = "",
    val len: Int = 0,
    val published: Long = 0,
    val views: Long = 0,
    val live: Boolean = false,
    /** when it was watched (history only), epoch seconds */
    val at: Long = 0,
) {
    fun video() = Video(id, title, author, authorId, len, views = views, publishedAt = published, live = live)
}

fun Video.meta(at: Long = 0) = Meta(id, title, author, channelId, lengthSec, publishedAt, views, live, at)

@Serializable
data class Sub(val name: String = "", val thumb: String = "", val at: Long = 0)

@Serializable
data class PlaylistData(val title: String, val items: List<Meta> = emptyList(), val updated: Long = 0)

@Serializable
data class Library(
    val subs: Map<String, Sub> = emptyMap(),
    val hist: List<Meta> = emptyList(),
    /** id -> [seconds, length, when] */
    val pos: Map<String, List<Long>> = emptyMap(),
    val wl: List<Meta> = emptyList(),
    val likes: List<Meta> = emptyList(),
    val dislikes: List<String> = emptyList(),
    val playlists: Map<String, PlaylistData> = emptyMap(),
)

/** What the recommenders learned. Everything here can be seen and wiped in Settings. */
@Serializable
data class Taste(
    /** channel id -> affinity, about -10..10 */
    val ch: Map<String, Double> = emptyMap(),
    /** keyword -> affinity */
    val kw: Map<String, Double> = emptyMap(),
    /** videos already shown as Shorts, id -> when */
    val seen: Map<String, Long> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val decayed: Long = 0,
    /** related videos of recently watched ones, the home feed's main candidates */
    val related: Map<String, List<Meta>> = emptyMap(),
    /** "not interested": hidden videos and channels */
    val hiddenVideos: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
)

@Serializable
data class Settings(
    /** interface language, "" until chosen (then English is used) */
    val lang: String = "",
    val region: String = "",
    /** highest video height to play, 0 = no limit */
    val maxHeight: Int = 1080,
    val maxHeightMobileData: Int = 480,
    val autoplay: Boolean = true,
    val captions: Boolean = false,
    val captionLang: String = "",
    val speed: Float = 1f,
    val backgroundPlay: Boolean = true,
    /** calm mode: no view counts, no Shorts tab, no autoplay of the next video */
    val calm: Boolean = false,
    val dark: Int = 0,            // 0 follow system, 1 light, 2 dark
    val historyOff: Boolean = false,
    /** the desktop player's own volume, 0..1 */
    val volume: Float = 1f,
    /** the first-start screen that offers to bring subscriptions over has been seen */
    val welcomed: Boolean = false,
    // focus: take away the parts of YouTube that pull you in, one switch each
    val hideThumbs: Boolean = false,
    val hideComments: Boolean = false,
    val homeOff: Boolean = false,
    val subsFeedOff: Boolean = false,
    val hideShorts: Boolean = false,
    val hideRelated: Boolean = false,
    /** a TubeHaus server the library syncs with ("" = none, see ServerSync), and its login if it has one */
    val server: String = "",
    val serverUser: String = "",
    val serverPass: String = "",
    /** once a day: a newer app version, and the stream client list (engine/Remote.kt) */
    val checkUpdates: Boolean = true,
    /** text size: 1 normal, up to 1.5 */
    val textScale: Float = 1f,
    /** search results as a grid of cards on wide screens (a list by default) */
    val searchGrid: Boolean = false,
    /** Android: hide the status and navigation bars everywhere, not only in full screen */
    val hideBars: Boolean = false,
    /** phones: the bottom tabs slide away while scrolling down and come back scrolling up */
    val autoHideNav: Boolean = true,
    /** touch screens: a tap on the video plays or pauses it (else it shows the controls) */
    val tapToPause: Boolean = false,
    /** read about 20 minutes ahead instead of 5 (more memory) */
    val longBuffer: Boolean = false,
    /** what the panel beside the video shows first on wide screens: "next" or "comments" */
    val sideTab: String = "next",
    /** phones: the search box at the bottom, within reach of a thumb */
    val searchAtBottom: Boolean = false,
    /** under the video: 0 everything, 1 only the title, 2 nothing (just the player) */
    val underVideo: Int = 0,
    /** with "everything" under the video: the parts switched off one by one (see UNDER_PARTS) */
    val hideUnder: List<String> = emptyList(),
    /** desktop and tablets: the side menu folded into a small button */
    val railCollapsed: Boolean = false,
    /** the quality a download is saved in (the last one chosen) */
    val offlineHeight: Int = 720,
    /** Android: the TV layout and remote keys; 0 when the device is a TV, 1 always, 2 never */
    val tvMode: Int = 0,
    val checkedAt: Long = 0,
    val skipVersion: String = "",
)
