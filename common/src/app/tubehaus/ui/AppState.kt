package app.tubehaus.ui

import kotlin.concurrent.Volatile
import app.tubehaus.engine.Sys
import app.tubehaus.engine.YouTube
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.data.Store
import app.tubehaus.engine.InnerTube
import app.tubehaus.engine.Remote
import app.tubehaus.engine.Video
import app.tubehaus.engine.urlDecode
import app.tubehaus.rec.Feed
import app.tubehaus.rec.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Shorts : Screen
    data object Subs : Screen
    data object Library : Screen
    data object Settings : Screen
    data object Learned : Screen
    data object History : Screen
    data class Search(val query: String = "") : Screen
    data class Watch(val id: String) : Screen
    data class Channel(val id: String, val name: String = "") : Screen
    data class Playlist(val id: String, val title: String = "") : Screen
    /** a list kept on this device: "wl", "likes" or one of your playlists (HP...) */
    data class Local(val key: String) : Screen
    /** the subscribed channels, to remove some or all */
    data object ManageSubs : Screen
    /** videos saved on this device */
    data object Downloads : Screen
    /** one of YouTube's topic pages (TOPICS): Music, Live, News... */
    data class Topic(val key: String) : Screen
    /** the music tab: search, recent songs, albums and artists */
    data object Music : Screen
    /** what is playing as music: the cover, the lyrics, the queue */
    data object NowPlaying : Screen
    data class Album(val id: String, val title: String = "") : Screen
    data class Artist(val id: String, val name: String = "") : Screen
    /** one category of Settings ("display", "player"...); a search through all of them when [query] is set */
    data class SettingsPage(val key: String) : Screen
    /** Settings → Wellbeing: watch time by day, channel and month */
    data object Stats : Screen
}

/** the screens a tab opens: going to one starts over from it (Music and Live are tabs at the top on phones) */
val TABS = listOf(Screen.Home, Screen.Shorts, Screen.Subs, Screen.Library, Screen.Music) + TOP_TOPICS.map { Screen.Topic(it) }

/** [parent]: the app's first window, when this is another one (a video opened with a middle click) */
class AppState(val platform: Platform, val store: Store, private val parent: AppState? = null) {
    /** another window of the app: the library is the first one's, the start-up things (updates, sync, who is watching) are left to it */
    val extra get() = parent != null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** F5: bumped to build the page again, which loads it afresh */
    val reload = androidx.compose.runtime.mutableIntStateOf(0)
    val stack = mutableStateListOf<Screen>(if (store.settings.value.homeOff) Screen.Subs else Screen.Home)
    private val homeRoot: Screen get() = if (store.settings.value.homeOff) Screen.Subs else Screen.Home
    val current: Screen get() = stack.last()
    val translations = CommentTranslations()
    init {
        // the YouTube visitor identity is kept with the app's data (never in backups or exports)
        InnerTube.identityFile = platform.dataDir / "identity.json"
        Remote.clientsFile = platform.dataDir / "clients.json"
        Remote.restoreClients()
    }

    @Volatile private var filling = false

    /**
     * Subscriptions imported from Google Takeout and other apps come without channel pictures
     * (and some without names): look each one up, four at a time.
     */
    fun fillSubInfo() {
        if (filling) return
        val missing = store.lib.value.subs.filter { (cid, s) -> s.thumb.isEmpty() || s.name.isEmpty() || s.name == cid }.keys.take(500)
        if (missing.isEmpty()) return
        filling = true
        scope.launch {
            try {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                kotlinx.coroutines.coroutineScope {
                    for (cid in missing) launch {
                        gate.acquire()
                        try {
                            val c = YouTube.channel(cid)
                            store.updateSub(cid, c.name, c.avatar)
                        } catch (_: Exception) {
                        } finally { gate.release() }
                    }
                }
            } finally { filling = false }
        }
    }

    /** full screen on a wide screen: the comments beside the video */
    var fsComments by mutableStateOf(false)
    /** Home shows things outside what you usually watch */
    var discover by mutableStateOf(false)

    /** the library shared with a TubeHaus server, when one is connected in Settings */
    val sync: app.tubehaus.data.ServerSync by lazy { parent?.sync ?: app.tubehaus.data.ServerSync(store, scope) }

    /** a newer version to offer, found by [checkForUpdates] */
    var update by mutableStateOf<Remote.Release?>(null)

