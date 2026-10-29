package app.tubehaus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.data.JsonFile
import app.tubehaus.engine.Format
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

// The downloader, Java's way (Android and the desktop): files and ranged requests through OkHttp.
// iOS plays HLS and has no downloads; its Offline is in common/ios.

/** the same format, pointing at the file: the players play it exactly as they play YouTube's */
fun SavedFile.format(f: File) = Format(
    itag = itag, url = f.toURI().toString(), mime = mime, codecs = codecs, width = width, height = height, fps = fps,
    bitrate = bitrate, contentLength = size, initRange = init?.let { it[0]..it[1] }, indexRange = index?.let { it[0]..it[1] },
    sampleRate = sampleRate, channels = channels,
)

/**
 * Videos saved on this device to watch without internet. The video and the audio are kept as
 * YouTube serves them (two files with their index), so no conversion is needed: Android's player
 * reads them through the same DASH description it uses online, the desktop's mpv opens them directly.
 */
class Offline(private val app: AppState) {
    private val dir = File(app.platform.dataDir.toString(), "offline")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val index = JsonFile(File(dir, "index.json").toOkioPath(), OfflineIndex.serializer(), OfflineIndex(), io)
    val saved: StateFlow<OfflineIndex> get() = index.state

    /** downloads under way: video id → done 0..1 */
    val progress = mutableStateMapOf<String, Float>()
    /** what the downloads under way are (for the Downloads page) */
    val pending = mutableStateMapOf<String, Video>()
    /** the quality question is open for this video */
    var asking by mutableStateOf<Video?>(null)

    private val jobs = HashMap<String, Job>()
    // four at a time, the rest wait their turn: more would only split the same connection, and many
    // parallel streams from one address is what makes YouTube ask for a sign-in
    private val slots = Semaphore(4)

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress

