package app.tubehaus.ui

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
}

val TABS = listOf(Screen.Home, Screen.Shorts, Screen.Subs, Screen.Library)

class AppState(val platform: Platform, val store: Store) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    init {
        // the YouTube visitor identity is kept with the app's data (never in backups or exports)
        InnerTube.identityFile = java.io.File(platform.dataDir, "identity.json")
        Remote.clientsFile = java.io.File(platform.dataDir, "clients.json")
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
        if (!now && (!set.checkUpdates || System.currentTimeMillis() - set.checkedAt < 10 * 60_000L)) return
        scope.launch {
            store.settings.update { it.copy(checkedAt = System.currentTimeMillis()) }
            Remote.refreshClients()
            val os = System.getProperty("os.name")
            // Linux: the release page, to pick deb/rpm/tar.gz
            // macOS: the Apple Silicon or the Intel build, whichever this Mac runs
            val mac = if (System.getProperty("os.arch") == "aarch64") "-mac-arm64.dmg" else "-mac-x64.dmg"
            val desktopFile = when { os.startsWith("Windows") -> ".msi"; os.startsWith("Mac") -> mac; else -> null }
            // one release per version (app-v*) carries every platform's file
            val r = Remote.newerRelease("app-v", VERSION, if (platform.name == "android") ".apk" else desktopFile)
            if (r != null && (now || r.version != store.settings.value.skipVersion)) update = r
            else if (!quiet) toast(tr("TubeHaus is up to date"))
        }
    }

    val feed = Feed(store)
    val rec = Recommender(store, feed)
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

    fun go(s: Screen) {
        if (s in TABS) {
            stack.clear(); stack.add(s)
            return
        }
        // opening the video that is already open just returns to it
        if (s is Screen.Watch) stack.removeAll { it is Screen.Watch }
        stack.add(s)
    }

    /** false when there is nowhere to go back to (the app may close) */
    fun back(): Boolean {
        if (fullscreen) { setFull(false); return true }
        if (stack.size > 1) { stack.removeAt(stack.size - 1); return true }
        if (stack.last() != Screen.Home) { stack[0] = Screen.Home; return true }
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

    fun share(title: String, url: String) {
        if (!platform.share(title, url)) toast(tr("Link copied"))
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }
}
