package app.tubehaus.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

/**
 * The iPad's YouTube engine lives in JavaScript (ios/engine/engine.js), so it can be replaced
 * without reinstalling the app. This runs it in Apple's JavaScriptCore and answers what it asks
 * for: HTTP requests (through Net), and the visitor identity (InnerTube, as on the other platforms).
 *
 * Which engine.js: the one built into the app, or the one in Files → On My iPad → TubeHaus,
 * whichever has the higher ENGINE_VERSION (the folder's on a tie). That folder copy is replaced
 * by a newer one from GitHub, checked every six hours ([update]); one put there by hand is used too.
 */
object Engine {
    private const val UPDATE_URL = "https://raw.githubusercontent.com/biolend83/tubehaus/ios-app/ios/engine/engine.js"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L

    private val lock = Mutex()
    private var ctx: JSContext? = null
    private var nextCall = 0
    private var lastCheck = 0L

    /** the engine in use, for Settings: its version and where it came from */
    var version = 0
        private set
    var source = ""
        private set

    /** Files → On My iPad → TubeHaus: the app's Documents folder, which Files shows under the app's name */
    val folder: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: ""
        docs.also { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) }
    }
    private val folderFile get() = "$folder/engine.js"

    private fun read(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    private fun versionOf(src: String?): Int = src?.let { Regex("var ENGINE_VERSION = (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    /** a JavaScript context with [src] loaded, or null when it doesn't load or isn't an engine */
    private fun start(src: String): JSContext? {
        val c = JSContext()
        c.evaluateScript(src)
        if (c.exception != null) return null
        val ok = c.evaluateScript("typeof OPS === 'object' && typeof __run === 'function'")?.toBool() == true
        return c.takeIf { ok && c.exception == null }
    }

    private fun load(): JSContext {
        val builtIn = NSBundle.mainBundle.pathForResource("engine", "js")?.let(::read)
        val mine = read(folderFile)
        val order = listOfNotNull(
            mine?.let { Triple(it, versionOf(it), "folder") },
            builtIn?.let { Triple(it, versionOf(it), "built-in") },
        ).sortedByDescending { it.second }
        for ((src, v, from) in order) {
            val c = start(src) ?: continue          // a broken file in the folder: fall back to the built-in one
            version = v; source = from
            return c
        }
        throw YtError("The YouTube engine is missing", YtError.Kind.OTHER)
    }

    private fun JSContext.run(js: String): String? {
        exception = null
        val v: JSValue? = evaluateScript(js)
        exception?.let { e -> throw YtError("Engine: " + (e.toObject()?.toString() ?: "error"), YtError.Kind.OTHER) }
        return v?.toObject() as? String
    }

    private fun env() = buildJsonObject {
        put("hl", InnerTube.hl)
        put("gl", InnerTube.gl)
        put("players", buildJsonArray {
            for (c in Clients.PLAYERS) add(buildJsonObject {
                put("name", c.name); put("version", c.version); put("id", c.id); put("userAgent", c.userAgent)
                putJsonObject("extra") {
                    for ((k, v) in c.extra) when (v) {
                        is Int -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
        })
    }.toString()

    /** runs [op] of engine.js with [args] and returns what it gives back */
    suspend fun call(op: String, args: JsonArray): JsonElement {
        val id = lock.withLock {
            val c = ctx ?: load().also { ctx = it }
            val n = ++nextCall
            c.globalObject?.setValue(args.toString(), forProperty = "__args")
            c.globalObject?.setValue(env(), forProperty = "__env")
            c.run("__run($n, ${JsonPrimitive(op)}, __args, __env)")
            n
        }
        var idle = 0
        while (true) {
            val (done, reqs) = lock.withLock {
                val c = ctx ?: throw YtError("The YouTube engine was reloaded", YtError.Kind.OTHER)
                val t = c.run("__take($id)") ?: "{}"
                val d = parse(t)
                if (d.at("state").str != "pending") d to JsonArray(emptyList())
                else null to (parse(c.run("__drain()") ?: "[]") as? JsonArray ?: JsonArray(emptyList()))
            }
            if (done != null) return finish(done)
            if (reqs.isEmpty()) {
                // nothing asked: the engine is between steps (or stuck); look again shortly
                if (++idle > 3000) throw YtError("The YouTube engine didn't answer", YtError.Kind.OTHER)
                delay(5)
                continue
            }
            idle = 0
            val answers = coroutineScope { reqs.map { r -> async { r.at("rid").str to answer(r) } }.awaitAll() }
            lock.withLock {
                val c = ctx ?: return@withLock
                for ((rid, a) in answers) {
                    c.globalObject?.setValue(a, forProperty = "__in")
                    c.run("__reply($rid, __in)")
                }
            }
        }
    }

    private fun finish(d: JsonElement): JsonElement {
        if (d.at("state").str == "ok") return d.at("value") ?: JsonPrimitive(null as String?)
        val msg = d.at("message").str ?: "YouTube engine error"
        val kind = runCatching { YtError.Kind.valueOf(d.at("kind").str ?: "OTHER") }.getOrDefault(YtError.Kind.OTHER)
        val code = d.at("code").int ?: 0
        if (kind == YtError.Kind.NETWORK && code > 0) throw HttpError(code, msg)
        if (kind == YtError.Kind.NETWORK) throw okio.IOException(msg)
        throw YtError(msg, kind)
    }

    /** what the app does for one thing the engine asks for; the answer as JSON */
    private suspend fun answer(r: JsonElement): String = when (r.at("kind").str) {
        "visitor" -> buildJsonObject { put("value", InnerTube.visitor()) }.toString()
        "reroll" -> buildJsonObject { put("value", InnerTube.reroll()) }.toString()
        "http" -> try {
            val headers = r.at("headers").obj?.mapValues { (_, v) -> v.str ?: "" } ?: emptyMap()
            val (status, body) = Net.raw(r.at("method").str ?: "GET", r.at("url").str ?: "", headers, r.at("body").str)
            buildJsonObject { put("status", status); put("body", body) }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: "network error") }.toString()
        }
        else -> buildJsonObject { put("error", "unknown request") }.toString()
    }

    /**
     * Every six hours at most: a newer engine.js from GitHub goes into the folder and is used
     * from the next call. A file that doesn't load as an engine is never saved.
     */
    suspend fun update() {
        val now = Sys.nowMs()
        if (now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        val text = runCatching { Net.get(UPDATE_URL) }.getOrNull() ?: return
        val v = versionOf(text)
        if (v <= maxOf(version, versionOf(read(folderFile)), versionOf(NSBundle.mainBundle.pathForResource("engine", "js")?.let(::read)))) return
        if (start(text) == null) return
        runCatching { okio.Path.Companion.run { folderFile.toPath() }.writeText(text) }
        lock.withLock { ctx = null }
    }
}
