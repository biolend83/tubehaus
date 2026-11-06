package app.tubehaus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import app.tubehaus.rec.Learn
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** a video left for another page (its channel) is paused, and plays on when you come back to it */
private object TvWatchState { var pausedByLeaving = false }

/**
 * The player on a TV, filling the screen and answering the remote as TV apps do:
 * - nothing on the picture while it plays; any key brings the controls back
 * - OK pauses and plays; left and right jump (held, faster and further) with the picture at that point
 * - on the controls: the time bar on top, the buttons under it, and down again the videos up next
 * - Back closes the open panel, then the controls, then the video
 */
@Composable
fun TvWatch(app: AppState) {
    val pb = app.playback
    val player = app.player
    val st by player.state.collectAsState()
    val v = pb.video
    val lib by app.store.lib.state.collectAsState()

    var shown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var nextOpen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var scrubPoke by remember { mutableIntStateOf(0) }
    var scrubAt by remember { mutableLongStateOf(0L) }
    var scrubDir by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var eatBack by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var seekFocused by remember { mutableStateOf(false) }
    var settingsFocused by remember { mutableStateOf(false) }
    var nextFocused by remember { mutableStateOf(false) }
    val backFocus = remember { FocusRequester() }
    var backFocused by remember { mutableStateOf(false) }
    // the page of the settings panel: "" its list, or quality, speed, captions...
    val setPage = remember { mutableStateOf("") }
    // where the focus should go once the controls are drawn: 1 the time bar, 2 settings, 3 up next
    var want by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        if (TvWatchState.pausedByLeaving) { TvWatchState.pausedByLeaving = false; if (pb.video != null && !player.state.value.ended) player.play() }
        onDispose {
            // back to the pages: the video stops (the TV has no small player); to its channel: it waits
            if (app.stack.none { it is Screen.Watch }) pb.close()
            else { player.pause(); TvWatchState.pausedByLeaving = true }
        }
    }
    // the controls fade away after a while of playing untouched
    LaunchedEffect(shown, poke, st.playing, settings, scrub, nextOpen) {
        if (shown && st.playing && !settings && scrub == null && !nextOpen) {
            delay(5000)
            shown = false
        }
    }
    LaunchedEffect(shown) { if (!shown) { nextOpen = false; settings = false; runCatching { rootFocus.requestFocus() } } }
    LaunchedEffect(want, shown) {
        if (want != 0 && shown) {
            val r = when (want) { 2 -> settingsFocus; 3 -> nextFocus; else -> seekFocus }
            for (i in 0 until 12) {
                delay(50)
                runCatching { r.requestFocus() }
                if (when (want) { 2 -> settingsFocused; 3 -> nextFocused; else -> seekFocused }) break
            }
            want = 0
        }
    }
    // the settings panel takes the cursor straight away (its back button), and again after picking a page in it
    LaunchedEffect(settings, setPage.value) {
        if (settings) {
            for (i in 0 until 30) {
                delay(60)
                runCatching { backFocus.requestFocus() }
                if (backFocused) break
            }
        } else setPage.value = ""
    }
    // a jump with left or right lands a moment after the last press, so presses add up first
    LaunchedEffect(scrubPoke) {
        if (scrub != null) {
            delay(800)
            scrub?.let { player.seekTo(it) }
            scrub = null
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { seekFocus.requestFocus() } }
    LaunchedEffect(pb.error != null) { if (pb.error != null) { delay(100); runCatching { retryFocus.requestFocus() } } }

    fun reveal(to: Int = 1) { shown = true; poke++; want = to }
    fun toggle() {
        when {
            st.ended -> if (pb.next != null) pb.skipNext() else { player.seekTo(0); player.play() }
            st.playing -> player.pause()
            else -> player.play()
        }
    }
    fun scrubBy(dir: Int) {
        val dur = st.durationMs
        if (dur <= 0 || v?.live == true) return
        val now = Sys.nowMs()
        streak = if (dir == scrubDir && now - scrubAt < 500) streak + 1 else 0
        scrubAt = now; scrubDir = dir
        val step = when { streak > 14 -> 60_000L; streak > 6 -> 30_000L; else -> 10_000L }
        scrub = ((scrub ?: st.positionMs) + dir * step).coerceIn(0, maxOf(0, dur - 1000))
        scrubPoke++
    }
    fun commitScrub() { scrub?.let { player.seekTo(it) }; scrub = null }

    val upNext = remember(pb.queue, pb.details, v?.id) {
        (pb.queue + (pb.details?.related?.filterIsInstance<Video>() ?: emptyList()))
            .distinctBy { it.id }.filter { it.id != v?.id && !it.short && !it.members }.take(30)
    }
    fun play(n: Video) {
        val qi = pb.queue.indexOfFirst { it.id == n.id }
        app.watch(n, if (qi >= 0) pb.queue.drop(qi + 1) else emptyList())
        nextOpen = false; reveal()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                    when {
                        e.type == KeyEventType.KeyDown && settings && setPage.value != "" -> { setPage.value = ""; eatBack = true; true }
                        e.type == KeyEventType.KeyDown && settings -> { settings = false; want = 2; eatBack = true; true }
                        e.type == KeyEventType.KeyDown && nextOpen -> { nextOpen = false; want = 1; eatBack = true; true }
                        e.type == KeyEventType.KeyDown && scrub != null -> { scrub = null; eatBack = true; true }
                        e.type == KeyEventType.KeyDown && shown && pb.error == null && !st.ended && st.playing -> { shown = false; eatBack = true; true }
                        e.type == KeyEventType.KeyUp && eatBack -> { eatBack = false; true }
                        else -> false
                    }
                } else {
                    if (e.type == KeyEventType.KeyDown) {
                        poke++
                        when (e.key) {
                            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext -> { if (!shown) reveal(); playerKey(app, e.key) }
                            Key.MediaFastForward -> { reveal(); scrubBy(1); true }
                            Key.MediaRewind -> { reveal(); scrubBy(-1); true }
                            Key.Menu -> { shown = true; settings = !settings; true }
                            Key.Captions -> { toggleCaptions(app, st); true }
                            else -> false
                        }
                    } else false
                }
            },
    ) {
        player.View(Modifier.fillMaxSize())
        // the picture itself holds the focus while the controls are away
        Box(
            Modifier.fillMaxSize().focusRequester(rootFocus)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            // a sponsor pill on screen: OK skips it, as its button says
                            if (pb.skippable != null && st.playing) pb.skipSegment() else toggle()
                            reveal(); true
                        }
                        Key.DirectionLeft -> { reveal(); scrubBy(-1); true }
                        Key.DirectionRight -> { reveal(); scrubBy(1); true }
                        Key.DirectionUp, Key.DirectionDown -> { reveal(); true }
                        else -> false
                    }
                }
                .focusable(),
        )
        if (st.buffering || (pb.video != null && pb.stream == null && pb.error == null)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White, strokeWidth = 4.dp)
        }
        SponsorPill(app, lifted = shown)

        // the top: what is playing
        AnimatedVisibility(shown && v != null, Modifier.align(Alignment.TopStart).fillMaxWidth(), enter = fadeIn(tween(Motion.MEDIUM)), exit = fadeOut(tween(Motion.MEDIUM))) {
            val d = pb.details
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 48.dp),
            ) {
                Text(
                    v?.let { titleOf(it, app) }?.ifEmpty { d?.title } ?: "", color = Color.White, fontSize = 26.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp),
                )
                val meta = listOf(v?.author?.ifEmpty { d?.author } ?: "", d?.viewsText ?: v?.viewsText ?: "", d?.publishedText ?: v?.publishedText ?: "")
                    .filter { it.isNotEmpty() }.joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, color = Color(0xCCFFFFFF), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // the bottom: the time bar, the buttons, and under them the videos up next
        AnimatedVisibility(
            shown, Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            enter = fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM)) { it / 6 },
            exit = fadeOut(tween(Motion.MEDIUM)) + slideOutVertically(tween(Motion.MEDIUM)) { it / 6 },
        ) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000))))
                    .padding(top = 72.dp, bottom = if (nextOpen) 20.dp else 28.dp),
            ) {
                TvSeekBar(
                    app, st, scrub, Modifier.padding(horizontal = 56.dp).focusRequester(seekFocus).onFocusChanged { seekFocused = it.isFocused },
                    onKey = { k ->
                        when (k) {
                            Key.DirectionLeft -> { scrubBy(-1); true }
                            Key.DirectionRight -> { scrubBy(1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (scrub != null) commitScrub() else toggle(); true }
                            // down: to play/pause, the first button, wherever the bar's mark is
                            Key.DirectionDown -> { runCatching { playFocus.requestFocus() }; true }
                            else -> false
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 18.dp)
                        .onPreviewKeyEvent { e ->
                            // down from the buttons opens the row of videos up next
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && upNext.isNotEmpty() && !nextOpen) { nextOpen = true; want = 3; true } else false
                        }
                        .focusGroup(),
                    verticalAlignment = Alignment.Top,
                ) {
                    TvRoundButton(if (st.ended) Sym.Replay.fill else if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill,
                        tr(if (st.ended) "Replay" else if (st.playing) "Pause" else "Play"), Modifier.focusRequester(playFocus)) {
                        if (st.ended) { player.seekTo(0); player.play() } else toggle()
                    }
                    if (pb.next != null) TvRoundButton(Sym.SkipNext.fill, tr("Next")) { pb.skipNext() }
                    if (!pb.stream?.captions.isNullOrEmpty()) TvRoundButton(
                        if (st.captions != null) Sym.ClosedCaption.fill else Sym.ClosedCaptionOff.outline, tr("Captions"), on = st.captions != null,
                    ) { toggleCaptions(app, st) }
                    if (v != null) {
                        val liked = lib.likes.any { it.id == v.id }
                        val saved = lib.wl.any { it.id == v.id }
                        TvRoundButton(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), on = liked) {
                            app.store.setInList("likes", v, !liked)
                            if (!liked) Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0)
                        }
                        TvRoundButton(if (saved) Sym.WatchLater.fill else Sym.WatchLater.outline, tr("Watch later"), on = saved) {
                            app.store.setInList("wl", v, !saved)
                        }
                    }
                    TvRoundButton(Sym.Settings.fill, tr("Settings"), Modifier.focusRequester(settingsFocus).onFocusChanged { settingsFocused = it.isFocused }, on = settings) { settings = !settings }
                    Spacer(Modifier.weight(1f))
                    // the channel: its page, and subscribing
                    val cid = v?.channelId?.ifEmpty { pb.details?.channelId } ?: ""
                    if (cid.isNotEmpty()) {
                        val name = v?.author?.ifEmpty { pb.details?.author } ?: ""
                        val pic = pb.details?.avatar?.ifEmpty { null } ?: channelAvatar(app, cid, v?.avatar ?: "")
                        TvChannelChip(name, pic, pb.details?.subsText ?: "") { app.go(Screen.Channel(cid, name)) }
                        val subbed = cid in lib.subs
                        TvPill(tr(if (subbed) "Subscribed" else "Subscribe"), subbed) { app.store.subscribe(cid, name, pic, !subbed) }
                    }
                }
                if (nextOpen && upNext.isNotEmpty()) {
                    Text(tr("Up next"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, top = 22.dp, bottom = 6.dp))
                    TvPinned(lead = 56.dp) {
                        LazyRow(
                            Modifier.fillMaxWidth().onFocusChanged { nextFocused = it.hasFocus; if (!it.hasFocus && nextOpen && want == 0) nextOpen = false },
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(start = 56.dp, end = 120.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            items(upNext, key = { it.id }) { n ->
                                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                                    TvCard(n, app, Modifier.width(200.dp).let { if (n.id == upNext[0].id) it.focusRequester(nextFocus) else it }, onClick = { play(n) })
                                }
                            }
                        }
                    }
                }
            }
        }

        // the end of a video without autoplay: watch again, or the next one
        if (st.ended && !shown) LaunchedEffect(Unit) { reveal(1) }

        // a video that won't play: why, and trying again
        pb.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).widthIn(max = 640.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, fontSize = 18.sp, lineHeight = 26.sp)
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvPill(tr("Try again"), false, Modifier.focusRequester(retryFocus)) { pb.retry() }
                    TvPill(tr("Back"), false) { app.back() }
                }
            }
        }

        // the settings: quality, speed, captions, audio... in a panel from the right
        AnimatedVisibility(
            settings, Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally(tween(Motion.MEDIUM)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(Motion.SHORT)) { it } + fadeOut(),
        ) {
            Surface(Modifier.width(380.dp).fillMaxHeight(), color = Color(0xF21C1C1C), contentColor = Color.White) {
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)
                            // the cursor stays in the panel until it's closed
                            .focusProperties { onExit = { cancelFocusChange() } }
                            .focusRequester(panelFocus).focusGroup(),
                    ) {
                        // back: from a page (quality, speed...) to the list, from the list out of the panel
                        Row(Modifier.padding(start = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TvPill(
                                "←  " + tr("Back"), false,
                                Modifier.focusRequester(backFocus).onFocusChanged { backFocused = it.isFocused },
                            ) { if (setPage.value != "") setPage.value = "" else { settings = false; want = 2 } }
                            Text(tr("Settings"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 5.dp))
                        }
                        SettingsPages(app, setPage) { settings = false; want = 2 }
                    }
                }
            }
        }
    }
}

