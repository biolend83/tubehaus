package app.tubehaus.data

import app.tubehaus.engine.arr
import app.tubehaus.engine.at
import app.tubehaus.engine.long
import app.tubehaus.engine.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import app.tubehaus.engine.Sys
import app.tubehaus.engine.hex
import kotlin.random.Random

// Import and export, so your data is never stuck in the app. The same formats the TubeHaus
// server reads and writes: a full TubeHaus backup, Invidious' export, the CSV Google Takeout
// uses for subscriptions and OPML; on import also NewPipe, FreeTube and Takeout's history.

private val UCID = Regex("UC[\\w-]{22}")
private val VID = Regex("[\\w-]{11}")

data class Imported(
    val format: String?,
    val subs: Map<String, Sub> = emptyMap(),
    val hist: List<Meta> = emptyList(),
    val playlists: List<Pair<String, List<Meta>>> = emptyList(),
    val wl: List<Meta> = emptyList(),
    val likes: List<Meta> = emptyList(),
    val dislikes: List<String> = emptyList(),
    val pos: Map<String, List<Long>> = emptyMap(),
    /** subscription groups: name → channel ids */
    val groups: List<Pair<String, List<String>>> = emptyList(),
) {
    val empty get() = subs.isEmpty() && hist.isEmpty() && playlists.isEmpty() && wl.isEmpty() && likes.isEmpty()
}

data class ImportResult(val format: String, val subs: Int, val hist: Int, val playlists: Int, val videos: Int)

