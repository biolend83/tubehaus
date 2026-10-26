package app.tubehaus.android

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.app.UiModeManager
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.Request
import app.tubehaus.engine.Net
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import app.tubehaus.data.Store
import app.tubehaus.ui.AppState
import app.tubehaus.ui.Platform
import app.tubehaus.ui.VideoPlayer
import java.io.File
import kotlin.math.roundToInt

class TubeApp : Application() {
    lateinit var platform: AndroidPlatform
        private set
    val state: AppState by lazy { AppState(platform, Store(platform.dataDir)) }

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
    }
}

val Context.tube: TubeApp get() = applicationContext as TubeApp

class AndroidPlatform(private val app: Application) : Platform {
    override val name = "android"
    override val dataDir: File = File(app.filesDir, "tubehaus")
    override val cacheDir: File = app.cacheDir
    /** a TV says so by its UI mode (Android TV, Google TV) or the leanback feature */
    private val isTv: Boolean by lazy {
        (app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    // Settings → TV mode can force it either way (a TV box that doesn't say it is one)
    override val tv: Boolean
        get() = when (runCatching { (app as TubeApp).state.store.settings.value.tvMode }.getOrDefault(0)) {
            1 -> true
            2 -> false
            else -> isTv
        }
    // a TV has a remote, not a touch screen: it gets the wide layout and the keyboard player
    override val touch: Boolean get() = !tv
    val androidPlayer by lazy {
        AndroidPlayer.longBuffer = runCatching { (app as TubeApp).state.store.settings.value.longBuffer }.getOrDefault(false)
        AndroidPlayer(app)
    }
    override val player: VideoPlayer get() = androidPlayer

    /** set by the activity: runs file pickers and full screen, which need an Activity */
    var activity: MainActivity? = null

    override fun share(title: String, url: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { app.startActivity(chooser); true } catch (e: ActivityNotFoundException) { false }
    }

    override fun openExternal(url: String) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {}
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        activity?.saveFile(name, mime, bytes, done) ?: done(false)
    }

    override fun openTextFile(done: (String?) -> Unit) {
        activity?.openTextFile(done) ?: done(null)
    }

    override fun setImmersive(on: Boolean) {
        activity?.setHideBars(on)
    }

    override fun setFullscreen(on: Boolean) {
        activity?.setFullscreen(on)
        // brightness set by the gesture lasts while in full screen; out of it the system's applies again
        if (!on) activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }

    private val audio by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** this window's brightness; until the gesture sets one it follows the system setting */
    override fun brightness(): Float? {
        val w = activity?.window ?: return null
        val own = w.attributes.screenBrightness
        if (own >= 0) return own
        return runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    override fun setBrightness(v: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }

    override fun mediaVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setMediaVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt(), 0)
    }

    /** downloads the APK into the cache and hands it to the system installer (same signing key, so it updates in place) */
    override fun installUpdate(url: String, progress: (Float) -> Unit, done: (Boolean) -> Unit): Boolean {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = runCatching {
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val apk = File(dir, "TubeHaus.apk")
                Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    require(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body ?: error("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1
                    body.byteStream().use { inp ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            var shown = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); got += n
                                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                                if (pct != shown) { shown = pct; main.post { progress(pct / 100f) } }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".updates", apk)
                main.post { progress(1f) }
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            main.post { done(ok) }
        }.start()
        return true
    }

    override fun wallpaperColors(dark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT < 31) null else if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)

    /** YTDLnis and Seal take a shared link and download it; the first one installed gets it */
    override fun externalDownload(url: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        val pm = app.packageManager
        for (pkg in listOf("com.deniscerri.ytdl", "com.junkfood.seal", "com.junkfood.seal.foss")) {
            val target = Intent(send).setPackage(pkg)
            if (pm.queryIntentActivities(target, 0).isEmpty()) continue
            return try { app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: ActivityNotFoundException) { false }
        }
        return false
    }

    override fun metered(): Boolean =
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}
