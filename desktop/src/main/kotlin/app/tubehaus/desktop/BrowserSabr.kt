package app.tubehaus.desktop

import app.tubehaus.engine.Net
import app.tubehaus.engine.Sabr
import app.tubehaus.engine.SabrHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SabrHost] on a computer: the Edge or Chrome already on it, headless, nothing shown and its own profile
 * (none of the person's sign-ins), with a blank page of ours at https://www.youtube.com/tubehaus-sabr
 * (answered through DevTools, nothing of YouTube's is loaded for it) running common/sabr/sabr-page.js.
 * Started the first time a video needs SABR; without Edge or Chrome there is no SABR on this computer.
 */
class BrowserSabr(private val profile: File) : SabrHost {
    private var proc: Process? = null
    private var ws: WebSocket? = null
    private val lock = Mutex()
    private val debug = System.getenv("TUBEHAUS_SABRLOG") != null
    private val ids = AtomicInteger(0)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    // to the browser on this computer: no proxy, and a DevTools socket that may be quiet for long
    private val http = Net.client.newBuilder().proxy(java.net.Proxy.NO_PROXY).readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    /** the DevTools port of the browser in use */
    @Volatile private var devPort = 0

    init { Runtime.getRuntime().addShutdownHook(Thread { closeBrowser() }) }

    /** the DevTools targets of a browser on [port], or null */
    private fun targets(port: Int): String? = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/json").build()).execute().use { if (it.isSuccessful) it.body!!.string() else null }
    }.getOrNull()

    /**
     * The browser closed for good, also one this app found already running: a launch's own process
     * (what destroy() stops) can be gone while the browser it started lives on, and a browser left over
     * holds the profile, so the next launch hands over to it and quits at once, and SABR never came up.
     */
    private fun closeBrowser() {
        val port = devPort
        if (port > 0) runCatching {
            val v = http.newCall(Request.Builder().url("http://127.0.0.1:$port/json/version").build()).execute().use { it.body!!.string() }
            val url = Json.parseToJsonElement(v).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content
            val done = java.util.concurrent.CountDownLatch(1)
            http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"id\":1,\"method\":\"Browser.close\"}") }
                override fun onMessage(webSocket: WebSocket, text: String) { done.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            })
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        proc?.destroy()
    }

    companion object {
        private const val PAGE = "https://www.youtube.com/tubehaus-sabr"

        /** Edge or Chrome (or Chromium), where they install */
        fun browser(): File? {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            val c = when {
                "win" in os -> listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")).flatMap {
                    listOf("$it/Microsoft/Edge/Application/msedge.exe", "$it/Google/Chrome/Application/chrome.exe")
                }
                "mac" in os -> listOf("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium", "$home/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                else -> listOf("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge", "/snap/bin/chromium")
            }
            return c.map(::File).firstOrNull { it.canExecute() }
        }

        private fun resource(name: String) = BrowserSabr::class.java.getResourceAsStream("/$name")?.use { it.readBytes().decodeToString() }
    }

    private fun send(method: String, params: JsonObject = JsonObject(emptyMap())): CompletableDeferred<JsonObject> {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<JsonObject>()
        waiting[id] = d
        val ok = ws?.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString()) ?: false
        if (!ok) { waiting.remove(id); d.complete(JsonObject(emptyMap())) }
        return d
    }

    private suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject? =
        withTimeoutOrNull(timeoutMs) { send(method, params).await() }

    private suspend fun eval(expr: String, timeoutMs: Long = 30_000): JsonElement? {
        val r = call("Runtime.evaluate", buildJsonObject { put("expression", expr); put("awaitPromise", true); put("returnByValue", true) }, timeoutMs) ?: return null
        val res = r["result"]?.jsonObject ?: return null
        if (res["exceptionDetails"] != null) return null
        return res["result"]?.jsonObject?.get("value")
    }

    private fun onEvent(o: JsonObject) {
        val p = o["params"]?.jsonObject ?: return
        when (o["method"]?.jsonPrimitive?.content) {
            "Fetch.requestPaused" -> {
                // our blank page; every other request goes on as it is
                val id = p["requestId"]?.jsonPrimitive?.content ?: return
                send("Fetch.fulfillRequest", buildJsonObject {
                    put("requestId", id); put("responseCode", 200)
                    put("responseHeaders", buildJsonArray { add(buildJsonObject { put("name", "Content-Type"); put("value", "text/html; charset=utf-8") }) })
                    put("body", Base64.getEncoder().encodeToString("<!doctype html><html><head><meta charset=utf-8></head><body></body></html>".toByteArray()))
                })
            }
            "Runtime.bindingCalled" -> if (p["name"]?.jsonPrimitive?.content == "__thHost") {
                val m = runCatching { Json.parseToJsonElement(p["payload"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: return
                when (m["t"]?.jsonPrimitive?.content) {
                    "piece" -> Sabr.piece(m["run"]?.jsonPrimitive?.intOrNull ?: return, m["at"]?.jsonPrimitive?.longOrNull ?: return,
                        m["init"]?.jsonPrimitive?.booleanOrNull == true, m["ms"]?.jsonPrimitive?.longOrNull ?: -1,
                        Base64.getDecoder().decode(m["data"]?.jsonPrimitive?.content ?: return))
                    "log" -> if (debug) System.err.println("sabr page: " + m["msg"]?.jsonPrimitive?.contentOrNull)
                    "end" -> { if (debug) System.err.println("sabr end: $m"); Sabr.end(m["run"]?.jsonPrimitive?.intOrNull ?: return, m["bad"]?.jsonPrimitive?.booleanOrNull == true,
                        m["error"]?.jsonPrimitive?.contentOrNull) }
                }
            }
        }
    }

    private fun drop() {
        runCatching { ws?.close(1000, null) }; ws = null
        proc?.destroy(); proc = null
        waiting.values.forEach { it.complete(JsonObject(emptyMap())) }; waiting.clear()
    }

    /** the browser and its page, started when first needed and again when it went away */
    private suspend fun ready(): Boolean = lock.withLock {
        // the DevTools socket, not the launched process: Edge's launcher quits once it has handed off
        if (ws != null) return@withLock true
        drop()
        withContext(Dispatchers.IO) { start() }
    }

    /** the browsers that have this profile open (one left from before a crash): ended */
    private fun endLeftovers() {
        val os = System.getProperty("os.name").lowercase()
        val cmd = if (os.contains("win")) {
            // as -EncodedCommand: quotes inside a Windows command line don't survive the trip
            val ps = "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe' or Name='chrome.exe'\" | " +
                "Where-Object { \$_.CommandLine -like '*" + profile.absolutePath.replace("'", "''") + "*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
            listOf("powershell", "-NoProfile", "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE)))
        } else listOf("pkill", "-f", "--user-data-dir=" + profile.absolutePath)
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private suspend fun start(): Boolean = launch(true)

    private suspend fun launch(again: Boolean): Boolean {
        val exe = browser() ?: return false
        val page = resource("sabr-page.js") ?: return false
        val solver = (resource("yt.solver.lib.min.js") ?: return false) + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" + (resource("yt.solver.core.min.js") ?: return false)
        profile.mkdirs()
        // a browser left from before (the app was killed) still has this profile: use it, a new one would quit
        val left = runCatching { File(profile, "tubehaus-port").readText().trim().toInt() }.getOrNull()
        val leftList = left?.let { targets(it) }
        if (left != null && leftList != null) {
            if (debug) System.err.println("sabr browser left running on $left: using it")
            devPort = left
            return attach(leftList, page, solver)
        }
        // a free port picked here: with --remote-debugging-port=0 (the browser picks) YouTube turns its tokens down
        val free = runCatching { java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort } }.getOrNull() ?: return false
        val p = runCatching {
            ProcessBuilder(exe.path, "--headless=new", "--remote-debugging-port=$free", "--user-data-dir=${profile.absolutePath}",
                "--no-first-run", "--no-default-browser-check", "--disable-extensions", "--disable-sync", "--mute-audio", "about:blank")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return false
        proc = p
        var list: String? = null
        for (i in 0 until 100) {
            list = targets(free)
            // not "|| !p.isAlive": Edge's launcher can hand off to a new browser process and quit while that one comes up
            if (list != null) break
            delay(100)
        }
        if (debug) System.err.println("sabr browser ${exe.name} on $free: " + (if (list == null) "no DevTools (alive ${p.isAlive})" else "up"))
        if (list == null && again) {
            // it handed over to a browser that still holds the profile and quit: end that one, start again
            proc = null
            endLeftovers()
            delay(500)
            return launch(false)
        }
        devPort = free
        runCatching { File(profile, "tubehaus-port").writeText(free.toString()) }
        return attach(list, page, solver)
    }

    private suspend fun attach(list: String?, page: String, solver: String): Boolean {
        val url = list?.let { runCatching { Json.parseToJsonElement(it).jsonArray.firstOrNull { t -> t.jsonObject["type"]?.jsonPrimitive?.content == "page" }
            ?.jsonObject?.get("webSocketDebuggerUrl")?.jsonPrimitive?.content }.getOrNull() } ?: run { drop(); return false }
        val open = CompletableDeferred<Boolean>()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open.complete(true) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val id = o["id"]?.jsonPrimitive?.intOrNull
                if (id != null) waiting.remove(id)?.complete(o) else onEvent(o)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { open.complete(false); if (ws === webSocket) ws = null }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (ws === webSocket) ws = null }
        })
        if (withTimeoutOrNull(10_000) { open.await() } != true) { drop(); return false }
        call("Runtime.enable"); call("Page.enable")
        call("Fetch.enable", buildJsonObject { put("patterns", buildJsonArray { add(buildJsonObject { put("urlPattern", PAGE); put("requestStage", "Request") }) }) })
        call("Runtime.addBinding", buildJsonObject { put("name", "__thHost") })
        call("Page.navigate", buildJsonObject { put("url", PAGE) })
        for (i in 0 until 50) { if (eval("location.href")?.jsonPrimitive?.contentOrNull == PAGE && eval("document.readyState")?.jsonPrimitive?.contentOrNull == "complete") break; delay(100) }
        eval("$page\n;1") ?: run { drop(); return false }
        if (eval("__thSabr.setSolver(${JsonPrimitive(solver)})")?.jsonPrimitive?.contentOrNull != "ok") { drop(); return false }
        return true
    }

    private fun q(s: String) = JsonPrimitive(s).toString()

    private suspend fun ask(expr: String): String? {
        if (!ready()) return null
        return eval("$expr.catch(()=>'')", 60_000)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    private fun fire(expr: String) { if (ws != null) send("Runtime.evaluate", buildJsonObject { put("expression", expr) }) }

    override suspend fun warm() { ready() }
    override suspend fun prepare(id: String, hl: String, gl: String) = ask("__thSabr.prepare(${q(id)},${q(hl)},${q(gl)})")
    override suspend fun caption(id: String, url: String) = ask("__thSabr.caption(${q(id)},${q(url)})")
    override fun start(run: Int, id: String, index: Int, startMs: Long) = if (debug) { System.err.println("sabr start $run $id #$index from $startMs ms"); fire("__thSabr.start($run,${q(id)},$index,$startMs)") } else fire("__thSabr.start($run,${q(id)},$index,$startMs)")
    override fun allow(run: Int, upTo: Long) = fire("__thSabr.allow($run,$upTo)")
    override fun stop(run: Int) = fire("__thSabr.stop($run)")

    fun close() = drop()
}
