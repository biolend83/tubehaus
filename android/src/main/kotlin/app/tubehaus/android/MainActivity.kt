package app.tubehaus.android

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.App
import app.tubehaus.ui.Screen
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val state get() = tube.state
    private var controller: ListenableFuture<MediaController>? = null

    private var pendingSave: Pair<ByteArray, (Boolean) -> Unit>? = null
    private var pendingOpen: ((String?) -> Unit)? = null

    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val (bytes, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult done(false)
        done(runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.isSuccess)
    }
    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        if (uri == null) return@registerForActivityResult done(null)
        done(runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull())
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tube.platform.activity = this
        if (Build.VERSION.SDK_INT >= 31) lifecycleScope.launch {
            state.platform.player.state.map { it.playing }.distinctUntilChanged().collect { runCatching { setPictureInPictureParams(pipParams()) } }
        }
        setContent {
            Box(Modifier.fillMaxSize().let { if (state.fullscreen || state.pip) it else it.safeDrawingPadding() }) { App(state) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!state.back()) moveTaskToBack(true)
            }
        })
        // the media session service keeps playing in the background and shows the controls
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        // the media notification; a TV has none worth asking for (and the question would take the remote's focus)
        if (Build.VERSION.SDK_INT >= 33 && !state.platform.tv) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** a shared or opened YouTube link */
    private fun handle(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        state.scope.launch {
            val r = runCatching { YouTube.resolve(url) }.getOrNull() ?: return@launch
            when (r.first) {
                "video" -> state.watch(Video(r.second, ""))
                "playlist" -> state.go(Screen.Playlist(r.second))
                "channel" -> state.go(Screen.Channel(r.second))
            }
        }
    }

    fun saveFile(name: String, @Suppress("UNUSED_PARAMETER") mime: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        pendingSave = bytes to done
        saver.launch(name)
    }

    fun openTextFile(done: (String?) -> Unit) {
        pendingOpen = done
        opener.launch(arrayOf("*/*"))
    }

    // Full screen the YouTube way: the button turns the screen and holds it there until the phone
    // is physically turned the same way; from then on turning the phone enters and leaves full
    // screen by itself (as long as the system's auto-rotate allows it).
    /** the status and navigation bars stay hidden outside full screen too (Settings) */
    private var immersive = false
    private var full = false

    fun setHideBars(on: Boolean) {
        immersive = on
        bars(!on && !full)
    }

    fun setFullscreen(on: Boolean) {
        full = on
        bars(!on && !immersive)
        // a TV never turns
        if (state.platform.tv) return
        // tablets keep whichever way they're held when leaving full screen; phones go back upright
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = when {
            on -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            tablet -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun bars(show: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (show) c.show(WindowInsetsCompat.Type.systemBars())
        else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private val tilt by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                val sideways = angle in 60..120 || angle in 240..300
                val upright = angle <= 30 || angle >= 330
                // with the system's auto-rotate off, letting go would snap back to the locked
                // (portrait) way and leave full screen: then the button's direction stays
                if (!autoRotate()) return
                when (requestedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> if (sideways) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> if (upright) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    private fun autoRotate() =
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    override fun onStart() {
        super.onStart()
        // opening the app again (it often stays alive in the background): look for a new version
        state.checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (!state.platform.tv && tilt.canDetectOrientation()) tilt.enable()
    }

    override fun onPause() {
        tilt.disable()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (state.pip || state.platform.tv) return
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (newConfig.smallestScreenWidthDp >= 600) return
        if (landscape && !state.fullscreen && state.current is Screen.Watch && state.playback.video != null) {
            state.fullscreen = true; bars(false)
        } else if (!landscape && state.fullscreen) {
            state.fullscreen = false; bars(true)
        }
    }

    private fun watchingVideo() = state.playback.video != null && state.platform.player.state.value.playing

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(watchingVideo()).setSeamlessResizeEnabled(true)
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // going home while a video plays: keep it in a small window
        if (watchingVideo() && Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        state.pip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        state.store.flush()
        // background play is off: pause when the app goes away (but not into picture-in-picture)
        if (!state.store.settings.value.backgroundPlay && !state.pip) state.platform.player.pause()
    }

    override fun onDestroy() {
        if (tube.platform.activity === this) tube.platform.activity = null
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
