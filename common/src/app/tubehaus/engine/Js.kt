package app.tubehaus.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Small helpers for walking YouTube's JSON. Its responses are deep, change shape often and
// mix old "renderers" with newer "view models", so every read here is forgiving: a missing
// key gives null instead of an exception, and the parsers decide what is essential.

val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun parse(s: String): JsonElement = json.parseToJsonElement(s)

/** obj["a"]["b"][0]... with dots; numbers index arrays. */
fun JsonElement?.at(path: String): JsonElement? {
    var cur = this
    for (k in path.split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[k]
            is JsonArray -> k.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> return null
        } ?: return null
    }
    return cur
}

val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

val JsonElement?.long: Long?
    get() = str?.toLongOrNull() ?: str?.toDoubleOrNull()?.toLong()

val JsonElement?.int: Int? get() = long?.toInt()

val JsonElement?.bool: Boolean get() = str == "true"

val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

/** The text of a YouTube text object: {simpleText}, {runs:[{text}]} or {content}. */
val JsonElement?.text: String?
    get() {
        val o = this as? JsonObject ?: return str
        o["simpleText"].str?.let { return it }
        o["content"].str?.let { return it }
        val runs = o["runs"].arr
        if (runs.isNotEmpty()) return runs.joinToString("") { it.at("text").str ?: "" }
        return null
    }

/** Every value stored under [key], anywhere below this element (depth first, document order). */
fun JsonElement.findAll(key: String, skip: Set<String> = NOISE, limit: Int = Int.MAX_VALUE): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun walk(e: JsonElement) {
        if (out.size >= limit) return
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key) out += v
                else if (k !in skip) walk(v)
            }
            is JsonArray -> for (x in e) walk(x)
            else -> {}
        }
    }
    walk(this)
    return out
}

fun JsonElement.findFirst(key: String, skip: Set<String> = NOISE): JsonElement? = findAll(key, skip, 1).firstOrNull()

/** Branches that only hold menus, sheets and logging - never content we show. */
val NOISE = setOf(
    "trackingParams", "loggingDirectives", "menu", "menuButton", "sheetViewModel", "topbar",
    "frameworkUpdates", "responseContext", "contextMenu", "overlay", "hotkeyDialog", "microformat",
    "playerOverlays", "onResponseReceivedEndpoints_unused",
)

/** a letter, digit or combining mark (what Regex \p{L}\p{N}\p{M} would match; Kotlin/Native has no \p{..}) */
fun Char.isWordChar(): Boolean = isLetterOrDigit() || category == CharCategory.NON_SPACING_MARK ||
    category == CharCategory.COMBINING_SPACING_MARK || category == CharCategory.ENCLOSING_MARK

/**
 * "1.2M views" / "1,234 views" / "12K" -> a number (0 when it is not one). Korean, Japanese and
 * Chinese counts too: "조회수 4,812만회", "1.3천만", "2억", "12万回", "3.4億".
 */
fun countOf(s: String?): Long {
    if (s.isNullOrBlank()) return 0
    // a unit must end its word (a space, digit or punctuation after it; not \p{L}, which Kotlin/Native's
    // Regex doesn't have, #67): "12 mil" (es), "1,2 Mio." (de), "1.6 লাখ" (bn), but not the M of "12 members".
    // Two letters mean another number in one language: B is a thousand in Turkish (bin), M a billion in Indonesian (miliar)
    val m = Regex("""(\d(?:[\d.,]|[ \u00a0\u202f](?=\d{3}(?!\d)))*)[\s\u00a0\u202f]*(?:(mil[\s\u00a0\u202f]*M|mil|mi|bi|Mrd|Mio|Tsd|Md|Mn|Mr|rb|jt|тыс|млрд|млн|[KMB])(?=$|[\s\u00a0\u202f\d.,;:!?()\[\]"'/|·•-])|(हज़ार|हज़ार|लाख|करोड़|करोड़|क॰|হাজার|হাটি|লাখ|লাটি|কোটি|হা|লা|কো)(?=$|[\s\u00a0\u202f\d.,;:!?()\[\]"'/|·•-])|([천만억千万萬億亿]+))?""", RegexOption.IGNORE_CASE).find(s) ?: return 0
    val raw = m.groupValues[1].replace(Regex("[ \u00a0\u202f]"), "").trimEnd('.', ',')
    var mult = when (m.groupValues[2].lowercase().replace(Regex("[\\s\u00a0\u202f]"), "")) {
        "b" -> if (InnerTube.hl.startsWith("tr")) 1e3 else 1e9
        "m" -> if (InnerTube.hl.startsWith("id")) 1e9 else 1e6
        "milm", "mrd", "md", "mr", "bi", "млрд" -> 1e9
        "mio", "mn", "mi", "jt", "млн" -> 1e6
        "mil", "tsd", "k", "rb", "тыс" -> 1e3
        else -> 1.0
    }
    mult *= when (m.groupValues[3]) {
        "हज़ार", "हज़ार", "হাজার", "হাটি", "হা" -> 1e3
        "लाख", "লাখ", "লাটি", "লা" -> 1e5
        "करोड़", "करोड़", "क॰", "কোটি", "কো" -> 1e7
        else -> 1.0
    }
    for (c in m.groupValues[4]) mult *= when (c) {
        '천', '千' -> 1e3; '만', '万', '萬' -> 1e4; '억', '億', '亿' -> 1e8; else -> 1.0
    }
    val n = when {
        mult == 1.0 -> raw.replace(",", "").replace(".", "").toDoubleOrNull()
        // both: the later one is the decimal point ("1.234,5" / "1,234.5")
        ',' in raw && '.' in raw ->
            if (raw.lastIndexOf(',') > raw.lastIndexOf('.')) raw.replace(".", "").replace(",", ".").toDoubleOrNull()
            else raw.replace(",", "").toDoubleOrNull()
        // "4,812만": thousands; "1,2 Mio.": a decimal comma
        ',' in raw ->
            if (Regex("""\d{1,3}(,\d{3})+""").matches(raw)) raw.replace(",", "").toDoubleOrNull()
            else raw.replace(",", ".").toDoubleOrNull()
        else -> raw.toDoubleOrNull()
    } ?: return 0
    // 2.3억 is 230000000, not 229999999.99...
    return if (mult == 1.0) n.toLong() else kotlin.math.round(n * mult).toLong()
}

/** "1:02:03" -> 3723 */
fun secondsOf(s: String?): Int {
    if (s.isNullOrBlank()) return 0
    val parts = s.trim().split(':').map { it.trim().toIntOrNull() ?: return 0 }
    return parts.fold(0) { acc, p -> acc * 60 + p }
}
