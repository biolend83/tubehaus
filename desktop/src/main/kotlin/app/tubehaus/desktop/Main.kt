package app.tubehaus.desktop

import app.tubehaus.ui.tr
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
import okio.Path
import okio.Path.Companion.toOkioPath
import java.net.URI

private val home = System.getProperty("user.home")
val isMac = System.getProperty("os.name").startsWith("Mac")

/** Linux: $XDG_CONFIG_HOME or ~/.config (in the Flatpak, ~/.var/app/<id>/config: the sandbox can't write to ~/.config) */
private val linuxConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.config"
private val linuxCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.cache"

class DesktopPlatform : Platform {
    override val name = "desktop"
    // Windows: %APPDATA%; macOS: ~/Library/Application Support (and Caches); Linux: XDG config and cache folders
    override val dataDir: Path = File(System.getenv("APPDATA") ?: if (isMac) "$home/Library/Application Support" else linuxConfig, "TubeHaus").toOkioPath()
    override val cacheDir: Path = File(System.getenv("LOCALAPPDATA") ?: if (isMac) "$home/Library/Caches" else linuxCache, "TubeHaus").toOkioPath()
    override val touch = false
    /** Pretendard, one variable font file for every weight (resources: common/fonts) */
    override val font: androidx.compose.ui.text.font.FontFamily? by lazy {
        val data = DesktopPlatform::class.java.getResourceAsStream("/PretendardVariable.ttf")?.use { it.readBytes() } ?: return@lazy null
        runCatching {
            androidx.compose.ui.text.font.FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
                androidx.compose.ui.text.platform.Font("Pretendard-$w", data, androidx.compose.ui.text.font.FontWeight(w),
                    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
            })
        }.getOrNull()
    }
    // TUBEHAUS_TV=1: the TV layout on a computer, driven with the arrow keys, Enter and Escape (to try it without a TV)
    override val tv = System.getenv("TUBEHAUS_TV") == "1"
    val mpv = MpvPlayer()
    override val player get() = mpv
    override val caster: app.tubehaus.ui.Caster by lazy {
        app.tubehaus.cast.GoogleCast(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main))
    }
    override val nearby: app.tubehaus.ui.Nearby by lazy { app.tubehaus.cast.Lan() }
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

    override val canPickFolder get() = true

    override fun pickFolder(start: String, done: (String?) -> Unit) {
        val c = javax.swing.JFileChooser(File(start).takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = tr("Download folder")
        }
        done(if (c.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile.absolutePath else null)
    }

    override fun openFolder(path: String) {
        runCatching { File(path).mkdirs(); Desktop.getDesktop().open(File(path)) }
    }

    override fun openTextFile(done: (String?) -> Unit) {
        val d = FileDialog(frame, "Open", FileDialog.LOAD).apply { isVisible = true }
        val f = d.file ?: return done(null)
        done(runCatching { File(d.directory, f).readText() }.getOrNull())
    }

    override fun openTextFiles(done: (List<Pair<String, String>>) -> Unit) {
        val d = FileDialog(frame, "Open", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
        done(d.files.mapNotNull { f -> runCatching { f.name to f.readText() }.getOrNull() })
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

    // the window's drawing surface, which has the keyboard focus, takes keys with or without the input method
    // (the one turned off is remembered: by the time it is turned back on the focus may be elsewhere)
    private var plain: java.awt.Component? = null
    override fun plainKeys(on: Boolean) {
        if (on) {
            plain = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.apply { enableInputMethods(false) }
        } else {
            plain?.enableInputMethods(true)
            plain = null
        }
    }

    // an invisible pointer while the video plays with its controls hidden
    override val blankPointer = PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none"),
    )
}

// a crash (also one before the window opens) is written to crash.log in the data folder and shown,
// so that "it closes right after start" comes with a reason that can be sent in
private var shown = false

fun crashed(where: String, e: Throwable) {
    val dir = File(System.getenv("APPDATA") ?: if (isMac) "$home/Library/Application Support" else linuxConfig, "TubeHaus")
    val log = File(dir, "crash.log")
    val text = buildString {
        append("TubeHaus ").append(System.getProperty("jpackage.app-version") ?: "dev").append(" — ").append(java.time.LocalDateTime.now()).append('\n')
        append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch"))
        append(", Java ").append(System.getProperty("java.version")).append(", ").append(where).append("\n\n")
        append(e.stackTraceToString()).append("\n")
    }
    runCatching { dir.mkdirs(); log.appendText(text) }
    System.err.print(text)
    // a message only when nothing of the app is on screen any more (a hiccup in a running window is just logged)
    if (shown || java.awt.Frame.getFrames().any { it.isVisible }) return
    shown = true
    runCatching {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "TubeHaus stopped because of an error:\n${e.javaClass.simpleName}: ${e.message ?: ""}\n\nThe details are in\n${log.absolutePath}\nPlease attach that file to a report at github.com/biolend83/tubehaus/issues",
            "TubeHaus",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
    }
}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e -> crashed("thread " + t.name, e) }
    try {
        startApp()
    } catch (e: Throwable) {
        crashed("start", e)
        Runtime.getRuntime().halt(1)
    }
}

