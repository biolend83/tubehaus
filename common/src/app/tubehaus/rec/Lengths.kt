package app.tubehaus.rec

import androidx.compose.runtime.mutableStateMapOf
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The lengths the subscription feed lacks (#131): it comes from each channel's RSS, which has none. A channel's
 * Videos tab lists its recent videos with their lengths, so it is asked once, when one of that channel's videos
 * without a length is on screen, never for channels nobody scrolls to.
 */
object Lengths {
    /** video id → seconds, for the cards to read (they redraw when one arrives) */
    val known = mutableStateMapOf<String, Int>()
    private val asked = Sys.concurrentMap<String, Boolean>()
    private val gate = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Sys.IO)

    fun of(v: Video): Int = v.lengthSec.takeIf { it > 0 } ?: known[v.id] ?: 0

    /** looks up [v]'s channel when the video has no length yet (once per channel and run of the app) */
    fun want(v: Video) {
        if (v.lengthSec > 0 || v.live || v.short || v.channelId.isEmpty() || v.id in known) return
        if (asked.put(v.channelId, true) != null) return
        scope.launch {
            gate.withPermit {
                val found = runCatching { YouTube.channel(v.channelId, YouTube.Tab.VIDEOS) }.getOrNull()
                    ?.items?.items?.filterIsInstance<Video>()?.filter { it.lengthSec > 0 }?.associate { it.id to it.lengthSec }
                if (!found.isNullOrEmpty()) known.putAll(found)
            }
        }
    }
}
