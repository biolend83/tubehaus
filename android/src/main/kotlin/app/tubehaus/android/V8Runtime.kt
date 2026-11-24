package app.tubehaus.android

import app.tubehaus.engine.JsRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [JsRuntime] in V8 with its JIT (libthv8.so, arm64 only); every native call on [thread] */
class V8Runtime(private val thread: CoroutineDispatcher, heapMb: Long) : JsRuntime {
    companion object {
        /** true when this device can load the library */
        val available: Boolean by lazy { runCatching { System.loadLibrary("thv8"); true }.getOrDefault(false) }
    }

    private var ptr = 0L
    private val sync = HashMap<String, (Array<Any?>) -> Any?>()
    private val async = HashMap<String, suspend (Array<Any?>) -> Any?>()
    private val waits = ConcurrentHashMap<Int, Continuation<Unit>>()
    private var nextWait = 1
    private val scope = CoroutineScope(SupervisorJob() + thread)

    init {
        check(available) { "no V8 here" }
        ptr = create(heapMb)
    }

    private external fun create(heapMb: Long): Long
    private external fun eval(ptr: Long, code: String, name: String): Any?
    private external fun settle(ptr: Long, id: Int, value: Any?, error: String?)
    private external fun destroy(ptr: Long)

    // from the native side, on [thread]
    @Suppress("unused") fun call(name: String, args: Array<Any?>): Any? = sync[name]!!(args)
    @Suppress("unused") fun callAsync(id: Int, name: String, args: Array<Any?>) {
        val f = async[name]!!
        scope.launch {
            val r = runCatching { f(args) }
            if (ptr != 0L) settle(ptr, id, r.getOrNull(), r.exceptionOrNull()?.let { it.toString() })
        }
    }
    @Suppress("unused") fun settled(id: Int, error: String?) {
        val c = waits.remove(id) ?: return
        if (error == null) c.resume(Unit) else c.resumeWithException(RuntimeException(error))
    }

    private fun define(name: String, how: String) {
        eval(ptr, "globalThis[${quote(name)}]=(...a)=>$how(${quote(name)},...a)", "define.js")
    }
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override fun function(name: String, f: (Array<Any?>) -> Any?) { sync[name] = f; define(name, "__h") }
    override fun asyncFunction(name: String, f: suspend (Array<Any?>) -> Any?) { async[name] = f; define(name, "__ha") }

    override suspend fun evaluate(code: String, name: String): Any? = withContext(thread) { eval(ptr, code, name) }

    override suspend fun await(code: String, name: String) = withContext(thread) {
        suspendCancellableCoroutine<Unit> { c ->
            val id = nextWait++
            waits[id] = c
            try {
                eval(ptr, "Promise.resolve().then(()=>($code)).then(()=>__settled($id),e=>__settled($id,String(e&&e.stack||e)))", name)
            } catch (e: Throwable) { waits.remove(id); c.resumeWithException(e) }
        }
    }

    override fun close() {
        val p = ptr
        ptr = 0L
        scope.launch { if (p != 0L) destroy(p) }
    }
}
