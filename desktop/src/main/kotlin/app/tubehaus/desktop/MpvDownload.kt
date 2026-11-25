package app.tubehaus.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Linux without a libmpv of its own: the app's release has libmpv and the libraries it needs as
 * libmpv-linux-<arch>.tar.gz (packaging/linux/bundle-mpv.sh), checked against the release's
 * SHA256SUMS-desktop.txt and unpacked once into ~/.local/share/TubeHaus/libmpv. The system's libmpv,
 * when there is one, always comes first (LibMpv.load).
 */
object MpvDownload {
    private const val BASE = "https://github.com/biolend83/tubehaus/releases/latest/download/"

    val possible: Boolean get() = System.getProperty("os.name").startsWith("Linux") && arch != null
    private val arch: String? get() = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }
    private val dir: File by lazy {
        val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.local/share")
        File(data, "TubeHaus/libmpv")
    }
    /** the downloaded libmpv, when it is there */
    val lib: File? get() = File(dir, "libmpv.so.2").takeIf { possible && it.isFile }

    /** what the download is doing ("Downloading mpv… 42%"), null when it isn't running; an error stays until the next try */
    val status = MutableStateFlow<String?>(null)
    @Volatile var running = false
        private set

    /** downloads and unpacks it in the background (once at a time); [done] gets true when the library is in place */
    @Synchronized
    fun start(done: (Boolean) -> Unit) {
        if (running || !possible) return
        running = true
        status.value = "Downloading mpv…"
        thread(isDaemon = true, name = "mpv-download") {
            val ok = runCatching { fetch() }.onFailure { status.value = "mpv couldn't be downloaded (${it.message}). Check the connection and open the video again, or install libmpv (Debian/Ubuntu: sudo apt install libmpv2, Fedora: sudo dnf install mpv-libs, Arch: mpv)." }.isSuccess
            if (ok) status.value = null
            running = false
            done(ok)
        }
    }

    private fun get(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TubeHaus")
            val code = c.responseCode
            if (code in 300..399) { u = URI(u).resolve(c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code != 200) error("HTTP $code")
            return c
        }
        error("too many redirects")
    }

    private fun fetch() {
        val name = "libmpv-linux-$arch.tar.gz"
        val want = get(BASE + "SHA256SUMS-desktop.txt").inputStream.use { it.readBytes().decodeToString() }
            .lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1].trimStart('*') == name }?.get(0)?.lowercase()
            ?: error("not in this release")
        dir.parentFile.mkdirs()
        val part = File(dir.parentFile, "$name.part")
        val c = get(BASE + name)
        val total = c.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { inp ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                var shown = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); got += n
                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                    if (pct != shown) { shown = pct; status.value = if (pct >= 0) "Downloading mpv… $pct%" else "Downloading mpv… ${got shr 20} MB" }
                }
            }
        }
        val have = md.digest().joinToString("") { "%02x".format(it) }
        if (have != want) { part.delete(); error("checksum mismatch") }
        status.value = "Unpacking mpv…"
        val tmp = File(dir.parentFile, "libmpv.new").apply { deleteRecursively(); mkdirs() }
        part.inputStream().use { untar(GZIPInputStream(it), tmp) }
        part.delete()
        if (!File(tmp, "libmpv.so.2").isFile) { tmp.deleteRecursively(); error("libmpv.so.2 missing in the download") }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) error("couldn't move it into place")
    }

    /** the plain files of a tar stream into [to] (flat: names with folders in them are refused) */
    internal fun untar(s: InputStream, to: File) {
        val inp = DataInputStream(s)
        val h = ByteArray(512)
        while (true) {
            inp.readFully(h)
            if (h.all { it == 0.toByte() }) return
            fun str(from: Int, len: Int) = String(h, from, len, Charsets.UTF_8).substringBefore('\u0000')
            val name = (str(345, 155).let { if (it.isEmpty()) "" else "$it/" } + str(0, 100)).removePrefix("./")
            val size = str(124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = h[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && name.isNotEmpty()) {
                require('/' !in name && name != ".." && name != ".") { "unexpected path $name" }
                File(to, name).outputStream().use { out ->
                    var left = size
                    val buf = ByteArray(1 shl 16)
                    while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) error("short tar"); out.write(buf, 0, n); left -= n }
                }
                inp.skipNBytes(padded - size)
            } else inp.skipNBytes(padded)
        }
    }
}
