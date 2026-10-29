package app.tubehaus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit



/** The one HTTP client of the app. Everything goes straight from this device to YouTube. */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