object Transfer {
    fun exportFile(lib: Library, format: String): Pair<String, ByteArray> {
        val day = Sys.today()
        return when (format) {
            "invidious" -> {
                val o = buildJsonObject {
                    put("subscriptions", buildJsonArray { lib.subs.keys.forEach { add(JsonPrimitive(it)) } })
                    put("watch_history", buildJsonArray { lib.hist.forEach { add(JsonPrimitive(it.id)) } })
                    put("preferences", JsonObject(emptyMap()))
                    put("playlists", buildJsonArray {
                        lib.playlists.values.forEach { p ->
                            add(buildJsonObject {
                                put("title", p.title); put("description", ""); put("privacy", "Private")
                                put("videos", buildJsonArray { p.items.forEach { add(JsonPrimitive(it.id)) } })
                            })
                        }
                    })
                }
                "invidious-export-$day.json" to o.toString().encodeToByteArray()
            }
            "csv" -> {
                val sb = StringBuilder("Channel Id,Channel Url,Channel Title\n")
                lib.subs.forEach { (cid, s) -> sb.append("$cid,http://www.youtube.com/channel/$cid,${csv(s.name)}\n") }
                "subscriptions-$day.csv" to sb.toString().encodeToByteArray()
            }
            "opml" -> {
                val rows = lib.subs.entries.joinToString("") { (cid, s) ->
                    val n = xml(s.name.ifEmpty { cid })
                    "\n      <outline text=\"$n\" title=\"$n\" type=\"rss\" xmlUrl=\"https://www.youtube.com/feeds/videos.xml?channel_id=$cid\"/>"
                }
                val x = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"1.1\">\n  <head><title>TubeHaus subscriptions</title></head>\n" +
                    "  <body>\n    <outline text=\"YouTube\" title=\"YouTube\">$rows\n    </outline>\n  </body>\n</opml>\n"
                "subscriptions-$day.opml" to x.encodeToByteArray()
            }
            else -> {
                val body = storeJson.encodeToJsonElement(lib) as JsonObject
                val o = JsonObject(
                    mapOf(
                        "format" to JsonPrimitive("tubehaus-backup"),
                        "version" to JsonPrimitive(1),
                        "exported" to JsonPrimitive(Sys.nowMs() / 1000),
                    ) + body,
                )
                "tubehaus-backup-$day.json" to o.toString().encodeToByteArray()
            }
        }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun meta(e: JsonElement?): Meta? {
        if (e is JsonPrimitive) return e.str?.takeIf { VID.matches(it) }?.let { Meta(it) }
        val id = e.at("id").str?.takeIf { VID.matches(it) } ?: return null
        return Meta(
            id, e.at("title").str ?: "", e.at("author").str ?: "", e.at("authorId").str ?: "",
            e.at("len").long?.toInt() ?: 0, e.at("published").long ?: 0, e.at("views").long ?: 0,
            at = e.at("at").long ?: 0,
        )
    }

    /** Recognises the file by its content; returns format = null when nothing is in it. */
    fun parse(raw: String): Imported {
        val text = raw.trimStart('﻿').trim()
        val subs = LinkedHashMap<String, Sub>()
        fun sub(cid: String?, name: String? = "", thumb: String? = "") {
            if (cid != null && UCID.matches(cid) && cid !in subs) subs[cid] = Sub((name ?: "").ifEmpty { cid }.take(200), (thumb ?: "").take(500))
        }
        val data = runCatching { storeJson.parseToJsonElement(text) }.getOrNull()
        if (data == null && text.startsWith("{") && '\n' in text) {
            // FreeTube: one JSON object (a profile) per line
            text.lineSequence().mapNotNull { runCatching { storeJson.parseToJsonElement(it) }.getOrNull() }.forEach { r ->
                r.at("subscriptions").arr.forEach { s -> sub(s.at("id").str, s.at("name").str, s.at("thumbnail").str) }
            }
            if (subs.isNotEmpty()) return Imported("freetube", subs)
        }
        if (data is JsonObject && data["format"].str == "tubehaus-backup") {
            data["subs"].let { it as? JsonObject }?.forEach { (cid, s) -> sub(cid, s.at("name").str, s.at("thumb").str) }
            val pls = (data["playlists"] as? JsonObject)?.values?.map { p ->
                (p.at("title").str ?: "").take(150) to p.at("items").arr.mapNotNull(::meta)
            } ?: emptyList()
            val pos = (data["pos"] as? JsonObject)?.mapNotNull { (k, v) ->
                val l = v.arr.mapNotNull { it.long }
                if (VID.matches(k) && l.size == 3) k to l else null
            }?.toMap() ?: emptyMap()
            return Imported(
                "tubehaus", subs,
                hist = data["hist"].arr.mapNotNull(::meta),
                playlists = pls,
                wl = data["wl"].arr.mapNotNull(::meta),
                likes = data["likes"].arr.mapNotNull(::meta),
                dislikes = data["dislikes"].arr.mapNotNull { it.str?.takeIf { s -> VID.matches(s) } },
                pos = pos,
                groups = data["groups"].arr.mapNotNull { g ->
                    val name = g.at("name").str?.trim()?.take(60) ?: return@mapNotNull null
                    name to g.at("channels").arr.mapNotNull { it.str?.takeIf { s -> UCID.matches(s) } }
                },
            )
        }
        pocketTube(data)?.let { groups ->
            groups.forEach { (_, ids) -> ids.forEach { sub(it) } }
            return Imported("pockettube", subs, groups = groups)
        }
        if (data is JsonObject && data["subscriptions"] is JsonArray) {
            val list = data["subscriptions"].arr
            val first = list.firstOrNull()
            val format = when {
                first is JsonObject && "url" in first -> "newpipe"
                first is JsonObject -> "freetube"
                else -> "invidious"
            }
            for (s in list) when (format) {
                "newpipe" -> if ((s.at("service_id").str ?: "0") == "0") UCID.find(s.at("url").str ?: "")?.let { sub(it.value, s.at("name").str) }
                "freetube" -> sub(s.at("id").str, s.at("name").str, s.at("thumbnail").str)
                else -> sub(s.str)
            }
            val t = Sys.nowMs() / 1000
            val hist = data["watch_history"].arr.mapNotNull { it.str?.takeIf { s -> VID.matches(s) } }.take(3000)
                .mapIndexed { i, id -> Meta(id, at = t - i) }
            val pls = data["playlists"].arr.mapNotNull { p ->
                val ids = p.at("videos").arr.mapNotNull { it.str?.takeIf { s -> VID.matches(s) } }
                (p.at("title").str ?: "Imported").take(150) to ids.map { Meta(it) }
            }
            return Imported(format, subs, hist, pls)
        }
        if (data is JsonArray && data.firstOrNull() is JsonObject) {
            val f = data.first() as JsonObject
            if ("titleUrl" in f || "header" in f) {
                // Google Takeout watch-history.json
                val hist = data.take(20000).mapNotNull { x ->
                    val id = Regex("[?&]v=([\\w-]{11})").find(x.at("titleUrl").str ?: "")?.groupValues?.get(1) ?: return@mapNotNull null
                    val ch = x.at("subtitles.0")
                    val at = (Sys.isoSeconds(x.at("time").str ?: "") ?: 0)
                    Meta(id, (x.at("title").str ?: "").removePrefix("Watched "), ch.at("name").str ?: "",
                        UCID.find(ch.at("url").str ?: "")?.value ?: "", at = at)
                }
                return Imported("takeout-history", hist = hist)
            }
            data.forEach { s -> sub(s.at("authorId").str ?: s.at("id").str, s.at("author").str ?: s.at("name").str) }
            return Imported("invidious-subscriptions", subs)
        }
        if ("<opml" in text.take(500).lowercase() || "<outline" in text) {
            Regex("<outline\\b[^>]*>").findAll(text).forEach { m ->
                val tag = m.value
                val cid = UCID.find(tag)?.value
                val name = Regex("""\b(?:title|text)=(?:"([^"]*)"|'([^']*)')""").find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                sub(cid, name?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&#39;", "'"))
            }
            return Imported(if (subs.isEmpty()) null else "opml", subs)
        }
        if (UCID.containsMatchIn(text)) {
            for (line in text.lines()) {
                val cells = line.split(',').map { it.trim().trim('"') }
                val cid = cells.firstOrNull { UCID.matches(it) } ?: UCID.find(line)?.value ?: continue
                val name = cells.getOrNull(2)?.takeIf { !UCID.matches(it) } ?: ""
                sub(cid, name)
            }
            return Imported("csv", subs)
        }
        return Imported(null)
    }

    /**
     * PocketTube's backup (youtube_subscription_manager_<date>.json): one key per group with its
     * channel ids; a group over 250 channels comes in several "_ysm_" pieces, joined again here.
     * Its own settings (other keys) are skipped. Null when the file isn't that.
     */
    private fun pocketTube(data: JsonElement?): List<Pair<String, List<String>>>? {
        if (data !is JsonObject || "format" in data || "subscriptions" in data) return null
        val groups = LinkedHashMap<String, MutableList<String>>()
        for ((key, value) in data) {
            if (value !is JsonArray) continue
            val ids = value.mapNotNull { v -> (v.str ?: v.at("id").str ?: v.at("channelId").str)?.takeIf { UCID.matches(it) } }
            if (ids.isEmpty()) continue
            val name = key.replace(Regex("_ysm_\\d*$"), "").replace(Regex("^_?ysm_"), "").trim().ifEmpty { key }.take(60)
            groups.getOrPut(name) { mutableListOf() }.addAll(ids)
        }
        return groups.map { (k, v) -> k to v.distinct() }.takeIf { it.isNotEmpty() }
    }

    /** Merges an import into the library without losing anything already there. */
    fun merge(store: Store, p: Imported): ImportResult {
        val t = Sys.nowMs() / 1000
        var n = ImportResult(p.format ?: "?", 0, 0, 0, 0)
        store.lib.update { l ->
            val subs = l.subs.toMutableMap()
            p.subs.forEach { (k, v) -> if (k !in subs) { subs[k] = v.copy(at = t); n = n.copy(subs = n.subs + 1) } }
            val have = l.hist.map { it.id }.toSet()
            val newHist = p.hist.filter { it.id !in have }.distinctBy { it.id }.map { if (it.at == 0L) it.copy(at = t) else it }
            n = n.copy(hist = newHist.size)
            val hist = (l.hist + newHist).sortedByDescending { it.at }.take(3000)
            fun add(a: List<Meta>, b: List<Meta>) = (a + b.filter { m -> a.none { it.id == m.id } }).take(5000)
            val pos = l.pos.toMutableMap()
            p.pos.forEach { (k, v) -> if ((pos[k]?.getOrNull(2) ?: -1) < v[2]) pos[k] = v }
            val pls = l.playlists.toMutableMap()
            val existing = pls.values.map { it.title to it.items.map { m -> m.id } }.toSet()
            for ((title, items) in p.playlists) {
                if (items.isEmpty() || (title to items.map { it.id }) in existing) continue

                pls["HP" + Random.nextBytes(8).hex()] = PlaylistData(title.ifEmpty { "Imported" }, items.take(5000), t)
                n = n.copy(playlists = n.playlists + 1, videos = n.videos + items.size)
            }
            // groups: a new name is added, one already there gets the channels it didn't have
            val groups = l.groups.toMutableList()
            for ((name, ids) in p.groups) {
                val i = groups.indexOfFirst { it.name == name }
                if (i < 0) groups += SubGroup(name, ids) else groups[i] = groups[i].copy(channels = (groups[i].channels + ids).distinct())
            }
            l.copy(
                subs = subs, hist = hist, pos = pos, playlists = pls, groups = groups,
                wl = add(l.wl, p.wl), likes = add(l.likes, p.likes),
                dislikes = (l.dislikes + p.dislikes).distinct(),
            )
        }
        return n
    }
}
