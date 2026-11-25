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

    /** extra certificates (PEM) the user added for a self-hosted TubeHaus server with its own CA */
    private val customCerts = mutableListOf<X509Certificate>()
    private var customTrustManager: X509TrustManager? = null

    /** replace the set of custom certificates trusted in addition to the system ones */
    fun setCustomCerts(pems: List<String>) {
        customCerts.clear()
        val cf = CertificateFactory.getInstance("X.509")
        for (pem in pems) {
            runCatching {
                cf.generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))).filterIsInstance<X509Certificate>()
            }.getOrNull()?.let { customCerts.addAll(it) }
        }
        customTrustManager = if (customCerts.isEmpty()) null else run {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            customCerts.forEachIndexed { i, c -> ks.setCertificateEntry("custom$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }
    }

    /** trusts the system CAs plus any custom certificates the user added */
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            defaultTrustManager.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                customTrustManager?.checkServerTrusted(chain, authType) ?: throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            defaultTrustManager.acceptedIssuers + (customTrustManager?.acceptedIssuers ?: emptyArray())
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
