package app.tubehaus.rec

import app.tubehaus.data.Meta
import app.tubehaus.data.Store
import app.tubehaus.data.Taste
import app.tubehaus.data.meta
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.tubehaus.engine.Sys
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

// The recommendation engine. It runs entirely on this device: it learns from what you watch,
// how much of it, what you like, dislike, skip and hide, and keeps a score per channel and per
// keyword (a video's tags and #hashtags). Nothing is sent anywhere; Settings shows everything
// it learned and can wipe it.
//
// Home is built from candidate pools (videos related to what you watched, new uploads from
// your subscriptions, searches for the words you like, topics you picked) ranked by those
// scores, with a few slots kept for something new so the feed doesn't collapse into one topic.

private const val DECAY = 0.98          // per day: old tastes fade
private const val CAP = 10.0
private const val SHORT_MAX = 180       // seconds: what counts as a Short among search results

private fun now() = Sys.nowMs() / 1000

object Learn {
    private val STOP = setOf("shorts", "short", "youtubeshorts", "viral", "fyp", "trending", "video", "youtube", "new", "official")

    /** Words that describe a video: its tags and the #hashtags in its title. */
    fun tokens(title: String, keywords: List<String> = emptyList()): List<String> {
        val tags = Regex("#([^\\s#]{2,40})").findAll(title).map { it.groupValues[1] }.toList()
        return (keywords.take(15) + tags).map { it.trim().lowercase().removePrefix("#") }
            .filter { it.length in 2..40 && it !in STOP }.distinct().take(20)
    }

    /** How much one viewing says you liked it: skipped < 0 < watched < finished < replayed < liked. */
    fun engagement(watched: Double, length: Double, liked: Boolean = false, disliked: Boolean = false): Double {
        val len = length.coerceAtLeast(1.0)
        val ratio = watched.coerceAtLeast(0.0) / len
        var e = when {
            watched < 2 || ratio < 0.15 -> -0.8
            ratio < 0.5 -> 0.2
            ratio < 0.85 -> 0.6
            else -> 1.0 + 0.5 * minOf(3, if (ratio >= 2) ratio.toInt() - 1 else 0)
        }
        if (liked) e += 2.0
        if (disliked) e -= 3.0
        return e
    }

    private fun decayed(t: Taste): Taste {
        val days = (now() - (if (t.decayed == 0L) now() else t.decayed)) / 86400.0
        if (days < 0.25) return if (t.decayed == 0L) t.copy(decayed = now()) else t
        val f = DECAY.pow(days)
        fun m(x: Map<String, Double>) = x.mapValues { it.value * f }.filterValues { abs(it) >= 0.05 }
        return t.copy(ch = m(t.ch), kw = m(t.kw), decayed = now())
    }

    /** Learn from one viewing. [short]: also remember it as seen so it doesn't come back. */
    fun learn(store: Store, v: Video, keywords: List<String>, watched: Double, length: Double,
              liked: Boolean = false, disliked: Boolean = false, short: Boolean = false) {
        val e = engagement(watched, length, liked, disliked)
        val toks = tokens(v.title, keywords)
        store.taste.update { t0 ->
            val t = decayed(t0)
            val ch = t.ch.toMutableMap()
            val names = t.names.toMutableMap()
            if (v.channelId.startsWith("UC")) {
                ch[v.channelId] = ((ch[v.channelId] ?: 0.0) + e).coerceIn(-CAP, CAP)
                names[v.channelId] = v.author.take(100)
            }
            val kw = t.kw.toMutableMap()
            for (k in toks) kw[k] = ((kw[k] ?: 0.0) + e * 0.5).coerceIn(-CAP, CAP)
            val seen = if (short) (t.seen + (v.id to now())).let { s ->
                if (s.size > 3000) s.entries.sortedByDescending { it.value }.take(3000).associate { it.key to it.value } else s
            } else t.seen
            fun trim(x: Map<String, Double>, keep: Int) =
                if (x.size > keep) x.entries.sortedByDescending { abs(it.value) }.take(keep).associate { it.key to it.value } else x
            val chT = trim(ch, 400)
            t.copy(ch = chT, kw = trim(kw, 600), seen = seen, names = names.filterKeys { it in chT })
        }
    }

    /** Explicit signals from buttons. */
    fun nudgeChannel(store: Store, channelId: String, name: String, by: Double) = store.taste.update { t ->
        t.copy(ch = t.ch + (channelId to ((t.ch[channelId] ?: 0.0) + by).coerceIn(-CAP, CAP)), names = t.names + (channelId to name))
    }

    fun pickTopics(store: Store, topics: List<String>) = store.taste.update { t ->
        t.copy(kw = t.kw + topics.associate { it.lowercase() to maxOf(2.0, t.kw[it.lowercase()] ?: 0.0) })
    }

    fun rememberRelated(store: Store, id: String, related: List<Video>) = store.taste.update { t ->
        val m = (t.related - id) + (id to related.filter { !it.short && !it.live }.take(20).map { it.meta() })
        t.copy(related = if (m.size > 40) m.entries.toList().takeLast(40).associate { it.key to it.value } else m)
    }

