package app.tubehaus.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Turns the many shapes YouTube uses for "a video in a list" into Video / Channel / Playlist.
// Old renderers (videoRenderer, channelRenderer...) and the newer view models
// (lockupViewModel, shortsLockupViewModel) are both still in use, sometimes in one response.

private val ITEM_KEYS = setOf(
    "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer",
    "lockupViewModel", "shortsLockupViewModel", "reelItemRenderer",
    "channelRenderer", "playlistRenderer", "playlistPanelVideoRenderer",
)

private val ID = Regex("[\\w-]{11}")
private val UC = Regex("UC[\\w-]{22}")
/** "1.2M views", "조회수 79만회", "12K watching" */
private val VIEWS = Regex("(?i)\\bviews?\\b|\\bwatching\\b|조회수|시청 ?중")
/** "3 days ago", "3일 전", "Streamed 2 weeks ago" */
private val AGO = Regex("(?i)\\bago\\b|전$|^streamed|스트리밍")

/** Every item below [root], in order, without duplicates. */
fun parseItems(root: JsonElement?): List<Item> {
    if (root == null) return emptyList()
    val out = LinkedHashMap<String, Item>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k in ITEM_KEYS) {
                    parseItem(k, v)?.let { if (it.id !in out) out[it.id] = it }
                } else if (k !in NOISE && k != "continuationItemRenderer") walk(v)
            }
            is JsonArray -> for (x in e) walk(x)
            else -> {}
        }
    }
    walk(root)
    return out.values.toList()
}

/** The last "load more" token below [root] (the one that continues this list). */
fun continuationOf(root: JsonElement?): String? {
    if (root == null) return null
    val renderers = root.findAll("continuationItemRenderer") + root.findAll("continuationItemViewModel")
    return renderers.mapNotNull { it.findFirst("token").str }.lastOrNull()
}

fun parseItem(kind: String, v: JsonElement): Item? = try {
    when (kind) {
        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "playlistPanelVideoRenderer" -> videoRenderer(v)
        "lockupViewModel" -> lockup(v)
        "shortsLockupViewModel" -> shortsLockup(v)
        "reelItemRenderer" -> v.at("videoId").str?.let {
            Video(it, v.at("headline").text ?: "", viewsText = v.at("viewCountText").text ?: "", short = true)
        }
        "channelRenderer" -> channelRenderer(v)
        "playlistRenderer" -> playlistRenderer(v)
        else -> null
    }
} catch (e: Exception) {
    null       // one odd item must never sink the whole list
}

fun https(u: String?): String = when {
    u.isNullOrEmpty() -> ""
    u.startsWith("//") -> "https:$u"
    else -> u
}

/** The largest image in a {thumbnails:[...]} or {sources:[...]} list. */
fun bestImage(e: JsonElement?): String {
    val list = e?.findFirst("thumbnails")?.arr?.takeIf { it.isNotEmpty() } ?: e?.findFirst("sources")?.arr ?: return ""
    return https(list.maxByOrNull { (it.at("width").int ?: 0) }?.at("url").str)
}

private fun videoRenderer(v: JsonElement): Video? {
    val id = v.at("videoId").str?.takeIf { ID.matches(it) } ?: return null
    val byline = v.at("longBylineText") ?: v.at("ownerText") ?: v.at("shortBylineText")
    val channelId = byline?.findFirst("browseId").str ?: ""
    val badges = v.at("badges")?.findAll("style")?.mapNotNull { it.str } ?: emptyList()
    val overlays = v.at("thumbnailOverlays")?.findAll("style")?.mapNotNull { it.str } ?: emptyList()
    val views = v.at("viewCountText").text ?: v.at("videoInfo").text ?: ""
    val live = "BADGE_STYLE_TYPE_LIVE_NOW" in badges || "LIVE" in overlays
    val length = v.at("lengthText").text?.let(::secondsOf) ?: v.at("lengthSeconds").int ?: 0
    return Video(
        id = id,
        title = v.at("title").text ?: v.at("headline").text ?: "",
        author = byline.text ?: "",
        channelId = channelId,
        lengthSec = length,
        viewsText = views,
        views = countOf(views),
        publishedText = v.at("publishedTimeText").text ?: "",
        live = live,
        short = v.at("navigationEndpoint.reelWatchEndpoint") != null || "SHORTS" in overlays,
        avatar = bestImage(v.at("channelThumbnailSupportedRenderers") ?: v.at("channelThumbnail")),
    )
}

