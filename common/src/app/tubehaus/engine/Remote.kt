package app.tubehaus.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/**
 * The app's one look outside YouTube, at most once a day and only when the setting is on:
 * GitHub, for two public files. Nothing about the user is sent.
 *
 * - clients.json: the InnerTube clients the player asks for streams. YouTube breaks clients
 *   by retiring their versions; with the list fetched here a fix reaches installed apps
 *   without a new release. The last good list is kept, and the built-in one is the fallback.
 * - the releases list: whether a newer version of this app is out.
 */
object Remote {
    private const val CLIENTS_URL = "https://raw.githubusercontent.com/biolend83/tubehaus/android-app/clients.json"
    private const val RELEASES_URL = "https://api.github.com/repos/biolend83/tubehaus/releases?per_page=30"
    const val DAY = 86_400_000L

    /** where the last good client list is kept; set by the app */
    @Volatile var clientsFile: okio.Path? = null

    /** the list saved by an earlier check, if any; called once at start */
    fun restoreClients() {
        runCatching { clientsFile?.readTextOrNull()?.let(::clients) }?.getOrNull()?.let { Clients.PLAYERS = it }
    }

    /** fetches the client list; keeps the current one when the file is missing or doesn't make sense */
    suspend fun refreshClients() {
        val text = runCatching { Net.get(CLIENTS_URL) }.getOrNull() ?: return
        val list = runCatching { clients(text) }.getOrNull() ?: return
        Clients.PLAYERS = list
        runCatching { clientsFile?.writeText(text) }
    }

    /**
     * {"players": [{"name": "VISIONOS", "version": "1.02", "id": 101, "userAgent": "...",
     *               "extra": {"deviceMake": "Apple", ...}}, ...]}  (in the order they are tried)
     */
    private fun clients(text: String): List<Client>? {
        val list = parse(text).at("players").arr.mapNotNull { p ->
            Client(
                name = p.at("name").str ?: return@mapNotNull null,
                version = p.at("version").str ?: return@mapNotNull null,
                id = p.at("id").int ?: return@mapNotNull null,
                userAgent = p.at("userAgent").str ?: return@mapNotNull null,
                extra = p.at("extra").obj?.mapValues { (_, v) ->
                    val prim = v as? JsonPrimitive
                    if (prim != null && !prim.isString) prim.content.toIntOrNull() ?: prim.content else v.str ?: ""
                } ?: emptyMap(),
            )
        }
        return list.takeIf { it.isNotEmpty() }
    }

    data class Release(val version: String, val page: String, val download: String?)

    /**
     * The newest release for this app newer than [current], or null. [tag] is the release
     * line: "app-v", one release per version with every platform's file (apps up to 0.3.6
     * looked for "desktop-v" on the desktop); [asset] picks the file to offer (".apk", ".msi",
     * ".dmg"), and without one the release page is offered.
     */
    suspend fun newerRelease(tag: String, current: String, asset: String?): Release? {
        val text = runCatching { Net.get(RELEASES_URL, mapOf("Accept" to "application/vnd.github+json")) }.getOrNull() ?: return null
        val best = runCatching { parse(text).arr }.getOrNull().orEmpty()
            .filter { it.at("draft").bool.not() && it.at("tag_name").str?.startsWith(tag) == true }
            .maxByOrNull { version(it.at("tag_name").str!!.removePrefix(tag)) }
            ?: return null
        val v = best.at("tag_name").str!!.removePrefix(tag)
        if (version(v) <= version(current)) return null
        val file = asset?.let { a -> best.at("assets").arr.firstOrNull { it.at("name").str?.endsWith(a) == true }?.at("browser_download_url").str }
        return Release(v, best.at("html_url").str ?: "https://github.com/biolend83/tubehaus/releases", file)
    }

    /** "0.3.10" → comparable, so 0.3.10 sorts after 0.3.9 */
    private fun version(v: String): Long =
        v.split('.').take(3).map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0 }
            .let { p -> (p.getOrElse(0) { 0 } shl 32) + (p.getOrElse(1) { 0 } shl 16) + p.getOrElse(2) { 0 } }
}
