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
fun compact(n: Long): String = when {
    n >= 1_000_000_000 -> (oneDecimal(n / 1e9) + "B").replace(".0B", "B")
    n >= 1_000_000 -> (oneDecimal(n / 1e6) + "M").replace(".0M", "M")
    n >= 1_000 -> (oneDecimal(n / 1e3) + "K").replace(".0K", "K")
    else -> n.toString()
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
