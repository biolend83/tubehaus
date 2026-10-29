package app.tubehaus.data

import app.tubehaus.engine.HttpError
import app.tubehaus.engine.Net
import app.tubehaus.engine.at
import app.tubehaus.engine.bool
import app.tubehaus.engine.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.tubehaus.engine.Sys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Optional: keeps the library in step with a TubeHaus server (the self-hosted web version), so
 * the phone, the TV, the computer and the web share subscriptions, history and playlists.
 *
 * The server is the source of truth. A change made here goes to it within seconds (and waits in
 * a queue while offline, see [Store.queue]); the server's library is fetched each time the app
 * opens and every five minutes, which also brings over what other devices changed or removed.
 * Nothing here runs unless the user connects a server in Settings.
 */
class ServerSync(private val store: Store, private val scope: CoroutineScope) {
    /** what Settings shows: null while not connected, "" when the last sync worked, else why it didn't */
    val problem = MutableStateFlow<String?>(null)
    /** when the server's library last arrived, epoch ms */
    val syncedAt = MutableStateFlow(0L)

    private val mutex = Mutex()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    val connected get() = store.settings.value.server.isNotEmpty()

    init {
        store.onQueued = { kick.trySend(Unit) }
        scope.launch {
            while (true) {
                // a change: send it a little later, so a burst (a playing video saves its position
                // every few seconds) goes together; with nothing to send, look at the server every 5 minutes
                val changed = withTimeoutOrNull(300_000) { kick.receive(); delay(15_000) } != null
                if (connected) runCatching { sync(pull = !changed) }
            }
        }
    }

    /** now, in the background: when the app comes to the front, after an import, "Sync now" */
    fun refreshSoon() {
        if (connected) scope.launch { runCatching { sync() } }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun basic(userPass: String) = Base64.encode(userPass.encodeToByteArray())

    private fun headers(s: Settings = store.settings.value): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (s.serverUser.isNotEmpty()) {
            put("Authorization", "Basic " + basic("${s.serverUser}:${s.serverPass}"))
        }
    }

    /** sends the changes that are waiting, then (if [pull]) takes the server's library */
    suspend fun sync(pull: Boolean = true) = mutex.withLock {
        val base = store.settings.value.server.ifEmpty { return@withLock }
        try {
            flush(base)
            if (pull) pull(base)
            problem.value = ""
        } catch (e: Exception) {
            problem.value = reason(e)
            throw e
        }
    }

    private suspend fun flush(base: String) {
        while (true) {
            val op = store.queue.value.firstOrNull() ?: return
            val answer = try {
                Net.post("$base/api/p${op.path}", op.body.toString(), headers())
            } catch (e: HttpError) {
                // the server refused this one change (a playlist deleted elsewhere, an old server without
                // that endpoint): drop it rather than block everything after it; a login or server problem stops here
                if (e.code !in 400..499 || e.code == 401 || e.code == 403 || e.code == 429) throw e
                null
            }
            if (op.path == "/yt/pl/new" && answer != null) {
                // older servers choose the playlist ID themselves: follow theirs
                val mine = op.body["id"].str
                val theirs = runCatching { storeJson.parseToJsonElement(answer).at("id").str }.getOrNull()
                if (mine != null && theirs != null && theirs != mine) store.renamePlaylistId(mine, theirs)
            }
            store.queue.update { q -> q - op }
        }
    }

    private suspend fun pull(base: String) {
        val text = Net.get("$base/api/p/yt/export?format=tubehaus", headers())
        val lib = storeJson.decodeFromString(Library.serializer(), text)
        // a change made while it was on its way goes first; the next sync brings it back
        if (store.queue.value.isEmpty()) store.lib.update { lib }
        syncedAt.value = Sys.nowMs()
    }

    /** the whole library to the server, which merges it with its own (adds, never removes) */
    private suspend fun pushAll(base: String, s: Settings) {
        val l = store.lib.value
        if (l.subs.isEmpty() && l.hist.isEmpty() && l.wl.isEmpty() && l.likes.isEmpty() && l.playlists.isEmpty()) return
        val text = String(Transfer.exportFile(l, "tubehaus").second)
        val body = buildJsonObject { put("text", text) }
        try {
            Net.post("$base/api/p/yt/import", body.toString(), headers(s))
        } catch (e: HttpError) {
            if (e.code != 400) throw e     // 400: nothing the server didn't have already
        }
    }

    /** after importing a file here: send it all over, then take the merged library back */
    fun importedLocally() {
        if (!connected) return
        scope.launch {
            runCatching {
                mutex.withLock { pushAll(store.settings.value.server, store.settings.value) }
                sync()
            }
        }
    }

    /**
     * Checks the server, merges this device's library into it, then takes the merged library.
     * Returns null when it worked, or a short reason: "address", "login", "public", "notTubeHaus", "network".
     */
    suspend fun connect(address: String, user: String, pass: String): String? {
        val base = normalize(address) ?: return "address"
        val s = store.settings.value.copy(server = base, serverUser = user.trim(), serverPass = pass)
        val health = try {
            Net.get("$base/api/p/health", headers(s))
        } catch (e: Exception) {
            return reason(e)
        }
        val h = runCatching { storeJson.parseToJsonElement(health) as JsonObject }.getOrNull()
            // a web page instead of JSON: not TubeHaus, or a login page the app can't fill in
            ?: return "notTubeHaus"
        if (h["ok"] != JsonPrimitive(true)) return "notTubeHaus"
        // public instances keep libraries in each visitor's browser, not on the server
        if (h.at("public").bool) return "public"
        return mutex.withLock {
            try {
                pushAll(base, s)
                store.queue.update { emptyList() }
                store.settings.update { s }
                pull(base)
                problem.value = ""
                null
            } catch (e: Exception) {
                reason(e)
            }
        }
    }

    /** this device keeps its copy of the library and goes back to being on its own */
    fun disconnect() {
        store.settings.update { it.copy(server = "", serverUser = "", serverPass = "") }
        store.queue.update { emptyList() }
        problem.value = null
        syncedAt.value = 0
    }

    private fun reason(e: Exception) = when {
        e is HttpError && (e.code == 401 || e.code == 403) -> "login"
        e is HttpError && e.code == 404 -> "notTubeHaus"
        e is kotlinx.serialization.SerializationException -> "notTubeHaus"
        else -> "network"
    }

    companion object {
        /** "tube.example.com" or "https://tube.example.com/" → "https://tube.example.com"; null when it isn't an address */
        fun normalize(raw: String): String? {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty() || ' ' in u) return null
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.takeIf { Regex("^https?://[^/?#:]+(:[0-9]+)?([/?#].*)?$").matches(it) }
        }
    }
}
