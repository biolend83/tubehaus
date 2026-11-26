package app.tubehaus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The one HTTP client of the app: to YouTube from this device, or to the connected TubeHaus server. */
object Net {
    /** the system's default trust manager, used for the public internet (YouTube, ...) */
    private val defaultTrustManager: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** extra certificates (PEM) the user added for a self-hosted TubeHaus server with its own CA, and that server's host:
     *  they vouch for that host only, never for YouTube or any other site */
    private class Custom(val tm: X509TrustManager, val host: String)
    @Volatile private var custom: Custom? = null

    /** whether this platform keeps its own certificates for the server (iOS uses the system's instead) */
    const val customCerts = true

    /** the certificates trusted, besides the system's, for the server at [server] (its address as in the settings) */
    fun setCustomCerts(pems: List<String>, server: String) {
        val host = runCatching { java.net.URI(server.trim()).host?.trim('[', ']')?.lowercase() }.getOrNull()
        val cf = CertificateFactory.getInstance("X.509")
        val certs = pems.flatMap { pem ->
            runCatching { cf.generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))).filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())
        }
        custom = if (certs.isEmpty() || host.isNullOrEmpty()) null else runCatching {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> ks.setCertificateEntry("custom$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()?.let { Custom(it, host) }
        }.getOrNull()
    }

    /** whether [leaf] is a certificate for [c]'s host (its names), and the connection ([peer]) goes there when known */
    private fun forServer(c: Custom, leaf: X509Certificate?, peer: String?): Boolean {
        if (leaf == null) return false
        if (peer != null && !peer.equals(c.host, true)) return false
        val names = runCatching { leaf.subjectAlternativeNames?.mapNotNull { (it.getOrNull(1) as? String)?.lowercase() } }.getOrNull().orEmpty()
        return names.any { n -> n == c.host || (n.startsWith("*.") && c.host.endsWith(n.substring(1)) && c.host.count { it == '.' } == n.count { it == '.' }) }
    }

    /** the system's answer first; when it refuses, the user's certificates, for the server's own host only */
    private inline fun server(chain: Array<out X509Certificate>, authType: String, peer: String?, system: () -> Unit) {
        try { system() } catch (e: java.security.cert.CertificateException) {
            val c = custom
            if (c == null || !forServer(c, chain.firstOrNull(), peer)) throw e
            c.tm.checkServerTrusted(chain, authType)
        }
    }

    /**
     * The system's trust plus the user's certificates. An extended trust manager that hands the system's the
     * connection too: Android's, with a network security config that names a domain (ours: 127.0.0.1), refuses
     * every check that comes without the host name.
     */
    private val trustManager = object : javax.net.ssl.X509ExtendedTrustManager() {
        private val ext = defaultTrustManager as? javax.net.ssl.X509ExtendedTrustManager
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = defaultTrustManager.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: java.net.Socket?) =
            ext?.checkClientTrusted(chain, authType, socket) ?: defaultTrustManager.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) =
            ext?.checkClientTrusted(chain, authType, engine) ?: defaultTrustManager.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            server(chain, authType, null) { defaultTrustManager.checkServerTrusted(chain, authType) }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: java.net.Socket?) =
            server(chain, authType, (socket as? javax.net.ssl.SSLSocket)?.handshakeSession?.peerHost) {
                ext?.checkServerTrusted(chain, authType, socket) ?: defaultTrustManager.checkServerTrusted(chain, authType)
            }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) =
            server(chain, authType, engine?.peerHost) {
                ext?.checkServerTrusted(chain, authType, engine) ?: defaultTrustManager.checkServerTrusted(chain, authType)
            }
        override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers + (custom?.tm?.acceptedIssuers ?: emptyArray())
    }

    private val sslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }.socketFactory

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslSocketFactory, trustManager)
        // the connected TubeHaus server's login, on requests to the server only (its streams, captions)
        .addInterceptor { chain ->
            val req = chain.request()
            val auth = ServerVideo.authFor(req.url.toString())
            chain.proceed(if (auth != null && req.header("Authorization") == null) req.newBuilder().header("Authorization", auth).build() else req)
        }
        // subtitles arrive cleaned (Vtt): the player never sees YouTube's repeated lines
        .addInterceptor { chain ->
            val r = chain.proceed(chain.request())
            if (!r.isSuccessful || !Vtt.isCaption(chain.request().url.toString())) return@addInterceptor r
            val text = r.body?.string() ?: ""
            r.newBuilder().body(Vtt.clean(text).toResponseBody("text/vtt".toMediaType())).build()
        }
        .build()

    /** a subtitle track, cleaned, as a file the desktop player can open; null when it can't be fetched */
    fun captionFile(url: String): String? = runCatching {
        if (url.startsWith("file:")) return java.io.File(java.net.URI(url)).takeIf { it.isFile }?.absolutePath
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val f = java.io.File.createTempFile("tubehaus-subs", ".vtt")
            f.deleteOnExit()
            f.writeText(r.body?.string() ?: "")
            f.absolutePath
        }
    }.getOrNull()

    private val JSON = "application/json".toMediaType()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        // a file saved with a download (its captions)
        if (url.startsWith("file:")) return@withContext java.io.File(java.net.URI(url)).readText()
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
            body
        }
    }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
            text
        }
    }
}
