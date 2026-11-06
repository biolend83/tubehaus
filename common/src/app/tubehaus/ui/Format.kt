package app.tubehaus.ui

import app.tubehaus.patch.Patchable
import app.tubehaus.engine.pad2
import app.tubehaus.engine.oneDecimal
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video

@Patchable
fun duration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

fun durationMs(ms: Long) = duration((ms / 1000).toInt()).ifEmpty { "0:00" }

/** "3 days ago" from epoch seconds. */
@Patchable
fun ago(epoch: Long): String {
    if (epoch <= 0) return ""
    val d = Sys.nowMs() / 1000 - epoch
    val (n, unit) = when {
        d < 3600 -> maxOf(1, d / 60) to "minute"
        d < 86400 -> d / 3600 to "hour"
        d < 7 * 86400 -> d / 86400 to "day"
        d < 30 * 86400 -> d / (7 * 86400) to "week"
        d < 365 * 86400 -> d / (30 * 86400) to "month"
        else -> d / (365 * 86400) to "year"
    }
    return tr("{n} ${unit}s ago").replace("{n}", n.toString()).let { if (n == 1L) tr("1 $unit ago") else it }
}

@Patchable
fun compact(n: Long): String = when (L.lang) {
    "ko" -> compactKo(n)
    "ja" -> compactJa(n)
    "es" -> compactWith(n, " mil M", " M", " mil")
    "de" -> compactWith(n, " Mrd.", " Mio.", " Tsd.")
    "zh" -> compactJa(n).replace('億', '亿')
    "pt" -> compactWith(n, " bi", " mi", " mil")
    "fr" -> compactWith(n, " Md", " M", " k")
    "ru" -> compactWith(n, " млрд", " млн", " тыс.")
    "id" -> compactWith(n, " M", " jt", " rb")
    "tr" -> compactWith(n, " Mr", " Mn", " B")
    "hi" -> compactIndic(n, " क॰", " लाख", " हज़ार")
    "bn" -> compactIndic(n, " কোটি", " লাখ", " হাজার")
    else -> compactEn(n)
}

private fun compactEn(n: Long): String = when {
    n >= 1_000_000_000 -> (oneDecimal(n / 1e9) + "B").replace(".0B", "B")
    n >= 1_000_000 -> (oneDecimal(n / 1e6) + "M").replace(".0M", "M")
    n >= 1_000 -> (oneDecimal(n / 1e3) + "K").replace(".0K", "K")
    else -> n.toString()
}

/** as YouTube writes it in Korean: 1.2천, 3.4만, 48만, 4812만, 2.3억 */
private fun compactKo(n: Long): String = when {
    n >= 1_000_000_000 -> "${n / 100_000_000}억"
    n >= 100_000_000 -> (oneDecimal(n / 1e8) + "억").replace(".0억", "억")
    n >= 100_000 -> "${n / 10_000}만"
    n >= 10_000 -> (oneDecimal(n / 1e4) + "만").replace(".0만", "만")
    n >= 1_000 -> (oneDecimal(n / 1e3) + "천").replace(".0천", "천")
    else -> n.toString()
}

/** as YouTube writes it in Japanese: 1234, 1.2万, 48万, 2.3億 */
private fun compactJa(n: Long): String = when {
    n >= 1_000_000_000 -> "${n / 100_000_000}億"
    n >= 100_000_000 -> (oneDecimal(n / 1e8) + "億").replace(".0億", "億")
    n >= 100_000 -> "${n / 10_000}万"
    n >= 10_000 -> (oneDecimal(n / 1e4) + "万").replace(".0万", "万")
    else -> n.toString()
}

/** Spanish and German: a decimal comma and the language's own words, "1,2 Mio.", "12 mil" */
private fun compactWith(n: Long, b: String, m: String, k: String): String {
    fun f(x: Double, u: String) = oneDecimal(x).removeSuffix(".0").replace('.', ',') + u
    return when {
        n >= 1_000_000_000 -> f(n / 1e9, b)
        n >= 1_000_000 -> f(n / 1e6, m)
        n >= 1_000 -> f(n / 1e3, k)
        else -> n.toString()
    }
}

/** Hindi and Bengali count in lakhs (100000) and crores (10000000), with a decimal point: "1.6 লাখ" */
private fun compactIndic(n: Long, crore: String, lakh: String, k: String): String {
    fun f(x: Double, u: String) = oneDecimal(x).removeSuffix(".0") + u
    return when {
        n >= 10_000_000 -> f(n / 1e7, crore)
        n >= 100_000 -> f(n / 1e5, lakh)
        n >= 1_000 -> f(n / 1e3, k)
        else -> n.toString()
    }
}

/** The small grey line under a title: channel · views · when. Calm mode leaves the numbers out. */
fun Video.subline(calm: Boolean, withAuthor: Boolean = true): String {
    val parts = ArrayList<String>()
    if (withAuthor && author.isNotEmpty()) parts += author
    if (!calm) {
        when {
            viewsText.isNotEmpty() -> parts += viewsText
            views > 0 -> parts += tr("{n} views").replace("{n}", compact(views))
        }
    }
    when {
        live -> parts += tr("LIVE")
        publishedText.isNotEmpty() -> parts += publishedText
        publishedAt > 0 -> parts += ago(publishedAt)
    }
    return parts.joinToString(" · ")
}
