package app.tubehaus.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tubehaus.data.Store
import app.tubehaus.ui.App
import app.tubehaus.ui.AppState
import app.tubehaus.ui.Platform
import app.tubehaus.ui.Screen
import app.tubehaus.ui.playerKey
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Point
import java.awt.image.BufferedImage
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

private val home = System.getProperty("user.home")
val isMac = System.getProperty("os.name").startsWith("Mac")

class DesktopPlatform : Platform {
    override val name = "desktop"
    // Windows: %APPDATA%; macOS: ~/Library/Application Support (and Caches); Linux: ~/.config, ~/.cache
    override val dataDir: File = File(System.getenv("APPDATA") ?: if (isMac) "$home/Library/Application Support" else "$home/.config", "TubeHaus")
    override val cacheDir: File = File(System.getenv("LOCALAPPDATA") ?: if (isMac) "$home/Library/Caches" else "$home/.cache", "TubeHaus")
    override val touch = false
    val mpv = MpvPlayer()
    override val player get() = mpv
    var window: WindowState? = null
    var frame: Frame? = null

    override fun share(title: String, url: String): Boolean {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        return false
    }

    override fun openExternal(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        val d = FileDialog(frame, "Save", FileDialog.SAVE).apply { file = name; isVisible = true }
        val f = d.file ?: return done(false)
        done(runCatching { File(d.directory, f).writeBytes(bytes) }.isSuccess)
    }

    override fun openTextFile(done: (String?) -> Unit) {
        val d = FileDialog(frame, "Open", FileDialog.LOAD).apply { isVisible = true }
        val f = d.file ?: return done(null)
        done(runCatching { File(d.directory, f).readText() }.getOrNull())
    }

    override fun setFullscreen(on: Boolean) {
        // the title bar and borders come off and the window covers the monitor; Java's own full screen keeps the title bar
        val f = frame
        if (f != null && WinFrame.fullscreen(f, on)) return
        window?.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }

    override fun titleBar(background: androidx.compose.ui.graphics.Color) {
        val f = frame ?: return
        WinFrame.titleBar(f, (background.red * 255).toInt(), (background.green * 255).toInt(), (background.blue * 255).toInt())
    }

    // an invisible pointer while the video plays with its controls hidden
    override val blankPointer = PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none"),
    )
}

fun main() {
    // macOS: the title bar's light or dark look is fixed when the window system starts, so it is
    // chosen here from the saved theme (dark and black: dark; light: light; otherwise the system's)
    if (isMac) {
        val dir = File("$home/Library/Application Support", "TubeHaus")
        val theme = runCatching { Regex(""""dark"\s*:\s*(\d)""").find(File(dir, "settings.json").readText())?.groupValues?.get(1) }.getOrNull()
        System.setProperty("apple.awt.application.appearance", when (theme) { "2", "3" -> "NSAppearanceNameDarkAqua"; "1" -> "NSAppearanceNameAqua"; else -> "system" })
    }
    // libmpv-2.dll: next to the installed app, or in desktop/resources/windows when run from Gradle;
    // on macOS Homebrew's mpv (Apple Silicon /opt/homebrew, Intel /usr/local), which isn't on the default search path
    val bundled = System.getProperty("compose.application.resources.dir") ?: File("resources/windows").absolutePath
    System.setProperty("jna.library.path", if (isMac) "$bundled:/opt/homebrew/lib:/usr/local/lib" else bundled)
    val platform = DesktopPlatform()
    val state = AppState(platform, Store(platform.dataDir))
    application {
        val ws = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        platform.window = ws
        Window(
            onCloseRequest = {
                state.playback.close()
                state.store.flush()
                platform.mpv.shutdown()
                exitApplication()
            },
            state = ws,
            title = "TubeHaus",
            icon = androidx.compose.ui.res.painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.Escape -> state.back()
                    Key.F11 -> { state.setFull(!state.fullscreen); true }
                    // the player's keys (space, arrows, J/K/L, F, M) where a video is on screen
                    else -> when {
                        state.current is Screen.Shorts -> state.shortsKeys?.invoke(e.key) ?: false
                        state.fullscreen || state.current is Screen.Watch -> playerKey(state, e.key)
                        else -> false
                    }
                }
            },
        ) {
            if (platform.frame !== window) {
                platform.frame = window
                WinFrame.darkTitleBar(window)
            }
            App(state)
        }
    }
}
