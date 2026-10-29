package app.tubehaus.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * InnerTube is the JSON API YouTube's own apps talk to. Each request names a "client"
 * (the web site, a TV, the Vision Pro app...) and YouTube answers in that client's format.
 *
 * - Lists (search, related videos, channels, playlists, comments) come from the WEB client.
 * - Video streams come from clients whose player response carries plain, ready-to-play URLs
 *   with no PO token and no JavaScript challenge: VISIONOS first (whole video), ANDROID_VR as
 *   a last resort (YouTube cuts its streams off about a minute in).
 *   (Checked October 2026. Client versions drift; when YouTube stops answering one, the
 *   versions here are the first thing to update.)
 *
 * Every request carries a visitor identity ("visitorData", what a browser keeps in a cookie).
 * Without one YouTube answers most player requests with "Sign in to confirm you're not a bot",
 * even from a home connection. Its bot wall is tied to the visitor, so when every client is
 * walled the identity is replaced once and the request retried (as Flow does).
 */
data class Client(
    val name: String,
    val version: String,
    val id: Int,
    val userAgent: String,
    val extra: Map<String, Any> = emptyMap(),
)

object Clients {
    val WEB = Client(
        "WEB", "2.20260708.00.00", 1,
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
    )
    val VISIONOS = Client(
        "VISIONOS", "1.02", 101,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
    )
    val ANDROID_VR = Client(
        "ANDROID_VR", "1.65.10", 28,
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
    )
    /** in the order the player tries them; replaced by the list on GitHub when there is a newer one (Remote) */
    @Volatile var PLAYERS = listOf(VISIONOS, ANDROID_VR)
}

object InnerTube {
    /** interface language and content region of the requests; set from the app settings */
    @Volatile var hl = "en"
    @Volatile var gl = "US"

    /** where the visitor identity is kept between starts; set by the app */
    @Volatile var identityFile: okio.Path? = null
    @Volatile private var visitorData: String? = null
    private val visitorLock = Mutex()
    private val rerolls = ArrayList<Long>()
    private const val VISITOR_MAX_AGE = 7 * 86400L
    private const val REROLL_LIMIT = 2
    private const val REROLL_WINDOW = 6 * 3600L

    private fun now() = Sys.nowMs() / 1000

    /** The visitor identity in use: the saved one while it is under a week old, else a new one. */
    suspend fun visitor(): String? {
        visitorData?.let { return it }
        return visitorLock.withLock {
            visitorData ?: (restore() ?: fetchVisitor())?.also { visitorData = it }
        }
    }

    private fun restore(): String? = try {
        val o = identityFile?.readTextOrNull()?.let(::parse)
        val v = o.at("visitorData").str
        val at = o.at("at").long ?: 0
        v?.takeIf { it.isNotBlank() && now() - at in 0 until VISITOR_MAX_AGE }
    } catch (e: Exception) { null }

    private fun save(v: String) {
        val f = identityFile ?: return
        runCatching {
            f.writeText(buildJsonObject { put("visitorData", v); put("at", now()) }.toString())
        }
    }

    private suspend fun fetchVisitor(): String? = try {
        val body = buildJsonObject { put("context", context(Clients.WEB, null)) }
        val d = parse(Net.post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body.toString(), headers(Clients.WEB, null)))
        d.at("responseContext.visitorData").str?.takeIf { it.isNotBlank() }?.also(::save)
    } catch (e: Exception) { null }

    /** Replaces a walled visitor identity. At most twice in six hours, so a walled network can't loop. */
    suspend fun reroll(): Boolean = visitorLock.withLock {
        val t = now()
        rerolls.removeAll { t - it >= REROLL_WINDOW }
        if (rerolls.size >= REROLL_LIMIT) return@withLock false
        val fresh = fetchVisitor() ?: return@withLock false
        rerolls += t
        visitorData = fresh
        true
    }

    private fun headers(c: Client, visitor: String?) = buildMap {
        put("User-Agent", c.userAgent)
        put("X-YouTube-Client-Name", c.id.toString())
        put("X-YouTube-Client-Version", c.version)
        put("X-Goog-Api-Format-Version", "1")
        put("Origin", "https://www.youtube.com")
        put("Referer", "https://www.youtube.com/")
        put("Accept-Language", hl)
        if (visitor != null) put("X-Goog-Visitor-Id", visitor)
    }

    private fun context(c: Client, visitor: String?): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", c.name)
            put("clientVersion", c.version)
            put("hl", hl)
            put("gl", gl)
            put("userAgent", c.userAgent)
            if (visitor != null) put("visitorData", visitor)
            for ((k, v) in c.extra) when (v) {
                is Int -> put(k, v)
                else -> put(k, v.toString())
            }
        })
    }

    suspend fun call(endpoint: String, body: Map<String, Any?>, client: Client = Clients.WEB): JsonElement {
        val visitor = visitor()
        val payload = buildJsonObject {
            put("context", context(client, visitor))
            for ((k, v) in body) when (v) {
                null -> {}
                is JsonElement -> put(k, v)
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
        val headers = headers(client, visitor)
        val text = try {
            Net.post("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", payload.toString(), headers)
        } catch (e: HttpError) {
            throw YtError("YouTube answered ${e.code}", YtError.Kind.NETWORK)
        } catch (e: okio.IOException) {
            throw YtError("No connection to YouTube", YtError.Kind.NETWORK)
        }
        return parse(text)
    }
}
