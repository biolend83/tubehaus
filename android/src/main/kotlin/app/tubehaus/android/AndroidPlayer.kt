package app.tubehaus.android

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.net.Uri
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Dash
import app.tubehaus.engine.Format
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.VideoPlayer
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ExoPlayer playing YouTube's adaptive streams through a DASH manifest built on the device
 * (see [Dash]), so quality follows the connection and can be pinned from the app.
 * One player for the whole app; [PlaybackService] puts it in a media session for the
 * notification, the lock screen and background play.
 */
@OptIn(UnstableApi::class)
class AndroidPlayer(private val ctx: Context) : VideoPlayer {
    companion object {
        /** Settings → Read further ahead, set before the player is first made */
        @Volatile var longBuffer = false
    }

    override fun setReadAhead(long: Boolean) { longBuffer = long }

    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = st
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** a TV (the same test as TubeApp's): only there is the video fitted to the set, see [decodes] and the read-ahead below */
    private val tv: Boolean by lazy {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private val dataSource = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(Net.client))

    val exo: ExoPlayer = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        // read far ahead (up to 5 minutes, within ExoPlayer's memory budget) so a slow patch of network doesn't stop the video;
        // Settings → Read further ahead: up to 20 minutes, with up to 400 MB for it (150 MB on a TV: many have little memory)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, if (longBuffer) 1_200_000 else 300_000, 1_500, 3_000)
                .setBackBuffer(30_000, true)
                .apply { if (longBuffer) setTargetBufferBytes((if (tv) 150 else 400) * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true) }
                .build(),
        )
        .build()

    private var stream: Stream? = null
    private var opts = PlayOptions()
    private var caption: Caption? = null
    private var translateTo: String? = null
    /** the subtitle track inside the media item (shown or not): toggling it never reloads the video */
    private var loadedCaption: Caption? = null
    private var loadedTranslate: String? = null
    /** a car is connected (Android Auto): no picture is loaded */
    private var car = false
    /** the view on screen, for [frame] */
    private var view: PlayerView? = null
    /** the captions, drawn by a view of ours over the video in place of the PlayerView's own (see [youTubeLook]) */
    private var captions: SubtitleView? = null

