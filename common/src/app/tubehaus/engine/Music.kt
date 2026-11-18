package app.tubehaus.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

/** A song: a YouTube video, with what YouTube Music knows of its album and artist. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val lengthSec: Int = 0,
    /** the square album cover when known; else the video's picture is used */
    val art: String = "",
) {
    fun video() = Video(id, title, artist, artistId, lengthSec)
    val cover: String get() = art.ifEmpty { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
}

fun Video.song() = Song(id, title, author, channelId, lengthSec = lengthSec)

/** An album, a single, an artist or a playlist as YouTube Music lists them (a picture, a title, a line under it). */
data class MusicCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val art: String,
    /** "album", "artist" or "playlist" */
    val kind: String,
)

data class MusicResults(val artists: List<MusicCard>, val albums: List<MusicCard>, val songs: List<Song>)

data class AlbumPage(
    val title: String,
    /** "Album • 2017" */
    val subtitle: String,
    /** "10 songs • 39 minutes" */
    val info: String,
    val artist: String,
    val artistId: String,
    val art: String,
    val songs: List<Song>,
)

data class ArtistPage(
    val name: String,
    /** "29.8M monthly audience" */
    val audience: String,
    val art: String,
    val about: String,
    /** the artist's songs shuffled, and the radio of the artist and similar ones (YouTube playlists) */
    val shuffleId: String?,
    val radioId: String?,
    val songs: List<Song>,
    /** "Albums", "Singles & EPs", "Fans might also like"... */
    val shelves: List<Pair<String, List<MusicCard>>>,
)

/**
 * YouTube Music, without an account and without Premium. Where YouTube keeps Music's songs and
 * charts for Premium (Korea among other countries, November 2026), its albums and artists still
 * answer, and so do YouTube's own radio mixes of a song ("RDAMVM" + id), which are mostly the
 * songs' audio tracks. So: albums and artists from music.youtube.com, songs from the matching
 * albums and YouTube's search, and the radio from YouTube's watch page.
 */
object Music {
    private const val ARTISTS = "EgWKAQIgAWoQEBAQBRAJEAoQBBAVEA4QEQ%3D%3D"
    private const val ALBUMS = "EgWKAQIYAWoQEBAQBRAJEAoQBBAVEA4QEQ%3D%3D"

    /** three requests at once: artists and albums from YouTube Music, songs from YouTube */
    suspend fun search(q: String): MusicResults = coroutineScope {
        val artists = async { runCatching { cards(InnerTube.call("search", mapOf("query" to q, "params" to urlDecode(ARTISTS)), Clients.MUSIC)) }.getOrDefault(emptyList()) }
        val albums = async { runCatching { cards(InnerTube.call("search", mapOf("query" to q, "params" to urlDecode(ALBUMS)), Clients.MUSIC)) }.getOrDefault(emptyList()) }
        val songs = async { songsFor(q) }
        val ar = artists.await().filter { it.kind == "artist" }.take(6)
        val al = albums.await().filter { it.kind == "album" }.take(12)
        val vs = songs.await()
        MusicResults(ar, al, runCatching { albumSongsFirst(q, ar, al, vs) }.getOrDefault(vs))
    }

    private val KEEP = Regex("""(?i)live|cover|remix|acoustic|\bver\b|ver\.|version|perform|stage|practice|dance|sped|slowed|\binst|karaoke|""" +
        "piano|concert|fancam|teaser|relay|mashup|nightcore|medley|full album|playlist|커버|라이브|무대|직캠|교차|리믹스|연습")
    private val BRACKETS = Regex("""\(.*?\)|\[.*?]|（.*?）|【.*?】""")
    private val WORDS = Regex("(?i)official|music video|m/?v|lyrics?( video)?|audio|visualizer|performance video|live clip")
    private val IN_PARENS = Regex("[(（]([^()（）]+)[)）]")

