package app.tubehaus.data

import app.tubehaus.engine.Video
import app.tubehaus.engine.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom

val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/** One JSON file kept in memory as a StateFlow and written back (atomically) shortly after each change. */
@OptIn(FlowPreview::class)
class JsonFile<T>(private val file: File, private val ser: KSerializer<T>, empty: T, scope: CoroutineScope) {
    private val flow = MutableStateFlow(load() ?: empty)
    val state: StateFlow<T> get() = flow
    val value: T get() = flow.value

    init {
        flow.drop(1).debounce(400).onEach { save(it) }.launchIn(scope)
    }

    private fun load(): T? = try {
        if (file.exists()) storeJson.decodeFromString(ser, file.readText()) else null
    } catch (e: Exception) {
        // keep the unreadable file for the user instead of overwriting it with nothing
        file.copyTo(File(file.path + ".broken-" + System.currentTimeMillis()), overwrite = true)
        null
    }

    @Synchronized
    private fun save(v: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(storeJson.encodeToString(ser, v))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun update(f: (T) -> T) = flow.update(f)

    fun flushNow() = save(flow.value)
}

private fun now() = System.currentTimeMillis() / 1000

/** A change the TubeHaus server (ServerSync) hasn't taken yet: its endpoint and body; a newer one with the same [key] replaces it. */
@Serializable
data class SyncOp(val path: String, val body: JsonObject, val key: String = "")

/** The app's data: library, learned taste and settings, each in its own file. */
class Store(dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val lib = JsonFile(File(dir, "library.json"), Library.serializer(), Library(), scope)
    val taste = JsonFile(File(dir, "taste.json"), Taste.serializer(), Taste(), scope)
    val settings = JsonFile(File(dir, "settings.json"), Settings.serializer(), Settings(), scope)
    /** changes for the connected server, kept on disk until it has them */
    val queue = JsonFile(File(dir, "sync-queue.json"), ListSerializer(SyncOp.serializer()), emptyList(), scope)
    /** set by ServerSync: something was queued */
    var onQueued: (() -> Unit)? = null

    fun flush() {
        lib.flushNow(); taste.flushNow(); settings.flushNow(); queue.flushNow()
    }

    /** with a server connected: the same change for it (the endpoints the web version uses) */
    private fun push(path: String, key: String = "", body: JsonObjectBuilder.() -> Unit) {
        if (settings.value.server.isEmpty()) return
        val op = SyncOp(path, buildJsonObject(body), key)
        queue.update { q -> (if (key.isEmpty()) q else q.filter { it.key != key }) + op }
        onQueued?.invoke()
    }

    private fun JsonObjectBuilder.video(m: Meta) = put("video", storeJson.encodeToJsonElement(Meta.serializer(), m))

    // ---------------------------------------------------------- subscriptions
    fun isSubbed(cid: String) = cid in lib.value.subs

    fun subscribe(cid: String, name: String, thumb: String, on: Boolean) {
        lib.update { l -> l.copy(subs = if (on) l.subs + (cid to Sub(name, thumb, now())) else l.subs - cid) }
        push("/yt/sub", "sub:$cid") { put("id", cid); put("on", on); put("name", name); put("thumb", thumb) }
    }

    /** fills in a channel's name and picture (imports often come without them); keeps the subscription date */
    fun updateSub(cid: String, name: String, thumb: String) = lib.update { l ->
        val s = l.subs[cid] ?: return@update l
        l.copy(subs = l.subs + (cid to s.copy(name = name.ifEmpty { s.name }, thumb = thumb.ifEmpty { s.thumb })))
    }

    fun unsubscribeAll() {
        val all = lib.value.subs.keys
        lib.update { it.copy(subs = emptyMap()) }
        for (cid in all) push("/yt/sub", "sub:$cid") { put("id", cid); put("on", false) }
    }

    // ---------------------------------------------------------- history and resume points
    fun savePosition(v: Video, seconds: Long, length: Long) {
        if (settings.value.historyOff) return
        lib.update { l ->
            val pos = (l.pos + (v.id to listOf(seconds, length, now()))).let { p ->
                if (p.size > 5000) p.entries.sortedByDescending { it.value.getOrNull(2) ?: 0 }.take(5000).associate { it.key to it.value } else p
            }
            val old = l.hist.firstOrNull { it.id == v.id }
            val m = v.meta(now()).let { if (old != null && it.title.isEmpty()) old.copy(at = now()) else it }
            l.copy(pos = pos, hist = (listOf(m) + l.hist.filter { it.id != v.id }).take(3000))
        }
        push("/yt/pos", "pos:${v.id}") {
            put("id", v.id); put("t", seconds); put("len", length)
            put("meta", storeJson.encodeToJsonElement(Meta.serializer(), v.meta()))
        }
    }

    fun position(id: String): Long? = lib.value.pos[id]?.let { p ->
        val t = p.getOrNull(0) ?: 0
        val len = p.getOrNull(1) ?: 0
        // finished (or nearly) means start again from the beginning
        if (t < 10 || (len > 0 && t > len - 15)) null else t
    }

    fun removeFromHistory(id: String) {
        lib.update { it.copy(hist = it.hist.filter { h -> h.id != id }, pos = it.pos - id) }
        push("/yt/hist/del") { put("id", id) }
    }

    fun clearHistory() {
        lib.update { it.copy(hist = emptyList(), pos = emptyMap()) }
        push("/yt/hist/del") { put("all", true) }
    }

    // ---------------------------------------------------------- lists
    fun inList(list: String, id: String): Boolean = when (list) {
        "wl" -> lib.value.wl.any { it.id == id }
        "likes" -> lib.value.likes.any { it.id == id }
        "dislikes" -> id in lib.value.dislikes
        else -> lib.value.playlists[list]?.items?.any { it.id == id } == true
    }

    fun setInList(list: String, v: Video, on: Boolean) {
        setInListHere(list, v, on)
        push("/yt/list", "list:$list:${v.id}") { put("list", list); put("on", on); video(v.meta()) }
    }

    private fun setInListHere(list: String, v: Video, on: Boolean) = lib.update { l ->
        val m = v.meta()
        when (list) {
            "wl" -> l.copy(wl = (if (on) listOf(m) else emptyList()) + l.wl.filter { it.id != v.id })
            "likes" -> l.copy(
                likes = (if (on) listOf(m) else emptyList()) + l.likes.filter { it.id != v.id },
                dislikes = if (on) l.dislikes - v.id else l.dislikes,
            )
            "dislikes" -> l.copy(
                dislikes = l.dislikes.filter { it != v.id } + (if (on) listOf(v.id) else emptyList()),
                likes = if (on) l.likes.filter { it.id != v.id } else l.likes,
            )
            else -> l.playlists[list]?.let { pl ->
                val items = pl.items.filter { it.id != v.id } + (if (on) listOf(m) else emptyList())
                l.copy(playlists = l.playlists + (list to pl.copy(items = items, updated = now())))
            } ?: l
        }
    }

    fun newPlaylist(title: String, first: Video?): String {
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = "HP" + b.joinToString("") { "%02x".format(it) }
        lib.update { it.copy(playlists = it.playlists + (id to PlaylistData(title.take(150), listOfNotNull(first?.meta()), now()))) }
        push("/yt/pl/new") { put("id", id); put("title", title.take(150)); first?.let { video(it.meta()) } }
        return id
    }

    fun renamePlaylist(id: String, title: String) {
        lib.update { l ->
            l.playlists[id]?.let { l.copy(playlists = l.playlists + (id to it.copy(title = title.take(150), updated = now()))) } ?: l
        }
        push("/yt/pl/rename", "rename:$id") { put("id", id); put("title", title.take(150)) }
    }

    fun deletePlaylist(id: String) {
        lib.update { it.copy(playlists = it.playlists - id) }
        push("/yt/pl/del") { put("id", id) }
    }

    /** an older server chose another ID for a playlist made here: use its ID, here and in what's still queued */
    fun renamePlaylistId(mine: String, theirs: String) {
        lib.update { l -> l.playlists[mine]?.let { l.copy(playlists = l.playlists - mine + (theirs to it)) } ?: l }
        queue.update { q ->
            q.map { op ->
                val b = op.body
                when {
                    b["id"].str == mine -> op.copy(body = JsonObject(b + ("id" to kotlinx.serialization.json.JsonPrimitive(theirs))))
                    b["list"].str == mine -> op.copy(body = JsonObject(b + ("list" to kotlinx.serialization.json.JsonPrimitive(theirs))))
                    else -> op
                }
            }
        }
    }

    fun movePlaylistItem(id: String, from: Int, to: Int) {
        val n = lib.value.playlists[id]?.items?.size ?: return
        if (from !in 0 until n || to !in 0 until n) return
        moveHere(id, from, to)
        push("/yt/pl/move") { put("id", id); put("from", from); put("to", to) }
    }

    private fun moveHere(id: String, from: Int, to: Int) = lib.update { l ->
        val pl = l.playlists[id] ?: return@update l
        val items = pl.items.toMutableList()
        if (from !in items.indices || to !in items.indices) return@update l
        items.add(to, items.removeAt(from))
        l.copy(playlists = l.playlists + (id to pl.copy(items = items, updated = now())))
    }
}
