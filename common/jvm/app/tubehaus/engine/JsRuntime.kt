package app.tubehaus.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The JavaScript engine [JsSabr] runs its page in: QuickJS everywhere ([QuickJsRuntime]), or V8 where the app
 * carries it (Android on arm64: android's V8Runtime, which compiles the page's code and is many times faster on a phone).
 * Values between the two sides: strings, numbers, booleans, null, and bytes (ByteArray here, Uint8Array there).
 * Every call goes to the engine's one thread.
 */
interface JsRuntime {
    /** a global JavaScript function [name] that calls [f] and returns its answer */
    fun function(name: String, f: (Array<Any?>) -> Any?)
    /** a global JavaScript function [name] that returns a promise of [f]'s answer */
    fun asyncFunction(name: String, f: suspend (Array<Any?>) -> Any?)
    /** runs [code] (a script) and gives its value */
    suspend fun evaluate(code: String, name: String): Any?
    /** runs [code] (an expression giving a promise) until that promise settles; throws when it is rejected */
    suspend fun await(code: String, name: String)
    fun close()
}

/** [JsRuntime] in QuickJS (quickjs-kt): every platform */
@OptIn(ExperimentalUnsignedTypes::class)
class QuickJsRuntime(thread: CoroutineDispatcher, memory: Long, stack: Long) : JsRuntime {
    private val q = QuickJs.create(thread).apply { memoryLimit = memory; maxStackSize = stack }
    private fun inn(a: Array<Any?>) = Array(a.size) { i -> a[i].let { if (it is UByteArray) it.asByteArray() else it } }
    private fun out(v: Any?) = if (v is ByteArray) v.asUByteArray() else v
    override fun function(name: String, f: (Array<Any?>) -> Any?) { q.function(name) { a -> out(f(inn(a))) } }
    override fun asyncFunction(name: String, f: suspend (Array<Any?>) -> Any?) { q.asyncFunction(name) { a -> out(f(inn(a))) } }
    override suspend fun evaluate(code: String, name: String): Any? = q.evaluate<Any?>(code, name, false)
    override suspend fun await(code: String, name: String) { q.evaluate<Any?>("await ($code)", name, false) }
    override fun close() { runCatching { q.close() } }
}
