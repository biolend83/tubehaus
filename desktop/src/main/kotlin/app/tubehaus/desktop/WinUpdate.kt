// Windows: a new version installed from inside the app, like on Android. The .msi is downloaded with a
// progress bar, Windows Installer replaces the app with only its own small progress window (no wizard),
// and TubeHaus opens again. The library and settings are in %APPDATA%, which an update never touches.
package app.tubehaus.desktop

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

object WinUpdate {
    val supported: Boolean get() = System.getProperty("os.name").startsWith("Windows")

    /** false when it can't be done here (then the release page opens instead) */
    fun install(url: String, progress: (Float) -> Unit, done: (Boolean) -> Unit): Boolean {
        if (!supported || !url.endsWith(".msi")) return false
        Thread {
            val ok = runCatching {
                val dir = File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), "TubeHaus-update").apply {
                    mkdirs(); listFiles()?.forEach { it.delete() }
                }
                val msi = File(dir, "TubeHaus.msi")
                val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
                val r = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "TubeHaus").build(), HttpResponse.BodyHandlers.ofInputStream())
                require(r.statusCode() == 200) { "HTTP ${r.statusCode()}" }
                val total = r.headers().firstValueAsLong("Content-Length").orElse(-1)
                r.body().use { inp ->
                    msi.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var shown = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else 0
                            if (pct != shown) { shown = pct; progress(pct / 100f) }
                        }
                    }
                }
                require(msi.length() > 1_000_000) { "too small" }
                progress(1f)
                // after this app has quit: install with only Windows' progress bar, then open the new version
                val exe = ProcessHandle.current().info().command().orElse("")
                val again = if (exe.endsWith("TubeHaus.exe", ignoreCase = true)) " & start \"\" \"$exe\"" else ""
                ProcessBuilder("cmd", "/c", "timeout /t 2 /nobreak >nul & msiexec /i \"${msi.absolutePath}\" /passive /norestart$again")
                    .redirectErrorStream(true).start()
            }.isSuccess
            done(ok)
            if (ok) { Thread.sleep(500); exitProcess(0) }
        }.start()
        return true
    }
}
