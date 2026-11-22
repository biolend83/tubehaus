package app.tubehaus.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tubehaus.engine.Sabr
import app.tubehaus.engine.SabrHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SabrHost] on Android: a WebView nobody sees, its page at https://www.youtube.com (a blank page of ours,
 * nothing of YouTube's is shown), running common/sabr/sabr-page.js. Made the first time a video needs SABR.
 * It identifies as desktop Chrome of the WebView's own version, so YouTube answers as to its web player.
 */
class WebSabr(private val context: Context) : SabrHost {
    private var web: WebView? = null
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val ids = AtomicInteger(0)

    /** what the page sends back (on a WebView thread) */
    inner class Bridge {
        @JavascriptInterface fun post(text: String) {
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (o["t"]?.jsonPrimitive?.content) {
                "piece" -> {
                    val data = Base64.decode(o["data"]?.jsonPrimitive?.content ?: return, Base64.DEFAULT)
                    Sabr.piece(o["run"]?.jsonPrimitive?.intOrNull ?: return, o["at"]?.jsonPrimitive?.longOrNull ?: return,
                        o["init"]?.jsonPrimitive?.booleanOrNull == true, o["ms"]?.jsonPrimitive?.longOrNull ?: -1, data)
                }
                "end" -> Sabr.end(o["run"]?.jsonPrimitive?.intOrNull ?: return, o["bad"]?.jsonPrimitive?.booleanOrNull == true,
                    o["error"]?.jsonPrimitive?.content)
                "reply" -> replies.remove(o["id"]?.jsonPrimitive?.intOrNull ?: return)?.complete(o["r"]?.jsonPrimitive?.content)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun view(): WebView? = withContext(Dispatchers.Main) {
        web?.let { return@withContext it }
        val ready = CompletableDeferred<Unit>()
        val w = runCatching {
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                // desktop Chrome of this WebView's version: on a phone's UA YouTube sends its mobile site
                val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(context))?.value ?: "Chrome/130.0.0.0"
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
                addJavascriptInterface(Bridge(), "__thBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { ready.complete(Unit) }
                }
                loadDataWithBaseURL("https://www.youtube.com/tubehaus-sabr",
                    "<!doctype html><html><head><meta charset=utf-8></head><body></body></html>", "text/html", "utf-8", null)
            }
        }.getOrNull() ?: return@withContext null       // no WebView on this device
        if (withTimeoutOrNull(10_000) { ready.await() } == null) { w.destroy(); return@withContext null }
        val solver = withContext(Dispatchers.IO) { asset("yt.solver.lib.min.js") + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" + asset("yt.solver.core.min.js") }
        val page = withContext(Dispatchers.IO) { asset("sabr-page.js") }
        val quoted = withContext(Dispatchers.Default) { JsonPrimitive(solver).toString() }
        val ok = CompletableDeferred<String?>()
        w.evaluateJavascript("window.__thHost=function(s){__thBridge.post(s)};\n$page\n;__thSabr.setSolver($quoted)") { ok.complete(it) }
        if (withTimeoutOrNull(15_000) { ok.await() } != "\"ok\"") { w.destroy(); return@withContext null }
        web = w
        w
    }

    private fun asset(name: String) = context.assets.open(name).use { it.readBytes().decodeToString() }

    private fun js(script: String) { main.post { web?.evaluateJavascript(script, null) } }

    /** calls a page function that answers with a promise of text */
    private suspend fun ask(call: String, timeoutMs: Long): String? {
        val w = lock.withLock { view() } ?: return null
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String?>()
        replies[id] = d
        withContext(Dispatchers.Main) {
            w.evaluateJavascript("Promise.resolve().then(()=>$call).then(r=>__thHost(JSON.stringify({t:'reply',id:$id,r:r==null?'':String(r)})),e=>__thHost(JSON.stringify({t:'reply',id:$id,r:''})))", null)
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        replies.remove(id)
        return r?.takeIf { it.isNotEmpty() }
    }

    private fun q(s: String) = JsonPrimitive(s).toString()

    override suspend fun prepare(id: String, hl: String, gl: String) = ask("__thSabr.prepare(${q(id)},${q(hl)},${q(gl)})", 45_000)
    override suspend fun caption(id: String, url: String) = ask("__thSabr.caption(${q(id)},${q(url)})", 45_000)
    override fun start(run: Int, id: String, index: Int, startMs: Long) = js("__thSabr.start($run,${q(id)},$index,$startMs)")
    override fun allow(run: Int, upTo: Long) = js("__thSabr.allow($run,$upTo)")
    override fun stop(run: Int) = js("__thSabr.stop($run)")
    override val live get() = true
    override fun startLive(runV: Int, runA: Int, id: String, index: Int) = js("__thSabr.liveStart($runV,$runA,${q(id)},$index)")
}
