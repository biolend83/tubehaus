package app.tubehaus.ios

import androidx.compose.runtime.snapshotFlow
import app.tubehaus.engine.Sys
import app.tubehaus.ui.AppState
import app.tubehaus.ui.PlayerState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/** how far the shown position may drift from the real one before Control Center is told again (ms) */
private const val DRIFT_MS = 1500L

/**
 * What plays, for Control Center and the lock screen (title, artist, cover, position) and their
 * buttons (play, pause, previous, next, the position bar), as Android's media session does.
 * A player of its own (AVPlayer without AVPlayerViewController) shows there only when the app
 * fills [MPNowPlayingInfoCenter] and answers [MPRemoteCommandCenter].
 */
class MediaControls(private val app: AppState) {
    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private var artUrl = ""
    private var art: MPMediaItemArtwork? = null
    /** the position last told and when (ms), to tell again only after a seek or a stall */
    private var toldPos = 0L
    private var toldAt = 0L
    private var toldKey: Any? = null

    fun start() {
        commands()
        val pb = app.playback
        app.scope.launch {
            // the song or video's own words come in after the stream (details), so they are watched too
            val what = snapshotFlow { Triple(pb.video, pb.song, pb.music) }
            combine(app.player.state, what) { s, _ -> s }.collect { show(it) }
        }
        app.scope.launch {
            snapshotFlow { pb.song?.art?.ifEmpty { null } ?: pb.video?.thumbHigh ?: "" }.distinctUntilChanged().collect { loadArt(it) }
        }
    }

    // ------------------------------------------------------------------ Control Center

    private fun commands() {
        val c = MPRemoteCommandCenter.sharedCommandCenter()
        val player = { app.player }
        on(c.playCommand) { player().play() }
        on(c.pauseCommand) { player().pause() }
        on(c.togglePlayPauseCommand) { if (player().state.value.playing) player().pause() else player().play() }
        on(c.nextTrackCommand) { app.playback.skipNext() }
        on(c.previousTrackCommand) { app.playback.previous() }
        c.changePlaybackPositionCommand.addTargetWithHandler { e: MPRemoteCommandEvent? ->
            val at = (e as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            player().seekTo((at * 1000).toLong())
            MPRemoteCommandHandlerStatusSuccess
        }
        c.changePlaybackPositionCommand.enabled = true
    }

    private fun on(cmd: MPRemoteCommand, run: () -> Unit) {
        cmd.enabled = true
        cmd.addTargetWithHandler { _: MPRemoteCommandEvent? ->
            if (app.playback.video == null) return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            run()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun show(s: PlayerState) {
        val pb = app.playback
        val v = pb.video
        if (v == null || s.videoId == null) {
            if (toldKey != null) center.nowPlayingInfo = null
            toldKey = null
            return
        }
        val song = pb.song
        val title = song?.title ?: pb.details?.title?.ifEmpty { null } ?: v.title
        val artist = song?.artist ?: pb.details?.author?.ifEmpty { null } ?: v.author
        val rate = if (s.playing) s.speed.toDouble() else 0.0
        val key = listOf(v.id, title, artist, rate, s.durationMs, art)
        val now = Sys.nowMs()
        val expected = toldPos + ((now - toldAt) * (if (s.playing) s.speed else 0f)).toLong()
        if (key == toldKey && kotlin.math.abs(s.positionMs - expected) < DRIFT_MS) return
        toldKey = key
        toldPos = s.positionMs
        toldAt = now
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to title,
            MPMediaItemPropertyArtist to artist,
            MPMediaItemPropertyPlaybackDuration to s.durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to s.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to rate,
        )
        art?.let { info[MPMediaItemPropertyArtwork] = it }
        center.nowPlayingInfo = info
    }

    /** the cover, fetched off the main thread; Control Center shows the words alone until it comes */
    private suspend fun loadArt(url: String) {
        artUrl = url
        art = null
        toldKey = null
        if (url.isEmpty()) return
        val image = withContext(Sys.IO) { NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) }?.let { UIImage(data = it) } }
        if (artUrl != url) return
        if (image == null) {
            println("MediaControls: couldn't load the cover $url")
            return
        }
        art = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
        toldKey = null
        show(app.player.state.value)
    }
}