    /** letters and digits only, lower case, without what's in brackets: "Blueming (Official MV)" -> "blueming" */
    private fun norm(s: String, keepBrackets: Boolean = false): String =
        (if (keepBrackets) s else s.replace(BRACKETS, " ")).lowercase().filter { it.isLetterOrDigit() }

    /** ([titles], [artists]) a video's title stands for: "[MV] IU(아이유) _ Blueming(블루밍)" */
    private fun parts(title: String, channel: String): Pair<List<String>, List<String>> {
        var t = title.replace(Regex("""\[.*?]|【.*?】"""), " ").trim()
        val arts = mutableListOf(channel.removeSuffix(" - Topic").removeSuffix("VEVO").removeSuffix(" Official"))
        val q = Regex("['‘\"“](.+?)['’\"”]").find(t)
        if (q != null) {
            t.substring(0, q.range.first).trim().takeIf { it.isNotEmpty() }?.let { arts += it }
            t = q.groupValues[1]
        } else Regex("""^(.+?)\s+[-_–]\s+(.+)$""").find(t)?.let { arts += it.groupValues[1]; t = it.groupValues[2] }
        t = t.replace(WORDS, " ")
        val titles = listOf(t.replace(BRACKETS, " ")) + IN_PARENS.findAll(t).map { it.groupValues[1] }
        return titles to arts
    }

    /**
     * Songs as they are on their albums (the plain recording; YouTube Music's own Songs shelf is Premium-only
     * in some countries), then the videos that aren't just another upload of one of them (music video, lyrics,
     * TV stage), other versions (live, covers) last. YouTube's video search alone is mostly music videos.
     */
    private suspend fun albumSongsFirst(q: String, artists: List<MusicCard>, albums: List<MusicCard>, videos: List<Song>): List<Song> = coroutineScope {
        val nq = norm(q)
        val want = mutableSetOf(nq)
        val by = mutableSetOf<String>()
        val tops = videos.take(6).filter { !KEEP.containsMatchIn(it.title) }
        for (v in tops) {
            val (ts, ars) = parts(v.title, v.artist)
            ts.forEach { want += norm(it) }
            // and each word of a name: "IVE 아이브" is the album's "IVE (아이브)"
            val names = ars + IN_PARENS.findAll(v.title + " " + v.artist).map { it.groupValues[1] }
            (names + names.flatMap { it.split(' ') }).forEach { by += norm(it) }
        }
        want.removeAll { it.length < 2 }
        by.removeAll { it.length < 2 }
        // the album is by the artist searched for, not a karaoke or tribute single named after the song ("EP • IU • 2019")
        fun ours(a: MusicCard): Boolean {
            val ps = a.subtitle.split("•").map { it.trim() }
            return (if (ps.size > 2) ps.subList(1, ps.size - 1) else ps).any { p ->
                val x = norm(p)
                x.isNotEmpty() && (x in nq || by.any { y -> x == y || (minOf(x.length, y.length) >= 4 && (x in y || y in x)) })
            }
        }
        val opened = albums.filter(::ours).take(3).map { a -> async { runCatching { album(a.id).songs }.getOrDefault(emptyList()) } }.map { it.await() }
        val found = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        // a song's names: "Good day (좋은 날)" is "goodday" and "좋은날" (YouTube Music shows either, by language)
        fun names(t: String) = (listOf(norm(t)) + IN_PARENS.findAll(t).map { norm(it.groupValues[1]) }).filter { it.length >= 2 }
        val versionWanted = KEEP.containsMatchIn(q)
        fun take(s: Song) {
            val ns = names(s.title)
            // not a remix or live take unless asked for, not the song named like the artist ("New Jeans" for "뉴진스 hype boy")
            if (ns.isEmpty() || ns.any { it in seen } || (!versionWanted && KEEP.containsMatchIn(s.title)) || (nq !in ns && norm(s.artist) in ns)) return
            seen += ns; found += s
        }
        opened.forEach { l -> l.forEach { if (names(it.title).any { n -> n in want }) take(it) } }
        opened.forEach { l -> l.forEach { if (names(it.title).any { n -> n.length >= 3 && n in nq }) take(it) } }   // "아이유 좋은날" -> "좋은 날"
        // not on those albums (a single the search didn't list): the albums of "<song> <artist>", as the top video names them
        if (found.isEmpty()) for (v in tops.take(2)) {
            val (ts, ars) = parts(v.title, v.artist)
            val more = runCatching { cards(InnerTube.call("search", mapOf("query" to "${ts.first().trim()} ${ars.last().trim()}", "params" to urlDecode(ALBUMS)), Clients.MUSIC)) }
                .getOrDefault(emptyList()).filter { it.kind == "album" && ours(it) }.take(3)
            more.map { a -> async { runCatching { album(a.id).songs }.getOrDefault(emptyList()) } }.forEach { d ->
                d.await().forEach { if (names(it.title).any { n -> n in want }) take(it) }
            }
            if (found.isNotEmpty()) break
        }
        if (found.isEmpty() && artists.any { norm(it.title) == nq }) opened.forEach { l -> l.take(4).forEach(::take) }  // just a name
        if (found.isEmpty()) return@coroutineScope videos
        val same = { v: Song -> val f = norm(v.title, keepBrackets = true); seen.any { it.length >= 3 && it in f } }
        found + videos.filter { !same(it) && !KEEP.containsMatchIn(it.title) } + videos.filter { KEEP.containsMatchIn(it.title) }
    }

