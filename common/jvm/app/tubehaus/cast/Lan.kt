package app.tubehaus.cast

import app.tubehaus.ui.NearDevice
import app.tubehaus.ui.NearNow
import app.tubehaus.ui.Nearby
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Nearby devices over the home network: each TubeHaus says hello (who it is, what it plays) by UDP
 * broadcast every few seconds on one port, and listens there for the others' hellos and for the
 * commands sent straight to it. Small JSON messages; nothing leaves the local network.
 */
class Lan(private val awake: (Boolean) -> Unit = {}) : Nearby {
    companion object { const val PORT = 47615 }

    private val list = MutableStateFlow<List<NearDevice>>(emptyList())
    override val devices: StateFlow<List<NearDevice>> get() = list
    private val seen = java.util.concurrent.ConcurrentHashMap<String, NearDevice>()
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var me: Map<String, String> = emptyMap()
    @Volatile private var now: NearNow? = null
    @Volatile private var on = false

    override fun start(id: String, name: String, kind: String, message: (String, String, Map<String, String>) -> Unit) {
        if (on) return
        on = true
        awake(true)
        val s = runCatching {
            DatagramSocket(null as java.net.SocketAddress?).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
        }.getOrElse { runCatching { DatagramSocket().apply { broadcast = true; soTimeout = 1000 } }.getOrNull() } ?: run { on = false; return }
        sock = s
        me = mapOf("id" to id, "name" to name, "kind" to kind, "port" to "${s.localPort}")
        thread(isDaemon = true, name = "tubehaus-near-in") {
            val buf = ByteArray(4096)
            while (on) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { expire(); continue } catch (_: Exception) { if (!on) break else continue }
                val m = runCatching { parse(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                val from = m["id"] ?: continue
                if (from == id) continue
                when (m["t"]) {
                    "hi" -> {
                        val fresh = !seen.containsKey(from)
                        val port = m["port"]?.toIntOrNull() ?: PORT
                        val playing = m["v"]?.takeIf { it.isNotEmpty() }?.let {
                            NearNow(it, m["vt"] ?: "", m["va"] ?: "", m["vp"]?.toLongOrNull() ?: 0, m["vl"]?.toLongOrNull() ?: 0, m["vs"] == "1")
                        }
                        seen[from] = NearDevice(from, m["name"] ?: from, m["kind"] ?: "", p.address.hostAddress ?: "", port, playing, System.currentTimeMillis())
                        publish()
                        // a newcomer hears from us at once instead of at our next hello
                        if (fresh) hello(p.address, port)
                    }
                    "cmd" -> runCatching { message(from, m["name"] ?: from, m) }
                }
            }
        }
        thread(isDaemon = true, name = "tubehaus-near-out") {
            while (on) {
                for (a in broadcasts()) hello(a, PORT)
                expire()
                Thread.sleep(3000)
            }
        }
    }

    override fun stop() {
        on = false
        runCatching { sock?.close() }
        sock = null
        seen.clear()
        publish()
        awake(false)
    }

    override fun announce(now: NearNow?) { this.now = now }

    override fun send(to: NearDevice, msg: Map<String, String>) {
        val body = me + msg + ("t" to "cmd")
        thread(isDaemon = true) { runCatching { packet(InetAddress.getByName(to.host), to.port, body) } }
    }

    private fun hello(to: InetAddress, port: Int) {
        val n = now
        val body = me + ("t" to "hi") + (if (n == null) emptyMap() else mapOf(
            "v" to n.id, "vt" to n.title.take(140), "va" to n.author.take(60), "vp" to "${n.posMs}", "vl" to "${n.lenMs}", "vs" to if (n.playing) "1" else "0",
        ))
        runCatching { packet(to, port, body) }
    }

    private fun packet(to: InetAddress, port: Int, body: Map<String, String>) {
        val s = sock ?: return
        val bytes = JsonObject(body.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        s.send(DatagramPacket(bytes, bytes.size, to, port))
    }

    private fun parse(text: String): Map<String, String> =
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }

    private fun expire() {
        val old = System.currentTimeMillis() - 10_000
        if (seen.values.removeIf { it.seenAt < old }) publish()
    }

    private fun publish() { list.value = seen.values.sortedBy { it.name } }

    /** every IPv4 network's broadcast address, and the general one */
    private fun broadcasts(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) if (a.address is Inet4Address) a.broadcast?.let { out += it }
            }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }
}