    /** codec families this phone decodes in hardware, best first */
    private val codecs: List<String> by lazy {
        val hw = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && isHardware(it) }
            .flatMap { it.supportedTypes.toList() }.map { it.lowercase() }.toSet()
        buildList {
            if ("video/av01" in hw) add("av1")
            if ("video/x-vnd.on2.vp9" in hw) add("vp9")
            add("h264")
            add("vp9"); add("av1")
        }.distinct()
    }

    private val decoders by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }

    /** the screen in pixels (a TV's panel, not its smaller menu resolution) */
    private val screen by lazy { Util.getCurrentDisplayModeSize(ctx) }

    /**
     * For a TV: whether the screen shows more than the size below and a hardware decoder takes this size at this
     * frame rate. Some TVs freeze outright on 8K (or 8K at 60) rather than fail, and software decoding 4K stutters on them.
     */
    private fun decodes(f: Format): Boolean {
        val type = when (f.codecFamily) { "av1" -> "video/av01"; "vp9" -> "video/x-vnd.on2.vp9"; "h264" -> "video/avc"; else -> return true }
        if (f.width <= 0 || f.height <= 0) return true
        // the short sides: a 4096x2160 film still counts as 4K, a vertical video by its width
        if (screen.x > 0 && screen.y > 0 && minOf(f.width, f.height) > minOf(screen.x, screen.y)) return false
        return decoders.any { c ->
            isHardware(c) && type in c.supportedTypes.map { it.lowercase() } &&
                runCatching {
                    val v = c.getCapabilitiesForType(type).videoCapabilities ?: return@runCatching true
                    if (f.fps > 0) v.areSizeAndRateSupported(f.width, f.height, f.fps.toDouble()) else v.isSizeSupported(f.width, f.height)
                }.getOrDefault(true)
        }
    }

    /** isHardwareAccelerated is Android 10+; before that, the software decoders are the ones named OMX.google.* / c2.android.* */
    private fun isHardware(c: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) c.isHardwareAccelerated
        else c.name.lowercase().let { !it.startsWith("omx.google.") && !it.startsWith("c2.android.") && !it.contains(".sw.") }

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
            override fun onCues(cueGroup: CueGroup) { captions?.setCues(placed(cueGroup.cues)) }
            override fun onPlayerError(error: PlaybackException) {
                st.update { it.copy(error = error.message ?: error.errorCodeName) }
                // stream URLs expire after a few hours: fetch fresh ones and carry on
                val s = stream ?: return
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    val pos = exo.currentPosition
                    scope.launch {
                        runCatching { YouTube.stream(s.id) }.onSuccess { fresh ->
                            if (stream?.id == s.id) load(fresh, opts.copy(startMs = pos))
                        }
                    }
                }
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            // YouTube often turns down its machine translations (HTTP 429): then the track as it is
            override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                // side-loaded subtitles load as a plain file (track type unknown): tell them by the address
                val c = loadedCaption ?: return
                if (loadedTranslate == null || info.dataSpec.uri.toString() != YouTube.captionUrl(c, loadedTranslate)) return
                caption?.let { setCaptions(it, null) }
            }
        })
        scope.launch {
            while (true) {
                if (exo.isPlaying || exo.isLoading) sync()
                delay(500)
            }
        }
    }

    private fun sync() {
        val heights = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }.filter { it > 0 }.distinct().sorted()
        val pinned = exo.trackSelectionParameters.minVideoHeight.takeIf { it > 0 && it != Int.MAX_VALUE } ?: 0
        // one adaptation set per container and language (see Dash): one entry per language
        val audio = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        val languages = audio.mapNotNull { g -> g.getTrackFormat(0).let { f -> f.language?.let { l -> l to (f.label ?: l) } } }.distinctBy { it.first }
        val playingLang = audio.firstOrNull { it.isSelected }?.getTrackFormat(0)?.language
        st.update {
            it.copy(
                videoId = stream?.id,
                playing = exo.isPlaying,
                buffering = exo.playbackState == Player.STATE_BUFFERING,
                positionMs = exo.currentPosition.coerceAtLeast(0),
                durationMs = exo.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                bufferedMs = exo.bufferedPosition.coerceAtLeast(0),
                ended = exo.playbackState == Player.STATE_ENDED,
                heights = heights,
                height = pinned,
                speed = exo.playbackParameters.speed,
                captions = caption,
                captionsTranslated = translateTo.takeIf { caption != null },
                audioTracks = languages,
                audioTrack = playingLang,
                error = if (exo.playerError == null) null else it.error,
            )
        }
    }

    override fun load(s: Stream, o: PlayOptions) {
        // the same video again (fresh stream URLs) keeps the language you picked
        val audioLang = exo.trackSelectionParameters.preferredAudioLanguages.firstOrNull()?.takeIf { stream?.id == s.id }
        stream = s
        opts = o
        caption = o.captions
        translateTo = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        loadedCaption = caption ?: o.standbyCaption
        loadedTranslate = if (caption != null) translateTo else o.standbyTranslate
        st.value = PlayerState(videoId = s.id, buffering = true, speed = o.speed, captions = caption, captionsTranslated = translateTo)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMinVideoSize(0, 0)
            .setMaxVideoSize(Int.MAX_VALUE, o.maxHeight)
            .setPreferredAudioLanguage(audioLang)      // null: the original track (the manifest's "main" one)
            .setPreferredTextLanguage(caption?.lang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, caption == null)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, car || o.audioOnly)
            .build()
        exo.setMediaItem(item(s), o.startMs)
        exo.repeatMode = if (o.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.playbackParameters = PlaybackParameters(o.speed)
        exo.prepare()
        exo.playWhenReady = o.autoplay
    }

    private fun item(s: Stream): MediaItem {
        val b = MediaItem.Builder().setMediaId(s.id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(opts.title.ifEmpty { s.title }).setArtist(opts.artist.ifEmpty { s.author })
                    .setArtworkUri(opts.artwork.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build(),
            )
        if (s.live || s.video.isEmpty()) {
            b.setUri(s.hls).setMimeType(MimeTypes.APPLICATION_M3U8)
        } else {
            // the codec chosen in Settings first, then the best this phone decodes
            val order = if (opts.codec.isNotEmpty()) listOf(opts.codec) + codecs else codecs
            val mpd = if (!tv) Dash.manifest(s, order) else {
                // on a TV the codec that reaches the highest quality there first (say VP9 at 4K over AV1 that stops at 1080p)
                val best = if (opts.codec.isNotEmpty()) order else codecs.sortedByDescending { c -> s.video.filter { it.codecFamily == c && it.indexRange != null && decodes(it) }.maxOfOrNull { it.height } ?: 0 }
                Dash.manifest(s, best, plays = ::decodes)
            }
            b.setUri("data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP))
                .setMimeType(MimeTypes.APPLICATION_MPD)
        }
        loadedCaption?.let { c ->
            b.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(YouTube.captionUrl(c, loadedTranslate)))
                        .setMimeType(MimeTypes.TEXT_VTT).setLanguage(c.lang).setLabel(c.name)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
                ),
            )
        }
        return b.build()
    }

    override fun play() { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play() }
    override fun pause() = exo.pause()
    override fun seekTo(ms: Long) = exo.seekTo(ms)

    override fun setSpeed(x: Float) {
        exo.playbackParameters = PlaybackParameters(x); sync()
    }

    override fun setHeight(h: Int) {
        val p: TrackSelectionParameters.Builder = exo.trackSelectionParameters.buildUpon()
        if (h <= 0) p.setMinVideoSize(0, 0).setMaxVideoSize(Int.MAX_VALUE, opts.maxHeight)
        else p.setMinVideoSize(0, h).setMaxVideoSize(Int.MAX_VALUE, h)
        exo.trackSelectionParameters = p.build()
        sync()
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        val s = stream ?: return
        caption = c
        this.translateTo = translateTo
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(c?.lang ?: loadedCaption?.lang).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, c == null).build()
        // off, or back on with the track that is already loaded: just show or hide it
        if (c == null || (c == loadedCaption && translateTo == loadedTranslate)) { sync(); return }
        // another track: side-loaded subtitles are part of the media item, so rebuild it where we are
        loadedCaption = c
        loadedTranslate = translateTo
        val pos = exo.currentPosition
        val playing = exo.playWhenReady
        exo.setMediaItem(item(s), pos)
        exo.prepare()
        exo.playWhenReady = playing
        sync()
    }

    /** Android Auto: only the sound is loaded while a car is connected, as nothing shows the picture there */
    fun audioOnly(on: Boolean) {
        car = on
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on || opts.audioOnly).build()
    }

    override fun setAudioTrack(lang: String) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(lang).build()
        sync()
    }

    /** the picture copied off the video surface at 32×18 (the copy scales it), then blurred: scaled up it is a soft wash of colour */
    override suspend fun frame(): ImageBitmap? {
        val surface = view?.videoSurfaceView ?: return null
        if (surface.width == 0 || surface.height == 0) return null
        val bmp = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        val ok = runCatching {
            when (surface) {
                is SurfaceView -> surface.holder.surface.isValid && suspendCancellableCoroutine { c ->
                    PixelCopy.request(surface, bmp, { r -> c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }
                is TextureView -> surface.getBitmap(bmp) != null
                else -> false
            }
        }.getOrDefault(false)
        if (!ok) return null
        val w = bmp.width
        val h = bmp.height
        var px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(3) { px = blur(px, w, h, true); px = blur(px, w, h, false) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }

    /** one pass of a box blur, across or down */
    private fun blur(src: IntArray, w: Int, h: Int, across: Boolean, r: Int = 2): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var red = 0; var green = 0; var blue = 0
            for (d in -r..r) {
                val p = if (across) src[y * w + (x + d).coerceIn(0, w - 1)] else src[(y + d).coerceIn(0, h - 1) * w + x]
                red += p shr 16 and 0xff; green += p shr 8 and 0xff; blue += p and 0xff
            }
            val n = 2 * r + 1
            out[y * w + x] = (0xff shl 24) or (red / n shl 16) or (green / n shl 8) or (blue / n)
        }
        return out
    }

    /**
     * Captions as YouTube draws them: white on a see-through black box behind each line, a bit
     * larger than the phone's own caption style, and the video's colours and fonts left out.
     */
    private fun SubtitleView.youTubeLook() {
        // Settings → caption size, background (no box: an outline keeps them readable) and position (#54)
        val a = (capBg * 255 / 100).coerceIn(0, 255)
        setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, (a shl 24) or 0x080808, android.graphics.Color.TRANSPARENT,
            if (a == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null))
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f * capSize / 100f)
        setBottomPaddingFraction(if (capPos == 1) 0.2f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        setApplyEmbeddedStyles(false)
    }

    @Volatile private var capSize = 100
    @Volatile private var capBg = 75
    @Volatile private var capPos = 0

    override fun setCaptionStyle(size: Int, bg: Int, pos: Int) {
        capSize = size; capBg = bg; capPos = pos
        captions?.let { v -> v.post { v.youTubeLook(); v.setCues(placed(exo.currentCues.cues)) } }
    }

    /** the cues to show: centred, and with captions at the top stacked down from the top edge, one under the other */
    private fun placed(cues: List<Cue>): List<Cue> {
        if (capPos != 2) return cues.map(::centred)
        var line = 1
        return cues.map { c ->
            val n = c.text?.lines()?.size ?: 1
            centred(c).buildUpon().setLine(line.toFloat(), Cue.LINE_TYPE_NUMBER).setLineAnchor(Cue.ANCHOR_TYPE_START).build().also { line += n }
        }
    }

    /**
     * Placed as on YouTube. Auto-generated captions come pinned to the video's left edge
     * (align:start position:0%): the lines stay left-aligned but the block sits in the middle.
     * And WebVTT puts a caption on the very last line, half cut off by the edge: it goes a little
     * way up instead.
     */
    private fun centred(c: Cue): Cue {
        val b = c.buildUpon()
        if (c.position != Cue.DIMEN_UNSET || c.size != Cue.DIMEN_UNSET) b.setPosition(Cue.DIMEN_UNSET).setPositionAnchor(Cue.TYPE_UNSET).setSize(Cue.DIMEN_UNSET)
        if (c.lineType == Cue.LINE_TYPE_NUMBER && c.line < 0) b.setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        return b.build()
    }

    override fun setLoop(on: Boolean) {
        exo.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun stop() {
        exo.stop()
        exo.clearMediaItems()
        stream = null
        st.value = PlayerState()
    }

    @Composable
    override fun View(modifier: Modifier) {
        val s by state.collectAsState()
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)   // the thumbnail shows until the first frame
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exo
                    subtitleView?.visibility = View.GONE
                    val subs = SubtitleView(c).apply {
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        youTubeLook()
                        setCues(placed(exo.currentCues.cues))
                    }
                    overlayFrameLayout?.addView(subs)
                    captions = subs
                }.also { view = it }
            },
            update = { v ->
                // the controls are drawn in Compose (PlayerControls.kt), the same on every screen
                v.useController = false
                if (v.player !== exo) v.player = exo
                v.keepScreenOn = s.playing
            },
            onRelease = { v ->
                v.player = null
                if (view === v) { view = null; captions = null }
            },
            modifier = modifier,
        )
    }
}
