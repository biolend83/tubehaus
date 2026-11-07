package app.tubehaus.engine

/**
 * Song lyrics from LRCLIB (lrclib.net), a free, open database of lyrics timed to the music: no
 * account, no key. Looked up only when the lyrics are opened, at most three requests a song, and
 * kept for the rest of the session.
 */
object Lyrics {
    data class Line(val ms: Long, val text: String)

    /** [lines] carry times when [synced]; else they are the plain lyrics, one line each with time 0 */
    data class Found(val lines: List<Line>, val synced: Boolean)

    // only touched from the main thread (the lyrics view asks)
    private val cache = HashMap<String, Found>()
    private val missing = HashMap<String, Boolean>()
    private val HEADERS = mapOf("User-Agent" to "TubeHaus (https://github.com/biolend83/tubehaus)")

    /** null: none found (or no connection) */
    suspend fun find(s: Song): Found? {
        cache[s.id]?.let { return it }
        if (missing[s.id] == true) return null
        var failed = false
        for (q in queries(s.title, s.artist).take(3)) {
            val list = try {
                parse(Net.get("https://lrclib.net/api/search?q=" + urlEncode(q), HEADERS)).arr
            } catch (e: Exception) { failed = true; break }
            // a music video runs longer than the song (an intro, an ending): then the plain words of
            // the song with the same name, without times that would not fit
            val exact = choose(list, s.lengthSec)
            val pick = exact ?: loose(list, q) ?: continue
            val synced = if (exact == null) null else pick.at("syncedLyrics").str?.takeIf { it.isNotBlank() }
            val found = if (synced != null) Found(lrc(synced), true)
            else Found((pick.at("plainLyrics").str ?: "").lines().map { Line(0, it.trim()) }, false)
            if (found.lines.none { it.text.isNotEmpty() }) continue
            cache[s.id] = found
            return found
        }
        if (!failed) missing[s.id] = true
        return null
    }

    /** the result whose length matches the song's (within 5 seconds), timed lyrics first */
    private fun choose(list: List<kotlinx.serialization.json.JsonElement>, len: Int): kotlinx.serialization.json.JsonElement? {
        val fits = list.filter { r ->
            if (r.at("instrumental").bool) return@filter false
            val d = r.at("duration").str?.toDoubleOrNull() ?: return@filter len == 0
            len == 0 || kotlin.math.abs(d - len) <= 5
        }
        return fits.firstOrNull { !it.at("syncedLyrics").str.isNullOrBlank() } ?: fits.firstOrNull { !it.at("plainLyrics").str.isNullOrBlank() }
    }

    /** a result whose song name is in what was searched for, when no length fits */
    private fun loose(list: List<kotlinx.serialization.json.JsonElement>, q: String): kotlinx.serialization.json.JsonElement? {
        val query = q.lowercase()
        return list.firstOrNull { r ->
            val name = clean(r.at("trackName").str ?: "").lowercase()
            !r.at("instrumental").bool && name.length >= 3 && query.contains(name) && !r.at("plainLyrics").str.isNullOrBlank()
        }
    }

    /**
     * What to search for. YouTube's titles carry extras ("[MV] IU(아이유) _ Blueming(블루밍)",
     * "Palette (feat. G-DRAGON) (팔레트 (feat. G-DRAGON))") and channel names ("이지금 [IU Official]",
     * "IU - Topic"); the lyrics are under the plain title and artist.
     */
    fun queries(title: String, channel: String): List<String> {
        var t = title.replace(Regex("[\\[【].*?[\\]】]"), " ")
        var artistFromTitle: String? = null
        // "IU 'Dear my crazy soulmate' MV": the name in quotes, the artist before it
        Regex("^(.*?)(?:^|\\s)['‘\"“](.+?)['’\"”](?=\\s|$)(.*)$").find(t.trim())?.let { m ->
            if (m.groupValues[1].isNotBlank()) artistFromTitle = m.groupValues[1].trim()
            t = m.groupValues[2] + " " + m.groupValues[3]
        }
        // "Artist - Title", "Artist _ Title"
        if (artistFromTitle == null) Regex("^(.+?)\\s+[-_–]\\s+(.+)$").find(t.trim())?.let { m ->
            artistFromTitle = m.groupValues[1].trim(); t = m.groupValues[2]
        }
        t = t.replace(Regex("(?i)\\b(official|music video|m/?v|lyrics?( video)?|audio|visualizer|performance video|live clip)\\b"), " ")
        val main = clean(t.replace(Regex("\\(.*?\\)+|（.*?）"), " "))
        val inner = Regex("\\(([^()]+)\\)").findAll(t).map { clean(it.groupValues[1]) }
            .firstOrNull { it.isNotEmpty() && !Regex("(?i)^(feat|ft|prod|with|from|ost)\\b").containsMatchIn(it) }
        val ch = channel.removeSuffix(" - Topic").replace(Regex("(?i)vevo|official|\\bchannel\\b"), " ")
        val chMain = clean(ch.replace(Regex("[\\[(（].*?[\\])）]"), " "))
        val chInner = Regex("[\\[(（]([^\\])）]+)[\\])）]").find(ch)?.groupValues?.get(1)?.let(::clean)
        val artists = listOfNotNull(artistFromTitle?.let { clean(it.replace(Regex("\\(.*?\\)"), " ")) }, chInner, chMain).filter { it.isNotEmpty() }.distinct()
        val titles = listOfNotNull(main.takeIf { it.isNotEmpty() }, inner).distinct()
        return buildList {
            for (a in artists.take(2)) for (x in titles) add("$x $a")
            titles.firstOrNull()?.let { add(it) }
        }.distinct()
    }

    private fun clean(s: String) = s.replace(Regex("[\"'“”‘’|]"), " ").replace(Regex("\\s+"), " ").trim()

    /** "[01:02.34] words" (a line may carry several times) */
    fun lrc(text: String): List<Line> {
        val stamp = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")
        return text.lines().flatMap { l ->
            val times = stamp.findAll(l).toList()
            if (times.isEmpty()) return@flatMap emptyList()
            val words = l.substring(times.last().range.last + 1).trim()
            times.map { m -> Line((m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()), words) }
        }.sortedBy { it.ms }
    }
}