/** captions on or off: the track in your language, or YouTube's translation of one */
private fun toggleCaptions(app: AppState, st: PlayerState) {
    val player = app.player
    if (st.captions != null) {
        player.setCaptions(null, null); app.store.settings.update { it.copy(captions = false) }
    } else {
        val (c, translate) = app.playback.captionChoice() ?: return
        player.setCaptions(c, translate)
        app.store.settings.update { it.copy(captions = true) }
    }
}

/**
 * The time bar: thin while the buttons are chosen, thick with a white knob when it is; left and
 * right move a mark along it with the picture at that point above, and the video jumps there a
 * moment later (or at once with OK).
 */
@Composable
private fun TvSeekBar(app: AppState, st: PlayerState, scrub: Long?, modifier: Modifier, onKey: (Key) -> Boolean) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val live = app.playback.video?.live == true
    val dur = st.durationMs
    val pos = scrub ?: st.positionMs
    val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val h by animateDpAsState(if (focused) 8.dp else 4.dp, tween(Motion.SHORT))
    Column(
        modifier.fillMaxWidth()
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.key) }
            .focusable(interactionSource = src),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (scrub != null) 150.dp else 0.dp)) {
            // the picture at the mark, above it
            val board = app.playback.storyboard
            if (scrub != null) {
                val w = 200.dp
                val x = (maxWidth * f - w / 2).coerceIn(0.dp, maxWidth - w)
                Column(Modifier.offset(x = x).width(w).align(Alignment.BottomStart).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (board != null && !app.store.settings.value.hideThumbs) {
                        val fr = board.frame(scrub, dur)
                        val bh = w * board.height / maxOf(1, board.width)
                        Box(Modifier.size(w, bh).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                fr.url, null, contentScale = ContentScale.FillBounds,
                                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                                    .requiredSize(w * board.cols, bh * board.rows).offset(-w * fr.col, -bh * fr.row),
                            )
                        }
                    }
                    val chapter = app.playback.chapters.lastOrNull { it.startMs <= scrub }?.title
                    if (!chapter.isNullOrEmpty()) Text(chapter, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(durationMs(scrub), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (live) {
            Text(tr("LIVE"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Red, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
            val buf = if (dur > 0) (st.bufferedMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h)).background(Color(0x4DFFFFFF)))
            Box(Modifier.fillMaxWidth(buf).height(h).clip(RoundedCornerShape(h)).background(Color(0x66FFFFFF)))
            Box(Modifier.fillMaxWidth(f).height(h).clip(RoundedCornerShape(h)).background(Red))
            // the chapters' gaps
            for (c in app.playback.chapters.drop(1)) if (dur > 0) {
                Box(Modifier.offset(x = maxWidth * (c.startMs.toFloat() / dur)).width(3.dp).height(h).background(Color(0xCC000000)))
            }
            if (focused) Box(Modifier.offset(x = maxWidth * f - 10.dp).size(20.dp).clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(durationMs(pos), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (dur > 0) Text(durationMs(dur), color = Color(0xCCFFFFFF), fontSize = 15.sp)
        }
    }
}

/** a round button on the player: white with a black icon when the remote is on it, its name below */
@Composable
private fun TvRoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier.size(54.dp).clip(CircleShape)
                .background(if (focused) Color.White else if (on) Color(0x4D2DD4BF) else Color(0x33FFFFFF))
                .combinedClickable(src, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(28.dp), tint = if (focused) Color.Black else if (on) Teal else Color.White)
        }
        Text(label, color = Color.White.copy(alpha = if (focused) 1f else 0f), fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** a word button on the player (subscribe, try again) */
@Composable
private fun TvPill(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier.padding(top = 5.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else if (on) Color(0x33FFFFFF) else Color(0x4DFFFFFF))
            .combinedClickable(src, indication = null, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** the channel's picture and name on the player; OK opens its page */
@Composable
private fun TvChannelChip(name: String, pic: String, subs: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        Modifier.padding(top = 5.dp, end = 12.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .combinedClickable(src, indication = null, onClick = onClick).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(pic, 36)
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            if (subs.isNotEmpty()) Text(subs, color = if (focused) Color(0xFF444444) else Color(0xB3FFFFFF), fontSize = 11.sp, maxLines = 1)
        }
    }
}
