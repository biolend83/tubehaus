package app.tubehaus.desktop

import androidx.compose.runtime.snapshotFlow
import app.tubehaus.ui.AppState
import app.tubehaus.ui.PlayerState
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** how far the shown position may drift from the real one before Control Center is told again (ms) */
private const val DRIFT_MS = 1500L
private const val RESOURCE = "/nowplaying/libnowplaying.dylib"

/**
 * What plays, for macOS Control Center and the keyboard's media keys (title, artist, cover, position)
 * and their buttons (play, pause, previous, next, the position bar), as the iPad's MediaControls does.
 * Apple's MediaPlayer is reached through a small Swift library (desktop/nowplaying/NowPlaying.swift,
 * built into the jar by Gradle) loaded into this process; without it (not a Mac, no swiftc) nothing happens.
 */
class MacMediaControls(private val app: AppState, private val dir: File) {
    private interface Lib : Library {
        fun np_start(cb: Command)
        fun np_show(title: String, artist: String, duration: Double, elapsed: Double, rate: Double, art: String)
        fun np_clear()
    }

    /** a button pressed in Control Center: see NowPlaying.swift for the codes */
    fun interface Command : Callback {
        fun invoke(code: Int, seconds: Double)
    }

    private var lib: Lib? = null
    // JNA keeps only a weak reference to a callback: held here for as long as the app runs
    private val command = Command { code, seconds -> app.scope.launch { pressed(code, seconds) } }
    /** what was told last, and the position then (ms), to tell again only after a change, a seek or a stall */
    private var toldKey: Any? = null
    private var toldPos = 0L
    private var toldAt = 0L

    fun start() {
        val l = load() ?: return
        lib = l
        l.np_start(command)
        val pb = app.playback
        app.scope.launch {
            // the video's own words come in after the stream (details), so they are watched too
            val what = snapshotFlow { listOf(pb.video, pb.song, pb.music, pb.details) }
            combine(app.player.state, what) { s, _ -> s }.collect { show(it) }
        }
    }

    private fun load(): Lib? = runCatching {
        if (!isMac) return null
        val bytes = javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
        // copied again when this build's library differs from the one left by an earlier build
        val f = File(dir, "libnowplaying.dylib")
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) {
            dir.mkdirs()
            val tmp = File(dir, "libnowplaying.dylib.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        Native.load(f.absolutePath, Lib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }.onFailure { System.err.println("nowplaying: couldn't load the library: ${it.message}") }.getOrNull()

    private fun pressed(code: Int, seconds: Double) {
        if (app.playback.video == null) return
        val p = app.player
        when (code) {
            1 -> p.play()
            2 -> p.pause()
            3 -> if (p.state.value.playing) p.pause() else p.play()
            4 -> app.playback.skipNext()
            5 -> app.playback.previous()
            6 -> p.seekTo((seconds * 1000).toLong())
        }
    }

    private fun show(s: PlayerState) {
        val l = lib ?: return
        val pb = app.playback
        val v = pb.video
        if (v == null || s.videoId == null) {
            if (toldKey != null) l.np_clear()
            toldKey = null
            return
        }
        val song = pb.song
        val title = song?.title ?: pb.details?.title?.ifEmpty { null } ?: v.title
        val artist = song?.artist ?: pb.details?.author?.ifEmpty { null } ?: v.author
        val art = song?.art?.ifEmpty { null } ?: v.thumbHigh
        val rate = if (s.playing) s.speed.toDouble() else 0.0
        val key = listOf(v.id, title, artist, art, rate, s.durationMs)
        val now = System.currentTimeMillis()
        val expected = toldPos + ((now - toldAt) * (if (s.playing) s.speed else 0f)).toLong()
        if (key == toldKey && kotlin.math.abs(s.positionMs - expected) < DRIFT_MS) return
        toldKey = key
        toldPos = s.positionMs
        toldAt = now
        l.np_show(title, artist, s.durationMs / 1000.0, s.positionMs / 1000.0, rate, art)
    }
}
