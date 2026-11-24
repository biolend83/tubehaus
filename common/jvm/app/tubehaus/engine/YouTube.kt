package app.tubehaus.engine

import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.Volatile

/** Everything the app asks YouTube, straight from this device. */
object YouTube {

    enum class Filter(val params: String?) {
        ALL(null),
        VIDEOS("EgIQAQ%3D%3D"),
        CHANNELS("EgIQAg%3D%3D"),
        PLAYLISTS("EgIQAw%3D%3D"),
        SHORT("EgIYAQ%3D%3D"),          // videos under 4 minutes (used for Shorts)
    }

    enum class Tab(val params: String) {
        VIDEOS("EgZ2aWRlb3PyBgQKAjoA"),
        SHORTS("EgZzaG9ydHPyBgUKA5oBAA%3D%3D"),
        LIVE("EgdzdHJlYW1z8gYECgJ6AA%3D%3D"),
        PLAYLISTS("EglwbGF5bGlzdHPyBgQKAkIA"),
    }

    private fun enc(s: String) = urlEncode(s)

    // ------------------------------------------------------------------ search
    suspend fun search(query: String, filter: Filter = Filter.ALL): Page<Item> {
        val d = InnerTube.call("search", mapOf("query" to query, "params" to filter.params?.let { urlDecode(it) }))
        val root = d.at("contents")
        return Page(parseItems(root), continuationOf(root))
    }

    /** a search with YouTube's filters (upload date, length, live, order) */
    suspend fun search(query: String, o: SearchOptions): Page<Item> {
        val d = InnerTube.call("search", mapOf("query" to query, "params" to o.params()))
        val root = d.at("contents")
        return Page(parseItems(root), continuationOf(root))
    }

