package app.tubehaus.desktop

import app.tubehaus.ui.Translated
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Comment translation on macOS 26+ through Apple's own translation: a small Swift program
 * (desktop/translate/Translate.swift, built into the jar by Gradle) is copied out of the jar into
 * [dir] once and run per text, the text on its stdin and the translation on its stdout.
 */
class Translator(private val dir: File) {
    private val helper: File? by lazy { unpack() }

    /** macOS 26 or later, with the helper in this build (it is only built on a Mac) */
    val available: Boolean by lazy { isMac && macMajor() >= MIN_MACOS && javaClass.getResource(RESOURCE) != null }

    suspend fun translate(text: String, target: String): Translated = withContext(Dispatchers.IO) {
        val exe = helper ?: return@withContext Translated.Failed
        // the answers go to files, so a stuck helper can't hold the reads past the time limit
        val out = File.createTempFile("translate", ".out", dir)
        val err = File.createTempFile("translate", ".err", dir)
        try {
            val p = ProcessBuilder(exe.path, target).redirectOutput(out).redirectError(err).start()
            p.outputStream.use { it.write(text.toByteArray()) }
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                System.err.println("translate: no answer in $TIMEOUT_S s")
                return@withContext Translated.Failed
            }
            when (p.exitValue()) {
                0 -> Translated.Text(out.readText())
                2 -> Translated.SameLanguage
                4 -> Translated.NeedsDownload
                else -> { System.err.println("translate: ${err.readText().trim()}"); Translated.Failed }
            }
        } catch (e: Exception) {
            System.err.println("translate: ${e.message}")
            Translated.Failed
        } finally {
            out.delete()
            err.delete()
        }
    }

    // copied again when this build's helper differs from the one left by an earlier build
    private fun unpack(): File? = runCatching {
        val bytes = javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
        val f = File(dir, "translate-mac")
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) {
            dir.mkdirs()
            val tmp = File(dir, "translate-mac.tmp")
            tmp.writeBytes(bytes)
            tmp.setExecutable(true)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        f.takeIf { it.canExecute() }
    }.onFailure { System.err.println("translate: couldn't unpack the helper: ${it.message}") }.getOrNull()

    private fun macMajor(): Int = System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0

    private companion object {
        const val RESOURCE = "/translate/translate-mac"
        const val MIN_MACOS = 26
        const val TIMEOUT_S = 30L
    }
}
