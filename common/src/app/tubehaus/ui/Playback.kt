package app.tubehaus.ui

import app.tubehaus.engine.SponsorBlock
import app.tubehaus.engine.Sys
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Chapter
import app.tubehaus.engine.Storyboard
import app.tubehaus.engine.chaptersIn
import app.tubehaus.engine.Details
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.YtError
import app.tubehaus.rec.Learn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * What is playing: loads a video's streams and its watch page, resumes where you left off,
 * keeps the history and resume point up to date, learns from how much you watched and
 * plays the next one (your queue, else the top related video).
 */
class Playback(private val app: AppState) {
    companion object { const val END = -1L }

    var video by mutableStateOf<Video?>(null)
        private set
    var stream by mutableStateOf<Stream?>(null)
        private set
    var details by mutableStateOf<Details?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    /** the error is YouTube's age check, which needs a signed-in account: the player offers YouTube instead */
    var errorAge by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    /** videos to play after this one (a playlist you started) */
    var queue by mutableStateOf<List<Video>>(emptyList())
        private set
    /** "Up next" when there is no queue */
    val next: Video?
        get() = queue.firstOrNull() ?: details?.related?.filterIsInstance<Video>()?.firstOrNull { !it.live && !it.short }

    /** "Loop video": this one plays again from the start; off again for the next video */
    var loop by mutableStateOf(false)
        private set
    /** the sleep timer: when it pauses (epoch ms), [END] for the end of this video, 0 when off */
    var sleepAt by mutableStateOf(0L)
        private set
    private var sleepJob: Job? = null

    /** SponsorBlock: the parts of this video people marked (empty while switched off or unknown) */
    var segments by mutableStateOf<List<SponsorBlock.Segment>>(emptyList())
        private set
    /** the part playing now that isn't skipped by itself, or the highlight near the start: the player offers a button */
    var skippable by mutableStateOf<SponsorBlock.Segment?>(null)
        private set
    /** the part just skipped by itself: the player says so for a few seconds, with Undo */
    var justSkipped by mutableStateOf<SponsorBlock.Segment?>(null)
        private set
    private val skipped = HashSet<String>()
    private var noticeJob: Job? = null

    /** the button: past the part, or to the highlight */
    fun skipSegment() {
        val s = skippable ?: return
        skipped += s.uuid
        skippable = null
        player.seekTo(if (s.category == SponsorBlock.HIGHLIGHT) s.startMs else s.endMs)
    }

    /** back to where the skipped part began; it then plays through */
    fun undoSkip() {
        val s = justSkipped ?: return
        justSkipped = null
        player.seekTo(s.startMs)
    }

    private fun sponsorCheck(st: PlayerState) {
        val set = store.settings.value
        if (!set.sponsor || segments.isEmpty()) { skippable = null; return }
        val pos = st.positionMs
        // where parts overlap, one skipped by itself wins
        val hits = segments.filter { it.category != SponsorBlock.HIGHLIGHT && pos >= it.startMs && pos < it.endMs - 300 }
        val seg = hits.firstOrNull { it.category in set.sponsorCats && it.uuid !in skipped } ?: hits.firstOrNull()
        if (seg != null && seg.category in set.sponsorCats && seg.uuid !in skipped) {
            if (!st.playing) return
            skipped += seg.uuid
            skippable = null
            player.seekTo(seg.endMs)
            justSkipped = seg
            noticeJob?.cancel()
            noticeJob = app.scope.launch { delay(5000); justSkipped = null }
            return
        }
        // the highlight: offered in the first 20 seconds, while it is still ahead
        val hl = segments.firstOrNull { it.category == SponsorBlock.HIGHLIGHT && it.uuid !in skipped && pos < it.startMs - 2000 && pos < 20_000 }
        skippable = seg?.takeIf { it.category !in set.sponsorCats } ?: hl
    }

    fun loopVideo(on: Boolean) { loop = on; player.setLoop(on) }

