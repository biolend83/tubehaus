package app.tubehaus.desktop

import app.tubehaus.engine.Client
import app.tubehaus.engine.Clients
import app.tubehaus.engine.Net
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.YtError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.File

/**
 * `./gradlew :desktop:engineRepair` - when YouTube stops answering the clients in clients.json,
 * this finds the ones that still give whole, ready-to-play streams and writes a new list.
 *
 * What breaks most often is not the code but the list of InnerTube clients the player asks
 * (YouTube retires versions, or starts wanting a token from one). Installed apps read that list
 * from clients.json once a day, so a repaired list reaches them without a new release.
 *
 * Candidates: the clients in the file now, the ones built into the app, and the current
 * definitions two other open-source players keep up to date (yt-dlp and YouTube.js; read at
 * run time, only names, versions and device strings). Each is asked for a few different videos, and a stream only
 * counts when bytes from its start and from deep inside it really come back.
 *
 * The outcome goes to the file named by TH_REPAIR_STATUS, one word: "fine" (clients.json is good
 * as it is), "rewritten" (a new list was written; TH_REPAIR_REPORT says why) or "stuck" (nothing
 * can be done from here: every client is asked to sign in, which is about this network address,
 * or no candidate works).
 */
private val VIDEOS = listOf(
    "dQw4w9WgXcQ" to "music video, 4K",
    "jNQXAC9IVRw" to "old short clip",
    "aqz-KE-bpKQ" to "long 4K film",
    "0e3GPea1Tyg" to "video with dubs",
)

private class Probe(val client: Client, val source: String) {
    val results = LinkedHashMap<String, String>()       // video id -> "ok ..." or the reason it failed
    var ok = 0
    var walled = 0
    var topHeight = 0
    var dubs = false
    var captions = false
    val key get() = client.name
    val whole get() = ok == VIDEOS.size
}

