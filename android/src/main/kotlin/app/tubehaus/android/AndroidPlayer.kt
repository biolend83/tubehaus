package app.tubehaus.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Dash
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val dataSource = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(Net.client))

    val exo: ExoPlayer = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        // read far ahead (up to 5 minutes, within ExoPlayer's memory budget) so a slow patch of network doesn't stop the video;
        // Settings → Read further ahead: up to 20 minutes, with up to 400 MB for it
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, if (longBuffer) 1_200_000 else 300_000, 1_500, 3_000)
                .setBackBuffer(30_000, true)
                .apply { if (longBuffer) setTargetBufferBytes(400 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true) }
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

    /** isHardwareAccelerated is Android 10+; before that, the software decoders are the ones named OMX.google.* / c2.android.* */
    private fun isHardware(c: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) c.isHardwareAccelerated
        else c.name.lowercase().let { !it.startsWith("omx.google.") && !it.startsWith("c2.android.") && !it.contains(".sw.") }

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
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
                error = if (exo.playerError == null) null else it.error,
            )
        }
    }

    override fun load(s: Stream, o: PlayOptions) {
        stream = s
        opts = o
        caption = o.captions
        translateTo = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        loadedCaption = caption ?: o.standbyCaption
        loadedTranslate = if (caption != null) translateTo else o.standbyTranslate
        st.value = PlayerState(videoId = s.id, buffering = true, speed = o.speed, captions = caption)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMinVideoSize(0, 0)
            .setMaxVideoSize(Int.MAX_VALUE, o.maxHeight)
            .setPreferredTextLanguage(caption?.lang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, caption == null)
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
            val mpd = Dash.manifest(s, codecs)
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
                }
            },
            update = { v ->
                // the controls are drawn in Compose (PlayerControls.kt), the same on every screen
                v.useController = false
                if (v.player !== exo) v.player = exo
                v.keepScreenOn = s.playing
            },
            onRelease = { v -> v.player = null },
            modifier = modifier,
        )
    }
}
