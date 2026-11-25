package app.tubehaus.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SabrHost] without a browser: the same SABR page code (common/sabr/sabr-page.js's source) in QuickJS, a small
 * JavaScript engine in the app, with jsdom as the document BotGuard looks at (common/sabr/lite; built into
 * common/sabr/sabr-lite.js). YouTube takes jsdom's tokens as it does on the TubeHaus server, and yt-dlp's solver
 * answers n in the same engine. The page's fetch comes here (OkHttp), and its pieces go to [Sabr.piece].
 *
 * One evaluation runs for the engine's whole life: a loop in the page that takes the app's calls off [cmds]
 * one at a time (QuickJS runs one thing at a time), so a call never waits for another to finish.
 * [load] reads a file the app carries (a resource on a computer, an asset on Android); [cache] keeps the
 * solver's work on YouTube's player script, which takes seconds here and changes every week or two.
 */
class JsSabr(
    private val load: (String) -> String?, private val cache: File?,
    // the engine: QuickJS unless the platform has a faster one (V8 on Android)
    private val runtime: (kotlinx.coroutines.CoroutineDispatcher) -> JsRuntime = { QuickJsRuntime(it, 1024L shl 20, 8L shl 20) },
) : SabrHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jsThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(null, r, "sabr-js", 64L shl 20).apply { isDaemon = true } }.asCoroutineDispatcher()
    private val lock = Mutex()
    private val debug = System.getenv("TUBEHAUS_SABRLOG") != null
    @Volatile private var engine: JsRuntime? = null
    @Volatile private var loop: Job? = null
    @Volatile private var cmds = Channel<String>(Channel.UNLIMITED)
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val ids = AtomicInteger(0)
    private val fetchIds = AtomicInteger(0)
    private val fetches = ConcurrentHashMap<Int, Response>()
    // the video servers keep a SABR answer open while they wait: no read timeout of our own
    private val http = Net.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var usedAt = 0L

    private fun log(s: String) { if (debug) System.err.println("jssabr: $s") }

    /** the engine with the page in it, made when first wanted and again after it closed */
    private suspend fun ready(): Boolean = lock.withLock {
        usedAt = System.currentTimeMillis()
        if (engine != null && loop?.isActive == true) return@withLock true
        close()
        val polyfill = load("sabr-lite-polyfill.js") ?: return@withLock false
        val page = load("sabr-lite.js") ?: return@withLock false
        val solver = (load("yt.solver.lib.min.js") ?: return@withLock false) + SOLVER_GLUE + (load("yt.solver.core.min.js") ?: return@withLock false) +
            "\n;" + (load("fastpre.js") ?: "") // the player's preprocess in a few seconds instead of most of a minute
        // one thread of its own with a large stack: jsdom goes deep, and a phone's pool threads have about 1 MB (QuickJS ran past it and the app
        // went down); QuickJS also wants the same thread every time, which a pool does not promise
        // QuickJS's native library may be missing for this machine (an unusual processor or system): then never again
        val q = try { runtime(jsThread) } catch (e: Throwable) { log("no engine here: $e"); broken = true; return@withLock false }
        bind(q)
        cmds = Channel(Channel.UNLIMITED)
        val started = CompletableDeferred<Boolean>()
        val t = System.currentTimeMillis()
        loop = scope.launch {
            try {
                q.evaluate("globalThis.__cores = ${Runtime.getRuntime().availableProcessors()};", "cores.js")
                q.evaluate(polyfill, "sabr-lite-polyfill.js")
                q.evaluate(page, "sabr-lite.js")
                q.evaluate("window.__thSabr.setSolver(${JsonPrimitive(solver)})", "solver.js")
                log("engine up in ${System.currentTimeMillis() - t} ms")
                started.complete(true)
                q.await("__thLoop()", "loop.js")
            } catch (e: Throwable) {
                log("engine stopped: $e")
                started.complete(false)
            } finally {
                if (engine === q) { engine = null; Sabr.hostGone() }
                replies.values.forEach { it.complete(null) }; replies.clear()
                runCatching { q.close() }
            }
        }
        engine = q
        if (!started.await()) {
            engine = null
            // the page did not start twice in a row: something in this machine's engine, not a passing hitch
            if (++failedStarts >= 2) broken = true
            return@withLock false
        }
        failedStarts = 0
        idleWatch()
        true
    }

    /** QuickJS cannot run here (no native library, or the page never starts): [Fallback] then uses another host */
    @Volatile var broken = false
        private set
    private var failedStarts = 0

    private fun bind(q: JsRuntime) {
        q.function("__log") { a -> log(a.joinToString(" ")) }
        q.function("__now") { _ -> System.nanoTime() / 1e6 }
        q.function("__rand") { a -> val b = ByteArray((a[0] as Number).toInt()); java.security.SecureRandom().nextBytes(b); b.joinToString("") { "%02x".format(it) } }
        q.asyncFunction("__sleep") { a -> delay((a[0] as Number).toLong()) }
        q.asyncFunction("__next") { _ -> cmds.receiveCatching().getOrNull() }
        q.function("__reply") { a ->
            val d = replies.remove((a[0] as Number).toInt()) ?: return@function Unit
            // a long answer with letters past ASCII comes as its UTF-8 bytes (a string that size is cut off on its way out of QuickJS)
            if (a.getOrNull(2) != null) { log("call failed: ${a[2]}"); d.complete(null) }
            else d.complete(a.getOrNull(1).let { if (it is ByteArray) it.decodeToString() else it as String? })
        }
        q.function("__thHost") { a ->
            val m = runCatching { Json.parseToJsonElement(a[0] as String).jsonObject }.getOrNull() ?: return@function Unit
            when (m["t"]?.jsonPrimitive?.content) {
                "log" -> log("page: " + m["msg"]?.jsonPrimitive?.contentOrNull)
                "end" -> { log("end: $m"); Sabr.end(m["run"]?.jsonPrimitive?.intOrNull ?: return@function Unit, m["bad"]?.jsonPrimitive?.booleanOrNull == true, m["error"]?.jsonPrimitive?.contentOrNull) }
            }
            Unit
        }
        q.function("__thPiece") { a ->
            val m = runCatching { Json.parseToJsonElement(a[0] as String).jsonObject }.getOrNull() ?: return@function Unit
            Sabr.piece(m["run"]?.jsonPrimitive?.intOrNull ?: return@function Unit, m["at"]?.jsonPrimitive?.longOrNull ?: return@function Unit,
                m["init"]?.jsonPrimitive?.booleanOrNull == true, m["ms"]?.jsonPrimitive?.longOrNull ?: -1, bytes(a[1]) ?: return@function Unit)
            Unit
        }
        q.function("__cacheGet") { a -> cacheFile(a[0] as String)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } }
        q.function("__cacheSet") { a ->
            val f = cacheFile(a[0] as String) ?: return@function Unit
            runCatching { f.parentFile?.mkdirs(); f.parentFile?.listFiles { x -> x.name.startsWith("player-") && x != f }?.forEach { it.delete() }; f.writeText(a[1] as String) }
            Unit
        }
        q.asyncFunction("__fetch") { a -> fetch(a[0] as String, bytes(a.getOrNull(1))) }
        q.asyncFunction("__read") { a -> read((a[0] as Number).toInt()) }
        q.function("__fetchAbort") { a -> fetches.remove((a[0] as Number).toInt())?.let { r -> scope.launch(Dispatchers.IO) { runCatching { r.close() } } }; Unit }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun bytes(v: Any?): ByteArray? = v as? ByteArray

    private fun cacheFile(key: String): File? = cache?.let { File(it, key.replace(Regex("[^\\w.-]"), "_").take(120) + ".js") }

    /** a request of the page's: its head as JSON {id, status, headers}; the body comes through [read] */
    private suspend fun fetch(json: String, body: ByteArray?): String = withContext(Dispatchers.IO) {
        val o = Json.parseToJsonElement(json).jsonObject
        val url = o["url"]!!.jsonPrimitive.content
        val method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val h = o["headers"]?.jsonObject ?: JsonObject0
        val b = Request.Builder().url(url)
        for ((k, v) in h) if (k.lowercase() !in setOf("content-length", "host", "connection", "accept-encoding")) b.header(k, v.jsonPrimitive.content)
        val type = h.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.jsonPrimitive?.content
        b.method(method, if (method == "GET" || method == "HEAD") null else (body ?: ByteArray(0)).toRequestBody(type?.toMediaTypeOrNull()))
        val r = http.newCall(b.build()).execute()
        val id = fetchIds.incrementAndGet()
        fetches[id] = r
        buildJsonObject {
            put("id", id); put("status", r.code)
            put("headers", buildJsonObject { for (name in r.headers.names()) put(name.lowercase(), r.headers.values(name).joinToString(", ")) })
        }.toString()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private suspend fun read(id: Int): ByteArray? = withContext(Dispatchers.IO) {
        val r = fetches[id] ?: return@withContext null
        val src = r.body?.source() ?: run { fetches.remove(id); r.close(); return@withContext null }
        val buf = ByteArray(64 shl 10)
        val n = runCatching { src.read(buf) }.getOrDefault(-1)
        if (n <= 0) { fetches.remove(id); r.close(); null } else buf.copyOf(n)
    }

    /** a call into the page; its answer (a string), or null */
    private suspend fun ask(f: String, vararg args: Any, timeoutMs: Long = 90_000): String? {
        if (!ready()) return null
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String?>()
        replies[id] = d
        cmds.trySend(command(id, f, args))
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { replies.remove(id) }?.takeIf { it.isNotEmpty() }
    }

    /** a call into the page whose answer nobody waits for */
    private fun fire(f: String, vararg args: Any) {
        usedAt = System.currentTimeMillis()
        if (engine != null) cmds.trySend(command(0, f, args))
        else scope.launch { if (ready()) cmds.trySend(command(0, f, args)) }
    }

    private fun command(id: Int, f: String, args: Array<out Any>) = buildJsonObject {
        put("id", id); put("f", f)
        put("a", JsonArray(args.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
    }.toString()

    /** the engine (some hundred MB with a video's sessions) closes after a while with nothing to do */
    private fun idleWatch() {
        scope.launch {
            while (engine != null) {
                delay(60_000)
                if (System.currentTimeMillis() - usedAt > IDLE_MS && fetches.isEmpty()) { log("idle: closing"); lock.withLock { close() }; Sabr.hostGone(); break }
            }
        }
    }

    private fun close() {
        cmds.close()
        loop?.cancel(); loop = null
        engine = null
        fetches.values.forEach { runCatching { it.close() } }; fetches.clear()
    }

    override suspend fun warm() { ready() }
    // the engine went down while it worked on it (memory, a crash in the page): once more in a fresh one
    override suspend fun prepare(id: String, hl: String, gl: String) =
        ask("prepare", id, hl, gl) ?: if (engine == null && !broken) { log("prepare again in a fresh engine"); ask("prepare", id, hl, gl) } else null
    override suspend fun caption(id: String, url: String) = ask("caption", id, url)
    override fun start(run: Int, id: String, index: Int, startMs: Long) { log("start $run $id #$index from $startMs ms"); fire("start", run, id, index, startMs) }
    override fun allow(run: Int, upTo: Long) = fire("allow", run, upTo)
    override fun stop(run: Int) = fire("stop", run)
    override val live get() = true
    override fun startLive(runV: Int, runA: Int, id: String, index: Int) = fire("liveStart", runV, runA, id, index)

    companion object {
        private const val IDLE_MS = 10 * 60_000L
        private val JsonObject0 = kotlinx.serialization.json.JsonObject(emptyMap())
        /** yt-dlp's solver in QuickJS: astring writing into a list, not one string grown a piece at a time (minutes → seconds) */
        const val SOLVER_GLUE = "\n;var meriyah=lib.meriyah,astring={generate:function(a,o){var w={p:[],write:function(s){this.p.push(s)}};" +
            "lib.astring.generate(a,Object.assign({},o,{output:w}));return w.p.join(\"\")}};\n"
    }
}

/**
 * [JsSabr] while it can run here, else [backup] (a browser page): a machine without QuickJS's native library, or one
 * where the page never starts, still plays what needs SABR. The switch is for good, made when JsSabr gives up.
 */
class Fallback(private val js: JsSabr, private val backup: () -> SabrHost?) : SabrHost {
    @Volatile private var other: SabrHost? = null
    private fun now(): SabrHost = other ?: if (js.broken) (backup()?.also { other = it } ?: js) else js
    private suspend fun <T> tried(f: suspend (SabrHost) -> T?): T? {
        val h = now()
        val r = f(h)
        // it gave up just now: the same call on the backup
        return if (r == null && h === js && js.broken) backup()?.also { other = it }?.let { f(it) } else r
    }
    override suspend fun prepare(id: String, hl: String, gl: String) = tried { it.prepare(id, hl, gl) }
    override suspend fun caption(id: String, url: String) = tried { it.caption(id, url) }
    override fun start(run: Int, id: String, index: Int, startMs: Long) = now().start(run, id, index, startMs)
    override fun allow(run: Int, upTo: Long) = now().allow(run, upTo)
    override fun stop(run: Int) = now().stop(run)
    override val live get() = now().live
    override fun startLive(runV: Int, runA: Int, id: String, index: Int) = now().startLive(runV, runA, id, index)
    override suspend fun warm() = now().warm()
}