    fun notInterested(store: Store, v: Video, channel: Boolean) = store.taste.update { t ->
        if (channel) t.copy(hiddenChannels = (t.hiddenChannels + v.channelId).distinct().takeLast(500),
            ch = t.ch + (v.channelId to -CAP))
        else t.copy(hiddenVideos = (t.hiddenVideos + v.id).distinct().takeLast(2000),
            ch = if (v.channelId.isEmpty()) t.ch else t.ch + (v.channelId to ((t.ch[v.channelId] ?: 0.0) - 1.5).coerceIn(-CAP, CAP)))
    }

    fun reset(store: Store) = store.taste.update { Taste() }
}

/** New uploads of every subscription, from their RSS feeds (fast, no API quota, no account). */
class Feed(private val store: Store) {
    private val cache = Sys.concurrentMap<String, Pair<Long, List<Video>>>()
    @Volatile var updated = 0L
        private set

    suspend fun channel(cid: String, maxAge: Long = 900): List<Video> {
        cache[cid]?.let { (t, v) -> if (now() - t < maxAge) return v }
        return try {
            YouTube.feed(cid).also { cache[cid] = now() to it }
        } catch (e: Exception) {
            cache[cid]?.second ?: emptyList()
        }
    }

    suspend fun all(force: Boolean = false): List<Video> = withContext(Sys.IO) {
        val subs = store.lib.value.subs
        val gate = Semaphore(8)
        val lists = coroutineScope {
            subs.keys.map { cid -> async { gate.withPermit { channel(cid, if (force) 60 else 900) } } }.awaitAll()
        }
        updated = now()
        lists.flatten().map { v ->
            // the feed has no avatar; the subscription remembers one
            if (v.avatar.isEmpty()) v.copy(avatar = subs[v.channelId]?.thumb ?: "") else v
        }.sortedByDescending { it.publishedAt }
    }

    fun cached(): List<Video> = cache.values.flatMap { it.second }.sortedByDescending { it.publishedAt }
}

private data class Cand(val v: Video, val src: String, val tag: String? = null)

class Recommender(private val store: Store, private val feed: Feed) {
    private val pools = Sys.concurrentMap<String, Pair<Long, List<Cand>>>()
    private val rnd = Random(Sys.nowMs())

    private suspend fun pool(key: String, ttl: Long, fetch: suspend () -> List<Cand>): List<Cand> {
        pools[key]?.let { (t, v) -> if (now() - t < ttl) return v }
        val items = try { fetch() } catch (e: Exception) { pools[key]?.second ?: emptyList() }
        pools[key] = now() to items
        return items
    }

    private suspend fun search(q: String, src: String, tag: String?, shorts: Boolean): List<Cand> =
        YouTube.search(q, if (shorts) YouTube.Filter.SHORT else YouTube.Filter.VIDEOS).items
            .filterIsInstance<Video>()
            .filter { if (shorts) it.short || it.lengthSec in 1..SHORT_MAX else !it.short }
            .map { Cand(it, src, tag) }

    /** Starter topics for an empty profile. */
    val topics = listOf(
        "Music", "Gaming", "News", "Science", "Technology", "Cooking", "Travel", "Sports", "Comedy",
        "Movies", "Podcasts", "Documentaries", "Fitness", "Art", "Nature", "Cars", "Anime", "Education", "DIY", "Finance",
    )

    fun needsTopics(): Boolean = store.taste.value.kw.isEmpty() && store.lib.value.hist.isEmpty() && store.lib.value.subs.isEmpty()

    suspend fun home(n: Int = 60, skip: Set<String> = emptySet()): List<Video> = coroutineScope {
        val t = store.taste.value
        val lib = store.lib.value
        val cands = ArrayList<Cand>()
        // videos related to what you watched recently (remembered when you watched them)
        for ((_, list) in t.related) cands += list.map { Cand(it.video(), "rel") }
        // subscriptions: the last two weeks
        val subs = (feed.cached().ifEmpty { feed.all() }).filter { !it.short && now() - it.publishedAt < 14 * 86400 }
        cands += subs.map { Cand(it, "sub") }
        // the words you like
        val liked = t.kw.entries.sortedByDescending { it.value }.filter { it.value > 0.5 }.map { it.key }.take(5)
        val kwPools = liked.map { kw -> async { pool("kw:$kw", 3600) { search(kw, "kw", kw, false) } } }
        // channels you like but aren't subscribed to
        val likedCh = t.ch.entries.sortedByDescending { it.value }.filter { it.value > 1.5 && it.key !in lib.subs }.map { it.key }.take(4)
        val chPools = likedCh.map { cid -> async { pool("ch:$cid", 3600) { feed.channel(cid, 3600).filter { !it.short }.map { Cand(it, "chan") } } } }
        cands += kwPools.awaitAll().flatten() + chPools.awaitAll().flatten()
        if (cands.size < 30) {
            val seeds = (liked + topics.shuffled(rnd)).distinct().take(4)
            cands += seeds.map { q -> async { pool("seed:$q", 3600) { search(q, "pop", q, false) } } }.awaitAll().flatten()
        }
        rank(cands, n, skip, t, lib.hist.associate { it.id to it }, shorts = false)
    }