private fun lockup(v: JsonElement): Item? {
    val id = v.at("contentId").str ?: return null
    val type = v.at("contentType").str ?: ""
    val meta = v.at("metadata.lockupMetadataViewModel")
    val title = meta.at("title").text ?: ""
    val rows = meta?.at("metadata.contentMetadataViewModel.metadataRows").arr.map { row ->
        row.at("metadataParts").arr.mapNotNull { it.at("text").text }
    }.filter { it.isNotEmpty() }
    val image = v.at("contentImage")
    val badges = image?.findAll("thumbnailBadgeViewModel") ?: emptyList()
    val badgeText = badges.mapNotNull { it.at("text").str }.firstOrNull() ?: ""
    val live = badges.any { (it.at("badgeStyle").str ?: "").contains("LIVE") }
    val channelId = (meta?.findAll("browseId", skip = setOf("menuButton"))?.mapNotNull { it.str } ?: emptyList())
        .firstOrNull { UC.matches(it) } ?: ""
    if (type == "LOCKUP_CONTENT_TYPE_VIDEO" || (type.isEmpty() && ID.matches(id))) {
        // channel pages show [views, date]; everywhere else [channel] then [views, date]
        val (author, stats) = when {
            rows == null || rows.isEmpty() -> "" to emptyList()
            rows.size == 1 -> "" to rows[0]
            else -> rows[0].first() to rows[1]
        }
        // where the views and the date sit differs by page and by YouTube's experiments: look for them by
        // what they say first, by position second
        val parts = rows?.flatten().orEmpty()
        val viewsText = parts.firstOrNull { VIEWS.containsMatchIn(it) } ?: stats.getOrNull(0) ?: ""
        val dateText = parts.firstOrNull { it != viewsText && AGO.containsMatchIn(it) } ?: stats.getOrNull(1)?.takeIf { it != viewsText } ?: ""
        return Video(
            id = id, title = title, author = author, channelId = channelId,
            lengthSec = secondsOf(badgeText.takeIf { it.contains(':') }),
            viewsText = viewsText, views = countOf(viewsText),
            publishedText = dateText,
            live = live,
            avatar = bestImage(meta?.at("image")),
        )
    }
    // playlists, mixes, albums, podcasts: all open as a playlist
    return Playlist(
        id = id, title = title,
        thumb = bestImage(image),
        countText = badgeText,
        author = rows?.firstOrNull()?.firstOrNull() ?: "",
    )
}

private fun shortsLockup(v: JsonElement): Video? {
    val id = v.at("onTap.innertubeCommand.reelWatchEndpoint.videoId").str
        ?: v.at("entityId").str?.substringAfterLast('-')?.takeIf { ID.matches(it) }
        ?: return null
    val views = v.at("overlayMetadata.secondaryText").text ?: ""
    return Video(id, v.at("overlayMetadata.primaryText").text ?: "", viewsText = views, views = countOf(views), short = true)
}

private fun channelRenderer(v: JsonElement): Channel? {
    val id = v.at("channelId").str ?: return null
    // since 2024 "subscriberCountText" holds the @handle and "videoCountText" the subscribers
    val a = v.at("subscriberCountText").text ?: ""
    val b = v.at("videoCountText").text ?: ""
    val handle = listOf(a, b).firstOrNull { it.startsWith("@") } ?: ""
    val subs = listOf(a, b).firstOrNull { !it.startsWith("@") && it.isNotBlank() } ?: ""
    return Channel(id, v.at("title").text ?: "", bestImage(v.at("thumbnail")), subs, handle)
}

private fun playlistRenderer(v: JsonElement): Playlist? {
    val id = v.at("playlistId").str ?: return null
    return Playlist(
        id, v.at("title").text ?: "",
        bestImage(v.at("thumbnails")),
        v.at("videoCountText").text ?: v.at("videoCount").str ?: "",
        v.at("shortBylineText").text ?: v.at("longBylineText").text ?: "",
    )
}

// ---------------------------------------------------------------- channel Home and Posts

/** Where a link in a YouTube text goes, as an address; outside links lose YouTube's redirect page. */
fun linkOf(e: JsonElement?): String? {
    if (e == null) return null
    e.at("urlEndpoint.url").str?.let { u ->
        return Regex("[?&]q=([^&]+)").find(u)?.groupValues?.get(1)?.let(::urlDecode)
            ?: if (u.startsWith("/")) "https://www.youtube.com$u" else u
    }
    e.at("watchEndpoint.videoId").str?.let { v ->
        val t = e.at("watchEndpoint.startTimeSeconds").int
        return "https://www.youtube.com/watch?v=$v" + (t?.let { "&t=${it}s" } ?: "")
    }
    val b = e.at("browseEndpoint") ?: return null
    b.at("canonicalBaseUrl").str?.let { return "https://www.youtube.com$it" }
    val id = b.at("browseId").str ?: return null
    return when {
        id.startsWith("UC") -> "https://www.youtube.com/channel/$id"
        id.startsWith("VL") -> "https://www.youtube.com/playlist?list=${id.drop(2)}"
        else -> null
    }
}

