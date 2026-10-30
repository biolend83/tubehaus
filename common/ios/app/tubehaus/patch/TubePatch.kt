package app.tubehaus.patch

import app.tubehaus.engine.Engine
import app.tubehaus.engine.Net
import app.tubehaus.engine.Sys
import app.tubehaus.engine.SysLock
import app.tubehaus.engine.parse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.JavaScriptCore.JSContext
import kotlin.concurrent.Volatile

/**
 * The runtime of TubePatch on the iPad: a JSPatch for Kotlin. Every @Patchable function asks
 * [call] first (the TubePatch compiler plugin puts that call in); when patch.js has a
 * replacement for it, the JavaScript runs and its answer is returned instead.
 *
 * patch.js: built into the app, or in Files → On My iPad → TubeHaus, whichever has the higher
 * PATCH_VERSION; a newer one from GitHub replaces the folder's copy (checked every six hours).
 * It defines PATCHES = { "app.tubehaus.ui.compact": function (n) { ... }, ... }, keyed by the
 * function's full name. A replacement that throws, or answers with the wrong kind of value, is
 * ignored and the function runs as built.
 */
object TubePatch {
    private const val UPDATE_URL = "https://raw.githubusercontent.com/biolend83/tubehaus/ios-app/ios/patch/patch.js"
    private const val CHECK_EVERY_MS = 6 * 3_600_000L

    private val lock = SysLock()
    private var ctx: JSContext? = null
    /** the functions the loaded patch replaces: every other call returns at once */
    @Volatile private var ids: Set<String> = emptySet()
    private var lastCheck = 0L
    var version = 0
        private set

    private val folderFile get() = Engine.folder + "/patch.js"
    private fun read(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    private fun builtIn(): String? = NSBundle.mainBundle.pathForResource("patch", "js")?.let(::read)
    private fun versionOf(src: String?): Int = src?.let { Regex("var PATCH_VERSION = (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    private fun start(src: String): Pair<JSContext, Set<String>>? {
        val c = JSContext()
        c.evaluateScript(src)
        if (c.exception != null) return null
        val keys = c.evaluateScript("typeof PATCHES === 'object' ? JSON.stringify(Object.keys(PATCHES)) : null")?.toObject() as? String ?: return null
        if (c.exception != null) return null
        return c to parse(keys).let { it as? JsonArray }.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    }

    /** loads the patch to use; called at start and after an update */
    fun load() = lock.run {
        val candidates = listOfNotNull(read(folderFile), builtIn()).sortedByDescending(::versionOf)
        for (src in candidates) {
            val (c, keys) = start(src) ?: continue
            ctx = c; ids = keys; version = versionOf(src)
            return@run
        }
        ctx = null; ids = emptySet(); version = 0
    }

    /** called at the start of every @Patchable function (put there by the compiler plugin) */
    fun call(id: String, returns: String, args: Array<Any?>): Any? {
        if (id !in ids) return NotPatched
        return try {
            lock.run {
                val c = ctx ?: return@run NotPatched
                c.exception = null
                val out = c.evaluateScript(
                    "JSON.stringify({v: PATCHES[${JsonPrimitive(id)}].apply(null, JSON.parse(${JsonPrimitive(JsonArray(args.map(::json)).toString())}))})",
                )?.toObject() as? String
                if (c.exception != null || out == null) NotPatched
                else convert((parse(out) as? JsonObject)?.get("v") ?: JsonNull, returns)
            }
        } catch (e: Throwable) {
            NotPatched
        }
    }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Enum<*> -> JsonPrimitive(v.name)
        is Iterable<*> -> JsonArray(v.map(::json))
        is Array<*> -> JsonArray(v.map(::json))
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
        else -> JsonPrimitive(v.toString())
    }

    /** JavaScript's answer as the Kotlin type the function returns; NotPatched when it doesn't fit */
    private fun convert(v: JsonElement, returns: String): Any? {
        val p = v as? JsonPrimitive
        return when (returns) {
            "kotlin.Unit" -> Unit
            "kotlin.String" -> p?.takeIf { it.isString }?.content ?: NotPatched
            "kotlin.Int" -> p?.doubleOrNull?.toInt() ?: NotPatched
            "kotlin.Long" -> p?.doubleOrNull?.toLong() ?: NotPatched
            "kotlin.Double" -> p?.doubleOrNull ?: NotPatched
            "kotlin.Float" -> p?.doubleOrNull?.toFloat() ?: NotPatched
            "kotlin.Boolean" -> p?.booleanOrNull ?: NotPatched
            "kotlin.collections.List" -> (v as? JsonArray)?.map { e ->
                val q = e as? JsonPrimitive ?: return NotPatched
                if (q.isString) q.content else q.doubleOrNull ?: q.booleanOrNull ?: return NotPatched
            } ?: NotPatched
            else -> NotPatched          // a type JavaScript can't make: the function runs as built
        }
    }

    /** every six hours at most: a newer patch.js from GitHub into the folder, then loaded */
    suspend fun update() {
        val now = Sys.nowMs()
        if (now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        val text = runCatching { Net.get(UPDATE_URL) }.getOrNull() ?: return
        if (versionOf(text) <= maxOf(version, versionOf(read(folderFile)), versionOf(builtIn()))) return
        if (start(text) == null) return
        runCatching { with(okio.Path.Companion) { folderFile.toPath() }.let { p -> okio.FileSystem.SYSTEM.write(p) { writeUtf8(text) } } }
        load()
    }
}
