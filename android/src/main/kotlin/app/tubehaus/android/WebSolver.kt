package app.tubehaus.android

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tubehaus.engine.JsSolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * yt-dlp's EJS solver (assets yt.solver.*.min.js, see common/ejs) in a WebView that is never shown
 * and loads nothing from the network: the phone's own JavaScript engine reads YouTube's player
 * script once (a second or two) and then answers its challenges in a fraction of one. Made only
 * when a video needs it (the TV client), and dropped when the solver fails, so a broken WebView
 * never keeps the other videos from playing.
 */
class WebSolver(private val context: Context) : JsSolver {
    private var web: WebView? = null
    private var libLoaded = false
    private val lock = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun view(): WebView? {
        web?.let { return it }
        val ready = CompletableDeferred<Unit>()
        val w = runCatching {
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { ready.complete(Unit) }
                }
                loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            }
        }.getOrNull() ?: return null       // no WebView on this device (some TV boxes)
        if (withTimeoutOrNull(10_000) { ready.await() } == null) { w.destroy(); return null }
        web = w; libLoaded = false
        return w
    }

    private suspend fun eval(script: String): String? = withContext(Dispatchers.Main) {
        val w = view() ?: return@withContext null
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { c -> w.evaluateJavascript(script) { r -> if (c.isActive) c.resume(r) } }
        }
    }

    /** evaluateJavascript hands back its result as JSON: a string comes quoted */
    private fun unquote(r: String?): String? = r?.takeIf { it != "null" }?.let { runCatching { Json.parseToJsonElement(it).jsonPrimitive.content }.getOrNull() }

    private fun asset(name: String) = context.assets.open(name).use { it.readBytes().decodeToString() }

    private suspend fun drop() = withContext(Dispatchers.Main) { web?.destroy(); web = null; libLoaded = false }

    override suspend fun load(playerJs: String): Boolean = lock.withLock {
        if (!libLoaded) {
            val lib = withContext(Dispatchers.IO) { asset("yt.solver.lib.min.js") + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" + asset("yt.solver.core.min.js") }
            libLoaded = unquote(eval("$lib\n;'ok'")) == "ok"
            if (!libLoaded) { drop(); return@withLock false }
        }
        val quoted = withContext(Dispatchers.Default) { JsonPrimitive(playerJs).toString() }
        val r = unquote(eval(
            "(function(){try{var o=jsc({type:'player',player:$quoted,requests:[],output_preprocessed:true});" +
                "window.__thPre=o.preprocessed_player;return 'ok'}catch(e){return 'error: '+e}})()",
        ))
        if (r != "ok") { drop(); return@withLock false }
        true
    }

    override suspend fun solve(n: List<String>, sig: List<String>): Map<String, String>? = lock.withLock {
        if (web == null) return@withLock null
        val reqs = buildList {
            if (n.isNotEmpty()) add("{\"type\":\"n\",\"challenges\":" + n.map { JsonPrimitive(it) }.let { kotlinx.serialization.json.JsonArray(it) } + "}")
            if (sig.isNotEmpty()) add("{\"type\":\"sig\",\"challenges\":" + sig.map { JsonPrimitive(it) }.let { kotlinx.serialization.json.JsonArray(it) } + "}")
        }
        if (reqs.isEmpty()) return@withLock emptyMap()
        val r = unquote(eval(
            "(function(){try{return JSON.stringify(jsc({type:'preprocessed',preprocessed_player:window.__thPre,requests:[${reqs.joinToString(",")}]}))}catch(e){return ''}})()",
        ))?.takeIf { it.isNotEmpty() } ?: return@withLock null
        runCatching {
            val out = HashMap<String, String>()
            val responses = Json.parseToJsonElement(r).jsonObject["responses"]!!.jsonArray
            // the answers come in the order asked: n first (when asked), then sig
            val types = buildList { if (n.isNotEmpty()) add("n"); if (sig.isNotEmpty()) add("sig") }
            responses.forEachIndexed { i, resp ->
                val data = (resp.jsonObject["data"] as? JsonObject) ?: return@forEachIndexed
                for ((k, v) in data) out[types[i] + ":" + k] = v.jsonPrimitive.content
            }
            out
        }.getOrNull()
    }
}