/** A text's runs joined into one string, with the parts that are links. */
fun linkedText(e: JsonElement?): Pair<String, List<TextLink>> {
    val runs = e.at("runs").arr
    if (runs.isEmpty()) return (e.text ?: "") to emptyList()
    val sb = StringBuilder()
    val links = ArrayList<TextLink>()
    for (r in runs) {
        val t = r.at("text").str ?: continue
        val start = sb.length
        sb.append(t)
        linkOf(r.at("navigationEndpoint"))?.let { links += TextLink(start, sb.length, it) }
    }
    return sb.toString() to links
}

/**
 * The rows of a channel's Home tab, from its sections (or the sections a continuation adds):
 * each titled row of videos, shorts or playlists. The featured video is read separately.
 */
fun parseShelves(sections: List<JsonElement>, channelId: String): List<Shelf> = sections.mapNotNull { s ->
    val c = s.at("itemSectionRenderer.contents.0") ?: s
    val (kind, v) = (c as? JsonObject)?.entries?.firstOrNull()?.let { it.key to it.value } ?: return@mapNotNull null
    if (kind in setOf("channelVideoPlayerRenderer", "continuationItemRenderer", "recognitionShelfRenderer")) return@mapNotNull null
    val items = parseItems(v)
    if (items.isEmpty()) return@mapNotNull null
    val title = (v.at("title") ?: v.at("headerRenderer")?.findFirst("title")).text?.trim() ?: ""
    // "Play all" on a playlist's row, or the tab a row like "Videos" stands for
    val go = (v.at("endpoint") ?: v.at("title"))?.findFirst("browseEndpoint")
    val browse = go.at("browseId").str
    Shelf(title, items, playlistId = browse?.takeIf { it.startsWith("VL") }?.drop(2), tabParams = go.at("params").str?.takeIf { browse == channelId })
}

/** The posts below [root] (a Posts tab, or a page more of it). */
fun parsePosts(root: JsonElement?): List<Post> = root?.findAll("backstagePostRenderer")?.mapNotNull(::post) ?: emptyList()

private fun post(p: JsonElement): Post? = try {
    val id = p.at("postId").str
    if (id == null) null else {
        val (text, links) = linkedText(p.at("contentText"))
        val att = p.at("backstageAttachment")
        val images = att.at("backstageImageRenderer")?.let { listOf(bestImage(it.at("image"))) }
            ?: att.at("postMultiImageRenderer.images").arr.map { bestImage(it.at("backstageImageRenderer.image")) }
        // a video or a playlist the post shares
        val shared = if (att != null) parseItems(att) else emptyList()
        val poll = att.at("pollRenderer")
        Post(
            id = id,
            author = p.at("authorText").text ?: "",
            avatar = bestImage(p.at("authorThumbnail")),
            published = p.at("publishedTimeText").text ?: "",
            text = text,
            links = links,
            likes = p.at("voteCount").text?.trim() ?: "",
            replies = p.at("actionButtons")?.findFirst("replyButton")?.findFirst("text").text?.trim() ?: "",
            images = images.filter { it.isNotEmpty() },
            video = shared.firstOrNull { it is Video } as? Video,
            playlist = shared.firstOrNull { it is Playlist } as? Playlist,
            poll = poll.at("choices").arr.mapNotNull { it.at("text").text },
            pollVotes = poll.at("totalVotes").text ?: "",
        )
    }
} catch (e: Exception) {
    null
}

/**
 * Chapters written in a description ("0:00 Intro", "1:23 - The part"), as YouTube reads them:
 * at least three, the first at 0:00, in order. Empty when the description has none.
 */
fun chaptersIn(description: String): List<Chapter> {
    val line = Regex("""^\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*[-–—:|.]?\s*(.+)$""")
    val out = description.lineSequence().mapNotNull { l ->
        val m = line.find(l) ?: return@mapNotNull null
        Chapter(m.groupValues[2].trim(), secondsOf(m.groupValues[1]) * 1000L)
    }.toList()
    if (out.size < 3 || out[0].startMs != 0L || out.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
    return out
}