    /** a saved video as a stream for the player; null when it isn't saved (or its files are gone) */
    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        val vf = File(dir, s.video.file)
        val af = File(dir, s.audio.file)
        if (!vf.isFile || !af.isFile) return null
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = "", keywords = emptyList(), live = false, hls = null,
            video = listOf(s.video.format(vf)), audio = listOf(s.audio.format(af)),
            captions = emptyList(), expiresAt = Long.MAX_VALUE, client = "offline",
        )
    }

    fun start(v: Video, maxHeight: Int) {
        if (has(v.id) || downloading(v.id)) return
        progress[v.id] = 0f
        pending[v.id] = v
        app.toast(tr("Downloading: {t}").replace("{t}", v.title))
        jobs[v.id] = app.scope.launch {
            try {
                val got = slots.withPermit { withContext(Dispatchers.IO) { download(v, maxHeight) } }
                index.update { it.copy(items = it.items + (v.id to got)) }
                app.toast(tr("Saved for offline: {t}").replace("{t}", got.title))
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { File(dir, v.id).deleteRecursively() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { File(dir, v.id).deleteRecursively() }
                app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
            } finally {
                progress.remove(v.id); pending.remove(v.id); jobs.remove(v.id)
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun remove(id: String) {
        index.update { it.copy(items = it.items - id) }
        io.launch { File(dir, id).deleteRecursively() }
    }

    // ------------------------------------------------------------------ downloading

    private suspend fun download(v: Video, maxHeight: Int): SavedVideo {
        val s = YouTube.stream(v.id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        val video = pickVideo(s.video, maxHeight) ?: error(tr("This video can't be downloaded"))
        val audio = pickAudio(s.audio, video) ?: error(tr("This video can't be downloaded"))
        val folder = File(dir, v.id).apply { deleteRecursively(); mkdirs() }
        val vName = "${v.id}/video.${ext(video)}"
        val aName = "${v.id}/audio.${ext(audio)}"
        val total = (video.contentLength + audio.contentLength).coerceAtLeast(1)
        var before = 0L
        val vSize = fetch(v.id, video, File(dir, vName)) { got -> report(v.id, (before + got).toFloat() / total) }
        before = vSize
        val aSize = fetch(v.id, audio, File(dir, aName)) { got -> report(v.id, (before + got).toFloat() / total) }
        if (!folder.isDirectory) error("folder gone")
        return SavedVideo(
            id = v.id, title = s.title.ifEmpty { v.title }, author = s.author.ifEmpty { v.author },
            channelId = s.channelId.ifEmpty { v.channelId }, lengthSec = s.lengthSec.takeIf { it > 0 } ?: v.lengthSec,
            video = saved(video, vSize, vName), audio = saved(audio, aSize, aName), at = System.currentTimeMillis() / 1000,
        )
    }

    private var lastReport = 0L
    private fun report(id: String, f: Float) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 250 && f < 1f) return
        lastReport = now
        app.scope.launch { if (id in progress) progress[id] = f.coerceIn(0f, 1f) }
    }

    /**
     * Downloads one stream file in 10 MB pieces (one long request is slowed down by YouTube).
     * When the link expires or is refused part way, a fresh one is asked for and it carries on
     * where it stopped. Returns the size.
     */
    private suspend fun fetch(id: String, f: Format, out: File, progress: (Long) -> Unit): Long {
        val part = File(out.path + ".part")
        part.delete()
        var url = f.url
        var len = f.contentLength
        var have = 0L
        var renewed = 0
        FileOutputStream(part).use { sink ->
            while (len <= 0 || have < len) {
                coroutineContext.ensureActive()
                val to = if (len > 0) minOf(len - 1, have + CHUNK - 1) else have + CHUNK - 1
                val r = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=$have-$to").build()).execute()
                val code = r.code
                if (code == 403 || code == 410) {
                    r.close()
                    if (renewed++ >= 3) error("HTTP $code")
                    url = YouTube.stream(id).let { s -> (s.video + s.audio).firstOrNull { it.itag == f.itag }?.url } ?: error("HTTP $code")
                    continue
                }
                var got = 0L
                r.use {
                    if (code != 200 && code != 206) error("HTTP $code")
                    if (len <= 0) len = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: r.body?.contentLength()?.takeIf { code == 200 && it > 0 } ?: 0
                    val input = r.body?.byteStream() ?: error("empty")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        got += n
                        progress(have + got)
                    }
                }
                have += got
                // a whole file in one answer, or nothing more to come: done
                if (code == 200 || got == 0L) break
            }
        }
        if (len > 0 && have < len) error(tr("The download stopped early"))
        if (!part.renameTo(out)) { out.delete(); part.renameTo(out) }
        return have
    }

    private fun saved(f: Format, size: Long, name: String) = SavedFile(
        itag = f.itag, mime = f.mime, codecs = f.codecs, width = f.width, height = f.height, fps = f.fps, bitrate = f.bitrate,
        size = size, init = f.initRange?.let { listOf(it.first, it.last) }, index = f.indexRange?.let { listOf(it.first, it.last) },
        sampleRate = f.sampleRate, channels = f.channels, file = name,
    )

    private fun ext(f: Format) = if (f.mime.endsWith("webm")) "webm" else if (f.isVideo) "mp4" else "m4a"

    companion object {
        private const val CHUNK = 10L shl 20

        /** the highest height up to [maxHeight]; at that height H.264 first (plays everywhere), then VP9, AV1 */
        fun pickVideo(list: List<Format>, maxHeight: Int): Format? {
            val usable = list.filter { it.indexRange != null && it.height > 0 }
            val fit = usable.filter { it.height <= maxHeight }.ifEmpty { usable.sortedBy { it.height }.take(1) }
            val top = fit.maxOfOrNull { it.height } ?: return null
            val rank = mapOf("h264" to 0, "vp9" to 1, "av1" to 2)
            return fit.filter { it.height == top }
                .sortedWith(compareBy<Format>({ rank[it.codecFamily] ?: 3 }, { -it.fps }, { -it.bitrate })).firstOrNull()
        }

        /** the original-language audio, in the container that goes with the video (AAC with MP4, Opus with WebM) */
        fun pickAudio(list: List<Format>, video: Format): Format? {
            val usable = list.filter { it.indexRange != null && !it.drc }
            val main = usable.filter { it.audioDefault }.ifEmpty { usable }
            val want = if (video.mime.endsWith("webm")) "webm" else "mp4"
            return (main.filter { it.mime.endsWith(want) }.ifEmpty { main }).maxByOrNull { it.bitrate }
        }
    }
}

