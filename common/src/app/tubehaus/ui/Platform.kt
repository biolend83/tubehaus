package app.tubehaus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Stream
import kotlinx.coroutines.flow.StateFlow
import app.tubehaus.engine.Sys
import okio.Path

/** What each platform (Android, the desktop, iOS) supplies; everything else is shared. */
interface Platform {
    val name: String               // "android" / "desktop" / "ios"
    val dataDir: Path
    val cacheDir: Path
    /** phones get the bottom bar and portrait layouts; desktops a wider grid */
    val touch: Boolean
    /** a TV (Android TV, Google TV): the remote moves a visible focus, and videos fill the screen */
    val tv: Boolean get() = false
    /** videos can be saved to watch without internet (every app does; a platform without it can say so) */
    val canDownload: Boolean get() = true
    val player: VideoPlayer

    /** a share sheet where there is one; false when the link was copied to the clipboard instead */
    fun share(title: String, url: String): Boolean
    fun openExternal(url: String)
    /** asks where to save [bytes]; calls [done] with true when written */
    fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit)
    /** lets the user pick a file; calls [done] with its text (null when cancelled) */
    fun openTextFile(done: (String?) -> Unit)
    fun setFullscreen(on: Boolean)
    /**
     * Downloads [url] (an app update) and opens the system installer; false when this platform
     * can't, and the release page is opened instead.
     */
    fun installUpdate(url: String, progress: (Float) -> Unit, done: (Boolean) -> Unit): Boolean = false
    /** iPad: the YouTube engine (engine.js) is updated on its own; its version, null elsewhere */
    fun engineVersion(): Int? = null
    /** iPad: the newest engine.js from GitHub now: its version when one was put in, 0 when up to date, -1 when it failed */
    suspend fun updateEngine(): Int = -1
    /** Android: hide the status and navigation bars outside full screen too */
    fun setImmersive(on: Boolean) {}
    /** screen brightness for the player's swipe gesture, 0..1; null where there is none (the desktop) */
    fun brightness(): Float? = null
    fun setBrightness(v: Float) {}
    /** the system media volume, 0..1, for the other swipe gesture; null where the app has its own (the desktop) */
    fun mediaVolume(): Float? = null
    fun setMediaVolume(v: Float) {}
    /** a mouse pointer that shows nothing, hidden while a video plays (none on touch screens) */
    val blankPointer: androidx.compose.ui.input.pointer.PointerIcon? get() = null
    /** true on a metered connection (mobile data): the lower quality cap applies */
    fun metered(): Boolean = false
    /** the device language, e.g. "ko" */
    fun deviceLanguage(): String = Sys.deviceLanguage()
    fun deviceRegion(): String = Sys.deviceRegion().ifEmpty { "US" }
    /** Android 12+: colours taken from the wallpaper (Material You); null where there are none */
    fun wallpaperColors(dark: Boolean): androidx.compose.material3.ColorScheme? = null
    /**
     * Hands a video link to a downloader app (YTDLnis, Seal, ...): false when there is none
     * installed, or the platform has no such thing
     */
    fun externalDownload(url: String): Boolean = false
    /** desktop: the window's own title bar in the app's background colour (light, dark or black) */
    fun titleBar(background: androidx.compose.ui.graphics.Color) {}
    /**
     * desktop: typing goes past the input method (Korean, Japanese...) while [on], as in the
     * system's own password boxes; a password typed through one waits on its composition
     */
    fun plainKeys(on: Boolean) {}
    /** desktop: the downloads can be kept in a folder of the user's choosing */
    val canPickFolder: Boolean get() = false
    /** desktop: asks for a folder, starting at [start]; null when cancelled */
    fun pickFolder(start: String, done: (String?) -> Unit) { done(null) }
    /** desktop: shows a folder in the file manager */
    fun openFolder(path: String) {}
}

data class PlayerState(
    val videoId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** how far ahead is already downloaded */
    val bufferedMs: Long = 0,
    val ended: Boolean = false,
    val error: String? = null,
    /** heights the current video offers */
    val heights: List<Int> = emptyList(),
    /** chosen height, 0 = automatic */
    val height: Int = 0,
    val speed: Float = 1f,
    val captions: Caption? = null,
    /** the language YouTube is machine-translating [captions] into; null: the track as it is */
    val captionsTranslated: String? = null,
    /** the video's audio languages (YouTube's dubs) as language to name; empty or one when there is no choice */
    val audioTracks: List<Pair<String, String>> = emptyList(),
    /** the language playing now */
    val audioTrack: String? = null,
    /** 0..1, only where the app has its own volume (the desktop) */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** why nothing can be heard while the picture plays (the desktop player finds out); null: all is well */
    val soundIssue: String? = null,
)

data class PlayOptions(
    val startMs: Long = 0,
    val maxHeight: Int = 1080,
    val autoplay: Boolean = true,
    val speed: Float = 1f,
    val captions: Caption? = null,
    val captionLang: String? = null,
    /** the track the captions button turns on: loaded with the video but hidden, so the button is instant */
    val standbyCaption: Caption? = null,
    val standbyTranslate: String? = null,
    val loop: Boolean = false,
    val volume: Float = 1f,
    /** for the system media controls */
    val title: String = "",
    val artist: String = "",
    val artwork: String = "",
)

interface VideoPlayer {
    val state: StateFlow<PlayerState>
    fun load(s: Stream, o: PlayOptions)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(x: Float)
    /** 0 = automatic */
    fun setHeight(h: Int)
    fun setCaptions(c: Caption?, translateTo: String?)
    /** one of [PlayerState.audioTracks] for this video; every video starts in its original language */
    fun setAudioTrack(lang: String) {}
    fun setLoop(on: Boolean)
    /** read about 20 minutes ahead instead of 5 */
    fun setReadAhead(long: Boolean) {}
    /** the desktop: the sound outputs to choose from, as (name, description); empty where the system picks */
    fun audioDevices(): List<Pair<String, String>> = emptyList()
    /** one of [audioDevices]; "" = the system's default */
    fun setAudioDevice(name: String) {}
    /**
     * A small, already softened picture of the frame showing now, for the glow around the video
     * in the dark theme (Ambient mode); null where the platform can't take one
     */
    suspend fun frame(): androidx.compose.ui.graphics.ImageBitmap? = null
    fun stop()

    /** phones use the system volume; the desktop has a slider of its own */
    val hasVolume: Boolean get() = false
    fun setVolume(v: Float) {}
    fun setMuted(on: Boolean) {}

    /** Just the picture: the controls on top of it are drawn in Compose (PlayerControls.kt). */
    @Composable
    fun View(modifier: Modifier)
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("no platform") }