    /** YouTube's videos for [q] that look like songs: not live, not Shorts, a song's length */
    suspend fun songsFor(q: String): List<Song> =
        YouTube.search(q, YouTube.Filter.VIDEOS).items.filterIsInstance<Video>()
            .filter { !it.live && !it.short && !it.members && (it.lengthSec == 0 || it.lengthSec in 60..900) }
            .map { it.song() }

    /** the radio of a song: about 25 songs like it, the song itself first */
    suspend fun radio(videoId: String): List<Song> = mix("RDAMVM$videoId", videoId)

    /** a YouTube mix or playlist (an artist's radio or shuffle, an album's playlist) as songs */
    suspend fun mix(playlistId: String, videoId: String? = null): List<Song> {
        val d = InnerTube.call("next", mapOf("playlistId" to playlistId, "videoId" to videoId))
        val list = d.at("contents.twoColumnWatchNextResults.playlist.playlist.contents")
        return parseItems(list).filterIsInstance<Video>().filter { !it.live }.map { it.song() }
    }

    suspend fun album(browseId: String): AlbumPage {
        val d = InnerTube.call("browse", mapOf("browseId" to browseId), Clients.MUSIC)
        val h = d.findFirst("musicResponsiveHeaderRenderer") ?: d.findFirst("musicDetailHeaderRenderer")
        val strap = h.at("straplineTextOne.runs")?.arr ?: emptyList()
        val artist = strap.joinToString("") { it.at("text").str ?: "" }
        val artistId = strap.firstNotNullOfOrNull { it.at("navigationEndpoint.browseEndpoint.browseId").str } ?: ""
        val art = square(bestImage(h.at("thumbnail")))
        val title = h.at("title").text ?: ""
        val songs = d.findAll("musicResponsiveListItemRenderer").mapNotNull { row(it) }.map {
            it.copy(
                artist = it.artist.ifEmpty { artist }, artistId = it.artistId.ifEmpty { artistId },
                album = title, albumId = browseId, art = art,
            )
        }
        return AlbumPage(
            title, h.at("subtitle").text ?: "", h.at("secondSubtitle").text ?: "",
            artist, artistId, art, songs,
        )
    }

