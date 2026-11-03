package app.tubehaus.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.tubehaus.engine.Net
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.L
import app.tubehaus.ui.tr
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Android Auto: the lists the car shows (Home, Subscriptions, History, Library) and search, by
 * typing or by voice. A video picked in the car plays through the app's own Playback, as a tap on
 * the phone does, with the rest of that list after it. While a car is connected only the sound is loaded.
 */
@OptIn(UnstableApi::class)
class CarLibrary(private val ctx: Context) : MediaLibrarySession.Callback {
    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val SUBS = "subs"
        const val HISTORY = "history"
        const val LIBRARY = "library"
        const val WL = "wl"
        const val LIKES = "likes"
        const val OFFLINE = "offline"
        const val SEARCH = "search"
        const val PL = "pl:"
        /** the most a list shows (and a queue takes) */
        const val MAX = 100
    }

    private val app get() = ctx.tube.state

    /** the last list shown for each folder: a picked video plays on with the ones after it */
    val shown = ConcurrentHashMap<String, List<Video>>()
    private var cars = 0

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        if (isCar(session, controller) && cars++ == 0) ctx.tube.platform.androidPlayer.audioOnly(true)
        return super.onConnect(session, controller)
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (isCar(session, controller) && cars > 0 && --cars == 0) ctx.tube.platform.androidPlayer.audioOnly(false)
    }

    private fun isCar(s: MediaSession, c: MediaSession.ControllerInfo) = s.isAutoCompanionController(c) || s.isAutomotiveController(c)

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        // the phone's "recent media" card asks for this too: only the car gets the lists
        if (params?.isRecent == true) return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        L.lang = app.store.settings.value.lang.ifEmpty { "en" }
        val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
        return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "TubeHaus"), LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
        val (parent, id) = split(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        val v = shown[parent]?.firstOrNull { it.id == id } ?: Video(id, "")
        return Futures.immediateFuture(LibraryResult.ofItem(video(parent, v), null))
    }

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val items = when (parentId) {
            ROOT -> listOf(folder(HOME, tr("Home")), folder(SUBS, tr("Subscriptions")), folder(HISTORY, tr("History")), folder(LIBRARY, tr("Library")))
            LIBRARY -> buildList {
                add(folder(WL, tr("Watch later")))
                add(folder(LIKES, tr("Liked videos")))
                add(folder(OFFLINE, tr("Downloads")))
                for ((id, p) in app.store.lib.value.playlists) add(folder(PL + id, p.title))
            }
            else -> videos(parentId).also { shown[parentId] = it }.map { video(parentId, it) }
        }
        LibraryResult.ofItemList(pageOf(items, page, pageSize), params)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        app.scope.launch {
            val found = runCatching { search(query) }.getOrDefault(emptyList())
            session.notifySearchResultChanged(browser, query, found.size, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val found = shown[SEARCH].takeIf { lastQuery == query } ?: search(query)
        LibraryResult.ofItemList(pageOf(found.map { video(SEARCH, it) }, page, pageSize), params)
    }

    private var lastQuery = ""

    private suspend fun search(q: String): List<Video> {
        val found = YouTube.search(q, YouTube.Filter.VIDEOS).items.filterIsInstance<Video>().filter { !it.short }.take(MAX)
        shown[SEARCH] = found
        lastQuery = q
        return found
    }

    /** "Hey Google, play … on TubeHaus": the search's first video plays, the rest come after it; nothing said plays Home */
    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val q = mediaItems.firstOrNull()?.requestMetadata?.searchQuery ?: return Futures.immediateFuture(mediaItems)
        return future {
            if (q.isBlank()) videos(HOME).also { shown[HOME] = it }.map { video(HOME, it) }
            else search(q).map { video(SEARCH, it) }
        }
    }

    /** Play in the car with nothing loaded: the last video you watched, where you left it */
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val hist = app.store.lib.value.hist.take(MAX).map { it.video() }
        if (hist.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("nothing watched yet"))
        shown[HISTORY] = hist
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(video(HISTORY, hist[0])), 0, C.TIME_UNSET))
    }

    private suspend fun videos(parent: String): List<Video> {
        val lib = app.store.lib.value
        val list = when {
            parent == HOME -> app.rec.home(40)
            parent == SUBS -> app.feed.cached().ifEmpty { app.feed.all() }.filter { !it.short }
            parent == HISTORY -> lib.hist.map { it.video() }
            parent == WL -> lib.wl.map { it.video() }
            parent == LIKES -> lib.likes.map { it.video() }
            parent == OFFLINE -> app.offline.saved.value.items.values.sortedByDescending { it.at }.map { it.asVideo() }
            parent.startsWith(PL) -> lib.playlists[parent.removePrefix(PL)]?.items?.map { it.video() }.orEmpty()
            else -> emptyList()
        }
        return list.take(MAX)
    }

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    /** a video in a list: its id says which list, so the ones after it can follow */
    private fun video(parent: String, v: Video) = MediaItem.Builder().setMediaId("$parent|${v.id}")
        .setUri("tubehaus://video/${v.id}")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(v.title).setArtist(v.author).setArtworkUri(ArtProvider.uri(ctx, v.id))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build(),
        ).build()

    /** a list's id and the video's id out of [video]'s media id */
    fun split(mediaId: String): Pair<String, String>? {
        val i = mediaId.lastIndexOf('|')
        if (i <= 0 || i == mediaId.length - 1) return null
        return mediaId.substring(0, i) to mediaId.substring(i + 1)
    }

    private fun pageOf(items: List<MediaItem>, page: Int, size: Int): List<MediaItem> =
        if (size <= 0 || size == Int.MAX_VALUE) items else items.drop(page * size).take(size)

    /** runs [f] on the app's scope; a failed load shows an empty list rather than an error */
    private fun <T> future(f: suspend () -> T): ListenableFuture<T> {
        val r = SettableFuture.create<T>()
        app.scope.launch { runCatching { f() }.onSuccess { r.set(it) }.onFailure { r.setException(it) } }
        return r
    }
}

