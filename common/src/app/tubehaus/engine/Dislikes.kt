package app.tubehaus.engine

import kotlinx.coroutines.CancellationException

/**
 * Return YouTube Dislike (returnyoutubedislikeapi.com): an estimate of a video's dislikes, which
 * YouTube no longer shows, from archived counts and the votes of its extension's users. It is asked
 * with the video's ID (it has no hash prefix lookup like SponsorBlock's).
 */
object Dislikes {
    data class Votes(val likes: Long, val dislikes: Long)

    private val cache = Sys.concurrentMap<String, Votes>()
    private val ID = Regex("[\\w-]{11}")

    suspend fun votes(id: String): Votes? {
        cache[id]?.let { return it }
        if (!ID.matches(id)) return null
        val d = try {
            parse(Net.get("https://returnyoutubedislikeapi.com/votes?videoId=$id"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        val v = Votes(d.at("likes").long ?: 0, d.at("dislikes").long ?: return null)
        if (cache.size < 500) cache[id] = v
        return v
    }
}