    suspend fun artist(browseId: String): ArtistPage {
        val d = InnerTube.call("browse", mapOf("browseId" to browseId), Clients.MUSIC)
        val h = d.at("header")?.let { it.findFirst("musicImmersiveHeaderRenderer") ?: it.findFirst("musicVisualHeaderRenderer") }
        val name = h.at("title").text ?: ""
        val sections = d.findAll("musicCarouselShelfRenderer")
        val shelves = sections.mapNotNull { s ->
            val t = s.at("header.musicCarouselShelfBasicHeaderRenderer.title").text ?: return@mapNotNull null
            val items = s.at("contents").arr.mapNotNull { it.at("musicTwoRowItemRenderer")?.let(::twoRow) }
            if (items.isEmpty()) null else t to items
        }
        // "Top songs", where YouTube Music shows them (not without Premium in some countries)
        val songs = d.findAll("musicShelfRenderer").flatMap { it.findAll("musicResponsiveListItemRenderer") }.mapNotNull { row(it) }
            .map { it.copy(artist = it.artist.ifEmpty { name }) }
        return ArtistPage(
            name = name,
            audience = h.at("monthlyListenerCount").text ?: h.at("subscriptionButton.subscribeButtonRenderer.subscriberCountText").text ?: "",
            art = bestImage(h.at("thumbnail")),
            about = d.findFirst("musicDescriptionShelfRenderer").at("description").text ?: h.at("description").text ?: "",
            shuffleId = h.at("playButton")?.findFirst("playlistId").str,
            radioId = h.at("startRadioButton")?.findFirst("playlistId").str,
            songs = songs,
            shelves = shelves,
        )
    }

    /** a song in a list (an album's tracks, an artist's top songs) */
    private fun row(r: JsonElement): Song? {
        val id = r.at("playlistItemData.videoId").str ?: r.findFirst("watchEndpoint").at("videoId").str ?: return null
        val cols = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer.text") }
        val title = cols.getOrNull(0).text ?: return null
        val by = cols.getOrNull(1).at("runs").arr
        // "IU • Palette • 3:38" on an artist's top songs; just the artists on an album's (or nothing)
        val artistRuns = by.filter { it.at("navigationEndpoint.browseEndpoint.browseId").str?.startsWith("UC") == true }
        val artist = artistRuns.joinToString(", ") { it.at("text").str ?: "" }.ifEmpty {
            by.firstOrNull()?.at("text").str?.takeIf { it.isNotBlank() && ":" !in it } ?: ""
        }
        val len = r.at("fixedColumns").arr.firstNotNullOfOrNull { it.at("musicResponsiveListItemFixedColumnRenderer.text").text }
        return Song(
            id, title, artist, artistRuns.firstOrNull()?.at("navigationEndpoint.browseEndpoint.browseId").str ?: "",
            lengthSec = secondsOf(len), art = square(bestImage(r.at("thumbnail"))).takeIf { it.contains("googleusercontent") } ?: "",
        )
    }

    /** search results: each row is an artist, an album, a playlist... (what its page type says) */
    private fun cards(d: JsonElement): List<MusicCard> = d.findAll("musicResponsiveListItemRenderer").mapNotNull { r ->
        val b = r.at("navigationEndpoint.browseEndpoint") ?: return@mapNotNull null
        val id = b.at("browseId").str ?: return@mapNotNull null
        val cols = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer.text").text ?: "" }
        MusicCard(id, cols.getOrElse(0) { "" }, cols.getOrElse(1) { "" }, square(bestImage(r.at("thumbnail"))), kindOf(b))
    }

    private fun twoRow(t: JsonElement): MusicCard? {
        val b = t.at("navigationEndpoint.browseEndpoint") ?: return null
        val id = b.at("browseId").str ?: return null
        return MusicCard(id, t.at("title").text ?: "", t.at("subtitle").text ?: "", square(bestImage(t.at("thumbnailRenderer"))), kindOf(b))
    }

    private fun kindOf(b: JsonElement): String = when (b.at("browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType").str) {
        "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> "artist"
        "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> "album"
        "MUSIC_PAGE_TYPE_PLAYLIST" -> "playlist"
        else -> "other"
    }

    /** the pictures come small ("=w120-h120-..."); ask for a size that stays sharp on a large cover */
    private fun square(u: String): String = u.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
}
