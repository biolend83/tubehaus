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

/** A named set of subscribed channels (like PocketTube's), shown as its own tab in Subscriptions. */
@Serializable
data class SubGroup(val name: String, val channels: List<String> = emptyList())

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
    /** subscription groups, in the order of their tabs (kept on this device; a server doesn't have them) */
    val groups: List<SubGroup> = emptyList(),
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
    /** captions: text size in % (50..200), the box behind them in % opacity (0 = no box, an outline instead),
     *  and where they sit: 0 at the bottom, 1 a little higher, 2 at the top (#54) */
    val captionSize: Int = 100,
    val captionBg: Int = 75,
    val captionPos: Int = 0,
    val speed: Float = 1f,
    val backgroundPlay: Boolean = true,
    /** calm mode: no view counts, no Shorts tab, no autoplay of the next video */
    val calm: Boolean = false,
    val dark: Int = 0,            // 0 follow system, 1 light, 2 dark
    val historyOff: Boolean = false,
    /** the taste profile in use (rec/Profiles): "" = the main one */
    val profile: String = "",
    /** the desktop player's own volume, 0..1 */
    val volume: Float = 1f,
    /** the first-start screen that offers to bring subscriptions over has been seen */
    val welcomed: Boolean = false,
    // focus: take away the parts of YouTube that pull you in, one switch each
    val hideThumbs: Boolean = false,
    val hideComments: Boolean = false,
    val homeOff: Boolean = false,
    val subsFeedOff: Boolean = false,
    /** with groups: Subscriptions shows only the groups' tabs, not the feed of every channel */
    val subsHideAll: Boolean = false,
    /** the tab Subscriptions opens on: a group's name, "" = all */
    val subsGroup: String = "",
    /** what you searched for lately, newest first (none kept while history is off) */
    val recentSearches: List<String> = emptyList(),
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
    /** desktop: where downloads are kept; "" = the app's own folder */
    val offlineDir: String = "",
    /** desktop: the sound output mpv plays to (a name from its list); "" = the system's default */
    val audioDevice: String = "",
    /** Android: the TV layout and remote keys; 0 when the device is a TV, 1 always, 2 never */
    val tvMode: Int = 0,
    /** dark theme: the video's colours glow softly around it, as on YouTube */
    val ambient: Boolean = true,
    /** the "Most replayed" wave over the seek bar */
    val heatmap: Boolean = true,
    /** DeArrow: titles (and with [dearrowThumbs] thumbnails) people chose instead of clickbait */
    val dearrow: Boolean = true,
    val dearrowThumbs: Boolean = true,
    /** the dislike count under a video (Return YouTube Dislike's estimate) */
    val showDislikes: Boolean = true,
    /** SponsorBlock: the parts people marked in videos; the kinds in [sponsorCats] are skipped by themselves */
    val sponsor: Boolean = true,
    val sponsorCats: List<String> = listOf("sponsor", "selfpromo", "interaction"),
    val checkedAt: Long = 0,
    val skipVersion: String = "",
    /** the version last opened: when the app opens on a newer one, it shows what's new */
    val seenVersion: String = "",
    /** the speed you chose for a channel (id -> speed): its videos start at it */
    val channelSpeed: Map<String, Float> = emptyMap(),
    /** a speed changed while watching is kept for that channel (else it becomes the speed of every video) */
    val speedPerChannel: Boolean = true,
    /** the video codec the player prefers: "" automatic, "h264", "vp9" or "av1" */
    val codec: String = "",
    /** music: only the sound is downloaded (less data), the picture comes back with "Watch video" */
    val musicAudioOnly: Boolean = true,
    /** wellbeing: a reminder after this many minutes of watching in a row; 0 off */
    val breakMinutes: Int = 0,
    /** wellbeing: a reminder once a day past this many minutes; 0 off */
    val dailyMinutes: Int = 0,
)

/** One day of watching, kept on this device only: seconds of video and music, per channel and artist. */
@Serializable
data class DayStats(
    val video: Long = 0,
    val music: Long = 0,
    /** channel id -> seconds (videos) */
    val ch: Map<String, Long> = emptyMap(),
    /** artist -> seconds (music) */
    val art: Map<String, Long> = emptyMap(),
    /** how many videos and songs were started */
    val n: Int = 0,
)

/** Watch time by day ("2026-11-07"), for Settings → Wellbeing and the monthly recap; a year is kept. */
@Serializable
data class WatchStats(
    val days: Map<String, DayStats> = emptyMap(),
    /** channel id -> its name, for the lists */
    val names: Map<String, String> = emptyMap(),
    /** the day the daily reminder was last shown */
    val remindedOn: String = "",
)

/** a song as the music tab remembers it */
@Serializable
data class SongMeta(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val len: Int = 0,
    val art: String = "",
)

/** an album or an artist as the music tab remembers it */
@Serializable
data class CardMeta(val id: String, val title: String = "", val subtitle: String = "", val art: String = "", val kind: String = "")

/** The music tab's memory, on this device: songs played, searches, albums and artists opened, newest first. */
@Serializable
data class MusicData(
    val recent: List<SongMeta> = emptyList(),
    val searches: List<String> = emptyList(),
    val opened: List<CardMeta> = emptyList(),
    /** songs liked in the music player (also in Liked videos) */
    val liked: List<SongMeta> = emptyList(),
)
