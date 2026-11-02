package app.tubehaus.engine

/**
 * YouTube's storyboards: sheets of small pictures of a video, one every few seconds, that the
 * seek bar shows while you drag it. The spec is "base url|level|level...", each level
 * "width#height#count#columns#rows#interval ms#name#signature"; the last level has the largest pictures.
 */
class Storyboard private constructor(
    private val base: String,
    private val level: Int,
    val width: Int,
    val height: Int,
    private val count: Int,
    val cols: Int,
    val rows: Int,
    private val intervalMs: Long,
    private val name: String,
    private val sigh: String,
) {
    /** one picture: the sheet it is on and its column and row there */
    data class Frame(val url: String, val col: Int, val row: Int)

    fun frame(ms: Long, durationMs: Long): Frame {
        val i = (if (intervalMs > 0) ms / intervalMs else if (durationMs > 0) ms * count / durationMs else 0L)
            .toInt().coerceIn(0, maxOf(0, count - 1))
        val per = cols * rows
        val sheet = i / per
        val at = i % per
        val url = base.replace("\$L", "$level").replace("\$N", name.replace("\$M", "$sheet")) + "&sigh=" + sigh
        return Frame(url, at % cols, at / cols)
    }

    companion object {
        fun parse(spec: String?): Storyboard? {
            val parts = spec?.split('|') ?: return null
            if (parts.size < 2) return null
            val level = parts.size - 2
            val f = parts.last().split('#')
            if (f.size < 8) return null
            return try {
                Storyboard(parts[0], level, f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toLong(), f[6], f[7])
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
