package app.tubehaus.ui

import app.tubehaus.engine.Details
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Sys
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The player's way to a video's streams, quicker:
 * - the next video is asked for before it is needed (near the end of this one, during the "Up
 *   next" countdown), so it starts at once;
 * - a request already on its way is joined rather than sent again;
 * - a video just watched, opened again (back from the mini player, replayed from history), uses
 *   the answer it already has while its links are still good.
 * Used from the main thread only (app.scope), so its maps need no lock.
 */
object Streams {
    private const val KEEP = 12
    private val done = LinkedHashMap<String, Stream>()
    private val going = HashMap<String, Deferred<Stream>>()

    private fun fresh(s: Stream) = s.expiresAt - Sys.nowMs() / 1000 > 15 * 60

    private fun cached(id: String): Stream? = run {
        done[id]?.let { if (fresh(it)) it else { done.remove(id); null } }
    }

    private fun keep(id: String, s: Stream) = run {
        done.remove(id); done[id] = s
        while (done.size > KEEP) done.remove(done.keys.first())
    }

    private fun request(scope: CoroutineScope, id: String): Deferred<Stream> = run {
        going[id]?.takeIf { it.isActive }?.let { return it }
        scope.async {
            try { YouTube.stream(id).also { keep(id, it) } }
            finally { going.remove(id) }
        }.also { going[id] = it }
    }

    /** the streams of [id]: kept, already on the way, or asked for now */
    suspend fun get(scope: CoroutineScope, id: String): Stream {
        cached(id)?.let { return it }
        return request(scope, id).await()
    }

    /** asks for [id] now, for later; does nothing when it is kept or on its way already */
    fun prefetch(scope: CoroutineScope, id: String) {
        if (cached(id) != null) return
        val d = request(scope, id)
        d.invokeOnCompletion { }   // a failure here only means the real open asks again
    }

    /** a link stopped working (403 and the like): ask afresh next time */
    fun forget(id: String) { done.remove(id) }
}

/**
 * The same for what shows under a video (title, likes, description, related videos): YouTube takes
 * about half a second for it, more than for the streams. It is asked for together with the streams
 * when a card is pressed, and kept for 30 minutes, so the page is filled when it opens.
 */
object Infos {
    private const val KEEP = 30
    private val done = LinkedHashMap<String, Pair<Long, Details>>()
    private val going = HashMap<String, Deferred<Details>>()

    private fun request(scope: CoroutineScope, id: String): Deferred<Details> = run {
        going[id]?.takeIf { it.isActive }?.let { return it }
        scope.async {
            try {
                YouTube.details(id).also { d ->
                    done.remove(id); done[id] = Sys.nowMs() to d
                    while (done.size > KEEP) done.remove(done.keys.first())
                }
            } finally { going.remove(id) }
        }.also { going[id] = it }
    }

    suspend fun get(scope: CoroutineScope, id: String): Details {
        done[id]?.let { (at, d) -> if (Sys.nowMs() - at < 30 * 60_000L) return d else done.remove(id) }
        return request(scope, id).await()
    }

    fun prefetch(scope: CoroutineScope, id: String) {
        val kept = done[id]
        if (kept != null && Sys.nowMs() - kept.first < 30 * 60_000L) return
        request(scope, id).invokeOnCompletion { }
    }
}
