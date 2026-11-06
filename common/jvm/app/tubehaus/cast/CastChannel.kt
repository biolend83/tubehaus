package app.tubehaus.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

internal const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
internal const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
internal const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
internal const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

/**
 * One connection to a Cast device, the Cast v2 protocol: a TLS socket to its port 8009 carrying
 * small framed messages (a 4-byte length, then a protobuf CastMessage whose payload is JSON).
 * The device's certificate is its own, signed by Google's device CA; nothing here is secret,
 * so it is taken as it is (the same as every Cast sender does for the socket itself).
 *
 * Writes go through one background thread, so the UI thread can call [send] (Android won't
 * let it touch the network itself); a ping every 5 seconds keeps the device from hanging up.
 */
internal class CastChannel(host: String, port: Int) {
    val sender = "sender-" + (SecureRandom().nextInt() and 0xffffff).toString(16)
    private val socket: SSLSocket
    private val out: DataOutputStream
    private val inp: DataInputStream
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cast-write").apply { isDaemon = true } }
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    @Volatile private var heard = System.currentTimeMillis()
    @Volatile var closed = false; private set

    /** every message from the device: namespace, who sent it (receiver-0, or the media app), the JSON */
    @Volatile var onMessage: (String, String, JsonObject) -> Unit = { _, _, _ -> }
    /** once, when the connection ends for any reason but [close] */
    @Volatile var onClosed: (String) -> Unit = {}

    init {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TrustAny), SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 6000)
        raw.tcpNoDelay = true
        socket = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.soTimeout = 6000
        socket.startHandshake()
        socket.soTimeout = 0
        out = DataOutputStream(socket.outputStream.buffered())
        inp = DataInputStream(socket.inputStream.buffered())
        thread(isDaemon = true, name = "cast-read") { readLoop() }
        writer.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - heard > 20_000) fail("The device stopped answering")
            else write(NS_HEARTBEAT, "receiver-0", """{"type":"PING"}""")
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** [payload] goes to [dest]; with [withId] it gets a request id (the device answers those) */
    fun send(ns: String, dest: String, payload: JsonObject, withId: Boolean = true) {
        val body = if (withId && "requestId" !in payload) JsonObject(payload + ("requestId" to JsonPrimitive(ids.getAndIncrement()))) else payload
        val text = body.toString()
        runCatching { writer.execute { write(ns, dest, text) } }
    }

    /** sends and waits for the answer to it (the message with the same request id) */
    suspend fun request(ns: String, dest: String, payload: JsonObject, timeoutMs: Long = 10_000): JsonObject {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        waiting[id] = d
        try {
            send(ns, dest, JsonObject(payload + ("requestId" to JsonPrimitive(id))))
            return withTimeout(timeoutMs) { d.await() }
        } finally { waiting.remove(id) }
    }

    fun close() {
        if (closed) return
        closed = true
        writer.execute { runCatching { socket.close() } }
        writer.shutdown()
        waiting.values.forEach { it.completeExceptionally(IOException("closed")) }
    }

    private fun fail(why: String) {
        if (closed) return
        close()
        runCatching { onClosed(why) }
    }

    private fun write(ns: String, dest: String, payload: String) {
        if (socket.isClosed) return     // not [closed]: what was sent just before close() still goes out
        try {
            val b = ByteArrayOutputStream()
            b.write(0x08); b.write(0)                    // protocol_version CASTV2_1_0
            field(b, 0x12, sender)                        // source_id
            field(b, 0x1a, dest)                          // destination_id
            field(b, 0x22, ns)                            // namespace
            b.write(0x28); b.write(0)                    // payload_type STRING
            field(b, 0x32, payload)                       // payload_utf8
            val bytes = b.toByteArray()
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        } catch (e: IOException) { fail("The connection to the device broke") }
    }

    private fun field(b: ByteArrayOutputStream, tag: Int, s: String) {
        val x = s.toByteArray(Charsets.UTF_8)
        b.write(tag)
        var v = x.size
        while (v >= 0x80) { b.write((v and 0x7f) or 0x80); v = v ushr 7 }
        b.write(v)
        b.write(x)
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val len = inp.readInt()
                if (len <= 0 || len > (4 shl 20)) throw IOException("bad frame")
                val p = ByteArray(len)
                inp.readFully(p)
                heard = System.currentTimeMillis()
                handle(p)
            }
        } catch (_: Exception) {
            fail("The connection to the device broke")
        }
    }

    private fun handle(p: ByteArray) {
        var source = ""
        var ns = ""
        var payload: String? = null
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (i < p.size) {
                val b = p[i++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return r
        }
        while (i < p.size) {
            val tag = varint().toInt()
            when (tag and 7) {
                0 -> varint()
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val l = varint().toInt()
                    if (l < 0 || i + l > p.size) return
                    val s = String(p, i, l, Charsets.UTF_8)
                    when (tag ushr 3) { 2 -> source = s; 4 -> ns = s; 6 -> payload = s }
                    i += l
                }
                else -> return
            }
        }
        val msg = runCatching { Json.parseToJsonElement(payload ?: return).jsonObject }.getOrNull() ?: return
        val type = runCatching { msg["type"]?.jsonPrimitive?.content }.getOrNull()
        if (ns == NS_HEARTBEAT && type == "PING") { runCatching { writer.execute { write(NS_HEARTBEAT, source, """{"type":"PONG"}""") } }; return }
        if (ns == NS_CONNECTION && type == "CLOSE" && source == "receiver-0") { fail("The device closed the connection"); return }
        val id = runCatching { msg["requestId"]?.jsonPrimitive?.intOrNull }.getOrNull()
        runCatching { onMessage(ns, source, msg) }
        if (id != null && id != 0) waiting.remove(id)?.complete(msg)
    }

    private object TrustAny : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

internal fun JsonObject.str(k: String): String? = runCatching { (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
internal fun JsonObject.num(k: String): Double? = runCatching { (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull() }.getOrNull()
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
