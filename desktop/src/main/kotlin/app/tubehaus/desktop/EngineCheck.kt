package app.tubehaus.desktop

import app.tubehaus.data.Transfer
import app.tubehaus.engine.Channel
import app.tubehaus.engine.Clients
import app.tubehaus.engine.Remote
import app.tubehaus.engine.Dash
import app.tubehaus.engine.Net
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/** `./gradlew :desktop:engineCheck` - runs every engine call against YouTube and prints what came back. */
fun main() = runBlocking {
    app.tubehaus.engine.InnerTube.identityFile = okio.Path.Companion.run { java.io.File(System.getProperty("java.io.tmpdir"), "tubehaus-check-identity.json").toOkioPath() }
    var fails = 0
    suspend fun check(name: String, f: suspend () -> String) {
        val t = System.currentTimeMillis()
        try {
            val r = f()
            println("ok   %-14s %5dms  %s".format(name, System.currentTimeMillis() - t, r))
        } catch (e: Throwable) {
            fails++
            println("FAIL %-14s %s".format(name, e))
        }
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
    check("channel more") { YouTube.browseMore(YouTube.channel("UCuAXFkgsw1L7xaCfnd5JJOw").items.next!!).items.size.toString() + " items" }
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
        val d = Remote.newerRelease("desktop-v", "0.2.0", ".msi")
        require(a != null && d != null) { "no release found" }
        require(Remote.newerRelease("app-v", "99.0.0", ".apk") == null) { "offered an older version" }
        "android ${a.version} ${a.download}; desktop ${d.version} ${d.download}"
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
}