fun main(): Unit = runBlocking<Unit> {
    System.getenv("TH_SOCKS")?.split(':')?.let { (h, p) -> System.setProperty("socksProxyHost", h); System.setProperty("socksProxyPort", p) }
    app.tubehaus.engine.InnerTube.identityFile = okio.Path.Companion.run { File(System.getProperty("java.io.tmpdir"), "tubehaus-check-identity.json").toOkioPath() }
    val file = File(System.getenv("TH_CLIENTS") ?: "../clients.json")
    val root = Json.parseToJsonElement(file.readText()).jsonObject
    val current = root["players"]!!.jsonArray.map { fromJson(it.jsonObject) }

    // the same name can come from several places: the file's own entry first, then the newest definition
    val candidates = LinkedHashMap<String, Probe>()
    fun add(c: Client, source: String) {
        val k = c.name + " " + c.version
        if (candidates.values.none { it.client.name == c.name && it.client.version == c.version }) candidates[k] = Probe(c, source)
    }
    current.forEach { add(it, "clients.json") }
    listOf(Clients.VISIONOS, Clients.ANDROID_VR).forEach { add(it, "built in") }
    val upstream = runCatching { upstreamClients() }.getOrElse { println("upstream definitions not read: $it"); emptyList() }
    upstream.forEach { add(it, "upstream") }
    runCatching { upstreamJsClients() }.getOrElse { println("second upstream list not read: $it"); emptyList() }.forEach { add(it, "upstream 2") }
    println("${candidates.size} candidates: " + candidates.values.joinToString { "${it.client.name} ${it.client.version} (${it.source})" })

    for (p in candidates.values) {
        for ((id, _) in VIDEOS) {
            p.results[id] = try {
                val s = YouTube.playerWith(id, p.client)
                val v = s.video.maxByOrNull { it.height } ?: throw YtError("no video formats")
                val a = s.audio.maxByOrNull { it.bitrate } ?: throw YtError("no audio formats")
                bytes(v.url, v.contentLength, p.client)
                bytes(a.url, a.contentLength, p.client)
                p.ok++
                p.topHeight = maxOf(p.topHeight, v.height)
                if (s.audio.mapNotNull { it.audioTrack }.distinct().size > 1) p.dubs = true
                if (s.captions.isNotEmpty()) p.captions = true
                "ok ${v.height}p ${s.video.size}v/${s.audio.size}a"
            } catch (e: YtError) {
                if (e.kind == YtError.Kind.LOGIN) p.walled++
                "${e.kind}: ${e.message}"
            } catch (e: Exception) {
                "${e::class.simpleName}: ${e.message}"
            }
        }
        println("%-22s %-10s %d/%d  %s".format(p.client.name + " " + p.client.version, p.source, p.ok, VIDEOS.size, p.results.values.joinToString(" | ")))
    }
    Net.client.dispatcher.executorService.shutdown()

    val all = candidates.values.toList()
    val now = all.filter { it.source == "clients.json" }
    val report = StringBuilder()
    fun table() {
        report.append("| Client | From | Videos | Top | Dubs | Captions | Detail |\n|---|---|---|---|---|---|---|\n")
        for (p in all) report.append("| `${p.client.name} ${p.client.version}` | ${p.source} | ${p.ok}/${VIDEOS.size} | ${if (p.topHeight > 0) "${p.topHeight}p" else "-"} | ${if (p.dubs) "yes" else "-"} | ${if (p.captions) "yes" else "-"} | ${p.results.values.joinToString("; ").replace("|", "/").take(200)} |\n")
    }
    fun finish(status: String, head: String): Nothing {
        println("\n[$status] $head")
        System.getenv("TH_REPAIR_REPORT")?.let { File(it).writeText("$head\n\n$report") }
        System.getenv("TH_REPAIR_STATUS")?.let { File(it).writeText(status) }
        kotlin.system.exitProcess(0)
    }
    table()

    // asked to sign in by every client on every video: this address is walled, the list is not the problem
    if (all.all { it.ok == 0 } && all.sumOf { it.walled } >= all.size * VIDEOS.size / 2)
        finish("stuck", "Every client was asked to sign in: this is about the network address the check ran from, not about clients.json.")
    if (now.isNotEmpty() && now.first().whole && now.all { it.ok > 0 })
        finish("fine", "clients.json is fine: `${now.first().client.name}` plays every test video and the others still answer.")

    // the new order: clients that play everything first (more quality, dubs and captions before less;
    // among equals the ones already in the file), then at most one that plays only some, as a last resort
    val rank = compareByDescending<Probe> { it.ok }.thenByDescending { it.topHeight }
        .thenByDescending { (if (it.dubs) 1 else 0) + (if (it.captions) 1 else 0) }
        .thenBy { when (it.source) { "clients.json" -> 0; "built in" -> 1; else -> 2 } }
    val best = all.filter { it.ok > 0 }.sortedWith(rank).distinctBy { it.client.name }
    val picked = (best.filter { it.whole }.take(3) + best.filter { !it.whole }.take(1)).take(3)
    if (picked.isEmpty() || !picked.first().whole && now.firstOrNull()?.let { it.ok >= picked.first().ok } == true)
        finish("stuck", "No candidate plays more than the list does now; this needs a person to look at it.")
    val same = picked.map { it.client.name to it.client.version } == current.map { it.name to it.version }
    if (same) finish("fine", "The best list found is the one already in clients.json.")

    val out = buildJsonObject {
        root["_comment"]?.let { put("_comment", it) }
        put("players", buildJsonArray { picked.forEach { add(toJson(it.client)) } })
    }
    file.writeText(Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), out) + "\n")
    val was = current.joinToString(", ") { "`${it.name} ${it.version}`" }
    val next = picked.joinToString(", ") { "`${it.client.name} ${it.client.version}`" }
    finish("rewritten", "clients.json rewritten.\n\n- was: $was\n- now: $next\n\nInstalled apps read this file once a day, so merging this reaches them without a new release. " +
        "The list built into the app (`Clients.PLAYERS` in InnerTube.kt) should follow in the next release.")
}

/** the first bytes and a stretch from deep inside: some clients hand out streams YouTube cuts off a minute in */
private fun bytes(url: String, length: Long, c: Client) {
    fun get(from: Long, to: Long) {
        val r = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=$from-$to").header("User-Agent", c.userAgent).build()).execute()
        r.use {
            if (r.code != 206) throw YtError("stream HTTP ${r.code} at byte $from")
            val n = r.body!!.bytes().size
            if (n < (to - from + 1) / 2) throw YtError("stream stopped after $n bytes at byte $from")
        }
    }
    get(0, 65535)
    if (length > 4L shl 20) get(length * 6 / 10, length * 6 / 10 + 262143)
}

private fun fromJson(o: JsonObject) = Client(
    name = o["name"]!!.jsonPrimitive.content,
    version = o["version"]!!.jsonPrimitive.content,
    id = o["id"]!!.jsonPrimitive.content.toInt(),
    userAgent = o["userAgent"]!!.jsonPrimitive.content,
    extra = (o["extra"] as? JsonObject)?.mapValues { (_, v) -> (v as JsonPrimitive).let { p -> if (p.isString) p.content else p.content.toIntOrNull() ?: p.content } } ?: emptyMap(),
)

