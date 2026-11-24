package app.tubehaus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.data.JsonFile
import app.tubehaus.engine.Net
import app.tubehaus.engine.ServerVideo
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetDownloadDelegateProtocol
import platform.AVFoundation.AVAssetDownloadTask
import platform.AVFoundation.AVAssetDownloadTaskMinimumRequiredMediaBitrateKey
import platform.AVFoundation.AVAssetDownloadURLSession
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRange
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSValue
import platform.AVFoundation.CMTimeRangeValue
import platform.darwin.NSObject

/**
 * Videos saved on the iPad to watch without internet. iOS plays YouTube's HLS stream, so it is
 * saved the way Apple does it (AVAssetDownloadURLSession: a .movpkg that AVPlayer opens like the
 * stream itself), in the quality picked; Downloads and the index are the same as elsewhere.
 * The same members as the JVM Offline (common/jvm), so the shared screens use it unchanged.
 */
class Offline(private val app: AppState) {
    private val io = CoroutineScope(SupervisorJob() + Sys.IO)
    private val index = JsonFile(app.platform.dataDir / "offline-ios.json", OfflineIndex.serializer(), OfflineIndex(), io)
    val saved: StateFlow<OfflineIndex> get() = index.state

    val progress = mutableStateMapOf<String, Float>()
    val pending = mutableStateMapOf<String, Video>()
    var asking by mutableStateOf<Video?>(null)
    /** the iPad saves Apple's HLS package, which has its own tracks: no audio track or captions to pick */
    val pickTracks = false

    /** the desktop can keep downloads in a folder of its choosing; iOS keeps them where Apple puts them */
    val folder: String get() = ""
    suspend fun moveTo(path: String): Boolean = false

    private val tasks = HashMap<String, AVAssetDownloadTask>()
    /** where each finished task put its .movpkg (relative to the app's home folder) */
    private val places = HashMap<String, String>()
    private val heights = HashMap<String, Int>()
    /** for a second try from lists made here (OfflineBridge): the stream and the height asked for */
    private val streams = HashMap<String, Stream>()
    private val asked = HashMap<String, Int>()
    private val bridged = HashSet<String>()
    /** what answers a second try's list requests: kept here while it runs, as AVFoundation holds it weakly */
    private val loaders = HashMap<String, Any>()

