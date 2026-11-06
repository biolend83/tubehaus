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
}

/** the screens a tab opens: going to one starts over from it (Music and Live are tabs at the top on phones) */
val TABS = listOf(Screen.Home, Screen.Shorts, Screen.Subs, Screen.Library) + TOP_TOPICS.map { Screen.Topic(it) }

class AppState(val platform: Platform, val store: Store) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
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
    val sync by lazy { app.tubehaus.data.ServerSync(store, scope) }

    /** a newer version to offer, found by [checkForUpdates] */
    var update by mutableStateOf<Remote.Release?>(null)

    /** each time the app opens (at most every 10 minutes) unless [now]; [quiet]: say nothing when there is nothing new */
    fun checkForUpdates(now: Boolean = false, quiet: Boolean = true) {
        val set = store.settings.value
        // iOS: installed by hand (SideStore), there is no file to offer
        if (platform.name == "ios") return
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
        private set

    fun go(s: Screen) {
        if (s in TABS) {
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

    fun back(): Boolean {
        tvBack?.let { if (it()) return true }
        if (fullscreen) { setFull(false); return true }
        if (stack.size > 1) { lastMove = -1; stack.removeAt(stack.size - 1); return true }
        if (stack.last() != Screen.Home) { lastMove = 0; stack[0] = Screen.Home; return true }
        return false
    }

    fun setFull(on: Boolean) {
        fullscreen = on
        platform.setFullscreen(on)
    }

    fun watch(v: Video, queue: List<Video> = emptyList()) {
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

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }
}
