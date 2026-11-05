package app.tubehaus.engine

import kotlinx.coroutines.CancellationException

/**
 * SponsorBlock (sponsor.ajay.app): the parts of a video its users marked, sponsors, intros, reminders to
 * subscribe and so on, and the highlight. Only the first 4 characters of the video ID's hash are sent;
 * the answer covers every video under that prefix.
 */
object SponsorBlock {
    data class Segment(val uuid: String, val category: String, val startMs: Long, val endMs: Long)

    /** one kind of part: its key, its English name and SponsorBlock's colour for it (ARGB) */
    data class Cat(val key: String, val label: String, val color: Long)

    val CATS = listOf(
        Cat("sponsor", "Sponsor", 0xFF00D400),
        Cat("selfpromo", "Self-promotion", 0xFFFFFF00),
        Cat("interaction", "Subscribe and like reminders", 0xFFCC00FF),
        Cat("intro", "Intro", 0xFF00FFFF),
        Cat("outro", "Endcards and credits", 0xFF0202ED),
        Cat("preview", "Preview and recap", 0xFF008FD6),
        Cat("music_offtopic", "Non-music part", 0xFFFF9900),
        Cat("filler", "Filler", 0xFF7300FF),
    )
    /** the best part of the video: a point to jump to, not a part to skip */
    const val HIGHLIGHT = "poi_highlight"
    val DEFAULT_AUTO = listOf("sponsor", "selfpromo", "interaction")

    fun cat(key: String): Cat? = CATS.firstOrNull { it.key == key }
    fun color(key: String): Long = cat(key)?.color ?: 0xFFFF1684

    /** the parts of [id]; "filler" is so common it is asked for only when it is skipped by itself ([auto]) */
    suspend fun segments(id: String, auto: List<String>): List<Segment> {
        val cats = CATS.map { it.key }.filter { it != "filler" || it in auto } + HIGHLIGHT
        val q = "%5B" + cats.joinToString("%2C") { "%22$it%22" } + "%5D"
        val acts = "%5B%22skip%22%2C%22poi%22%5D"
        val body = try {
            Net.get("https://sponsor.ajay.app/api/skipSegments/${DeArrow.prefixOf(id)}?categories=$q&actionTypes=$acts")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()       // 404: nobody marked anything under this prefix
        }
        val hit = runCatching { parse(body) }.getOrNull().arr.firstOrNull { it.at("videoID").str == id } ?: return emptyList()
        return hit.at("segments").arr.mapNotNull { s ->
            val cat = s.at("category").str ?: return@mapNotNull null
            val act = s.at("actionType").str ?: "skip"
            if (!(act == "skip" || act == "poi" && cat == HIGHLIGHT)) return@mapNotNull null
            val a = s.at("segment").arr
            val start = a.getOrNull(0).str?.toDoubleOrNull() ?: return@mapNotNull null
            val end = a.getOrNull(1).str?.toDoubleOrNull() ?: start
            Segment(s.at("UUID").str ?: "$cat$start", cat, (start * 1000).toLong(), (end * 1000).toLong())
        }.sortedBy { it.startMs }
    }
}
