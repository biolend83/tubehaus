package app.tubehaus.engine

/**
 * YouTube's WebVTT, made fit to show. Automatic and translated tracks "roll": each cue repeats the
 * line before it, cues overlap, and 10 ms cues sit between them, so a player stacks the same line
 * twice. Here every line is shown once, cues don't overlap, and the word-timing tags are gone.
 */
object Vtt {
    private val time = Regex("""^(\d{1,2}:)?\d{2}:\d{2}\.\d{3}\s+-->\s+((\d{1,2}:)?\d{2}:\d{2}\.\d{3})""")
    private val tags = Regex("""<[^>]*>""")

    private class Cue(val start: Long, var end: Long, val lines: List<String>)

    private fun ms(t: String): Long {
        val p = t.trim().split(':')
        val (h, m, s) = if (p.size == 3) Triple(p[0].toLong(), p[1].toLong(), p[2]) else Triple(0L, p[0].toLong(), p[1])
        return (h * 3600 + m * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }

    private fun stamp(v: Long): String =
        "%02d:%02d:%02d.%03d".format(v / 3_600_000, v / 60_000 % 60, v / 1000 % 60, v % 1000)

    fun clean(src: String): String {
        val cues = mutableListOf<Cue>()
        val rows = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < rows.size) {
            val m = time.find(rows[i].trim())
            if (m == null) { i++; continue }
            val (a, b) = rows[i].trim().split("-->").let { it[0].trim() to it[1].trim().substringBefore(' ') }
            i++
            val text = mutableListOf<String>()
            while (i < rows.size && rows[i].isNotBlank()) { text += rows[i]; i++ }
            val lines = text.map { tags.replace(it, "").replace("&nbsp;", " ").trim() }.filter { it.isNotEmpty() }
            cues += Cue(ms(a), ms(b), lines)
        }
        val out = mutableListOf<Cue>()
        var shown = emptyList<String>()
        for (c in cues) {
            // lines already on screen in the cue before are dropped, and repeats within a cue too
            val fresh = c.lines.distinct().filter { it !in shown }
            if (c.lines.isNotEmpty()) shown = c.lines
            if (fresh.isEmpty() || c.end - c.start < 50) continue
            out.lastOrNull()?.let { if (it.end > c.start) it.end = c.start }
            out += Cue(c.start, c.end, fresh)
        }
        return buildString {
            append("WEBVTT\n\n")
            for (c in out) {
                if (c.end <= c.start) continue
                append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n')
                c.lines.forEach { append(it).append('\n') }
                append('\n')
            }
        }
    }

    /** a timedtext address (subtitles), the only responses this rewrites */
    fun isCaption(url: String) = "/api/timedtext" in url && "fmt=vtt" in url
}