    /** pause after [minutes]; [END] at the end of this video; 0 turns the timer off */
    fun sleep(minutes: Int) {
        sleepJob?.cancel()
        sleepAt = when { minutes == 0 -> 0L; minutes < 0 -> END; else -> Sys.nowMs() + minutes * 60_000L }
        if (minutes > 0) sleepJob = app.scope.launch {
            delay(minutes * 60_000L)
            player.pause(); sleepAt = 0L
            app.toast(tr("Sleep timer: paused"))
        }
    }

    /** the video's chapters: YouTube's, or the ones written in its description */
    val chapters: List<Chapter>
        get() = details?.chapters?.takeIf { it.isNotEmpty() } ?: stream?.let { s ->
            if (chapterCache.first != s.id) chapterCache = s.id to chaptersIn(s.description)
            chapterCache.second
        } ?: emptyList()
    private var chapterCache: Pair<String, List<Chapter>> = "" to emptyList()

    /** the pictures the seek bar shows while dragging */
    val storyboard: Storyboard?
        get() = stream?.let { s ->
            if (boardCache.first != s.id) boardCache = s.id to Storyboard.parse(s.storyboard)
            boardCache.second
        }
    private var boardCache: Pair<String, Storyboard?> = "" to null

    private var job: Job? = null
    private var watchedMs = 0L          // the furthest point reached, for learning
    private var learned = false
    private var endHandled = false
    private val player get() = app.platform.player
    private val store get() = app.store

    init {
        // follow the player: save the resume point every few seconds, react to the end
        app.scope.launch {
            var lastSave = 0L
            player.state.collectLatest { s ->
                val v = video ?: return@collectLatest
                if (s.videoId != v.id) return@collectLatest
                if (s.positionMs > watchedMs) watchedMs = s.positionMs
                sponsorCheck(s)
                val now = Sys.nowMs()
                if (s.playing && now - lastSave > 5000) {
                    lastSave = now
                    store.savePosition(v.copy(lengthSec = v.lengthSec.takeIf { it > 0 } ?: (s.durationMs / 1000).toInt()), s.positionMs / 1000, s.durationMs / 1000)
                }
                if (s.ended && !endHandled) { endHandled = true; onEnded() }
            }
        }
    }

