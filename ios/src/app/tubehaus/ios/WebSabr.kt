@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.tubehaus.ios

import app.tubehaus.engine.Sabr
import app.tubehaus.engine.SabrHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import kotlin.io.encoding.Base64

/**
 * [SabrHost] on the iPad: a WKWebView nobody sees (one point big, in the app's window so WebKit keeps it
 * running), its page a blank one of ours at https://www.youtube.com, running common/sabr/sabr-page.js with
 * yt-dlp's solver (both in the app). Made the first time a video needs it.
 */
class WebSabr : SabrHost {
    private var web: WKWebView? = null
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replies = HashMap<Int, CompletableDeferred<String?>>()
    private var ids = 0
    // what the page sends, in order, off the main thread (pieces are written to disk)
    private val inbox = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch { for (m in inbox) runCatching { onMessage(m) } }
    }

    private class Handler(val on: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            (didReceiveScriptMessage.body as? String)?.let(on)
        }
    }

    private fun onMessage(text: String) {
        val o = Json.parseToJsonElement(text).jsonObject
        when (o["t"]?.jsonPrimitive?.content) {
            "piece" -> Sabr.piece(o["run"]?.jsonPrimitive?.intOrNull ?: return, o["at"]?.jsonPrimitive?.longOrNull ?: return,
                o["init"]?.jsonPrimitive?.booleanOrNull == true, o["ms"]?.jsonPrimitive?.longOrNull ?: -1,
                Base64.Default.decode(o["data"]?.jsonPrimitive?.content ?: return))
            "end" -> Sabr.end(o["run"]?.jsonPrimitive?.intOrNull ?: return, o["bad"]?.jsonPrimitive?.booleanOrNull == true, o["error"]?.jsonPrimitive?.contentOrNull)
            "reply" -> {
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return
                val d = lockedReply { replies.remove(id) }
                d?.complete(o["r"]?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    private val replyLock = platform.Foundation.NSLock()
    private inline fun <T> lockedReply(f: () -> T): T { replyLock.lock(); try { return f() } finally { replyLock.unlock() } }

    private fun resource(name: String, ext: String): String? =
        NSBundle.mainBundle.pathForResource(name, ext)?.let { NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null) }

    private suspend fun eval(w: WKWebView, js: String): Any? = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Any?>()
        w.evaluateJavaScript(js) { r, e -> d.complete(if (e != null) null else r) }
        withTimeoutOrNull(30_000) { d.await() }
    }

    private suspend fun view(): WKWebView? {
        web?.let { return it }
        val page = resource("sabr-page", "js") ?: return null
        val solver = (resource("yt.solver.lib.min", "js") ?: return null) + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" + (resource("yt.solver.core.min", "js") ?: return null)
        val w = withContext(Dispatchers.Main) {
            val cfg = WKWebViewConfiguration()
            cfg.userContentController.addScriptMessageHandler(Handler { m -> inbox.trySend(m) }, "thHost")
            val v = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = cfg)
            v.alpha = 0.01
            v.userInteractionEnabled = false
            (UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)?.addSubview(v)
            v.loadHTMLString("<!doctype html><html><head><meta charset=utf-8></head><body></body></html>", NSURL(string = "https://www.youtube.com/tubehaus-sabr"))
            v
        }
        for (i in 0 until 100) {
            if (eval(w, "location.href + ' ' + document.readyState") == "https://www.youtube.com/tubehaus-sabr complete") break
            delay(100)
        }
        val quoted = JsonPrimitive(solver).toString()
        val ok = eval(w, "window.__thHost=function(s){window.webkit.messageHandlers.thHost.postMessage(s)};\n$page\n;__thSabr.setSolver($quoted)")
        if (ok != "ok") { withContext(Dispatchers.Main) { w.removeFromSuperview() }; return null }
        web = w
        return w
    }

    private fun q(s: String) = JsonPrimitive(s).toString()

    /** calls a page function that answers with a promise of text */
    private suspend fun ask(call: String): String? {
        val w = lock.withLock { view() } ?: return null
        val d = CompletableDeferred<String?>()
        val id = lockedReply { val id = ++ids; replies[id] = d; id }
        withContext(Dispatchers.Main) {
            w.evaluateJavaScript("Promise.resolve().then(()=>$call).then(r=>__thHost(JSON.stringify({t:'reply',id:$id,r:r==null?'':String(r)})),e=>__thHost(JSON.stringify({t:'reply',id:$id,r:''})));1", null)
        }
        val r = withTimeoutOrNull(60_000) { d.await() }
        lockedReply { replies.remove(id) }
        return r?.takeIf { it.isNotEmpty() }
    }

    private fun fire(js: String) {
        scope.launch(Dispatchers.Main) { web?.evaluateJavaScript("$js;1", null) }
    }

    override suspend fun prepare(id: String, hl: String, gl: String) = ask("__thSabr.prepare(${q(id)},${q(hl)},${q(gl)})")
    override suspend fun caption(id: String, url: String) = ask("__thSabr.caption(${q(id)},${q(url)})")
    override fun start(run: Int, id: String, index: Int, startMs: Long) = fire("__thSabr.start($run,${q(id)},$index,$startMs)")
    override fun allow(run: Int, upTo: Long) = fire("__thSabr.allow($run,$upTo)")
    override fun stop(run: Int) = fire("__thSabr.stop($run)")
}
