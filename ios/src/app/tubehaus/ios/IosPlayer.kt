package app.tubehaus.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Net
import app.tubehaus.engine.ServerVideo
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.VideoPlayer
import kotlinx.cinterop.CValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVKit.AVPictureInPictureController
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.preferredMaximumResolution
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * The iPad's player: AVPlayer on YouTube's HLS stream (one address, every quality from 144p to
 * 4K, sound included). Captions are drawn here in Compose from the cleaned WebVTT, because
 * AVPlayer can't take a side-loaded track for HLS.
 */
class IosPlayer : VideoPlayer {
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> get() = st

    val av = AVPlayer()
    private val scope = MainScope()
    private var stream: Stream? = null
    private var opts = PlayOptions()
    /** where to start once the item is ready (AVPlayer ignores a seek before that); -1 = done */
    private var startAt = -1L
    private var heightsJob: Job? = null
    private var capsJob: Job? = null
    private var cues by mutableStateOf<List<Cue>>(emptyList())

    init {
        // sound even with the silent switch on, and the audio keeps playing with the screen locked
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        scope.launch {
            while (true) {
                tick()
                delay(250)
            }
        }
    }

    private fun secs(t: CValue<CMTime>): Double = CMTimeGetSeconds(t).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private fun tick() {
        val item = av.currentItem ?: return
        if (startAt >= 0 && item.status == AVPlayerItemStatusReadyToPlay) {
            if (startAt > 0) av.seekToTime(CMTimeMake(startAt, 1000))
            startAt = -1
        }
        val dur = secs(item.duration)
        val pos = secs(av.currentTime())
        val playing = av.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val waiting = av.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val ended = dur > 0 && pos >= dur - 0.4 && !playing
        if (ended && opts.loop) {
            av.seekToTime(CMTimeMake(0, 1000))
            av.playImmediatelyAtRate(opts.speed)
        }
        val failed = item.status == AVPlayerItemStatusFailed
        st.update {
            it.copy(
                playing = playing,
                buffering = waiting || (startAt >= 0 && !failed),
                positionMs = if (startAt > 0) startAt else (pos * 1000).toLong(),
                durationMs = (dur * 1000).toLong().takeIf { d -> d > 0 } ?: it.durationMs,
                ended = ended && !opts.loop,
                error = if (failed) item.error?.localizedDescription ?: "Playback failed" else null,
            )
        }
    }

    override fun load(s: Stream, o: PlayOptions) {
        stream = s
        opts = o
        val hls = s.hls
        if (hls == null) {
            st.value = PlayerState(videoId = s.id, error = "This video has no stream the iPad can play")
            return
        }
        // from the connected server, the list and every piece need its login
        val headers = ServerVideo.headersFor(hls)
        val item = if (headers == null) AVPlayerItem(uRL = NSURL(string = hls))
            else AVPlayerItem(asset = AVURLAsset(uRL = NSURL(string = hls), options = mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to headers)))
        limit(item, o.maxHeight)
        av.replaceCurrentItemWithPlayerItem(item)
        startAt = o.startMs
        st.value = PlayerState(videoId = s.id, buffering = true, positionMs = o.startMs, durationMs = s.lengthSec * 1000L,
            speed = o.speed, captions = o.captions)
        if (o.autoplay) av.playImmediatelyAtRate(o.speed) else av.pause()
        // the qualities on offer, from the HLS master list
        heightsJob?.cancel()
        heightsJob = scope.launch {
            val text = runCatching { Net.get(hls) }.getOrNull() ?: return@launch
            val heights = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()
            st.update { it.copy(heights = heights) }
        }
        val translate = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        captions(o.captions, translate)
    }

    /** the highest quality AVPlayer may pick; 0 = no limit (it adapts to the connection) */
    private fun limit(item: AVPlayerItem, h: Int) {
        item.preferredMaximumResolution = if (h in 1..4319) CGSizeMake(h * 16.0 / 9.0, h.toDouble()) else CGSizeMake(0.0, 0.0)
    }

    override fun play() {
        if (st.value.ended) av.seekToTime(CMTimeMake(0, 1000))
        av.playImmediatelyAtRate(opts.speed)
        st.update { it.copy(playing = true, ended = false) }
    }

    override fun pause() {
        av.pause()
        st.update { it.copy(playing = false) }
    }

    override fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0)
        if (startAt >= 0) startAt = to else av.seekToTime(CMTimeMake(to, 1000))
        st.update { it.copy(positionMs = to, ended = false) }
    }

    override fun setSpeed(x: Float) {
        opts = opts.copy(speed = x)
        if (av.rate > 0f) av.rate = x
        st.update { it.copy(speed = x) }
    }

    override fun setHeight(h: Int) {
        av.currentItem?.let { limit(it, if (h == 0) opts.maxHeight else h) }
        st.update { it.copy(height = h) }
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        st.update { it.copy(captions = c) }
        captions(c, translateTo)
    }

    private fun captions(c: Caption?, translateTo: String?) {
        capsJob?.cancel()
        cues = emptyList()
        if (c == null) return
        capsJob = scope.launch {
            val text = runCatching { Net.get(YouTube.captionUrl(c, translateTo)) }.getOrNull() ?: return@launch
            cues = parseVtt(text)
        }
    }

    override fun setLoop(on: Boolean) { opts = opts.copy(loop = on) }

    override fun setReadAhead(long: Boolean) {
        // 0 lets AVPlayer choose (a few seconds to a minute)
        av.currentItem?.preferredForwardBufferDuration = if (long) 1200.0 else 0.0
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        heightsJob?.cancel(); capsJob?.cancel()
        stream = null
        startAt = -1
        cues = emptyList()
        st.value = PlayerState()
    }

    @Composable
    override fun View(modifier: Modifier) {
        val s by st.collectAsState()
        Box(modifier.background(Color.Black)) {
            UIKitView(factory = { PlayerView(av) }, modifier = Modifier.fillMaxSize())
            val line = if (s.captions != null) cues.lastOrNull { s.positionMs >= it.start && s.positionMs < it.end }?.text else null
            if (line != null) Text(
                line, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
                    .background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The picture: an AVPlayerLayer that follows the view's size; taps go through to the controls.
 * Going to the home screen while it plays moves the video into a picture-in-picture window.
 */
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val picture = AVPlayerLayer.playerLayerWithPlayer(player)
    private val pip: AVPictureInPictureController? =
        if (AVPictureInPictureController.isPictureInPictureSupported()) AVPictureInPictureController(playerLayer = picture) else null

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        picture.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(picture)
        pip?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.frame = bounds
        CATransaction.commit()
    }
}

private class Cue(val start: Long, val end: Long, val text: String)

/** cues of a cleaned WebVTT (Vtt.clean: no tags, no overlaps) */
private fun parseVtt(src: String): List<Cue> {
    val out = ArrayList<Cue>()
    val rows = src.replace("\r\n", "\n").split('\n')
    var i = 0
    fun ms(t: String): Long {
        val p = t.trim().split(':')
        val (h, m, s) = if (p.size == 3) Triple(p[0].toLong(), p[1].toLong(), p[2]) else Triple(0L, p[0].toLong(), p[1])
        return (h * 3600 + m * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }
    while (i < rows.size) {
        val row = rows[i]
        if ("-->" !in row) { i++; continue }
        val a = row.substringBefore("-->")
        val b = row.substringAfter("-->").trim().substringBefore(' ')
        i++
        val text = StringBuilder()
        while (i < rows.size && rows[i].isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(rows[i].trim()); i++ }
        runCatching { out += Cue(ms(a), ms(b), text.toString()) }
    }
    return out
}