    /**
     * Discover: what you'd never be shown otherwise. Popular videos from topics you haven't
     * shown interest in, ranked without your taste (only what you've watched is left out).
     */
    suspend fun discover(n: Int = 40, skip: Set<String> = emptySet()): List<Video> = coroutineScope {
        val liked = store.taste.value.kw.keys.map { it.lowercase() }.toSet()
        val fresh = topics.filter { it.lowercase() !in liked }.shuffled(rnd).take(6)
        val cands = fresh.map { q -> async { pool("disc:$q", 3600) { search(q, "pop", q, false) } } }.awaitAll().flatten()
        rank(cands, n, skip, Taste(), store.lib.value.hist.associate { it.id to it }, shorts = false)
    }

    suspend fun shorts(n: Int = 10, skip: Set<String> = emptySet()): List<Video> = coroutineScope {
        val t = store.taste.value
        val lib = store.lib.value
        val cands = ArrayList<Cand>()
        cands += feed.cached().filter { it.short }.map { Cand(it, "sub") }
        val likedCh = t.ch.entries.sortedByDescending { it.value }.filter { it.value > 0.5 && it.key !in lib.subs }.map { it.key }.take(6)
        val likedKw = t.kw.entries.sortedByDescending { it.value }.filter { it.value > 0.5 }.map { it.key }.take(5)
        val jobs = likedCh.map { cid -> async { pool("sch:$cid", 3600) { feed.channel(cid, 3600).filter { it.short }.map { Cand(it, "chan") } } } } +
            likedKw.map { kw -> async { pool("skw:$kw", 1800) { search("$kw #shorts", "kw", kw, true) } } } +
            listOf(async { pool("spop", 1800) { search("#shorts", "pop", null, true) } })
        cands += jobs.awaitAll().flatten()
        val seen = t.seen.filterValues { now() - it < 30 * 86400 }.keys
        rank(cands.filter { it.v.id !in seen }, n, skip, t, emptyMap(), shorts = true)
    }

    private fun rank(items: List<Cand>, n: Int, skip: Set<String>, t: Taste, hist: Map<String, Meta>, shorts: Boolean): List<Video> {
        val lib = store.lib.value
        val hiddenV = t.hiddenVideos.toSet() + lib.dislikes + skip
        val hiddenC = t.hiddenChannels.toSet()
        val bonus = mapOf("sub" to 0.6, "rel" to 0.5, "chan" to 0.4, "kw" to 0.25, "pop" to 0.0)
        val best = HashMap<String, Pair<Double, Cand>>()
        for (c in items) {
            val v = c.v
            if (v.id in hiddenV || v.channelId in hiddenC) continue
            if (!shorts) {
                // already watched most of it
                val p = lib.pos[v.id]
                if (p != null && (p.getOrNull(1) ?: 0) > 0 && p[0] > (p[1] * 0.8)) continue
                if (v.id in hist && p == null) continue
            }
            val toks = Learn.tokens(v.title, listOfNotNull(c.tag))
            val kw = if (toks.isEmpty()) 0.0 else toks.sumOf { t.kw[it] ?: 0.0 } / sqrt(toks.size.toDouble())
            val fresh = v.publishedAt > 0 && now() - v.publishedAt < 3 * 86400
            val s = (t.ch[v.channelId] ?: 0.0) * 0.5 + 0.6 * kw + (bonus[c.src] ?: 0.0) +
                0.1 * log10(1.0 + v.views) / 7 + (if (fresh) 0.25 else 0.0) +
                gauss() * 0.2
            val old = best[v.id]
            // the same video from several pools: keep the best score plus a little for each extra pool
            best[v.id] = if (old == null) s to c else (maxOf(old.first, s) + 0.15) to (if (s > old.first) c else old.second)
        }
        val ranked = best.values.sortedByDescending { it.first }
        val explore = ranked.filter { it.second.src == "pop" || it.second.src == "kw" }.map { it.second }.shuffled(rnd).toMutableList()
        val out = ArrayList<Video>()
        val perCh = HashMap<String, Int>()
        val ids = HashSet<String>()
        val perChMax = if (shorts) 2 else 3
        fun take(c: Cand): Boolean {
            val v = c.v
            if (v.id in ids || (perCh[v.channelId] ?: 0) >= perChMax && v.channelId.isNotEmpty()) return false
            if ((t.ch[v.channelId] ?: 0.0) <= -3) return false       // channels you keep skipping
            out += v; ids += v.id
            perCh[v.channelId] = (perCh[v.channelId] ?: 0) + 1
            return true
        }
        for ((_, c) in ranked) {
            if (out.size >= n) break
            if (out.size % 5 == 4) {        // every 5th slot: something new
                while (explore.isNotEmpty()) if (take(explore.removeAt(explore.size - 1))) break
                if (out.size >= n) break
            }
            take(c)
        }
        return out
    }

    private fun gauss(): Double {
        // Box-Muller
        val u = rnd.nextDouble().coerceAtLeast(1e-9)
        val v = rnd.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    fun clearCache() = pools.clear()
}
