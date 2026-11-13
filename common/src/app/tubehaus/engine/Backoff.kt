package app.tubehaus.engine

import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * When YouTube says "too many requests" (429) or "busy" (503): wait and ask again, longer the
 * second time and never on a fixed beat (1-2 s, then 3-6 s), and hold the other YouTube requests
 * for that long too, so a burst (a page of results, a channel's tabs) doesn't keep knocking.
 * Two retries at most: someone is waiting, and a longer block isn't something waiting fixes.
 * The Apple TV app has the same rule in Swift (tvos/Sources/Engine.swift).
 */
object Backoff {
    @Volatile private var coolUntil = 0L
    private const val RETRIES = 2
    private const val MAX_HOLD_MS = 6000L

    fun busy(code: Int) = code == 429 || code == 503

    /** waits out a pause another request was told to take */
    suspend fun cool() {
        val w = coolUntil - Sys.nowMs()
        if (w > 0) delay(minOf(w, MAX_HOLD_MS))
    }

    private suspend fun pause(attempt: Int) {
        val base = if (attempt == 0) 1000L else 3000L
        val p = base + Random.nextLong(base)
        coolUntil = maxOf(coolUntil, Sys.nowMs() + p)
        delay(p)
    }

    /** [block] throws [HttpError] on a non-2xx answer (Net.get/post) */
    suspend fun <T> retry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            cool()
            try {
                return block()
            } catch (e: HttpError) {
                if (!busy(e.code) || attempt == RETRIES) throw e
                pause(attempt++)
            }
        }
    }

    /** [block] gives the HTTP status with the answer (Net.raw) */
    suspend fun <T> retryStatus(block: suspend () -> Pair<Int, T>): Pair<Int, T> {
        var attempt = 0
        while (true) {
            cool()
            val r = block()
            if (!busy(r.first) || attempt == RETRIES) return r
            pause(attempt++)
        }
    }
}