private fun startApp() {
    // macOS: the title bar's light or dark look is fixed when the window system starts, so it is
    // chosen here from the saved theme (dark and black: dark; light: light; otherwise the system's)
    if (isMac) {
        val dir = File("$home/Library/Application Support", "TubeHaus")
        val theme = runCatching { Regex(""""dark"\s*:\s*(\d)""").find(File(dir, "settings.json").readText())?.groupValues?.get(1) }.getOrNull()
        System.setProperty("apple.awt.application.appearance", when (theme) { "2", "3" -> "NSAppearanceNameDarkAqua"; "1" -> "NSAppearanceNameAqua"; else -> "system" })
    }
    // libmpv-2.dll: next to the installed app, or in desktop/resources/windows when run from Gradle;
    // on macOS the libmpv inside the app first, else Homebrew's mpv (Apple Silicon /opt/homebrew, Intel /usr/local)
    // or MacPorts' (/opt/local), which aren't on the default search path
    val bundled = System.getProperty("compose.application.resources.dir") ?: File("resources/windows").absolutePath
    System.setProperty("jna.library.path", if (isMac) "$bundled:/opt/homebrew/lib:/usr/local/lib:/opt/local/lib" else bundled)
    val platform = DesktopPlatform()
    val state = AppState(platform, Store(platform.dataDir))
    // macOS Control Center and the media keys: what plays and its buttons
    MacMediaControls(state, File(platform.cacheDir.toString(), "nowplaying")).start()
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
                    // Esc leaves the window's own full screen too (macOS's green button) before it goes back
                    // the TV layout (TUBEHAUS_TV) takes Esc as the remote's Back, and the arrows itself
                    Key.Escape -> if (platform.tv) false else if (!state.fullscreen && ws.placement == WindowPlacement.Fullscreen) {
                        ws.placement = WindowPlacement.Floating; true
                    } else state.back()
                    Key.F11 -> { state.setFull(!state.fullscreen); true }
                    // back and forward as in a browser: Alt+Left/Right, on a Mac also Cmd+[ and Cmd+]
                    Key.DirectionLeft, Key.DirectionRight, Key.LeftBracket, Key.RightBracket -> {
                        val arrow = e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                        val back = e.key == Key.DirectionLeft || e.key == Key.LeftBracket
                        when {
                            !platform.tv && (if (arrow) e.isAltPressed else e.isMetaPressed) -> { if (back) state.back() else state.forward(); true }
                            platform.tv -> false
                            state.current is Screen.Shorts -> state.shortsKeys?.invoke(e.key) ?: false
                            state.fullscreen || state.current is Screen.Watch -> playerKey(state, e.key)
                            else -> false
                        }
                    }
                    // the player's keys (space, arrows, J/K/L, F, M) where a video is on screen
                    else -> when {
                        platform.tv -> false
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
            // the mouse's side buttons go back and forward
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { ev ->
                    when (ev.button) {
                        PointerButton.Back -> { state.back(); ev.changes.forEach { it.consume() } }
                        PointerButton.Forward -> { state.forward(); ev.changes.forEach { it.consume() } }
                        else -> {}
                    }
                },
            ) { App(state) }
        }
    }
}
