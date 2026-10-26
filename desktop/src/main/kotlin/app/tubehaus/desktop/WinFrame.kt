package app.tubehaus.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Frame

/**
 * The bits of the Windows window the JVM doesn't reach: a dark title bar (Windows 10 20H1+ / 11),
 * and real full screen, which takes the title bar and borders off the window and covers the
 * whole monitor (Java's own full screen leaves the title bar on a decorated window).
 */
object WinFrame {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Memory): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Memory): Boolean
    }

    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val user32 by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private val dwm by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    private const val GWL_STYLE = -16
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_NOOWNERZORDER = 0x0200
    private const val MONITOR_DEFAULTTONEAREST = 2

    private var savedStyle = 0L
    private var savedRect: IntArray? = null

    private fun hwnd(f: Frame): Pointer? = if (windows && f.isDisplayable) Native.getComponentPointer(f) else null

    /** a title bar that matches the app instead of a white one */
    fun darkTitleBar(f: Frame) = titleBar(f, 0x0F, 0x0F, 0x0F)

    /**
     * The title bar in the app's background colour: Windows 10/11 dark or light title bar (and the
     * exact colour on Windows 11); macOS the dark or light window appearance.
     */
    fun titleBar(f: Frame, r: Int, g: Int, b: Int) {
        val dark = (r * 299 + g * 587 + b * 114) / 1000 < 128
        if (isMacOs) {
            (f as? javax.swing.JFrame)?.rootPane?.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
            return
        }
        val h = hwnd(f) ?: return
        val d = dwm ?: return
        d.DwmSetWindowAttribute(h, 20, IntByReference(if (dark) 1 else 0), 4)              // DWMWA_USE_IMMERSIVE_DARK_MODE
        d.DwmSetWindowAttribute(h, 35, IntByReference((b shl 16) or (g shl 8) or r), 4)     // DWMWA_CAPTION_COLOR (Windows 11), 0x00BBGGRR
    }

    private val isMacOs = System.getProperty("os.name").startsWith("Mac")

    /** false where it can't be done (not Windows): the caller falls back to Java's full screen */
    fun fullscreen(f: Frame, on: Boolean): Boolean {
        val h = hwnd(f) ?: return false
        val u = user32 ?: return false
        if (on) {
            if (savedRect != null) return true
            val r = Memory(16)
            u.GetWindowRect(h, r)
            savedRect = intArrayOf(r.getInt(0), r.getInt(4), r.getInt(8), r.getInt(12))
            savedStyle = u.GetWindowLongPtrW(h, GWL_STYLE)
            u.SetWindowLongPtrW(h, GWL_STYLE, savedStyle and (WS_CAPTION or WS_THICKFRAME).inv())
            // MONITORINFO: cbSize, rcMonitor, rcWork, dwFlags
            val m = u.MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST) ?: return false
            val info = Memory(40).apply { clear(); setInt(0, 40) }
            u.GetMonitorInfoW(m, info)
            val l = info.getInt(4); val t = info.getInt(8); val rr = info.getInt(12); val b = info.getInt(16)
            u.SetWindowPos(h, null, l, t, rr - l, b - t, SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        } else {
            val r = savedRect ?: return true
            savedRect = null
            u.SetWindowLongPtrW(h, GWL_STYLE, savedStyle)
            u.SetWindowPos(h, null, r[0], r[1], r[2] - r[0], r[3] - r[1], SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        }
        return true
    }
}