    fun open(v: Video, newQueue: List<Video> = emptyList(), startSec: Long? = null) {
        finishCurrent()
        video = v
        queue = newQueue.filter { it.id != v.id }
        stream = null
        details = null
        error = null; errorAge = false
        loading = true
        watchedMs = 0
        learned = false
        endHandled = false
        loop = false
        segments = emptyList(); skippable = null; justSkipped = null; skipped.clear()
        job?.cancel()
        job = app.scope.launch {
            if (store.settings.value.sponsor) launch {
                val s = SponsorBlock.segments(v.id, store.settings.value.sponsorCats)
                if (video?.id == v.id) segments = s
            }
            launch {
                try {
                    val d = YouTube.details(v.id)
                    if (video?.id == v.id) {
                        details = d
                        // a video opened from a link or a notification knows only its id; if it then won't play,
                        // this is where its title and channel come from
                        video?.let { cur ->
                            if (cur.title.isEmpty() || cur.author.isEmpty()) video = cur.copy(
                                title = cur.title.ifEmpty { d.title }, author = cur.author.ifEmpty { d.author },
                                channelId = cur.channelId.ifEmpty { d.channelId },
                            )
                        }
                        Learn.rememberRelated(store, v.id, d.related.filterIsInstance<Video>())
                    }
                } catch (_: Exception) {}
            }
            try {
                // a saved copy plays from the device, also without internet
                val s = app.offline.stream(v.id) ?: YouTube.stream(v.id)
                if (video?.id != v.id) return@launch
                stream = s
                // the player response knows the exact title, channel and length
                video = v.copy(
                    title = s.title.ifEmpty { v.title }, author = s.author.ifEmpty { v.author },
                    channelId = s.channelId.ifEmpty { v.channelId }, lengthSec = s.lengthSec.takeIf { it > 0 } ?: v.lengthSec,
                    views = if (s.views > 0) s.views else v.views, live = s.live,
                )
                start(s, startSec ?: store.position(v.id))
            } catch (e: YtError) {
                errorAge = e.kind == YtError.Kind.AGE
                error = when (e.kind) {
                    YtError.Kind.LOGIN -> tr(BOT_CHECK)
                    YtError.Kind.AGE -> tr("This video has an age check, and YouTube only shows it to a signed-in account. You can watch it on YouTube.")
                    else -> e.message
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    private fun start(s: Stream, startSec: Long?) {
        val set = store.settings.value
        val cap = if (app.platform.metered()) set.maxHeightMobileData else set.maxHeight
        val choice = captionChoice(s)
        player.load(
            s,
            PlayOptions(
                startMs = (startSec ?: 0) * 1000,
                maxHeight = if (cap <= 0) Int.MAX_VALUE else cap,
                autoplay = true,
                speed = set.speed,
                captions = choice?.first?.takeIf { set.captions },
                captionLang = set.captionLang.ifEmpty { lang() },
                standbyCaption = choice?.first,
                standbyTranslate = choice?.second,
                volume = set.volume,
                title = s.title, artist = s.author,
                artwork = video?.thumbHigh ?: "",
            ),
        )
    }

    private fun lang() = store.settings.value.lang.ifEmpty { "en" }

    /** the track in [want], else one that YouTube can translate into it */
    /** what the captions button shows: the track in your caption language, or YouTube's translation into it */
    fun captionChoice(s: Stream? = stream): Pair<Caption, String?>? {
        val want = store.settings.value.captionLang.ifEmpty { lang() }
        val c = pickCaption(s?.captions ?: return null, want) ?: return null
        return c to want.takeIf { c.lang.substringBefore('-') != it }
    }

    fun pickCaption(list: List<Caption>, want: String): Caption? =
        list.firstOrNull { it.lang.substringBefore('-') == want && !it.auto }
            ?: list.firstOrNull { it.lang.substringBefore('-') == want }
            ?: list.firstOrNull { !it.auto } ?: list.firstOrNull()

    fun retry() { video?.let { open(it, queue) } }

    private fun onEnded() {
        val v = video ?: return
        learnNow()
        store.savePosition(v, v.lengthSec.toLong(), v.lengthSec.toLong())
        if (sleepAt == END) { sleepAt = 0L; app.toast(tr("Sleep timer: paused")); return }
        if (app.store.settings.value.calm) return       // calm mode: nothing starts by itself
        if (!app.store.settings.value.autoplay && queue.isEmpty()) return
        val n = next ?: return
        app.scope.launch {
            delay(800)
            if (video?.id == v.id) {
                open(n, queue.drop(if (queue.firstOrNull()?.id == n.id) 1 else 0))
                if (app.current is Screen.Watch) {
                    app.stack[app.stack.size - 1] = Screen.Watch(n.id)
                }
            }
        }
    }

    private fun learnNow() {
        val v = video ?: return
        if (learned || app.store.settings.value.historyOff) return
        learned = true
        val len = (v.lengthSec.takeIf { it > 0 } ?: (player.state.value.durationMs / 1000).toInt()).toDouble()
        Learn.learn(
            store, v, stream?.keywords ?: emptyList(), watchedMs / 1000.0, len,
            liked = store.inList("likes", v.id), disliked = store.inList("dislikes", v.id),
        )
    }

    private fun finishCurrent() {
        val v = video ?: return
        val s = player.state.value
        if (s.videoId == v.id && s.positionMs > 0) store.savePosition(v, s.positionMs / 1000, s.durationMs / 1000)
        if (watchedMs > 0) learnNow()
    }

    /** close the video entirely */
    fun close() {
        finishCurrent()
        job?.cancel()
        player.stop()
        video = null; stream = null; details = null; error = null; errorAge = false; queue = emptyList()
        segments = emptyList(); skippable = null; justSkipped = null
    }

    fun skipNext() {
        val n = next ?: return
        open(n, queue.drop(if (queue.firstOrNull()?.id == n.id) 1 else 0))
        if (app.current is Screen.Watch) app.stack[app.stack.size - 1] = Screen.Watch(n.id)
    }
}
