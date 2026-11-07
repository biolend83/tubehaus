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
 * songs' audio tracks. So: albums and artists from music.youtube.com, songs from YouTube's
 * search, and the radio from YouTube's watch page.
 */
object Music {
    private const val ARTISTS = "EgWKAQIgAWoQEBAQBRAJEAoQBBAVEA4QEQ%3D%3D"
    private const val ALBUMS = "EgWKAQIYAWoQEBAQBRAJEAoQBBAVEA4QEQ%3D%3D"

    /** three requests at once: artists and albums from YouTube Music, songs from YouTube */
    suspend fun search(q: String): MusicResults = coroutineScope {
        val artists = async { runCatching { cards(InnerTube.call("search", mapOf("query" to q, "params" to urlDecode(ARTISTS)), Clients.MUSIC)) }.getOrDefault(emptyList()) }
        val albums = async { runCatching { cards(InnerTube.call("search", mapOf("query" to q, "params" to urlDecode(ALBUMS)), Clients.MUSIC)) }.getOrDefault(emptyList()) }
        val songs = async { songsFor(q) }
        MusicResults(artists.await().filter { it.kind == "artist" }.take(6), albums.await().filter { it.kind == "album" }.take(12), songs.await())
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