private fun toJson(c: Client) = buildJsonObject {
    put("name", c.name); put("version", c.version); put("id", c.id); put("userAgent", c.userAgent)
    if (c.extra.isNotEmpty()) put("extra", buildJsonObject { for ((k, v) in c.extra) if (v is Number) put(k, v) else put(k, v.toString()) })
}

/**
 * The player clients yt-dlp asks at the moment (its INNERTUBE_CLIENTS table): names, versions,
 * numbers and device strings. Clients that need the web player's JavaScript or a login are left
 * out; this engine only plays URLs that come ready.
 */
private suspend fun upstreamClients(): List<Client> {
    val src = Net.get("https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/yt_dlp/extractor/youtube/_base.py")
    val start = src.indexOf("INNERTUBE_CLIENTS")
    require(start >= 0) { "INNERTUBE_CLIENTS not found" }
    val table = src.substring(start).let { t -> t.substring(0, Regex("\n}\n").find(t)?.range?.first ?: t.length) }
    val blocks = Regex("""(?m)^    '(\w+)': \{$""").findAll(table).toList()
    val browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    val out = ArrayList<Client>()
    for ((i, m) in blocks.withIndex()) {
        val body = table.substring(m.range.last, blocks.getOrNull(i + 1)?.range?.first ?: table.length)
        if (Regex("""'REQUIRE_AUTH':\s*True""").containsMatchIn(body)) continue
        // the ones that need the page's JavaScript give ciphered URLs, which this engine skips anyway
        if (!Regex("""'REQUIRE_JS_PLAYER':\s*False""").containsMatchIn(body)) continue
        val client = Regex("""'client':\s*\{([^}]*)\}""").find(body)?.groupValues?.get(1) ?: continue
        val fields = LinkedHashMap<String, Any>()
        for (f in Regex("""'(\w+)':\s*(?:'([^']*)'|(\d+))""").findAll(client)) fields[f.groupValues[1]] = f.groupValues[3].toIntOrNull() ?: f.groupValues[2]
        val name = fields.remove("clientName") as? String ?: continue
        val version = fields.remove("clientVersion") as? String ?: continue
        val id = Regex("""'INNERTUBE_CONTEXT_CLIENT_NAME':\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        val ua = fields.remove("userAgent") as? String ?: browser
        fields.remove("hl"); fields.remove("timeZone"); fields.remove("utcOffsetMinutes")
        out += Client(name, version, id, ua, fields)
    }
    return out
}

/** InnerTube's number for each client name (the X-YouTube-Client-Name header) */
private val CLIENT_IDS = mapOf(
    "ANDROID" to 3, "IOS" to 5, "TVHTML5" to 7, "ANDROID_CREATOR" to 14, "ANDROID_KIDS" to 18, "ANDROID_MUSIC" to 21,
    "IOS_MUSIC" to 26, "ANDROID_VR" to 28, "ANDROID_UNPLUGGED" to 29, "TVHTML5_SIMPLY" to 75,
    "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to 85, "VISIONOS" to 101,
)

/** the same from YouTube.js (its CLIENTS table); web clients are left out, their URLs need the page's JavaScript */
private suspend fun upstreamJsClients(): List<Client> {
    val src = Net.get("https://raw.githubusercontent.com/LuanRT/YouTube.js/main/src/utils/Constants.ts")
    val start = src.indexOf("export const CLIENTS")
    require(start >= 0) { "CLIENTS not found" }
    val table = src.substring(start).substringBefore("} as const")
    val browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    val out = ArrayList<Client>()
    for (m in Regex("""(?m)^  \w+: \{([^}]*)\}""").findAll(table)) {
        val f = LinkedHashMap<String, String>()
        for (x in Regex("""(\w+):\s*(?:'([^']*)'|(\d+))""").findAll(m.groupValues[1])) f[x.groupValues[1]] = x.groupValues[2].ifEmpty { x.groupValues[3] }
        val name = (f["NAME"] ?: continue).let { if (it == "iOS") "IOS" else it }
        val id = CLIENT_IDS[name] ?: continue
        val extra = LinkedHashMap<String, Any>()
        f["DEVICE_MAKE"]?.let { extra["deviceMake"] = it }
        f["DEVICE_MODEL"]?.let { extra["deviceModel"] = it }
        f["SDK_VERSION"]?.toIntOrNull()?.let { extra["androidSdkVersion"] = it }
        f["OS_NAME"]?.let { extra["osName"] = it }
        f["OS_VERSION"]?.let { extra["osVersion"] = it }
        out += Client(name, f["VERSION"] ?: continue, id, f["USER_AGENT"] ?: browser, extra)
    }
    return out
}
