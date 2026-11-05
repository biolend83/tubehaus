package app.tubehaus.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.round

/**
 * DeArrow (dearrow.ajay.app, by the people behind SponsorBlock): titles and thumbnails its users chose
 * instead of the clickbait ones. Like SponsorBlock, only the first 4 characters of the video ID's hash
 * are sent; DeArrow answers with every video under that prefix, so it never learns which one you look at.
 */
object DeArrow {
    /** what DeArrow's users settled on: a title ("" = keep YouTube's) and the second its thumbnail is cut at */
    data class Brand(val title: String, val timeSec: Double?)

    private val NONE = Brand("", null)
    private val brands = Sys.concurrentMap<String, Brand>()
    private val lock = Mutex()
    private val prefixes = HashMap<String, CompletableDeferred<JsonElement?>>()
    /** a page of 40 cards must not open 40 connections at once */
    private val gate = Semaphore(4)
    private val ID = Regex("[\\w-]{11}")

    /** what is already known about [id] (null: nothing to change, or not asked yet) */
    fun cached(id: String): Brand? = brands[id]?.takeIf { it !== NONE }

    suspend fun brand(id: String): Brand? {
        brands[id]?.let { return it.takeIf { b -> b !== NONE } }
        if (!ID.matches(id)) return null
        val p = prefixOf(id)
        var mine = false
        val d = lock.withLock {
            if (prefixes.size > 400) prefixes.entries.removeAll { it.value.isCompleted }
            prefixes.getOrPut(p) { mine = true; CompletableDeferred() }
        }
        if (mine) {
            var res: JsonElement? = null
            try {
                res = gate.withPermit { parse(Net.get("https://sponsor.ajay.app/api/branding/$p")) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                d.complete(res)
            }
        }
        val all = d.await()
        if (all == null) {
            // no answer (offline, or the card went away while asking): ask again next time
            lock.withLock { if (prefixes[p] === d) prefixes.remove(p) }
            return null
        }
        val b = pick((all as? JsonObject)?.get(id)) ?: NONE
        // the iPad's map copies itself on each change: past a few thousand videos, ask again instead
        if (brands.size < 4000) brands[id] = b
        return b.takeIf { it !== NONE }
    }

    /** the first title and thumbnail people voted for (or that is locked), unless it is the original */
    fun pick(b: JsonElement?): Brand? {
        val o = b as? JsonObject ?: return null
        fun ok(x: JsonElement) = x.at("locked").bool || (x.at("votes").long ?: -1) >= 0
        val t = o["titles"].arr.firstOrNull(::ok)
        val th = o["thumbnails"].arr.firstOrNull(::ok)
        val title = if (t != null && !t.at("original").bool) clean(t.at("title").str ?: "") else ""
        val time = th?.takeIf { !it.at("original").bool }?.at("timestamp").str?.toDoubleOrNull()?.takeIf { it >= 0 }
        return if (title.isNotEmpty() || time != null) Brand(title, time) else null
    }

    /** DeArrow marks a word to keep as written with ">" ("the >iPhone"): that mark is not for showing */
    fun clean(s: String): String = Regex("(^|\\s)>(?=\\S)").replace(s) { it.groupValues[1] }.trim()

    /** the frame DeArrow's server cuts at [sec] seconds (a webp, 1280 wide) */
    fun thumbUrl(id: String, sec: Double): String =
        "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$id&time=${round(sec * 1000) / 1000}"

    fun prefixOf(id: String): String = sha256(id.encodeToByteArray()).take(2).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}

private val K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)

/** SHA-256, small and plain: the platforms have their own, but common code can't reach them */
internal fun sha256(msg: ByteArray): ByteArray {
    val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
    val len = ((msg.size + 9 + 63) / 64) * 64
    val m = ByteArray(len)
    msg.copyInto(m)
    m[msg.size] = 0x80.toByte()
    val bits = msg.size.toLong() * 8
    for (i in 0 until 8) m[len - 1 - i] = (bits ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (c in 0 until len step 64) {
        for (i in 0 until 16) {
            val o = c + 4 * i
            w[i] = ((m[o].toInt() and 255) shl 24) or ((m[o + 1].toInt() and 255) shl 16) or
                ((m[o + 2].toInt() and 255) shl 8) or (m[o + 3].toInt() and 255)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var cc = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and cc) xor (b and cc))
            hh = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += cc; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    val out = ByteArray(32)
    for (i in 0 until 8) for (j in 0 until 4) out[4 * i + j] = (h[i] ushr (24 - 8 * j)).toByte()
    return out
}