    /** each time the app opens (at most every 10 minutes) unless [now]; [quiet]: say nothing when there is nothing new */
    fun checkForUpdates(now: Boolean = false, quiet: Boolean = true) {
        val set = store.settings.value
        // iOS: installed by hand (Sideloadly, AltStore), so a newer version opens its release page; the engine's fixes come here too
        if (!now && (!set.checkUpdates || Sys.nowMs() - set.checkedAt < 10 * 60_000L)) return
        scope.launch {
            store.settings.update { it.copy(checkedAt = Sys.nowMs()) }
            Remote.refreshClients()
            val os = Sys.osName()
            // Linux: the release page, to pick deb/rpm/tar.gz
            // macOS: the Apple Silicon or the Intel build, whichever this Mac runs
            val mac = if (Sys.osArch() == "aarch64") "-mac-arm64.dmg" else "-mac-x64.dmg"
            val desktopFile = when { os.startsWith("Windows") -> ".msi"; os.startsWith("Mac") -> mac; else -> null }
            // one release per version (app-v*) carries every platform's file
            // Android: the APK signed with the same key as this one, or the system refuses it
            // ("App not installed"): TubeHaus-release-*.apk for the release key, TubeHaus-*.apk otherwise
            val pick: ((String) -> Boolean)? = when {
                platform.name != "android" -> desktopFile?.let { f -> { n: String -> n.endsWith(f) } }
                platform.releaseKey -> { n: String -> n.startsWith("TubeHaus-release-") && n.endsWith(".apk") }
                else -> { n: String -> n.endsWith(".apk") && !n.startsWith("TubeHaus-release-") }
            }
            val r = Remote.newerReleaseWhere("app-v", VERSION, pick)
            if (r != null && (now || r.version != store.settings.value.skipVersion)) update = r
            else if (!quiet) toast(tr("TubeHaus is up to date"))
        }
    }

    /** a Wellbeing reminder on screen (a break, today's amount) */
    var reminder by mutableStateOf<String?>(null)

    /** the release notes on screen (WhatsNewDialog); empty while they load */
    var whatsNew by mutableStateOf<List<Remote.Notes>?>(null)

    /** Settings → What's new: the last few versions */
    fun showWhatsNew() {
        whatsNew = emptyList()
        scope.launch {
            val all = Remote.releaseNotes()
            if (all == null) { whatsNew = null; toast(tr("Couldn't reach GitHub")); return@launch }
            whatsNew = all.filter { !Remote.newer(it.version, VERSION) }.take(6).ifEmpty { null }
        }
    }

    /**
     * Once after an update: what changed since the version opened before (only this one's when
     * that's unknown, coming from 0.5.14 or older). Not on a new install, and only with the update
     * check on, which is what lets the app look at GitHub.
     */
    fun whatsNewAfterUpdate() {
        val s = store.settings.value
        if (s.seenVersion == VERSION) return
        val used = s.welcomed || store.lib.value.subs.isNotEmpty()
        if (!used || !s.checkUpdates || (s.seenVersion.isNotEmpty() && !Remote.newer(VERSION, s.seenVersion))) {
            store.settings.update { it.copy(seenVersion = VERSION) }
            return
        }
        scope.launch {
            // no answer from GitHub: try again next time the app opens
            val all = Remote.releaseNotes() ?: return@launch
            store.settings.update { it.copy(seenVersion = VERSION) }
            val since = s.seenVersion
            val list = all.filter { !Remote.newer(it.version, VERSION) && (if (since.isEmpty()) it.version == VERSION else Remote.newer(it.version, since)) }.take(5)
            if (list.isNotEmpty()) whatsNew = list
        }
    }

    val feed = Feed(store)
    val rec = Recommender(store, feed)
    val near by lazy { NearbyHub(this) }
    /** the player in use: this device's own, or a TV's while casting */
    val player by lazy { SwitchPlayer(platform.player, scope) }
    val casting by lazy { Casting(this) }
    val playback = Playback(this)
    /** videos saved to watch without internet */
    val offline by lazy { Offline(this) }
    val snackbar = SnackbarHostState()

    var fullscreen by mutableStateOf(false)
    /** Android picture-in-picture: only the video is drawn */
    var pip by mutableStateOf(false)
    /** the Shorts screen's keys while it is open (up and down change the Short) */
    var shortsKeys: ((androidx.compose.ui.input.key.Key) -> Boolean)? = null

    /** Shorts are hidden everywhere: by their own switch or by calm mode */
    val noShorts: Boolean get() = store.settings.value.let { it.calm || it.hideShorts }

    /** how the last page change went, for its animation: 1 forward, -1 back, 0 to a tab */
    var lastMove = 0
    /** counts taps on the tab that is already open: its list goes back to the top (Refreshable) */
    var reselect by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** the pages Back left, newest last: Forward opens them again; going anywhere else forgets them */
    val ahead = mutableStateListOf<Screen>()

    /** there is a page to go back to (the side menu's Back button) */
    val canBack: Boolean get() = stack.size > 1 || stack.lastOrNull() != homeRoot

    fun forward(): Boolean {
        if (fullscreen || ahead.isEmpty()) return false
        val s = ahead.removeAt(ahead.size - 1)
        lastMove = 1
        // a video that was closed meanwhile is opened again
        if (s is Screen.Watch && playback.video?.id != s.id) playback.open(Video(s.id, ""))
        if (s is Screen.Watch) stack.removeAll { it is Screen.Watch }
        stack.add(s)
        return true
    }

