package app.tubehaus.engine

/** a check of the SABR way alone (developer builds): the video, a range at the start and in the middle of a video and an audio format, a caption */
suspend fun sabrCheck(id: String, log: (String) -> Unit) {
    val t = System.currentTimeMillis()
    val s = try { YouTube.sabr(id) } catch (e: Exception) { log("sabr-check failed: $e"); return }
    log("sabr-check ${s.client} ${s.title} video=${s.video.size} audio=${s.audio.size} caps=${s.captions.size} ms=${System.currentTimeMillis() - t}")
    val c = Net.client
    for (f in listOf(s.video.first { it.height in 360..720 }, s.audio.first())) {
        for (from in listOf(0L, f.contentLength / 2)) {
            val t1 = System.currentTimeMillis()
            val r = runCatching {
                c.newCall(okhttp3.Request.Builder().url(f.url).header("Range", "bytes=$from-${from + 999_999}").build()).execute().use { it.code to it.body!!.bytes().size }
            }.getOrElse { 0 to -1 }
            log("  itag ${f.itag} from $from: ${r.first} ${r.second} bytes in ${System.currentTimeMillis() - t1} ms")
        }
    }
    s.captions.firstOrNull()?.let { cap ->
        val r = runCatching { Net.get(YouTube.captionUrl(cap)) }.getOrElse { "failed: $it" }
        log("  caption ${cap.lang}: " + r.take(100).replace("\n", " | "))
    }
}
