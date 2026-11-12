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
import app.tubehaus.data.SongMeta
import app.tubehaus.engine.Music
import app.tubehaus.engine.Song
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
        get() = if (music) (songs.getOrNull(songAt + 1) ?: songs.firstOrNull()?.takeIf { repeat == 1 })?.video() else queue.firstOrNull() ?: details?.related?.filterIsInstance<Video>()?.firstOrNull { !it.live && !it.short }

    // ------------------------------------------------------------------ music
    /** what plays came from the music tab: the cover instead of the picture, songs one after another */
    var music by mutableStateOf(false)
        private set
    /** the music queue, the songs already heard included (so Previous works); [songAt] is the one playing */
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var songAt by mutableStateOf(0)
        private set
    val song: Song? get() = if (music) songs.getOrNull(songAt) else null
    /** 0 off, 1 the whole queue, 2 this song */
    var repeat by mutableStateOf(0)
    var shuffle by mutableStateOf(false)
        private set
    /** the queue comes from a radio and grows by itself near its end */
    private var radio = false
    /** "Watch video" in music: this song with its picture */
    var withVideo by mutableStateOf(false)
        private set

    /** A-B repeat: the part between [abA] and [abB] (ms) plays over and over; -1 when not set */
    var abA by mutableStateOf(-1L)
        private set
    var abB by mutableStateOf(-1L)
        private set

    /** the transcript was asked for (the player's menu, the button under the video): the watch page opens it */
    var transcriptAsked by mutableStateOf(false)
    /** a word to search the transcript for when it opens */
    var transcriptSearch by mutableStateOf("")
    /** chapters made from the captions (ui/Smart.kt) for a video without its own, by video id */
    var autoChapters by mutableStateOf<Pair<String, List<Chapter>>>("" to emptyList())

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
        }.orEmpty().ifEmpty { autoChapters.takeIf { it.first == video?.id && store.settings.value.smart }?.second ?: emptyList() }
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
    private var refreshed = false       // the streams were asked for again once after the player failed
    private val player get() = app.player
    private val store get() = app.store

    init {
        // follow the player: save the resume point every few seconds, react to the end
        app.scope.launch {
            var lastSave = 0L
            player.state.collectLatest { s ->
                val v = video ?: return@collectLatest
                if (s.videoId != v.id) return@collectLatest
                if (s.error != null && !refreshed) { refreshed = true; refresh(v, s.positionMs); return@collectLatest }
                if (s.positionMs > watchedMs) watchedMs = s.positionMs
                sponsorCheck(s)
                if (abB > 0 && s.playing && s.positionMs >= abB) player.seekTo(abA.coerceAtLeast(0))
                val now = Sys.nowMs()
                countTime(v, s.playing, now)
                if (s.playing && now - lastSave > 5000) {
                    lastSave = now
                    store.savePosition(v.copy(lengthSec = v.lengthSec.takeIf { it > 0 } ?: (s.durationMs / 1000).toInt()), s.positionMs / 1000, s.durationMs / 1000)
                }
                if (s.ended && !endHandled) { endHandled = true; onEnded() }
            }
        }
    }

    fun open(v: Video, newQueue: List<Video> = emptyList(), startSec: Long? = null) {
        openVideo(v, newQueue, startSec, asSong = false)
    }

    private fun openVideo(v: Video, newQueue: List<Video>, startSec: Long?, asSong: Boolean) {
        finishCurrent()
        if (!asSong) { music = false; songs = emptyList(); songAt = 0; radio = false }
        withVideo = false
        abA = -1L; abB = -1L
        countedStart = false
        video = v
        // a queue made by hand (#59) keeps going when another video is opened on the way, and as it plays;
        // a playlist's list replaces it
        val mine = ownQueue && !asSong && newQueue.all { n -> queue.any { it.id == n.id } }
        queue = (if (mine && newQueue.isEmpty()) queue else newQueue).filter { it.id != v.id }
        ownQueue = mine && queue.isNotEmpty()
        stream = null
        details = null
        error = null; errorAge = false
        loading = true
        watchedMs = 0
        learned = false
        endHandled = false
        refreshed = false
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
                // kids mode: only the picked channels, and nothing once today's time is used up
                val up = app.kidsTimeUp()
                if (up || !app.kidsAllow(s.channelId.ifEmpty { v.channelId })) {
                    error = tr(if (up) KIDS_TIME_UP else "This video isn't from a channel picked for kids mode.")
                    loading = false
                    return@launch
                }
                stream = s
                // the player response knows the exact title, channel and length
                video = v.copy(
                    title = s.title.ifEmpty { v.title }, author = s.author.ifEmpty { v.author },
                    channelId = s.channelId.ifEmpty { v.channelId }, lengthSec = s.lengthSec.takeIf { it > 0 } ?: v.lengthSec,
                    views = if (s.views > 0) s.views else v.views, live = s.live,
                )
                // songs start from the top; a video where you left it
                start(s, startSec ?: if (asSong) null else store.position(v.id))
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

    /** the same video again from [posMs] on whichever player is in use now (casting started or stopped) */
    fun replay(posMs: Long, autoplay: Boolean = true) {
        val s = stream ?: return
        endHandled = false
        start(s, posMs / 1000, autoplay)
    }

    private fun start(s: Stream, startSec: Long?, autoplay: Boolean = true) {
        val set = store.settings.value
        val cap = if (app.platform.metered()) set.maxHeightMobileData else set.maxHeight
        val choice = captionChoice(s)
        player.load(
            s,
            PlayOptions(
                startMs = (startSec ?: 0) * 1000,
                maxHeight = if (cap <= 0) Int.MAX_VALUE else cap,
                autoplay = autoplay,
                speed = if (music) 1f else speedFor(video?.channelId ?: s.channelId),
                audioOnly = music && set.musicAudioOnly && !withVideo,
                codec = set.codec,
                captions = choice?.first?.takeIf { set.captions || s.client == "offline" },
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
        return c to want.takeIf { c.lang.substringBefore('-') != it && s.client != "offline" }
    }

    fun pickCaption(list: List<Caption>, want: String): Caption? =
        list.firstOrNull { it.lang.substringBefore('-') == want && !it.auto }
            ?: list.firstOrNull { it.lang.substringBefore('-') == want }
            ?: list.firstOrNull { !it.auto } ?: list.firstOrNull()

    fun retry() { video?.let { open(it, queue) } }

    /**
     * The player couldn't load the streams it was given: their addresses are tied to the network
     * they were asked from, so a device that has since moved (Wi-Fi, mobile data, a VPN) or a
     * server whose way out to YouTube changed gets them refused ("resource unavailable" on
     * iPad). Ask for them afresh once and carry on from where it stopped; only if that fails too
     * does the error stay on screen. A saved copy plays from the device and isn't asked again.
     */
    private fun refresh(v: Video, atMs: Long) {
        if (app.offline.stream(v.id) != null) return
        app.scope.launch {
            val s = try { YouTube.stream(v.id) } catch (e: Exception) { return@launch }
            if (video?.id != v.id) return@launch
            stream = s
            start(s, atMs / 1000)
        }
    }

    private fun onEnded() {
        val v = video ?: return
        learnNow()
        store.savePosition(v, v.lengthSec.toLong(), v.lengthSec.toLong())
        if (sleepAt == END) { sleepAt = 0L; app.toast(tr("Sleep timer: paused")); return }
        if (music) {
            app.scope.launch {
                delay(300)
                if (video?.id != v.id) return@launch
                when {
                    repeat == 2 -> { endHandled = false; player.seekTo(0); player.play() }
                    songAt + 1 < songs.size -> playSongAt(songAt + 1)
                    repeat == 1 && songs.isNotEmpty() -> playSongAt(0)
                }
            }
            return
        }
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
        video = null; stream = null; details = null; error = null; errorAge = false; queue = emptyList(); ownQueue = false
        segments = emptyList(); skippable = null; justSkipped = null
        music = false; songs = emptyList(); songAt = 0; radio = false; abA = -1L; abB = -1L
    }

    fun skipNext() {
        if (music) { if (songAt + 1 < songs.size) playSongAt(songAt + 1) else if (repeat == 1 && songs.isNotEmpty()) playSongAt(0); return }
        val n = next ?: return
        open(n, queue.drop(if (queue.firstOrNull()?.id == n.id) 1 else 0))
        if (app.current is Screen.Watch) app.stack[app.stack.size - 1] = Screen.Watch(n.id)
    }

    // ------------------------------------------------------------------ speed per channel
    /** the speed this channel's videos start at: the one you chose for it, else your usual speed */
    fun speedFor(channelId: String): Float {
        val set = store.settings.value
        return set.channelSpeed[channelId] ?: set.speed
    }

    /** the speed picked in the player: kept for this channel (or for everything, by Settings) */
    fun setSpeed(x: Float) {
        player.setSpeed(x)
        if (music) return
        val cid = video?.channelId.orEmpty()
        store.settings.update { set ->
            if (set.speedPerChannel && cid.isNotEmpty()) {
                set.copy(channelSpeed = if (x == set.speed) set.channelSpeed - cid else set.channelSpeed + (cid to x))
            } else set.copy(speed = x)
        }
    }

    // ------------------------------------------------------------------ A-B repeat
    /** first press: A here; second: B here, and the part repeats; third: off */
    fun abStep() {
        val pos = player.state.value.positionMs
        when {
            abB > 0 -> { abA = -1L; abB = -1L; app.toast(tr("A-B repeat off")) }
            abA >= 0 && pos > abA + 500 -> { abB = pos; app.toast(tr("Repeating {a} to {b}").replace("{a}", durationMs(abA)).replace("{b}", durationMs(abB))) }
            else -> { abA = pos; app.toast(tr("A set at {t}. Choose A-B repeat again where it should end.").replace("{t}", durationMs(pos))) }
        }
    }

    fun abOff() { abA = -1L; abB = -1L }

    // ------------------------------------------------------------------ music
    /** plays [list] from [at] as music; [radio]: when it runs low, songs like the last one are added */
    fun playSongs(list: List<Song>, at: Int = 0, radio: Boolean = false, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        var l = list
        var i = at.coerceIn(0, list.size - 1)
        if (shuffled) { val first = l[i]; l = listOf(first) + (l - first).shuffled(); i = 0 }
        music = true; songs = l; songAt = i; this.radio = radio; shuffle = shuffled
        playSongAt(i)
        // a single song: its radio follows it
        if (radio && l.size < 3) moreRadio()
    }

    /** one song, then songs like it */
    fun playRadio(s: Song) = playSongs(listOf(s), 0, radio = true)

    fun playSongAt(i: Int) {
        val s = songs.getOrNull(i) ?: return
        songAt = i
        music = true
        store.rememberSong(s.meta())
        openVideo(s.video(), emptyList(), null, asSong = true)
        if (radio && songs.size - i <= 3) moreRadio()
    }

    fun previous() {
        if (!music) { player.seekTo(0); return }
        // as music players do: a song more than a few seconds in starts over, else the one before
        if (player.state.value.positionMs > 4000 || songAt == 0) player.seekTo(0) else playSongAt(songAt - 1)
    }

    fun toggleShuffle() {
        if (!music) return
        shuffle = !shuffle
        if (shuffle) {
            val head = songs.take(songAt + 1)
            songs = head + songs.drop(songAt + 1).shuffled()
        }
    }

    /** the queue's songs after this one, to reorder or remove (the Up next list) */
    fun removeSong(i: Int) {
        if (i == songAt || i !in songs.indices) return
        songs = songs.toMutableList().also { it.removeAt(i) }
        if (i < songAt) songAt--
    }

    /** "Play next" on a song: right after the one playing */
    fun playNext(s: Song) {
        if (!music || songs.isEmpty()) { playSongs(listOf(s)); return }
        val rest = songs.filterIndexed { j, x -> j <= songAt || x.id != s.id }
        songs = rest.take(songAt + 1) + s + rest.drop(songAt + 1)
    }

    /** the queue was made by hand with Play next / Add to queue (#59): it stays until it has played or playback stops */
    var ownQueue by mutableStateOf(false)

    /** a video's ⋮ menu: right after the one playing (nothing playing: it plays now) */
    fun queueNext(v: Video) {
        if (video == null || music) { app.watch(v); return }
        // the rest of the list this video was opened from is not a queue you made: yours starts here (#66)
        val mine = if (ownQueue) queue else emptyList()
        queue = listOf(v) + mine.filter { it.id != v.id }
        ownQueue = true
    }

    fun queueAdd(v: Video) {
        if (video == null || music) { app.watch(v); return }
        val mine = if (ownQueue) queue else emptyList()
        queue = if (mine.any { it.id == v.id }) mine else mine + v
        ownQueue = true
    }

    fun unqueue(v: Video) {
        queue = queue.filter { it.id != v.id }
        if (queue.isEmpty()) ownQueue = false
    }

    fun addToQueue(s: Song) {
        if (!music || songs.isEmpty()) { playSongs(listOf(s)); return }
        if (songs.drop(songAt + 1).none { it.id == s.id }) songs = songs + s
    }

    private var radioJob: Job? = null
    private fun moreRadio() {
        if (radioJob?.isActive == true) return
        val seed = songs.lastOrNull() ?: return
        radioJob = app.scope.launch {
            val more = runCatching { Music.radio(seed.id) }.getOrDefault(emptyList())
            val have = songs.map { it.id }.toHashSet()
            val add = more.filter { it.id !in have }
            if (add.isNotEmpty() && music) songs = songs + add
        }
    }

    /** music: the same song with its picture ("Watch video"), from where it is */
    fun showVideo() {
        if (!music || withVideo) return
        withVideo = true
        replay(player.state.value.positionMs)
    }

    // ------------------------------------------------------------------ watch time
    private var countedStart = false
    private var lastTick = 0L
    private var pending = 0L
    /** time played without a pause longer than 5 minutes, for the break reminder */
    private var streakMs = 0L
    private var lastPlaying = 0L

    private fun countTime(v: Video, playing: Boolean, now: Long) {
        if (!playing) { lastTick = 0L; return }
        if (!countedStart) { countedStart = true; store.addWatch(v, 0, music, started = true) }
        if (lastPlaying > 0 && now - lastPlaying > 5 * 60_000L) streakMs = 0
        lastPlaying = now
        val step = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0, 3000)
        lastTick = now
        pending += step
        streakMs += step
        if (pending >= 30_000) { store.addWatch(v, pending / 1000, music); pending %= 1000; checkReminders() }
    }

    /** the reminders of Settings → Wellbeing: a break after so long in a row, a daily amount */
    private fun checkReminders() {
        val set = store.settings.value
        if (app.kidsTimeUp()) { player.pause(); app.reminder = tr(KIDS_TIME_UP); return }
        if (set.breakMinutes > 0 && streakMs >= set.breakMinutes * 60_000L) {
            streakMs = 0
            if (!music) player.pause()
            app.reminder = tr("You've been watching for {n} minutes. Time for a short break?").replace("{n}", "${set.breakMinutes}")
        }
        if (set.dailyMinutes > 0) {
            val today = Sys.today()
            val st = store.stats.value
            val d = st.days[today]
            if (st.remindedOn != today && d != null && (d.video + d.music) >= set.dailyMinutes * 60L) {
                store.stats.update { it.copy(remindedOn = today) }
                app.reminder = tr("You've reached today's {n} minutes.").replace("{n}", "${set.dailyMinutes}")
            }
        }
    }
}

fun Song.meta() = SongMeta(id, title, artist, artistId, album, albumId, lengthSec, art)
fun SongMeta.song() = Song(id, title, artist, artistId, album, albumId, len, art)