    fun go(s: Screen) {
        ahead.clear()
        if (s in TABS) {
            if (stack.size == 1 && stack[0] == s) { reselect++; return }
            lastMove = 0
            stack.clear(); stack.add(s)
            return
        }
        lastMove = 1
        // opening the video that is already open just returns to it
        if (s is Screen.Watch) stack.removeAll { it is Screen.Watch }
        stack.add(s)
    }

    /** false when there is nowhere to go back to (the app may close) */
    /** the TV's pages: Back first moves to the menu, and on Home asks to be pressed again (TvApp) */
    var tvBack: (() -> Boolean)? = null
    /** what's open on the page in front (a panel, lyrics...): Back closes the newest one first; see BackHook */
    val backHooks = mutableListOf<() -> Boolean>()

    fun back(hooks: Boolean = true): Boolean {
        if (hooks) {
            for (h in backHooks.toList().asReversed()) if (h()) return true
            tvBack?.let { if (it()) return true }
        }
        if (fullscreen) { setFull(false); return true }
        if (stack.size > 1) { lastMove = -1; ahead.add(stack.removeAt(stack.size - 1)); return true }
        if (stack.last() != homeRoot) { lastMove = 0; stack[0] = homeRoot; return true }
        return false
    }

    fun setFull(on: Boolean) {
        fullscreen = on
        platform.setFullscreen(on)
    }

    /** the TV has asked "Who's watching?" since it opened (or there is only one person) */
    var whoAsked by mutableStateOf(parent != null)

    /** someone else uses the app now: their library, recommendations, settings and server login */
    fun switchPerson(id: String) {
        if (id == store.people.value.current) return
        playback.close()
        store.switchPerson(id)
        sync.personChanged()
        stack.clear(); stack.add(homeRoot)
        sync.refreshSoon()
    }

    /** a Short opened from a list: the Shorts player starts on it (then goes on with others) */
    var shortFirst: Video? = null

    fun watch(v: Video, queue: List<Video> = emptyList()) {
        // a middle click: in a window of its own
        if (NewWindow.next && !v.short) { NewWindow.next = false; if (platform.newWindow(v, queue)) return }
        // a Short plays upright in the Shorts player, as on YouTube, not in the wide video page
        if (v.short && !noShorts) {
            playback.close()
            shortFirst = v
            // on top of where it was tapped, so Back returns there (the Shorts tab itself starts over)
            ahead.clear(); lastMove = 1
            if (current != Screen.Shorts) stack.add(Screen.Shorts)
            return
        }
        playback.open(v, queue)
        go(Screen.Watch(v.id))
    }

    /** a downloader app (YTDLnis, Seal) takes the link; without one, say which to get */
    fun download(v: Video) {
        if (!platform.externalDownload("https://www.youtube.com/watch?v=${v.id}")) toast(tr("Install YTDLnis or Seal to download videos"))
    }

    /** A link in a description or a post: YouTube's own (videos, channels, playlists, #tags) open here, the rest in the browser. */
    fun openLink(url: String) {
        val u = if (url.startsWith("www.")) "https://$url" else url
        if (!Regex("^https?://([\\w-]+\\.)?(youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            platform.openExternal(u); return
        }
        Regex("/hashtag/([^/?#]+)").find(u)?.let { go(Screen.Search("#" + urlDecode(it.groupValues[1]))); return }
        scope.launch {
            val r = runCatching { YouTube.resolve(u) }.getOrNull()
            when (r?.first) {
                "video" -> watch(Video(r.second, ""))
                "playlist" -> go(Screen.Playlist(r.second))
                "channel" -> go(Screen.Channel(r.second))
                else -> platform.openExternal(u)
            }
        }
    }

    fun share(title: String, url: String) {
        if (!platform.share(title, url)) toast(tr("Link copied"))
    }

    /** a short message at the bottom; a newer one replaces the one showing (quick key presses don't queue up, #107) */
    private var toastJob: kotlinx.coroutines.Job? = null
    fun toast(msg: String) {
        // cancelling the last one takes it off the screen, or out of the queue if it hasn't shown yet
        toastJob?.cancel()
        toastJob = scope.launch { snackbar.showSnackbar(msg) }
    }

    /**
     * Once, ever: after the fifth video watched for a while, or the first cast to a TV, a small note
     * with a link to star the project on GitHub. Not on TVs (no browser to open it), never again after.
     */
    fun starNote(cast: Boolean = false) {
        val s = store.settings.value
        if (s.starAsked || platform.tv) return
        if (!cast) {
            store.settings.update { it.copy(watchedCount = it.watchedCount + 1) }
            if (s.watchedCount + 1 < 5) return
        }
        store.settings.update { it.copy(starAsked = true) }
        scope.launch {
            val r = snackbar.showSnackbar(tr("Enjoying TubeHaus? A star on GitHub helps others find it."), actionLabel = tr("Star"),
                withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) platform.openExternal("https://github.com/biolend83/tubehaus")
        }
    }
}
