package app.tubehaus.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The few libmpv functions the player needs (libmpv-2.dll): the client API (client.h) and the
 * render API (render.h), which hands each video frame to the app instead of opening a window.
 * Handles are opaque pointers, strings are UTF-8.
 */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, data: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    /** called on one of mpv's threads when a new frame is due; must not call back into mpv */
    fun interface UpdateCallback : Callback {
        fun invoke(data: Pointer?)
    }

    companion object {
        const val FORMAT_DOUBLE = 5
        const val EVENT_SHUTDOWN = 1
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_ERROR = 4

        // render.h
        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20
        const val RENDER_UPDATE_FRAME = 1L

        /** libmpv-2.dll next to the app on Windows; the system's libmpv on Linux (libmpv.so.2) and macOS (Homebrew, libmpv.2.dylib) */
        fun load(): LibMpv? {
            for (name in listOf("mpv-2", "mpv", "libmpv.so.2", "libmpv.so.1", "libmpv.2.dylib")) {
                try {
                    return Native.load(name, LibMpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                } catch (e: UnsatisfiedLinkError) {
                    if (name == "mpv-2") firstError = e.message?.lineSequence()?.firstOrNull()?.take(200)
                }
            }
            return null
        }

        private var firstError: String? = null

        /** libmpv was found but mpv_create gave nothing back */
        @Volatile var wontStart = false

        private interface CLib : Library { fun setlocale(category: Int, locale: String?): Pointer? }

        /**
         * libmpv only starts with C's number format (it refuses with "Non-C locale detected"), and
         * Java switches the process to the system's (en_US.UTF-8, de_DE...) when it starts. Only
         * numbers change: LC_NUMERIC is 1 in glibc, 4 on macOS. Windows' mpv doesn't check.
         */
        fun cNumbers() {
            val os = System.getProperty("os.name")
            if (os.startsWith("Windows")) return
            runCatching { Native.load("c", CLib::class.java).setlocale(if (os.startsWith("Mac")) 4 else 1, "C") }
        }

        /** libmpv-2.dll is there but Windows wouldn't load it: one of the DLLs it needs is missing */
        private val dllPresent: Boolean get() =
            System.getProperty("jna.library.path")?.split(java.io.File.pathSeparator)?.any { java.io.File(it, "libmpv-2.dll").exists() } == true

        val MISSING: String get() = System.getProperty("os.name").let { os ->
            when {
                wontStart -> "mpv is installed but wouldn't start. Please report it on GitHub (Issues) with your system's name and version."
                os.startsWith("Windows") && dllPresent ->
                    "mpv couldn't start (" + (firstError ?: "unknown error") + "). Updating your graphics driver usually fixes this."
                os.startsWith("Windows") -> "mpv is missing: put libmpv-2.dll next to TubeHaus"
                os.startsWith("Mac") -> "mpv is missing: install it with Homebrew (brew install mpv), then open TubeHaus again"
                else -> "mpv is missing: install libmpv (Debian/Ubuntu: sudo apt install libmpv2, Fedora: sudo dnf install mpv-libs, Arch: mpv)"
            }
        }

        /** a mpv_render_param array ({int type; void *data} each, ended by INVALID); the caller keeps the data alive */
        fun params(vararg p: Pair<Int, Pointer?>): Memory {
            val m = Memory(16L * (p.size + 1))
            m.clear()
            p.forEachIndexed { i, (type, data) ->
                m.setInt(16L * i, type)
                m.setPointer(16L * i + 8, data)
            }
            m.setInt(16L * p.size, RENDER_PARAM_INVALID)
            return m
        }

        fun cString(s: String): Memory {
            val b = s.toByteArray()
            return Memory(b.size + 1L).apply { write(0, b, 0, b.size); setByte(b.size.toLong(), 0) }
        }
    }
}