/**
 * The session's player: the app's ExoPlayer, except that a video picked in the car (or by voice)
 * is opened by the app's Playback, which finds its streams; and Next plays what comes next.
 */
@OptIn(UnstableApi::class)
class CarPlayer(private val ctx: Context, exo: Player, private val lib: CarLibrary) : ForwardingPlayer(exo) {
    private val app get() = ctx.tube.state

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) = open(items, startIndex, startPositionMs)
    override fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) = open(items, 0, C.TIME_UNSET)
    override fun setMediaItems(items: List<MediaItem>) = open(items, 0, C.TIME_UNSET)
    override fun setMediaItem(item: MediaItem) = open(listOf(item), 0, C.TIME_UNSET)
    override fun setMediaItem(item: MediaItem, startPositionMs: Long) = open(listOf(item), 0, startPositionMs)
    override fun setMediaItem(item: MediaItem, resetPosition: Boolean) = open(listOf(item), 0, C.TIME_UNSET)

    private fun open(items: List<MediaItem>, index: Int, startMs: Long) {
        val picked = items.getOrNull(index.coerceAtLeast(0)) ?: return
        val (parent, id) = lib.split(picked.mediaId) ?: return
        val list = lib.shown[parent].orEmpty()
        val at = list.indexOfFirst { it.id == id }
        val v = list.getOrNull(at) ?: Video(id, picked.mediaMetadata.title?.toString() ?: "", picked.mediaMetadata.artist?.toString() ?: "")
        val queue = if (at >= 0) list.drop(at + 1).filter { !it.live } else emptyList()
        app.playback.open(v, queue, startSec = startMs.takeIf { it > 0 && it != C.TIME_UNSET }?.let { it / 1000 })
    }

    override fun seekToNext() = app.playback.skipNext()
    override fun seekToNextMediaItem() = app.playback.skipNext()
    override fun hasNextMediaItem(): Boolean = app.playback.next != null

    private fun withNext(c: Player.Commands): Player.Commands =
        if (wrappedPlayer.mediaItemCount == 0) c
        else c.buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()

    override fun getAvailableCommands(): Player.Commands = withNext(super.getAvailableCommands())
    override fun isCommandAvailable(command: Int): Boolean = withNext(super.getAvailableCommands()).contains(command)
}

/**
 * Thumbnails for the car's lists. Android Auto only loads pictures from content:// addresses, so
 * this fetches YouTube's (the 16:9 one without black bars) and hands over a copy kept in the cache.
 */
class ArtProvider : ContentProvider() {
    companion object {
        fun uri(ctx: Context, id: String): Uri = Uri.parse("content://${ctx.packageName}.art/$id.jpg")
        private val ID = Regex("[A-Za-z0-9_-]{11}")
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.removeSuffix(".jpg")?.takeIf { ID.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val dir = File(context!!.cacheDir, "art").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (f.length() == 0L) {
            val part = File(dir, "$id.${Thread.currentThread().id}.part")
            try {
                Net.client.newCall(Request.Builder().url("https://i.ytimg.com/vi/$id/mqdefault.jpg").build()).execute().use { r ->
                    if (!r.isSuccessful) throw FileNotFoundException("HTTP ${r.code}")
                    part.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                part.renameTo(f)
            } catch (e: Exception) {
                part.delete()
                throw FileNotFoundException(e.message)
            }
            // keep the newest few hundred
            dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
