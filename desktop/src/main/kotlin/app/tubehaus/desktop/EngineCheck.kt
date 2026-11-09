package app.tubehaus.desktop

import app.tubehaus.data.Transfer
import app.tubehaus.engine.Channel
import app.tubehaus.engine.Clients
import app.tubehaus.engine.Remote
import app.tubehaus.engine.SearchOptions
import app.tubehaus.engine.Storyboard
import app.tubehaus.engine.Dash
import app.tubehaus.engine.Net
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/** `./gradlew :desktop:engineCheck` - runs every engine call against YouTube and prints what came back. */
fun main() = runBlocking {
    // TH_SOCKS=127.0.0.1:40000: every request through that SOCKS proxy (the daily CI check goes out through WARP)
    System.getenv("TH_SOCKS")?.split(':')?.let { (h, p) -> System.setProperty("socksProxyHost", h); System.setProperty("socksProxyPort", p) }
    val failed = ArrayList<String>()
    app.tubehaus.engine.InnerTube.identityFile = okio.Path.Companion.run { java.io.File(System.getProperty("java.io.tmpdir"), "tubehaus-check-identity.json").toOkioPath() }
    var fails = 0
    suspend fun check(name: String, f: suspend () -> String) {
        val t = System.currentTimeMillis()
        try {
            val r = f()
            println("ok   %-14s %5dms  %s".format(name, System.currentTimeMillis() - t, r))
        } catch (e: Throwable) {
            fails++
            failed += "$name: $e"
            println("FAIL %-14s %s".format(name, e))
        }
    }
    // TH_SERVER=https://tube.example.com (TH_SERVER_USER, TH_SERVER_PASS): videos through that TubeHaus server instead
    System.getenv("TH_SERVER")?.let { server ->
        app.tubehaus.engine.ServerVideo.set(server, System.getenv("TH_SERVER_USER") ?: "", System.getenv("TH_SERVER_PASS") ?: "")
        fun first(url: String): String = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=0-999").build()).execute()
            .use { r -> require(r.code == 206) { "HTTP ${r.code} $url" }; "${r.body!!.bytes().size} B ${r.header("Content-Type")}" }
        check("server stream") {
            val s = YouTube.stream("jNQXAC9IVRw")
            require(s.client == "server") { "came from ${s.client}" }
            val v = s.video.maxBy { it.height }
            val a = s.audio.maxBy { it.bitrate }
            "${s.title} | ${s.author} ${s.lengthSec}s, ${s.video.size}v/${s.audio.size}a, top ${v.height}p ${v.codecs} (init ${v.initRange}); video ${first(v.url)}; audio ${first(a.url)}"
        }
        // a video with dubs: one track per language, the original the default
        check("server dubs") {
            val s = YouTube.stream("0e3GPea1Tyg")
            val tracks = s.audio.distinctBy { it.audioTrack }
            require(tracks.size > 1) { "one audio track" }
            val main = tracks.filter { it.audioDefault }.map { "${it.audioTrack} ${it.audioTrackName}" }
            require(main.size == 1) { "default tracks: $main" }
            "${tracks.size} tracks, default $main, others ${tracks.filter { !it.audioDefault }.take(3).map { it.audioTrackName }}"
        }
        check("server captions") {
            val c = YouTube.stream("jNQXAC9IVRw").captions.first()
            val text = Net.client.newCall(Request.Builder().url(YouTube.captionUrl(c)).build()).execute().use { r -> require(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
            val ko = Net.client.newCall(Request.Builder().url(YouTube.captionUrl(c, "ko")).build()).execute().use { r -> require(r.isSuccessful) { "HTTP ${r.code}" }; r.body!!.string() }
            "${c.lang} ${c.name}: ${text.lines().filter { it.isNotBlank() }.take(4).joinToString(" / ")} | ko: ${ko.lines().filter { it.isNotBlank() }.drop(1).take(2).joinToString(" / ")}"
        }
        check("server dash") { Dash.manifest(YouTube.stream("dQw4w9WgXcQ"), listOf("vp9", "avc1")).length.toString() + " chars" }
        println(if (fails == 0) "\nall good" else "\n$fails failed")
        Net.client.dispatcher.executorService.shutdown()
        return@runBlocking
    }
    check("search") {
        val p = YouTube.search("lofi hip hop")
        val v = p.items.filterIsInstance<Video>()
        require(v.size >= 5) { "only ${v.size} videos" }
        "${p.items.size} items, next=${p.next != null}; first: ${v.first().title} | ${v.first().author} | ${v.first().lengthSec}s | ${v.first().viewsText}"
    }
    check("search more") {
        val t = YouTube.search("cats").next!!
        "${YouTube.searchMore(t).items.size} items"
    }
    check("channels") { YouTube.search("music", YouTube.Filter.CHANNELS).items.filterIsInstance<Channel>().take(2).joinToString { "${it.name} ${it.subsText} ${it.handle}" } }
    check("playlists") { YouTube.search("music", YouTube.Filter.PLAYLISTS).items.filterIsInstance<Playlist>().take(2).joinToString { "${it.title} (${it.countText})" } }
    check("shorts search") { YouTube.search("#shorts cat", YouTube.Filter.SHORT).items.filterIsInstance<Video>().count { it.short || it.lengthSec in 1..180 }.toString() + " shorts" }
    check("suggestions") { YouTube.suggestions("lofi").take(3).toString() }
    check("stream") {
        val s = YouTube.stream("dQw4w9WgXcQ")
        val v = s.video.maxBy { it.height }
        val r = Net.client.newCall(Request.Builder().url(v.url).header("Range", "bytes=0-65535").build()).execute()
        val code = r.code; r.close()
        "${s.client}: ${s.title} | ${s.video.size} video ${s.audio.size} audio ${s.captions.size} captions | ${v.height}p ${v.codecs} -> HTTP $code | mpd ${Dash.manifest(s, listOf("vp9")).length}B"
    }
    // a video with dubs: the original language must be the default track
    check("stream dubs") {
        val s = YouTube.stream("0e3GPea1Tyg")
        val tracks = s.audio.distinctBy { it.audioTrack }
        val main = tracks.filter { it.audioDefault }.map { "${it.audioLang}/${it.audioContent}" }
        "${tracks.size} tracks, default $main, others ${tracks.filter { !it.audioDefault }.take(4).map { "${it.audioLang}/${it.audioContent}" }}"
    }
    check("stream short") { YouTube.stream("SxEAxK_Eb3Q").let { "${it.client}: ${it.title} ${it.lengthSec}s ${it.keywords.take(3)}" } }
    check("stream zoo") {
        val s = YouTube.stream("jNQXAC9IVRw")
        val a = s.audio.first()
        // the far end of the file: a client YouTube cuts off a minute in answers 403 here
        val from = a.contentLength * 8 / 10
        val r = Net.client.newCall(Request.Builder().url(a.url).header("Range", "bytes=$from-${from + 65535}").build()).execute()
        val code = r.code; r.close()
        "${s.client}: ${s.title} | audio at 80% -> HTTP $code"
    }
    check("details") {
        val d = YouTube.details("dQw4w9WgXcQ")
        "likes=${d.likesText} subs=${d.subsText} views=${d.viewsText} date=${d.publishedText} related=${d.related.size} more=${d.relatedNext != null} comments=${d.commentsToken != null}"
    }
    check("storyboard") {
        val s = YouTube.stream("dQw4w9WgXcQ")
        val b = Storyboard.parse(s.storyboard) ?: error("${s.client}: no storyboard")
        val f = b.frame(60_000, s.lengthSec * 1000L)
        val r = Net.client.newCall(Request.Builder().url(f.url).build()).execute()
        val code = r.code; r.close()
        "${s.client}: ${b.width}x${b.height} ${b.cols}x${b.rows} frame(1:00)=${f.col},${f.row} HTTP $code"
    }
    check("chapters") {
        val d = YouTube.details("qp0HIF3SfI4")
        "${d.chapters.size} chapters: " + d.chapters.take(3).joinToString { "${it.startMs / 1000}s ${it.title} thumb=${it.thumb.isNotEmpty()}" }
    }
    check("comment sorts") {
        val d = YouTube.details("dQw4w9WgXcQ")
        val newest = d.commentSorts.getOrNull(1)?.second?.let { YouTube.comments(it).items.size } ?: -1
        "count=${d.commentsCount} sorts=${d.commentSorts.map { it.first }} newest page=$newest"
    }
    check("search filters") {
        val o = SearchOptions(type = 1, date = 4, duration = 2, sort = 3)
        val r = YouTube.search("music", o).items.filterIsInstance<Video>()
        "${o.params()}: ${r.size} videos, under 20m (want 0)=${r.count { it.lengthSec in 1 until 1200 }}"
    }
    check("related more") { YouTube.relatedMore(YouTube.details("dQw4w9WgXcQ").relatedNext!!).items.size.toString() + " items" }
    check("comments") {
        val p = YouTube.comments(YouTube.details("dQw4w9WgXcQ").commentsToken!!)
        val c = p.items.first()
        val rep = p.items.firstOrNull { it.repliesToken != null }?.let { YouTube.comments(it.repliesToken!!).items.size } ?: -1
        "${p.items.size} comments, next=${p.next != null}; '${c.text.take(40)}' by ${c.author} (${c.likes}, ${c.replies} replies); replies page=$rep"
    }
    check("channel") {
        val c = YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw")
        "${c.name} ${c.handle} ${c.subsText} ${c.videoCountText} avatar=${c.avatar.isNotEmpty()} banner=${c.banner.isNotEmpty()} items=${c.items.items.size} more=${c.items.next != null}"
    }
    // the phone's top tabs (Music, Live) and More: YouTube's topic pages, one flat list each
    check("topics") {
        val n = app.tubehaus.ui.TOPICS.mapValues { (_, id) -> YouTube.channel(id, null as String?).items.items.size }
        require(n.getValue("Music") > 0 && n.getValue("Live") > 0) { n.toString() }
        n.entries.joinToString { "${it.key} ${it.value}" }
    }
    // "Most replayed" from the watch page's answer, and DeArrow's choice for a video it is known to have
    check("most replayed") {
        val h = YouTube.details("dQw4w9WgXcQ").heat
        require(h.points.size >= 10) { "no heat" }
        "${h.points.size} points span=${h.spanMs / 1000}s peak=${h.peakMs?.div(1000)}s"
    }
    check("dearrow") {
        require(app.tubehaus.engine.DeArrow.prefixOf("dQw4w9WgXcQ") == "5f6b") { "hash" }
        val b = app.tubehaus.engine.DeArrow.brand("dQw4w9WgXcQ")
        "title=${b?.title} time=${b?.timeSec}"
    }
    // Despacito: SponsorBlock has its non-music parts and a highlight
    check("sponsorblock") {
        val s = app.tubehaus.engine.SponsorBlock.segments("kJQP7kiw5Fk", listOf("sponsor"))
        require(s.isNotEmpty()) { "no segments" }
        s.joinToString { "${it.category} ${it.startMs / 1000}-${it.endMs / 1000}s" }
    }
    check("channel more") { YouTube.browseMore(YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw").items.next!!).items.size.toString() + " items" }
    // a members-only video on a channel's Videos tab is known by its badge (Dammit Jeff, a few pages down)
    check("members only") {
        var page = YouTube.channel("UCXOSur_wiS9TK0WUhJrYrlA").items
        val seen = ArrayList<app.tubehaus.engine.Video>()
        repeat(8) {
            seen += page.items.filterIsInstance<app.tubehaus.engine.Video>()
            if (seen.any { it.members }) return@repeat
            page = YouTube.browseMore(page.next ?: return@repeat)
        }
        val m = seen.filter { it.members }
        require(m.isNotEmpty()) { "none marked in ${seen.size} videos" }
        "${m.size} of ${seen.size} marked, e.g. '${m.first().title}'"
    }
    // "...more" on a channel: description, links, joined date
    check("channel about") {
        val c = YouTube.channel("UCTlfC-3OQJ0be6YfTL_irOA")
        val a = YouTube.channelAbout(c.aboutToken ?: error("no token; preview '${c.description.take(30)}'"))
        require(a.links.isNotEmpty() && a.joinedText.isNotEmpty()) { "links ${a.links.size} joined '${a.joinedText}'" }
        "'${c.description.lineSequence().first()}' | ${a.description.length} chars, ${a.links.size} links (${a.links.first().title} -> ${a.links.first().url}) | ${a.url} ${a.country} | ${a.joinedText} | ${a.viewsText} | ${a.subsText} | ${a.videoCountText}"
    }
    // a channel opens on Home as on YouTube: its own tabs, the featured video and its rows
    check("channel home") {
        val c = YouTube.channel("UCTlfC-3OQJ0be6YfTL_irOA", null as String?)
        require(c.tabs.isNotEmpty() && c.shelves.items.isNotEmpty()) { "tabs ${c.tabs.size} shelves ${c.shelves.items.size}" }
        "tabs ${c.tabs.joinToString(",") { it.kind }} search=${c.searchable} | link '${c.linkText}' '${c.linkMore}' | featured ${c.featured?.title?.take(20)} | " +
            "${c.shelves.items.size} rows: " + c.shelves.items.take(3).joinToString(" / ") { "${it.title} (${it.items.size}, pl=${it.playlistId != null})" }
    }
    check("channel home more") {
        val c = YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw", null as String?)
        val more = c.shelves.next?.let { YouTube.shelvesMore(it, c.id).items.size } ?: -1
        "${c.shelves.items.size} rows (${c.shelves.items.joinToString(", ") { it.title }}), more=$more"
    }
    check("channel posts") {
        val c = YouTube.channel("UCTlfC-3OQJ0be6YfTL_irOA", null as String?)
        val t = c.tabs.first { it.kind == "posts" }
        val p = YouTube.channel(c.id, t.params).posts
        require(p.items.isNotEmpty()) { "no posts" }
        val more = p.next?.let { YouTube.postsMore(it).items.size } ?: -1
        val x = p.items.first()
        "${p.items.size} posts, more=$more | ${x.published} '${x.text.take(25)}' links ${p.items.sumOf { it.links.size }} " +
            "images ${p.items.sumOf { it.images.size }} videos ${p.items.count { it.video != null }} playlists ${p.items.count { it.playlist != null }} polls ${p.items.count { it.poll.isNotEmpty() }}"
    }
    check("channel search") {
        val r = YouTube.channelSearch("UCTlfC-3OQJ0be6YfTL_irOA", "방정식")
        require(r.items.isNotEmpty()) { "none" }
        "${r.items.size} items, more=${r.next?.let { YouTube.browseMore(it).items.size }}"
    }
    check("channel shorts") { YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw", YouTube.Tab.SHORTS).items.items.size.toString() + " items" }
    check("channel lists") { YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw", YouTube.Tab.PLAYLISTS).items.items.size.toString() + " items" }
    check("playlist") {
        val p = YouTube.playlist("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI")
        "${p.title} | ${p.author} | ${p.countText} | ${p.items.items.size} videos, more=${p.items.next != null}"
    }
    check("playlist more") { YouTube.browseMore(YouTube.playlist("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI").items.next!!).items.size.toString() + " items" }
    check("rss") { YouTube.feed("UCuAXFkgsw1L7xaCfnd5JJOw").let { "${it.size} videos, newest ${it.first().title} at ${it.first().publishedAt}" } }
    check("resolve") { listOf("https://www.youtube.com/@RickAstleyYT", "https://youtu.be/dQw4w9WgXcQ").map { YouTube.resolve(it) }.toString() }
    check("import csv") {
        val p = Transfer.parse("Channel Id,Channel Url,Channel Title\nUCuAXFkgsw1L7xaCfnd5JJOw,http://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw,Rick Astley\n")
        "${p.format} ${p.subs}"
    }
    check("update check") {
        val a = Remote.newerRelease("app-v", "0.2.0", ".apk")
        val d = Remote.newerRelease("app-v", "0.2.0", ".msi")
        require(a != null && d != null) { "no release found" }
        require(Remote.newerRelease("app-v", "99.0.0", ".apk") == null) { "offered an older version" }
        "android ${a.version}, windows ${d.version}, ${a.notes.size} lines of notes"
    }
    check("client list") {
        // the repository's clients.json, read the way an installed app reads its saved copy
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "tubehaus-check-clients.json")
        f.writeText(java.io.File("../clients.json").readText())
        Remote.clientsFile = okio.Path.Companion.run { f.toOkioPath() }
        Clients.PLAYERS = emptyList()
        Remote.restoreClients()
        require(Clients.PLAYERS.size >= 2) { "list not read" }
        val s = YouTube.stream("dQw4w9WgXcQ")
        Clients.PLAYERS.joinToString { "${it.name} ${it.version} ${it.extra}" } + " -> stream via " + s.client
    }
    // offline saving: what it picks, and a whole short video downloaded the way the app does it (10 MB ranges)
    check("offline pick") {
        val s = YouTube.stream("aqz-KE-bpKQ")
        val v = app.tubehaus.ui.Offline.pickVideo(s.video, 1080)!!
        val a = app.tubehaus.ui.Offline.pickAudio(s.audio, v)!!
        val v4 = app.tubehaus.ui.Offline.pickVideo(s.video, 2160)!!
        "1080 -> ${v.height}p ${v.codecs}, audio ${a.codecs} ${a.bitrate / 1000}k; 2160 -> ${v4.height}p ${v4.codecs}"
    }
    check("offline fetch") {
        val s = YouTube.stream("jNQXAC9IVRw")
        val v = app.tubehaus.ui.Offline.pickVideo(s.video, 360)!!
        val a = app.tubehaus.ui.Offline.pickAudio(s.audio, v)!!
        fun whole(url: String, len: Long): Long {
            var have = 0L
            while (have < len) {
                val r = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=$have-${minOf(len - 1, have + (10L shl 20) - 1)}").build()).execute()
                r.use { require(r.code == 206) { "HTTP ${r.code}" }; have += r.body!!.bytes().size }
            }
            return have
        }
        val vb = whole(v.url, v.contentLength)
        val ab = whole(a.url, a.contentLength)
        require(vb == v.contentLength && ab == a.contentLength)
        "video ${v.height}p ${v.codecs} $vb B, audio ${a.codecs} $ab B (index ${v.indexRange}, ${a.indexRange})"
    }
    println(if (fails == 0) "\nall good" else "\n$fails failed")
    Net.client.dispatcher.executorService.shutdown()
    // TH_REPORT=file: the failed checks, one per line, for the daily CI job to put in an issue
    System.getenv("TH_REPORT")?.let { java.io.File(it).writeText(failed.joinToString("\n")) }
    if (fails > 0) kotlin.system.exitProcess(1)
}