    suspend fun searchMore(token: String): Page<Item> {
        val d = InnerTube.call("search", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedCommands")
        return Page(parseItems(root), continuationOf(root))
    }

    suspend fun suggestions(q: String): List<String> {
        if (q.isBlank()) return emptyList()
        val t = Net.get("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=${InnerTube.hl}&q=${enc(q)}")
        return parse(t).at("1").arr.mapNotNull { it.str }
    }

    // ------------------------------------------------------------------ player
    /**
     * The streams of one video: through the connected TubeHaus server when there is one
     * (see [ServerVideo]), and from this device when there isn't or the server can't.
     */
    /**
     * The way to play videos picked in the player (Settings → Playback method): "" the usual order, else one of
     * [methods]. A picked one is asked straight from this device, nothing else tried, so a video it can't play says so.
     */
    @Volatile var force: String = ""

    /** the ways this device can play a video, for the player's menu */
    val methods: List<String> get() = listOfNotNull("VISIONOS", "IOS_PRODUCER", "ANDROID_VR", "KIDS",
        if (Sabr.available) "SABR" else null, if (Cipher.available) "TV" else null)

    suspend fun stream(id: String): Stream {
        when (force) {
            "VISIONOS" -> return player(id, Clients.VISIONOS)
            "IOS_PRODUCER" -> return player(id, Clients.IOS_PRODUCER)
            "ANDROID_VR" -> return player(id, Clients.ANDROID_VR)
            "KIDS" -> return player(id, Clients.KIDS)
            "SABR" -> if (Sabr.available) return sabr(id)
            "TV" -> if (Cipher.available) return tv(id)
        }
        // a test build that plays everything the SABR way (TUBEHAUS_SABR_ONLY / -PsabrOnly): no other client, no server
        if (Sabr.only && Sabr.available) return sabr(id)
        if (!ServerVideo.on) return liveOr(id, device(id))
        return try {
            liveOr(id, ServerVideo.stream(id))
        } catch (e: YtError) {
            // the server is down or YouTube refused it: this device may still get through,
            // and when it can't either, the server's reason is the one worth showing
            try { liveOr(id, device(id)) } catch (x: Exception) { if (x is kotlinx.coroutines.CancellationException) throw x; throw e }
        }
    }

    @Volatile private var warming = false
    /** a video always there, asked once to see the page answer well */
    private const val WARM_ID = "jNQXAC9IVRw"
    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** opens the front door in the background (at start, a few seconds in, so the first video is fast too) */
    fun prewarm(delayMs: Long = 0) {
        if (!Sabr.first || Sabr.warm || warming) return
        val h = Sabr.host ?: return
        warming = true
        warmScope.launch { try { if (delayMs > 0) kotlinx.coroutines.delay(delayMs); if (Sabr.available) { h.warm(); runCatching { Sabr.prepare(WARM_ID) } } } finally { warming = false } }
    }

    /** From this device. Tries each player client until one gives playable URLs. */
    private suspend fun device(id: String): Stream {
        // the front door first: the web player's own way (SABR, a real browser's token) once its page is up; it
        // starts in the background after the first video, so that one never waits for it
        if (Sabr.first && Sabr.warm) {
            // not a live stream: the usual clients' live playlists go back hours, live SABR keeps only the last minute
            try { sabr(id).let { if (!it.live) return it } } catch (e: YtError) { if (e.kind == YtError.Kind.NETWORK) throw e; if (e.kind == YtError.Kind.OTHER || e.kind == YtError.Kind.LOGIN) Sabr.cool() }  // a video that isn't there (ended live, removed, private) says nothing about the page
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Sabr.cool() }
        } else prewarm()
        var first: YtError? = null
        for (attempt in 0..1) {
            var walled = true
            for (c in Clients.PLAYERS) {
                try {
                    return player(id, c)
                } catch (e: YtError) {
                    if (first == null || first.kind == YtError.Kind.LOGIN) first = e
                    if (e.kind == YtError.Kind.NETWORK) throw e
                    if (e.kind != YtError.Kind.LOGIN) walled = false
                }
            }
            // the token-free clients said "not available" (made for kids, some labels) or are walled:
            // YouTube Kids serves the ones made for kids as they are, SABR or the TV client (links unlocked) the rest
            // the SABR host (a hidden browser on a computer) starts while YouTube Kids is asked, so it is
            // ready if Kids refuses too; a video the usual clients play never starts it
            if (attempt == 0 && first?.kind != YtError.Kind.AGE) Sabr.host?.let { h -> if (Sabr.available) warmScope.launch { runCatching { h.warm() } } }
            if (attempt == 0 && first?.kind != YtError.Kind.AGE) {
                try { return player(id, Clients.KIDS) } catch (e: YtError) { if (e.kind == YtError.Kind.NETWORK) throw e }
            }
            // then SABR, YouTube's web player way with its own web token, before the TV client (whose links wait behind an ad)
            if (attempt == 0 && first?.kind != YtError.Kind.AGE && Sabr.available) {
                try { return sabr(id) } catch (e: YtError) { if (e.kind == YtError.Kind.NETWORK) throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
            if (attempt == 0 && first?.kind != YtError.Kind.AGE && Cipher.available) {
                try { return tv(id) } catch (e: YtError) { if (e.kind == YtError.Kind.NETWORK) throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
            // every client asked to sign in: the wall is on this visitor identity, so try a new one
            if (!walled || attempt == 1 || !InnerTube.reroll()) break
            first = null
        }
        throw first ?: YtError("This video can't be played")
    }

    /** the TV client's answer, its locked links opened by [Cipher] */
    private suspend fun tv(id: String): Stream {
        val sts = Cipher.ready() ?: throw YtError("No player script", YtError.Kind.UNAVAILABLE)
        val ctx = kotlinx.serialization.json.buildJsonObject {
            put("contentPlaybackContext", kotlinx.serialization.json.buildJsonObject { put("signatureTimestamp", kotlinx.serialization.json.JsonPrimitive(sts)) })
        }
        val d = InnerTube.call("player", mapOf("videoId" to id, "contentCheckOk" to true, "racyCheckOk" to true, "playbackContext" to ctx), Clients.TV)
        val answeredAt = Sys.nowMs()
        val raw = d.at("streamingData.adaptiveFormats").arr
        val urls = Cipher.urls(raw)
        val s = parse(id, d, Clients.TV) { i -> urls[i] }
        if (s.video.isEmpty() || s.audio.isEmpty()) throw YtError("No playable streams", YtError.Kind.UNAVAILABLE)
        // YouTube holds the links back behind the start ad it placed (up to 0.8 of its skip offset), but they
        // often open sooner: ask for one byte every half second instead of waiting it all out
        val until = answeredAt + Cipher.prerollMs(d)
        // the sound too: the player needs both, and one open does not always mean the other is
        val probes = listOf(s.video.first().url, s.audio.first().url)
        while (Sys.nowMs() < until) {
            val open = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                probes.all { probe ->
                    runCatching {
                        Net.client.newCall(okhttp3.Request.Builder().url("$probe&range=0-0").build()).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                }
            }
            if (open) break
            kotlinx.coroutines.delay(500)
        }
        return s
    }

    /** a live stream that came without a playlist: the web player's way, its own live playlist on this device */
    private suspend fun liveOr(id: String, s: Stream): Stream {
        // a live playlist: served on this device with hours more of the stream in front, to go back as on YouTube
        if (s.live && s.hls != null) return LiveDvr.master(s.hls)?.let { s.copy(hls = it) } ?: s
        if (!s.live || s.hls != null || !Sabr.available || Sabr.host?.live != true) return s
        return try { sabr(id) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; s }
    }

    /** the web player's answer, its formats served by [Sabr] on this device (a live stream: as a live playlist) */
    internal suspend fun sabr(id: String): Stream {
        val d = Sabr.prepare(id) ?: throw YtError("No SABR", YtError.Kind.UNAVAILABLE)
        val s0 = parse(id, d, Clients.WEB_SABR) { i -> Sabr.url(id, i) }
        val s = if (!s0.live) s0 else s0.copy(hls = SabrLive.master(id) ?: throw YtError("No live SABR here", YtError.Kind.UNAVAILABLE))
        if (s.video.isEmpty() || s.audio.isEmpty()) throw YtError("No playable streams", YtError.Kind.UNAVAILABLE)
        return s.copy(captions = s.captions.map { it.copy(url = Sabr.captionUrl(id, it.url)) })
    }

    /** one client's answer for a video, as it is: for the engine check and repair tools (the app goes through [stream]) */
    suspend fun playerWith(id: String, c: Client): Stream = player(id, c)

    private suspend fun player(id: String, c: Client): Stream {
        val d = InnerTube.call("player", mapOf("videoId" to id, "contentCheckOk" to true, "racyCheckOk" to true), c)
        return parse(id, d, c) { null }
    }

    /** a player answer as a Stream; [unlocked] gives a format's URL by its index when it came locked */
    private fun parse(id: String, d: JsonElement, c: Client, unlocked: (Int) -> String?): Stream {
        val ps = d.at("playabilityStatus")
        val status = ps.at("status").str ?: "ERROR"
        if (status != "OK") {
            val reason = ps.at("reason").str ?: ps.at("errorScreen")?.findFirst("reason").text ?: "This video is unavailable"
            val kind = when {
                status == "LOGIN_REQUIRED" && reason.contains("age", true) -> YtError.Kind.AGE
                status == "LOGIN_REQUIRED" -> YtError.Kind.LOGIN
                else -> YtError.Kind.UNAVAILABLE
            }
            throw YtError(reason, kind)
        }
        val vd = d.at("videoDetails")
        val sd = d.at("streamingData")
        val formats = sd.at("adaptiveFormats").arr.mapIndexedNotNull { i, f -> format(f, unlocked(i)) }.filter { !it.drc }
        val hls = sd.at("hlsManifestUrl").str
        if (formats.isEmpty() && hls == null) throw YtError("No playable streams", YtError.Kind.UNAVAILABLE)
        val caps = d.at("captions.playerCaptionsTracklistRenderer.captionTracks").arr.mapNotNull { t ->
            val url = t.at("baseUrl").str ?: return@mapNotNull null
            Caption(t.at("languageCode").str ?: "", t.at("name").text ?: "", url, t.at("kind").str == "asr")
        }
        return Stream(
            id = id,
            title = vd.at("title").str ?: "",
            author = vd.at("author").str ?: "",
            channelId = vd.at("channelId").str ?: "",
            lengthSec = vd.at("lengthSeconds").int ?: 0,
            views = vd.at("viewCount").long ?: 0,
            description = vd.at("shortDescription").str ?: "",
            keywords = vd.at("keywords").arr.mapNotNull { it.str },
            live = vd.at("isLive").bool,
            hls = hls,
            video = formats.filter { it.isVideo },
            audio = originalFirst(formats.filter { !it.isVideo }),
            captions = caps,
            expiresAt = Sys.nowMs() / 1000 + (sd.at("expiresInSeconds").long ?: 18000),
            client = c.name,
            storyboard = d.at("storyboards.playerStoryboardSpecRenderer.spec").str,
        )
    }

    private fun range(e: JsonElement?): LongRange? {
        val s = e.at("start").long ?: return null
        val t = e.at("end").long ?: return null
        return s..t
    }

    private fun format(f: JsonElement, unlocked: String? = null): Format? {
        val url = unlocked ?: f.at("url").str ?: return null     // a locked one only when Cipher opened it
        val mimeFull = f.at("mimeType").str ?: return null
        val mime = mimeFull.substringBefore(';').trim()
        val codecs = mimeFull.substringAfter("codecs=\"", "").substringBefore('"')
        return Format(
            itag = f.at("itag").int ?: 0,
            url = url,
            mime = mime,
            codecs = codecs,
            width = f.at("width").int ?: 0,
            height = f.at("height").int ?: 0,
            fps = f.at("fps").int ?: 0,
            bitrate = f.at("bitrate").int ?: 0,
            contentLength = f.at("contentLength").long ?: 0,
            initRange = range(f.at("initRange")),
            indexRange = range(f.at("indexRange")),
            qualityLabel = f.at("qualityLabel").str ?: "",
            sampleRate = f.at("audioSampleRate").int ?: 0,
            channels = f.at("audioChannels").int ?: 2,
            audioTrack = f.at("audioTrack.id").str,
            audioTrackName = f.at("audioTrack.displayName").str,
            audioDefault = f.at("audioTrack.audioIsDefault").str?.let { it == "true" } ?: true,
            drc = f.at("isDrc").bool,
        )
    }

    // ------------------------------------------------------------------ watch page
    suspend fun details(id: String): Details {
        val d = InnerTube.call("next", mapOf("videoId" to id))
        val watch = d.at("contents.twoColumnWatchNextResults")
        val primary = watch?.findFirst("videoPrimaryInfoRenderer")
        val owner = watch?.findFirst("videoOwnerRenderer")
        val like = watch?.findFirst("segmentedLikeDislikeButtonViewModel")
        val related = watch.at("secondaryResults.secondaryResults")
        val panels = d.at("engagementPanels").arr.map { it.at("engagementPanelSectionListRenderer") }
        val comments = panels.firstOrNull { it.at("panelIdentifier").str == "engagement-panel-comments-section" }
        val commentsToken = comments.at("content")?.findFirst("token").str
            ?: watch?.findAll("itemSectionRenderer")?.firstOrNull { it.at("sectionIdentifier").str == "comment-item-section" }
                ?.findFirst("token").str
        return Details(
            title = primary.at("title").text ?: "",
            author = owner.at("title").text ?: "",
            channelId = owner.at("navigationEndpoint.browseEndpoint.browseId").str ?: "",
            likesText = like.at("likeButtonViewModel.likeButtonViewModel.toggleButtonViewModel.toggleButtonViewModel.defaultButtonViewModel.buttonViewModel.title").str ?: "",
            subsText = owner.at("subscriberCountText").text ?: "",
            avatar = bestImage(owner.at("thumbnail")).ifEmpty { bestImage(watch?.findFirst("decoratedAvatarViewModel")) },
            publishedText = primary.at("relativeDateText").text ?: primary.at("dateText").text ?: "",
            viewsText = primary.at("viewCount.videoViewCountRenderer.viewCount").text ?: "",
            related = parseItems(related),
            relatedNext = continuationOf(related),
            commentsToken = commentsToken,
            // "Top" / "Newest" sit in the comments panel's header
            commentSorts = comments.at("header")?.findFirst("sortFilterSubMenuRenderer", emptySet()).at("subMenuItems").arr.mapNotNull { m ->
                val t = m.findFirst("token", emptySet()).str ?: return@mapNotNull null
                (m.at("title").str ?: return@mapNotNull null) to t
            },
            commentsCount = comments.at("header")?.findFirst("contextualInfo").text ?: "",
            // the chapters the seek bar shows (the video's own, or YouTube's automatic ones)
            chapters = d.at("playerOverlays.playerOverlayRenderer.decoratedPlayerBarRenderer.decoratedPlayerBarRenderer.playerBar.multiMarkersPlayerBarRenderer.markersMap").arr
                .firstOrNull { (it.at("key").str ?: "").contains("CHAPTERS") }?.at("value.chapters").arr.mapNotNull { c ->
                    val r = c.at("chapterRenderer") ?: return@mapNotNull null
                    Chapter(r.at("title").text ?: "", r.at("timeRangeStartMillis").long ?: return@mapNotNull null, bestImage(r.at("thumbnail")))
                },
            heat = heatOf(d),
        )
    }

    suspend fun relatedMore(token: String): Page<Item> {
        val d = InnerTube.call("next", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedEndpoints")
        return Page(parseItems(root), continuationOf(root))
    }

    /** One page of comments, or of replies when [token] is a thread's replies token. */
    suspend fun comments(token: String): Page<Comment> {
        val d = InnerTube.call("next", mapOf("continuation" to token))
        val payloads = HashMap<String, JsonElement>()
        for (m in d.at("frameworkUpdates.entityBatchUpdate.mutations").arr) {
            val p = m.at("payload.commentEntityPayload") ?: continue
            p.at("properties.commentId").str?.let { payloads[it] = p }
        }
        val endpoints = d.at("onResponseReceivedEndpoints").arr
        // the items of this page: the last list in the response (the first one is the header on page 1)
        val items = endpoints.flatMap {
            (it.at("reloadContinuationItemsCommand.continuationItems") ?: it.at("appendContinuationItemsAction.continuationItems")).arr
        }
        val out = ArrayList<Comment>()
        for (it in items) {
            val thread = it.at("commentThreadRenderer")
            val vm = (thread?.at("commentViewModel.commentViewModel") ?: it.at("commentViewModel")) ?: continue
            val cid = vm.at("commentId").str ?: continue
            val p = payloads[cid] ?: continue
            val token2 = thread?.at("replies")?.findFirst("token").str
            out += Comment(
                id = cid,
                author = p.at("author.displayName").str ?: "",
                avatar = https(p.at("author.avatarThumbnailUrl").str),
                text = p.at("properties.content.content").str ?: "",
                likes = p.at("toolbar.likeCountNotliked").str?.trim() ?: "",
                replies = countOf(p.at("toolbar.replyCount").str).toInt(),
                published = p.at("properties.publishedTime").str ?: "",
                channelId = p.at("author.channelId").str ?: "",
                repliesToken = token2,
                byCreator = p.at("author.isCreator").bool,
                pinned = vm.at("pinnedText") != null,
            )
        }
        val next = items.lastOrNull()?.let { last ->
            (last.at("continuationItemRenderer") ?: return@let null).let { cr ->
                cr.findFirst("token").str
            }
        }
        return Page(out, next)
    }

    // ------------------------------------------------------------------ channels and playlists
    /** the search box inside a channel */
    private const val CHANNEL_SEARCH = "EgZzZWFyY2jyBgQKAloA"

    suspend fun channel(id: String, tab: Tab = Tab.VIDEOS): ChannelPage = channel(id, tab.params)

    /** The tab [params] stands for (one of [ChannelPage.tabs]); null is the channel's first tab, Home. */
    suspend fun channel(id: String, params: String?): ChannelPage {
        val d = InnerTube.call("browse", mapOf("browseId" to id, "params" to params?.let(::urlDecode)))
        val tabs = d.at("contents.twoColumnBrowseResultsRenderer.tabs").arr
        val sel = selectedTab(d)
        val sections = sel.at("sectionListRenderer.contents").arr
        val player = sections.firstNotNullOfOrNull { it.at("itemSectionRenderer.contents.0.channelVideoPlayerRenderer") }
        val head = d.at("header")?.findFirst("pageHeaderViewModel")
        val attr = head.at("attribution.attributionViewModel")
        val rows = head.at("metadata.contentMetadataViewModel.metadataRows").arr.map { r ->
            r.at("metadataParts").arr.mapNotNull { it.at("text").text }
        }.flatten()
        val stats = rows.filter { !it.startsWith("@") && it.any(Char::isDigit) }   // [subscribers, videos]
        val old = d.at("header.c4TabbedHeaderRenderer")
        val meta = d.at("metadata.channelMetadataRenderer")
        val preview = head?.findFirst("descriptionPreviewViewModel")
        return ChannelPage(
            id = id,
            name = head.at("title").text ?: old.at("title").str ?: meta.at("title").str ?: "",
            avatar = bestImage(head.at("image") ?: old.at("avatar") ?: meta.at("avatar")),
            banner = bestImage(head.at("banner") ?: old.at("banner")),
            handle = rows.firstOrNull { it.startsWith("@") } ?: "",
            subsText = stats.getOrNull(0) ?: old.at("subscriberCountText").text ?: "",
            videoCountText = stats.getOrNull(1) ?: "",
            items = Page(parseItems(sel), continuationOf(sel)),
            description = preview.at("description.content").str ?: meta.at("description").str ?: "",
            // "...more" opens a panel that is loaded with this token
            aboutToken = (preview ?: head)?.findFirst("continuationCommand").at("token").str,
            tabs = tabs.mapNotNull { t ->
                val r = t.at("tabRenderer") ?: return@mapNotNull null
                val p = r.at("endpoint.browseEndpoint.params").str ?: return@mapNotNull null
                val kind = r.at("endpoint.commandMetadata.webCommandMetadata.url").str?.substringAfterLast('/') ?: ""
                ChannelTab(r.at("title").str ?: kind, p, kind)
            },
            searchable = tabs.any { it.at("expandableTabRenderer") != null },
            linkText = attr.at("text.content").str?.trim() ?: "",
            linkMore = attr.at("suffix.content").str?.trim() ?: "",
            featured = player?.at("videoId").str?.let { v ->
                val views = player.at("viewCountText").text ?: ""
                Video(v, player.at("title").text ?: "", author = head.at("title").text ?: "", channelId = id,
                    viewsText = views, views = countOf(views), publishedText = player.at("publishedTimeText").text ?: "")
            },
            featuredText = player.at("description").text ?: "",
            shelves = Page(parseShelves(sections, id), continuationOf(sel)),
            posts = Page(parsePosts(sel), continuationOf(sel)),
        )
    }

    /** the content of the tab a browse response opened */
    private fun selectedTab(d: JsonElement): JsonElement? {
        val tabs = d.at("contents.twoColumnBrowseResultsRenderer.tabs").arr
        val t = tabs.firstOrNull { it.at("tabRenderer.selected").bool || it.at("expandableTabRenderer.selected").bool } ?: tabs.firstOrNull()
        return (t.at("tabRenderer") ?: t.at("expandableTabRenderer")).at("content")
    }

    /** more rows of a channel's Home tab */
    suspend fun shelvesMore(token: String, channelId: String): Page<Shelf> {
        val d = InnerTube.call("browse", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedActions") ?: d.at("onResponseReceivedEndpoints")
        val sections = root.arr.flatMap { it.at("appendContinuationItemsAction.continuationItems").arr }
        return Page(parseShelves(sections, channelId), continuationOf(root))
    }

    /** more of a channel's posts */
    suspend fun postsMore(token: String): Page<Post> {
        val d = InnerTube.call("browse", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedActions") ?: d.at("onResponseReceivedEndpoints")
        return Page(parsePosts(root), continuationOf(root))
    }

    /** the search inside a channel (more pages come from [browseMore]) */
    suspend fun channelSearch(id: String, query: String): Page<Item> {
        val d = InnerTube.call("browse", mapOf("browseId" to id, "params" to urlDecode(CHANNEL_SEARCH), "query" to query))
        val sel = selectedTab(d)
        return Page(parseItems(sel), continuationOf(sel))
    }

    /** what "...more" on a channel shows: the whole description, links, country, joined date, views */
    suspend fun channelAbout(token: String): ChannelAbout {
        val d = InnerTube.call("browse", mapOf("continuation" to token))
        val a = d.findFirst("aboutChannelViewModel") ?: throw YtError("This channel has no details", YtError.Kind.UNAVAILABLE)
        return ChannelAbout(
            description = a.at("description").str ?: "",
            links = a.at("links").arr.mapNotNull { l ->
                val m = l.at("channelExternalLinkViewModel") ?: return@mapNotNull null
                val text = m.at("link.content").str ?: return@mapNotNull null
                // YouTube's redirect page carries the real address in q=
                val redirect = m.findFirst("urlEndpoint").at("url").str ?: ""
                val url = Regex("[?&]q=([^&]+)").find(redirect)?.groupValues?.get(1)?.let(::urlDecode)
                    ?: if (text.startsWith("http")) text else "https://$text"
                ChannelLink(m.at("title.content").str ?: text, text, url, m.at("favicon.sources").arr.lastOrNull().at("url").str ?: "")
            },
            url = a.at("displayCanonicalChannelUrl").str ?: a.at("canonicalChannelUrl").str ?: "",
            country = a.at("country").str ?: "",
            joinedText = a.at("joinedDateText.content").str ?: a.at("joinedDateText").text ?: "",
            viewsText = a.at("viewCountText").str ?: a.at("viewCountText").text ?: "",
            subsText = a.at("subscriberCountText").str ?: a.at("subscriberCountText").text ?: "",
            videoCountText = a.at("videoCountText").str ?: a.at("videoCountText").text ?: "",
        )
    }

    suspend fun browseMore(token: String): Page<Item> {
        val d = InnerTube.call("browse", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedActions") ?: d.at("onResponseReceivedEndpoints")
        return Page(parseItems(root), continuationOf(root))
    }

    suspend fun playlist(id: String): PlaylistPage {
        val d = InnerTube.call("browse", mapOf("browseId" to if (id.startsWith("VL")) id else "VL$id"))
        val head = d.at("header")?.findFirst("pageHeaderViewModel")
        val rows = head.at("metadata.contentMetadataViewModel.metadataRows").arr.map { r ->
            r.at("metadataParts").arr.mapNotNull { it.at("text").text }
        }
        val content = d.at("contents")
        return PlaylistPage(
            id = id,
            title = head.at("title").text ?: d.at("metadata.playlistMetadataRenderer.title").str ?: "",
            author = rows.firstOrNull()?.firstOrNull() ?: "",
            countText = rows.flatten().firstOrNull { it.any(Char::isDigit) } ?: "",
            items = Page(parseItems(content).filterIsInstance<Video>(), continuationOf(content)),
        )
    }

    /** youtube.com/@handle, /c/name, /watch?v=, youtu.be/... -> what it points to */
    suspend fun resolve(url: String): Pair<String, String>? {
        Regex("(?:v=|youtu\\.be/|shorts/|live/|embed/)([\\w-]{11})").find(url)?.let { return "video" to it.groupValues[1] }
        Regex("list=([\\w-]+)").find(url)?.let { return "playlist" to it.groupValues[1] }
        Regex("channel/(UC[\\w-]{22})").find(url)?.let { return "channel" to it.groupValues[1] }
        val full = if (url.startsWith("@")) "https://www.youtube.com/$url" else url
        val d = InnerTube.call("navigation/resolve_url", mapOf("url" to full))
        d.at("endpoint.browseEndpoint.browseId").str?.let { return "channel" to it }
        d.at("endpoint.watchEndpoint.videoId").str?.let { return "video" to it }
        return null
    }

    // ------------------------------------------------------------------ feeds
    /** A channel's latest 15 uploads from its public RSS feed (cheap: one small request). */
    /** RSS failed lately (YouTube takes it down for hours now and then): go straight to the channel page */
    @Volatile private var rssDownUntil = 0L

    /**
     * A channel's latest uploads: its RSS feed (exact times, one small request), or when that
     * fails its Videos tab, with the times worked out from "3 days ago".
     */
    suspend fun feed(channelId: String): List<Video> {
        if (Sys.nowMs() > rssDownUntil) {
            try {
                return rss(channelId)
            } catch (e: okio.IOException) {
                if (e is HttpError && e.code !in 500..599 && e.code != 404) throw e
                rssDownUntil = Sys.nowMs() + 3_600_000
            }
        }
        val c = channel(channelId, Tab.VIDEOS)
        val now = Sys.nowMs() / 1000
        // the channel's Videos tab (when the RSS feed is down) also lists members-only videos, which won't play here
        return c.items.items.filterIsInstance<Video>().filter { !it.members }.map { v ->
            v.copy(author = v.author.ifEmpty { c.name }, channelId = channelId, publishedAt = ago(v.publishedText)?.let { now - it } ?: 0)
        }
    }

    /** "3 days ago" / "3일 전" → seconds; null when it can't be read */
    fun ago(text: String): Long? {
        val m = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year|초|분|시간|일|주|개월|달|년)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "second", "초" -> 1L
            "minute", "분" -> 60L
            "hour", "시간" -> 3600L
            "day", "일" -> 86400L
            "week", "주" -> 7 * 86400L
            "month", "개월", "달" -> 30 * 86400L
            else -> 365 * 86400L
        }
        return n * unit
    }

    private suspend fun rss(channelId: String): List<Video> {
        val xml = Net.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        val author = Regex("<author>\\s*<name>([^<]*)</name>").find(xml)?.groupValues?.get(1)?.let(::unxml) ?: ""
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            val id = Regex("<yt:videoId>([\\w-]{11})</yt:videoId>").find(e)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1)?.let(::unxml) ?: ""
            val pub = Regex("<published>([^<]+)</published>").find(e)?.groupValues?.get(1)
            val views = Regex("views=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val short = e.contains("/shorts/")
            Video(
                id, title, author, channelId,
                views = views,
                publishedAt = pub?.let { Sys.isoSeconds(it) } ?: 0,
                short = short,
            )
        }.toList()
    }

    private fun unxml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /** WebVTT text of a caption track, optionally machine-translated to [to]. */
    fun captionUrl(c: Caption, to: String? = null): String =
        if (c.url.startsWith("file:")) c.url        // saved with a download: as it is
        else ServerVideo.captionUrl(c, to) ?: c.url + "&fmt=vtt" + (to?.takeIf { it != c.lang.substringBefore('-') }?.let { "&tlang=$it" } ?: "")
}
