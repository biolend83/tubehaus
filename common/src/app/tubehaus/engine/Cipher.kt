package app.tubehaus.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Runs yt-dlp's EJS solver (common/ejs) on YouTube's player script. Supplied by the platform:
 * Android a hidden WebView; elsewhere none yet, and the videos that need it stay unplayable there.
 */
interface JsSolver {
    /** reads a player script, ready to answer its challenges; false when it couldn't */
    suspend fun load(playerJs: String): Boolean
    /** each challenge's answer, keyed "n:<challenge>" and "sig:<challenge>"; null when it failed */
    suspend fun solve(n: List<String>, sig: List<String>): Map<String, String>?
}

/**
 * The web player's challenges, for the clients whose stream links come locked: each link's
 * signature ("s", scrambled) and its "n" parameter (without the right answer YouTube slows the
 * download to a crawl or refuses it). The answers come from the player script YouTube's own pages
 * use, run by [JsSolver]; the script changes every week or two, so its id is checked every few
 * hours and a new one is loaded when it appears.
 *
 * Needed by the TV client only, which is the one that still serves what the token-free clients
 * call "not available": videos made for kids, and some labels' music videos (found in a
 * extractor, which uses the same client for them).
 */
object Cipher {
    @Volatile var solver: JsSolver? = null
    private val lock = Mutex()
    private var player: String? = null
    private var sts = 0
    private var checkedAt = 0L
    private const val RECHECK_S = 6 * 3600L
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    val available: Boolean get() = solver != null

    /** the player's signature timestamp, with its script loaded in the solver; null when there is none */
    suspend fun ready(): Int? = lock.withLock {
        val s = solver ?: return@withLock null
        val now = Sys.nowMs() / 1000
        if (player != null && now - checkedAt < RECHECK_S) return@withLock sts
        val id = runCatching {
            val api = Net.get("https://www.youtube.com/iframe_api", mapOf("User-Agent" to UA))
            Regex("""player\\?/([0-9a-f]{8})""").find(api)?.groupValues?.get(1)
        }.getOrNull()
        if (id == null) return@withLock if (player != null) sts else null
        checkedAt = now
        if (id == player) return@withLock sts
        // the TV player: since 2026-11 the main web player can't be solved outside a browser (yt-dlp moved the same way)
        val js = runCatching { Net.get("https://www.youtube.com/s/player/$id/tv-player-ias.vflset/tv-player-ias.js", mapOf("User-Agent" to UA)) }.getOrNull()
            ?: runCatching { Net.get("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js", mapOf("User-Agent" to UA)) }.getOrNull()
            ?: return@withLock if (player != null) sts else null
        val t = Regex("""signatureTimestamp[=:](\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@withLock if (player != null) sts else null
        if (!s.load(js)) return@withLock null
        player = id; sts = t
        sts
    }

    /**
     * Unlocks a TV answer's formats in place of their "signatureCipher" and "n": each format's
     * playable URL by its index in [formats]; formats it couldn't unlock are left out.
     */
    suspend fun urls(formats: List<kotlinx.serialization.json.JsonElement>): Map<Int, String> {
        val s = solver ?: return emptyMap()
        class Locked(val i: Int, val base: String, val sp: String, val sig: String?)
        val locked = formats.mapIndexedNotNull { i, f ->
            f.at("url").str?.let { return@mapIndexedNotNull Locked(i, it, "", null) }
            val c = f.at("signatureCipher").str ?: f.at("cipher").str ?: return@mapIndexedNotNull null
            val q = query(c)
            val url = q["url"] ?: return@mapIndexedNotNull null
            Locked(i, url, q["sp"] ?: "signature", q["s"])
        }
        val sigs = locked.mapNotNull { it.sig }.distinct()
        val ns = locked.mapNotNull { query(it.base.substringAfter('?', ""))["n"] }.distinct()
        val answers = s.solve(ns, sigs) ?: return emptyMap()
        val out = HashMap<Int, String>()
        for (l in locked) {
            var u = l.base
            if (l.sig != null) u += "&" + l.sp + "=" + enc(answers["sig:" + l.sig] ?: continue)
            val n = query(u.substringAfter('?', ""))["n"]
            if (n != null) u = withParam(u, "n", answers["n:$n"] ?: continue)
            out[l.i] = u
        }
        return out
    }

    /** how long the link waits behind the start ad the answer placed (YouTube refuses it until then; about 0.8 of the skip offset) */
    fun prerollMs(d: kotlinx.serialization.json.JsonElement?): Long =
        d.at("adPlacements").arr.filter { it.at("adPlacementRenderer.config.adPlacementConfig.kind").str == "AD_PLACEMENT_KIND_START" }
            .maxOfOrNull { p -> (p.at("adPlacementRenderer.renderer.instreamVideoAdRenderer.skipOffsetMilliseconds").str?.toLongOrNull()?.let { it * 8 / 10 } ?: 8000L) }
            ?.coerceAtMost(8000L) ?: 0L

    /** [url] with its [key] parameter set to [value] */
    private fun withParam(url: String, key: String, value: String): String {
        val base = url.substringBefore('?')
        val parts = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') != key }
        return base + "?" + (parts + (key + "=" + enc(value))).joinToString("&")
    }

    private fun query(q: String): Map<String, String> = q.split('&').mapNotNull { kv ->
        val k = kv.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        k to dec(kv.substringAfter('=', ""))
    }.toMap()

    private fun dec(s: String): String {
        val b = s.replace('+', ' ')
        val out = ArrayList<Byte>()
        var i = 0
        while (i < b.length) {
            val ch = b[i]
            if (ch == '%' && i + 2 < b.length) {
                val h = b.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out += h.toByte(); i += 3; continue }
            }
            ch.toString().encodeToByteArray().forEach { out += it }
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c)
            else append('%').append(((b.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
        }
    }
}
