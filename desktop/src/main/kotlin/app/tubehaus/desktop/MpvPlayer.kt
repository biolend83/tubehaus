package app.tubehaus.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Format
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.VideoPlayer
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * mpv (libmpv) as the desktop player. mpv plays YouTube's separate video and audio streams
 * together (the audio as an external track) and decodes on the GPU; through its render API it
 * draws each frame, subtitles included, into memory, and Compose paints that frame like any
 * image. So there is no native window inside the app: the controls (PlayerControls.kt), menus
 * and the mini player sit on top of the video exactly as on Android.
 *
 * YouTube gives one URL per quality, so changing quality reloads at the same position.
 */
class MpvPlayer : VideoPlayer {
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = st

    private val lib = LibMpv.load()
    private var h: Pointer? = null
    private val lock = Any()

    private var stream: Stream? = null
    private var opts = PlayOptions()
    private var pinned = 0
    private var caption: Caption? = null
    private var translateTo: String? = null
    /** the subtitle track added to the file (shown or not): the captions button only flips its visibility */
    @Volatile private var loadedCaption: Caption? = null
    @Volatile private var loadedTranslate: String? = null

    val available get() = lib != null

    // ------------------------------------------------------------------ the picture
    /** where mpv draws, at the current size */
    private class Target(val w: Int, val h: Int) {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(w, h, ColorType.RGB_888X, ColorAlphaType.OPAQUE), (w * 4 + 63) / 64 * 64)
        }
        val addr = Pointer(bitmap.peekPixels()!!.addr)
    }

    private var render: Pointer? = null
    private val renderLock = Any()
    private val wake = Semaphore(0)
    // kept in a field: JNA must not lose the callback while mpv holds it
    private val onUpdate = LibMpv.UpdateCallback { if (wake.availablePermits() == 0) wake.release() }
    private val apiType = LibMpv.cString("sw")
    // R G B and a byte mpv leaves at 0: Skia's RGB_888X never reads that byte (as alpha it would make the picture see-through)
    private val pixelFormat = LibMpv.cString("rgb0")
    private val swSize = Memory(8)
    private val stride = Memory(8)
    private var target: Target? = null
    /**
     * The frame on screen: a copy made once per frame and freed by hand when the next one
     * replaces it. (Drawing the bitmap itself made Skia copy it on every repaint into native
     * memory the garbage collector barely sees, and after a few minutes the app ran out of it.)
     */
    @Volatile private var front: Image? = null
    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
    /** bumped for every new frame: the Canvas reads it, so Compose repaints (only repaints) */
    private val frames = mutableIntStateOf(0)
    @Volatile private var view = IntSize.Zero
    @Volatile private var views = 0
    @Volatile private var closing = false
    /** the view changed size: draw the current frame again even when paused */
    @Volatile private var redraw = false

    private fun mpv(): Pointer? {
        synchronized(lock) {
            h?.let { return it }
            if (closing) return null
            val l = lib ?: return null
            val p = l.mpv_create() ?: return null
            fun o(k: String, v: String) = l.mpv_set_option_string(p, k, v)
            o("vo", "libmpv")
            if (o("hwdec", "auto-copy-safe") < 0) o("hwdec", "auto-copy")   // GPU decoding, frames copied back for us
            o("idle", "yes"); o("keep-open", "no"); o("ytdl", "no")
            o("input-default-bindings", "no"); o("input-vo-keyboard", "no"); o("osc", "no")
            // read far ahead (5 minutes, up to 500 MB) and keep the last part for seeking back
            o("cache", "yes"); o("cache-secs", "300"); o("demuxer-readahead-secs", "300")
            o("demuxer-max-bytes", "500MiB"); o("demuxer-max-back-bytes", "100MiB")
            o("sub-auto", "no"); o("sub-font-size", "42"); o("sub-border-size", "2.5"); o("sub-margin-y", "40")
            o("audio-display", "no"); o("audio-client-name", "TubeHaus")
            o("terminal", "no"); o("msg-level", "all=warn")
            if (l.mpv_initialize(p) < 0) { l.mpv_terminate_destroy(p); return null }
            val res = PointerByReference()
            if (l.mpv_render_context_create(res, p, LibMpv.params(LibMpv.RENDER_PARAM_API_TYPE to apiType)) >= 0) {
                render = res.value
                l.mpv_render_context_set_update_callback(res.value, onUpdate, null)
                thread(isDaemon = true, name = "mpv-render") { renderLoop(l) }
            }
            h = p
            thread(isDaemon = true, name = "mpv-events") { events(l, p) }
            thread(isDaemon = true, name = "mpv-state") { poll(l, p) }
            return p
        }
    }

    private fun renderLoop(l: LibMpv) {
        while (!closing) {
            wake.tryAcquire(250, TimeUnit.MILLISECONDS)
            synchronized(renderLock) {
                val ctx = render ?: return
                val fresh = l.mpv_render_context_update(ctx) and LibMpv.RENDER_UPDATE_FRAME != 0L
                if (!fresh && !redraw) return@synchronized
                // nothing on screen (another page is open): mpv drops the frame and plays on
                if (views == 0) return@synchronized
                val (w, h) = target() ?: return@synchronized
                redraw = false
                val t = target?.takeIf { it.w == w && it.h == h } ?: Target(w, h).also { target = it }
                swSize.setInt(0, w); swSize.setInt(4, h)
                stride.setLong(0, t.bitmap.rowBytes.toLong())
                val p = LibMpv.params(
                    LibMpv.RENDER_PARAM_SW_SIZE to swSize,
                    LibMpv.RENDER_PARAM_SW_FORMAT to pixelFormat,
                    LibMpv.RENDER_PARAM_SW_STRIDE to stride,
                    LibMpv.RENDER_PARAM_SW_POINTER to t.addr,
                )
                if (l.mpv_render_context_render(ctx, p) >= 0) {
                    t.bitmap.notifyPixelsChanged()
                    show(Image.makeFromBitmap(t.bitmap))      // a copy: mpv can draw the next one meanwhile
                }
            }
        }
    }

    /**
     * The size mpv draws at: the view's, but at most 1920x1080 (mpv scales on the CPU here,
     * and Compose stretches the last bit smoothly). mpv keeps the video's shape itself.
     */
    private fun target(): Pair<Int, Int>? {
        val v = view
        if (v.width < 2 || v.height < 2) return null
        val s = min(1.0, min(1920.0 / v.width, 1080.0 / v.height))
        return (v.width * s).roundToInt().coerceAtLeast(2) to (v.height * s).roundToInt().coerceAtLeast(2)
    }

    /** puts [img] on screen; the old frame is freed on the UI thread, after any repaint still using it */
    private fun show(img: Image?) {
        val old = front
        front = img
        frames.intValue++
        if (old != null) SwingUtilities.invokeLater { old.close() }
    }

    private fun clearPicture() = show(null)

    // ------------------------------------------------------------------ mpv's side
    private fun cmd(vararg a: String) {
        val p = mpv() ?: return
        lib!!.mpv_command(p, arrayOf(*a, null))
    }

    /** Settings → Read further ahead: 20 minutes and up to 2 GB instead of 5 minutes and 500 MB */
    override fun setReadAhead(long: Boolean) {
        set("cache-secs", if (long) "1200" else "300"); set("demuxer-readahead-secs", if (long) "1200" else "300")
        set("demuxer-max-bytes", if (long) "2GiB" else "500MiB")
    }

    private fun set(k: String, v: String) {
        val p = mpv() ?: return
        lib!!.mpv_set_property_string(p, k, v)
    }

    private fun events(l: LibMpv, p: Pointer) {
        while (true) {
            val e = l.mpv_wait_event(p, 1.0)
            when (e.getInt(0)) {
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_END_FILE -> {
                    val data = e.getPointer(16)
                    val reason = data?.getInt(0) ?: -1
                    if (reason == LibMpv.END_FILE_REASON_EOF && st.value.videoId != null) st.update { it.copy(ended = true, playing = false) }
                    if (reason == LibMpv.END_FILE_REASON_ERROR) st.update { it.copy(error = "Playback failed", buffering = false) }
                }
                LibMpv.EVENT_FILE_LOADED -> {
                    st.update { it.copy(ended = false, error = null) }
                    // mpv downloads the track here, on this thread, never on the UI's
                    loadedCaption?.let { c -> YouTube.captionUrl(c, loadedTranslate).let { u -> cmd("sub-add", Net.captionFile(u) ?: u, "select", c.name) } }
                }
            }
        }
    }

    private fun num(l: LibMpv, p: Pointer, name: String): Double? {
        val r = DoubleByReference()
        return if (l.mpv_get_property(p, name, LibMpv.FORMAT_DOUBLE, r) >= 0) r.value else null
    }

    private fun str(l: LibMpv, p: Pointer, name: String): String? {
        val r = l.mpv_get_property_string(p, name) ?: return null
        return try { r.getString(0, "UTF-8") } finally { l.mpv_free(r) }
    }

    private fun poll(l: LibMpv, p: Pointer) {
        while (!closing) {
            Thread.sleep(200)
            val s = stream ?: continue
            val pos = num(l, p, "time-pos")
            val dur = num(l, p, "duration")
            val paused = str(l, p, "pause") == "yes"
            val idle = str(l, p, "idle-active") == "yes"
            val waiting = str(l, p, "paused-for-cache") == "yes" || str(l, p, "seeking") == "yes"
            val speed = num(l, p, "speed")
            val cached = num(l, p, "demuxer-cache-time")
            st.update {
                it.copy(
                    videoId = s.id,
                    playing = !paused && !idle && !it.ended,
                    // from loadfile until the first frame there is no position yet
                    buffering = !it.ended && it.error == null && (waiting || (!paused && pos == null)),
                    positionMs = pos?.let { x -> (x * 1000).toLong() } ?: it.positionMs,
                    durationMs = dur?.let { x -> (x * 1000).toLong() } ?: (s.lengthSec * 1000L),
                    speed = speed?.toFloat() ?: it.speed,
                    bufferedMs = cached?.let { x -> (x * 1000).toLong() } ?: 0,
                )
            }
        }
    }

    // ------------------------------------------------------------------ choosing streams
    /** Windows GPUs decode VP9 almost everywhere; AV1 only on recent ones, H.264 everywhere. */
    private fun pickVideo(s: Stream, cap: Int): Format? {
        val ok = s.video.filter { it.height <= cap }.ifEmpty { s.video.sortedBy { it.height }.take(1) }
        val best = ok.maxOfOrNull { it.height } ?: return null
        val at = ok.filter { it.height == best }
        return at.firstOrNull { it.codecFamily == "vp9" } ?: at.firstOrNull { it.codecFamily == "h264" } ?: at.first()
    }

    private fun pickAudio(s: Stream): Format? {
        val main = s.audio.filter { it.audioDefault }.ifEmpty { s.audio }
        return main.filter { it.codecFamily == "opus" }.maxByOrNull { it.bitrate } ?: main.maxByOrNull { it.bitrate }
    }

    private fun source(u: String) = if (u.startsWith("file:")) java.io.File(java.net.URI(u)).absolutePath else LocalProxy.wrap(u)

    private fun open(startMs: Long) {
        val s = stream ?: return
        if (mpv() == null) {
            st.update { it.copy(error = LibMpv.MISSING, buffering = false) }
            return
        }
        val heights = s.video.map { it.height }.distinct().sorted()
        st.update { it.copy(heights = heights, height = pinned, buffering = true) }
        val url: String
        cmd("change-list", "audio-files", "clr", "")
        if (s.live || s.video.isEmpty()) {
            url = s.hls ?: return
        } else {
            val cap = if (pinned > 0) pinned else opts.maxHeight
            val v = pickVideo(s, cap) ?: return
            // through the local proxy, which fetches in 10 MB pieces (YouTube crawls on one long request);
            // a downloaded video is opened straight from its files
            url = source(v.url)
            pickAudio(s)?.let { a -> cmd("change-list", "audio-files", "append", source(a.url)) }
        }
        set("start", if (startMs > 0) "+%.1f".format(java.util.Locale.ROOT, startMs / 1000.0) else "none")
        set("loop-file", if (opts.loop) "inf" else "no")
        set("speed", opts.speed.toString())
        set("pause", if (opts.autoplay) "no" else "yes")
        set("force-media-title", opts.title.ifEmpty { s.title })
        cmd("loadfile", url, "replace")
    }

    // ------------------------------------------------------------------ VideoPlayer
    override fun load(s: Stream, o: PlayOptions) {
        stream = s
        opts = o
        pinned = 0
        caption = o.captions
        translateTo = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        loadedCaption = caption ?: o.standbyCaption
        loadedTranslate = if (caption != null) translateTo else o.standbyTranslate
        set("sub-visibility", if (caption != null) "yes" else "no")
        val muted = st.value.muted
        st.value = PlayerState(videoId = s.id, buffering = true, speed = o.speed, captions = caption, volume = o.volume, muted = muted)
        clearPicture()
        set("volume", (o.volume * 100).roundToInt().toString())
        open(o.startMs)
    }

    override fun play() {
        if (st.value.ended) { st.update { it.copy(ended = false) }; open(0); return }
        set("pause", "no")
    }

    override fun pause() = set("pause", "yes")
    override fun seekTo(ms: Long) {
        if (st.value.ended) { st.update { it.copy(ended = false) }; open(ms); return }
        st.update { it.copy(positionMs = ms) }
        cmd("seek", "%.1f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute")
    }
    override fun setSpeed(x: Float) { opts = opts.copy(speed = x); set("speed", x.toString()); st.update { it.copy(speed = x) } }

    override fun setHeight(h: Int) {
        pinned = h
        open(st.value.positionMs)
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        caption = c
        this.translateTo = translateTo
        st.update { it.copy(captions = c) }
        if (c == null || (c == loadedCaption && translateTo == loadedTranslate)) {
            set("sub-visibility", if (c != null) "yes" else "no")
            return
        }
        loadedCaption = c
        loadedTranslate = translateTo
        // another track: mpv downloads it, so not on the UI thread
        thread(isDaemon = true, name = "mpv-subs") {
            cmd("sub-remove")
            YouTube.captionUrl(c, translateTo).let { u -> cmd("sub-add", Net.captionFile(u) ?: u, "select", c.name) }
            set("sub-visibility", "yes")
        }
    }

    override fun setLoop(on: Boolean) { opts = opts.copy(loop = on); set("loop-file", if (on) "inf" else "no") }

    override val hasVolume get() = true
    override fun setVolume(v: Float) {
        val x = v.coerceIn(0f, 1f)
        set("volume", (x * 100).roundToInt().toString())
        st.update { it.copy(volume = x) }
    }

    override fun setMuted(on: Boolean) {
        set("mute", if (on) "yes" else "no")
        st.update { it.copy(muted = on) }
    }

    override fun stop() {
        stream = null
        cmd("stop")
        clearPicture()
        st.value = PlayerState(volume = st.value.volume, muted = st.value.muted)
    }

    @Composable
    override fun View(modifier: Modifier) {
        if (!available) {
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                Text(LibMpv.MISSING, color = Color.White)
            }
            return
        }
        DisposableEffect(Unit) {
            views++
            redraw = true
            onDispose { views-- }
        }
        // transparent until the first frame, so a thumbnail underneath shows through
        Canvas(modifier.onSizeChanged { view = it; redraw = true; wake.release() }) {
            frames.intValue          // repaint on every new frame
            val img = front ?: return@Canvas
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(
                    img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(size.width, size.height), sampling, null, true,
                )
            }
        }
    }

    fun shutdown() {
        closing = true
        val p = synchronized(lock) { h.also { h = null } } ?: return
        synchronized(renderLock) {
            render?.let { lib?.mpv_render_context_free(it) }   // before the core goes away
            render = null
        }
        lib?.mpv_terminate_destroy(p)
    }
}