    private val delegate = Delegate(this)
    private val session: AVAssetDownloadURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("io.github.biolend83.tubehaus.downloads")
        AVAssetDownloadURLSession.sessionWithConfiguration(config, assetDownloadDelegate = delegate, delegateQueue = NSOperationQueue.mainQueue)
    }

    init {
        // downloads left from an earlier run have lost their titles: drop them
        session.getAllTasksWithCompletionHandler { list -> list?.forEach { (it as? NSURLSessionTask)?.cancel() } }
    }

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress

    private fun absolute(rel: String) = NSHomeDirectory() + "/" + rel

    /** a saved video as a stream: the .movpkg in place of YouTube's HLS address */
    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        val path = absolute(s.video.file)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = "", keywords = emptyList(), live = false, hls = NSURL.fileURLWithPath(path).absoluteString,
            video = emptyList(), audio = emptyList(), captions = emptyList(), expiresAt = Long.MAX_VALUE, client = "offline",
        )
    }

    fun start(v: Video, maxHeight: Int, track: String? = null, caption: app.tubehaus.engine.Caption? = null) {
        if (has(v.id) || downloading(v.id)) return
        progress[v.id] = 0f
        pending[v.id] = v
        app.toast(tr("Downloading: {t}").replace("{t}", v.title))
        app.scope.launch {
            try {
                val s = YouTube.stream(v.id)
                if (s.live) error(tr("Live streams can't be downloaded"))
                val hls = s.hls ?: error(tr("This video can't be downloaded"))
                val pick = variant(Net.get(hls), maxHeight)
                val asset = AVURLAsset(uRL = NSURL(string = hls), options = ServerVideo.headersFor(hls)?.let { mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to it) })
                streams[v.id] = s
                asked[v.id] = maxHeight
                heights[v.id] = pick?.first ?: 0
                pending[v.id] = Video(v.id, s.title.ifEmpty { v.title }, s.author.ifEmpty { v.author }, s.channelId.ifEmpty { v.channelId },
                    s.lengthSec.takeIf { it > 0 } ?: v.lengthSec)
                startTask(v.id, asset, s.title.ifEmpty { v.title }, pick?.second)
            } catch (e: Exception) {
                finish(v.id)
                app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
            }
        }
    }

    /** the variant for [maxHeight] in a master list, as (height, bitrate): its bitrate is the floor AVFoundation saves at */
    private fun variant(master: String, maxHeight: Int): Pair<Int, Long>? {
        val variants = Regex("BANDWIDTH=(\\d+)[^\\n]*RESOLUTION=\\d+x(\\d+)").findAll(master)
            .map { it.groupValues[2].toInt() to it.groupValues[1].toLong() }.toList()
        return variants.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: variants.minByOrNull { it.first }
    }

    private fun startTask(id: String, asset: AVURLAsset, title: String, minBitrate: Long?) {
        val options = minBitrate?.let { mapOf<Any?, Any>(AVAssetDownloadTaskMinimumRequiredMediaBitrateKey to NSNumber(longLong = it)) }
        val task = session.assetDownloadTaskWithURLAsset(asset, assetTitle = title, assetArtworkData = null, options = options)
            ?: error(tr("This video can't be downloaded"))
        task.taskDescription = id
        tasks[id] = task
        task.resume()
    }

    /**
     * The same video once more, from lists made here from its MP4 files (OfflineBridge). If none can
     * be made, the first error is the one shown.
     */
    private fun retryBridged(id: String, v: Video, first: NSError) {
        bridged += id
        tasks.remove(id)
        progress[id] = 0f
        val s = streams[id]
        app.scope.launch {
            val made = try {
                s?.let { OfflineBridge.make?.invoke(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Offline: no lists of our own for $id: ${e.message}")
                null
            }
            // cancelled while the lists were made (no task to cancel, so it finished): nothing to start
            if (!downloading(id)) return@launch
            if (made == null) {
                finish(id)
                app.toast(tr("Couldn't download: {e}").replace("{e}", first.localizedDescription))
                return@launch
            }
            loaders[id] = made.loader
            val pick = variant(made.master, asked[id] ?: Int.MAX_VALUE)
            heights[id] = pick?.first ?: 0
            try {
                startTask(id, made.asset, v.title, pick?.second)
            } catch (e: Exception) {
                finish(id)
                app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
            }
        }
    }

    fun cancel(id: String) {
        tasks[id]?.cancel() ?: finish(id)
    }

    /** one file of picture and sound: the desktop does it; iOS keeps them apart (it plays them as HLS) */
    fun export(id: String, done: (String?) -> Unit) = done(null)

    fun remove(id: String) {
        val s = index.value.items[id] ?: return
        index.update { it.copy(items = it.items - id) }
        NSFileManager.defaultManager.removeItemAtPath(absolute(s.video.file), error = null)
    }

    private fun finish(id: String) {
        progress.remove(id); pending.remove(id); tasks.remove(id); places.remove(id); heights.remove(id)
        streams.remove(id); asked.remove(id); bridged.remove(id); loaders.remove(id)
    }

    // ---------------------------------------------------------------- from the delegate (main queue)

    internal fun loaded(id: String, f: Double) {
        if (id in progress) progress[id] = f.toFloat().coerceIn(0f, 1f)
    }

    internal fun placed(id: String, location: NSURL) {
        location.relativePath?.let { places[id] = it }
    }

    internal fun done(id: String, error: NSError?) {
        val v = pending[id]
        val rel = places[id]
        // YouTube's list failed (on some fresh uploads its H.264 and audio lists answer 404, while the
        // MP4 files are fine): once more from lists made here. Cancelled (-999) ends as before.
        if (error != null && error.code != -999L && v != null && id !in bridged && OfflineBridge.make != null) {
            rel?.let { NSFileManager.defaultManager.removeItemAtPath(absolute(it), error = null) }
            places.remove(id)
            return retryBridged(id, v, error)
        }
        if (error == null && v != null && rel != null) {
            val size = folderSize(absolute(rel))
            val file = SavedFile(itag = 0, mime = "application/x-mpegURL", codecs = "", height = heights[id] ?: 0, size = size, file = rel)
            index.update {
                it.copy(items = it.items + (id to SavedVideo(id, v.title, v.author, v.channelId, v.lengthSec, file, file.copy(size = 0, file = ""),
                    Sys.nowMs() / 1000)))
            }
            app.toast(tr("Saved for offline: {t}").replace("{t}", v.title))
        } else if (error != null && error.code != -999L) {      // -999: cancelled
            rel?.let { NSFileManager.defaultManager.removeItemAtPath(absolute(it), error = null) }
            app.toast(tr("Couldn't download: {e}").replace("{e}", error.localizedDescription))
        }
        finish(id)
    }

    private fun folderSize(path: String): Long {
        val fm = NSFileManager.defaultManager
        val subs = fm.subpathsOfDirectoryAtPath(path, error = null) ?: return 0
        return subs.sumOf { sub -> ((fm.attributesOfItemAtPath("$path/$sub", error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L) }
    }
}

/** AVFoundation's callbacks for the download session; the task's description is the video id */
private class Delegate(private val offline: Offline) : NSObject(), AVAssetDownloadDelegateProtocol {
    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
        didLoadTimeRange: CValue<CMTimeRange>, totalTimeRangesLoaded: List<*>, timeRangeExpectedToLoad: CValue<CMTimeRange>,
    ) {
        val id = assetDownloadTask.taskDescription ?: return
        val expected = timeRangeExpectedToLoad.useContents { CMTimeGetSeconds(duration.readValue()) }
        if (!(expected > 0)) return
        val loaded = totalTimeRangesLoaded.sumOf { r ->
            (r as? NSValue)?.CMTimeRangeValue?.useContents { CMTimeGetSeconds(duration.readValue()) } ?: 0.0
        }
        offline.loaded(id, loaded / expected)
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingToURL: NSURL) {
        assetDownloadTask.taskDescription?.let { offline.placed(it, didFinishDownloadingToURL) }
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        task.taskDescription?.let { offline.done(it, didCompleteWithError) }
    }
}

/**
 * Lists made on the device for a download whose YouTube HLS list failed: set by the app
 * (IosPlatform), which has HlsBridge. Null, or a null result: no second try.
 */
object OfflineBridge {
    var make: (suspend (Stream) -> Made?)? = null

    /** the asset to download, its master list, and what answers its list requests (kept while the download runs) */
    class Made(val asset: AVURLAsset, val master: String, val loader: Any)
}
