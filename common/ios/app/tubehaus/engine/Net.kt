package app.tubehaus.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * The one HTTP client of the app on iOS (Apple's URLSession, through Ktor). The same two calls as
 * the OkHttp one in common/jvm; everything goes straight from this device to YouTube.
 */
object Net {
    val client = HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val text = call(HttpMethod.Get, url, null, headers)
        // subtitles arrive cleaned (Vtt), as on the other platforms
        return if (Vtt.isCaption(url)) Vtt.clean(text) else text
    }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        call(HttpMethod.Post, url, body, headers)

    /**
     * For the JavaScript engine (Engine): any status comes back with its body, only a failed
     * connection throws. Its Content-Type header sets the body's type.
     */
    suspend fun raw(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val r: HttpResponse = try {
            client.request(url) {
                this.method = HttpMethod.parse(method.uppercase())
                headers.filterKeys { !it.equals("Content-Type", true) }.forEach { (k, v) -> header(k, v) }
                if (body != null) {
                    val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json"
                    contentType(ContentType.parse(type)); setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw okio.IOException(e.message ?: "network error")
        }
        return r.status.value to r.bodyAsText()
    }

    private suspend fun call(method: HttpMethod, url: String, body: String?, headers: Map<String, String>): String {
        val r: HttpResponse = try {
            client.request(url) {
                this.method = method
                headers.forEach { (k, v) -> header(k, v) }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the shared code catches okio's IOException for "no connection"; Ktor throws its own
            throw okio.IOException(e.message ?: "network error")
        }
        val text = try { r.bodyAsText() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw okio.IOException(e.message ?: "network error") }
        if (r.status.value !in 200..299) throw HttpError(r.status.value, "HTTP ${r.status.value}")
        return text
    }
}
