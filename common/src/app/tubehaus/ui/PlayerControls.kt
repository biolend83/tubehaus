package app.tubehaus.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import app.tubehaus.engine.SponsorBlock
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.requiredHeight
import kotlin.math.abs
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlin.math.pow
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import app.tubehaus.rec.Learn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The video with its controls, drawn in Compose on both platforms.
 * Touch: tap to show or hide, double-tap a side to jump 10 seconds, big centre buttons, a sheet for settings.
 * Mouse: the controls appear while the pointer moves, click plays or pauses, double-click is full screen,
 * and the bottom bar has volume and a settings menu, like YouTube on a computer.
 */
@Composable
fun VideoArea(app: AppState, modifier: Modifier = Modifier, fullscreen: Boolean = false, sideGestures: Boolean = false, controls: Boolean = true) {
    Box(modifier.background(Color.Black)) {
        app.player.View(Modifier.fillMaxSize())
        if (controls) Controls(app, fullscreen, sideGestures)
    }
}

/** Keyboard shortcuts while a video is on screen (YouTube's). True when the key was used. */
fun playerKey(app: AppState, key: Key, shift: Boolean = false): Boolean {
    val player = app.player
    val st = player.state.value
    if (st.videoId == null) return false
    fun seekBy(sec: Int) = player.seekTo((st.positionMs + sec * 1000L).coerceIn(0, maxOf(0, st.durationMs - 500)))
    fun volumeBy(d: Float) {
        val v = (st.volume + d).coerceIn(0f, 1f)
        player.setVolume(v); player.setMuted(false)
        // a TV's volume while casting is the TV's own, not the one this device remembers
        if (app.casting.link == null) app.store.settings.update { it.copy(volume = v) }
    }
    fun speedStep(d: Int) {
        val i = SPEEDS.indexOfFirst { it >= st.speed - 0.01f }.let { if (it < 0) SPEEDS.size - 1 else it }
        val x = SPEEDS[(i + d).coerceIn(0, SPEEDS.size - 1)]
        app.playback.setSpeed(x); app.toast(if (x == 1f) tr("Normal") else "${x}x")
    }
    if (shift) when (key) {
        // YouTube's: Shift+N the next video, Shift+P the one before, < and > the speed
        Key.N -> { if (app.playback.next != null || app.playback.music) app.playback.skipNext() else return false; return true }
        Key.P -> { app.playback.previous(); return true }
        Key.Comma -> { speedStep(-1); return true }
        Key.Period -> { speedStep(1); return true }
        else -> {}
    }
    when (key) {
        Key.C -> toggleCaptions(app, st)
        // 1 to 9: that tenth of the video
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
            val n = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(key) + 1
            if (st.durationMs <= 0) return false
            player.seekTo(st.durationMs * n / 10)
        }
        Key.MediaPrevious -> app.playback.previous()
        Key.Spacebar, Key.K -> if (st.playing) player.pause() else player.play()
        Key.DirectionLeft -> seekBy(-5)
        Key.DirectionRight -> seekBy(5)
        Key.J -> seekBy(-10)
        Key.L -> seekBy(10)
        Key.F -> app.setFull(!app.fullscreen)
        Key.M -> if (player.hasVolume) player.setMuted(!st.muted) else return false
        Key.DirectionUp -> if (player.hasVolume) volumeBy(0.05f) else return false
        Key.DirectionDown -> if (player.hasVolume) volumeBy(-0.05f) else return false
        Key.Home, Key.Zero -> player.seekTo(0)
        // media keys (TV remotes, keyboards)
        Key.MediaPlayPause -> if (st.playing) player.pause() else player.play()
        Key.MediaPlay -> player.play()
        Key.MediaPause -> player.pause()
        Key.MediaFastForward -> seekBy(10)
        Key.MediaRewind -> seekBy(-10)
        Key.MediaNext -> if (app.playback.next != null) app.playback.skipNext() else return false
        else -> return false
    }
    return true
}

/** the speeds the player offers */
val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** brightness the swipe works in (what the eye sees, gamma 2.2) and the screen's own value */
private fun perceived(b: Float) = b.coerceIn(0f, 1f).toDouble().pow(1 / 2.2).toFloat()
private fun actual(p: Float) = p.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat().coerceAtLeast(0.004f)

@Composable
private fun Controls(app: AppState, fullscreen: Boolean, sideGestures: Boolean) {
    val touch = app.platform.touch
    val player = app.player
    val st by player.state.collectAsState()
    var shown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }          // any touch or mouse move restarts the hide timer
    var dragging by remember { mutableStateOf<Float?>(null) }
    var jump by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // side (-1/1), seconds
    // where the last of a run of double-tap jumps was tapped, and a count that restarts its ripple
    var jumpAt by remember { mutableStateOf(Offset.Zero) }
    var jumpTick by remember { mutableIntStateOf(0) }
    // held down: playing at twice the speed until let go (the speed it had before)
    var fast by remember { mutableStateOf<Float?>(null) }
    var settings by remember { mutableStateOf(false) }
    // a sideways swipe over the picture: where it will land and how far that is from where it started
    var scrub by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // a click on the picture (mouse): the new state's icon swells in the middle and fades, as YouTube's
    var bezelTick by remember { mutableIntStateOf(0) }
    var bezelPlay by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf<Pair<String, Float>?>(null) }   // "brightness"/"volume", level
    // TV: the video itself holds the remote's focus (OK, left/right); up or down moves it onto the buttons
    val tv = app.platform.tv
    val videoFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var toBar by remember { mutableStateOf(false) }
    var inBar by remember { mutableStateOf(false) }
    var eatBack by remember { mutableStateOf(false) }
    // read inside the pointer loops, which outlive the composition they started in
    val playingNow by rememberUpdatedState(st.playing)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val settingsNow by rememberUpdatedState(settings)

    LaunchedEffect(shown, poke, st.playing, dragging, settings) {
        if (shown && st.playing && dragging == null && !settings) {
            delay(if (touch) 3000 else 2500)
            shown = false
        }
    }
    LaunchedEffect(jumpTick) {
        if (jump != null) { delay(800); jump = null }
    }
    if (tv) {
        LaunchedEffect(Unit) { runCatching { videoFocus.requestFocus() } }
        // the buttons fade away: the focus goes back to the video instead of getting lost
        LaunchedEffect(shown) { if (!shown) { inBar = false; runCatching { videoFocus.requestFocus() } } }
        // the buttons may still be fading in: keep asking until the focus is on them
        LaunchedEffect(toBar, shown) {
            if (toBar && shown) {
                for (i in 0 until 10) {
                    delay(60)
                    if (inBar) break
                    runCatching { playFocus.requestFocus() }
                }
                toBar = false
            }
        }
    }

    fun seekBy(sec: Int) {
        val to = (st.positionMs + sec * 1000L).coerceIn(0, maxOf(0, st.durationMs - 500))
        player.seekTo(to)
    }

    fun toggle() { if (st.playing) player.pause() else player.play() }

    /** the remote while the video has the focus; false lets the key move the focus instead */
    fun remote(key: Key): Boolean {
        when (key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                !fullscreen -> app.setFull(true)
                st.ended -> if (app.playback.next != null) app.playback.skipNext() else { player.seekTo(0); player.play() }
                else -> toggle()
            }
            Key.DirectionLeft, Key.DirectionRight -> {
                if (!fullscreen) return false
                val side = if (key == Key.DirectionLeft) -1 else 1
                seekBy(side * 10)
                jump = side to ((if (jump?.first == side) jump!!.second else 0) + 10)
                jumpTick++
            }
            // down always goes to the buttons (settings, full screen), also in the small player on the
            // watch page; from there down again leaves for the page. Up leaves the small player
            Key.DirectionDown -> toBar = true
            Key.DirectionUp -> if (fullscreen) toBar = true else return false
            else -> return playerKey(app, key)
        }
        shown = true; poke++
        return true
    }

    val blank = app.platform.blankPointer
    // Settings → gestures (#57); the mouse's double-click (full screen) stays
    val prefs by app.store.settings.state.collectAsState()
    val dtap = !touch || prefs.doubleTapSeek
    val swipe = prefs.swipeGestures
    // the mouse: on the outer box, so moving over the buttons counts too
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .let { if (blank != null && !shown && st.playing) it.pointerHoverIcon(blank) else it }
            // TV: any key brings the controls back; Back hides them first, then leaves
            .let {
                if (!tv) it else it.onPreviewKeyEvent { e ->
                    if (e.key == Key.Back) {
                        when {
                            e.type == KeyEventType.KeyDown && shown && st.playing && !settings -> { shown = false; eatBack = true; true }
                            e.type == KeyEventType.KeyUp && eatBack -> { eatBack = false; true }
                            else -> false
                        }
                    } else {
                        if (e.type == KeyEventType.KeyDown) { shown = true; poke++ }
                        false
                    }
                }
            }
            .pointerInput(touch) {
                if (touch) return@pointerInput
                awaitPointerEventScope {
                    // only a real move counts: hiding the pointer, and the controls fading in or out
                    // under a still pointer, send move, exit and enter events with the pointer where
                    // it was, which kept the controls up for good
                    var last: Offset? = null
                    while (true) {
                        val e = awaitPointerEvent()
                        val at = e.changes.firstOrNull()?.position
                        when (e.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val moved = at == null || last == null || (at - last!!).getDistance() > 3f
                                if (at != null) last = at
                                if (moved) { shown = true; poke++ }
                            }
                            PointerEventType.Exit -> {
                                // a real exit: the pointer is outside the video now
                                val out = at == null || at.x < 0 || at.y < 0 || at.x >= size.width || at.y >= size.height
                                if (out) { last = null; if (playingNow && !settingsNow) shown = false }
                            }
                        }
                    }
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        // full screen on a phone: two fingers spread fill the screen with the picture, pinched fit it back
        var zoom by remember { mutableStateOf(false) }
        LaunchedEffect(fullscreen) { if (!fullscreen && zoom) { zoom = false } }
        LaunchedEffect(zoom) { player.setZoom(zoom) }
        var zoomNote by remember { mutableStateOf(0) }
        val pinchHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        // taps and clicks anywhere on the video
        Box(
            Modifier.fillMaxSize()
                .let { if (touch && fullscreen) it.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var scale = 1f
                        var decided = false
                        do {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val fingers = e.changes.count { it.pressed }
                            if (fingers >= 2) {
                                scale *= e.calculateZoom()
                                e.changes.forEach { it.consume() }
                                if (!decided && (scale > 1.12f || scale < 0.88f)) {
                                    decided = true
                                    val want = scale > 1f
                                    if (want != zoom) { zoom = want; zoomNote++; pinchHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate) }
                                }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                } else it }
                .let { if (tv) it.onKeyEvent { e -> e.type == KeyEventType.KeyDown && remote(e.key) }.focusRequester(videoFocus).focusable() else it }
                // swiped sideways: the whole width is a minute and a half (less in a short video), a tick
                // every five seconds; it jumps there when the finger lifts
                .pointerInput(touch, st.durationMs > 0, app.playback.video?.live == true) {
                    if (!touch || app.playback.video?.live == true) return@pointerInput
                    var base = 0L; var travel = 0f; var lastTick = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            base = player.state.value.positionMs; travel = 0f; lastTick = base
                            scrub = base to 0L
                        },
                        onDragEnd = {
                            scrub?.let { (to, by) -> if (by != 0L) player.seekTo(to) }
                            scrub = null
                        },
                        onDragCancel = { scrub = null },
                    ) { c, dx ->
                        c.consume()
                        val dur = player.state.value.durationMs
                        if (dur <= 0 || size.width <= 0) return@detectHorizontalDragGestures
                        val span = minOf(90_000L, dur)
                        travel += dx
                        val raw = base + (travel / size.width * span).toLong()
                        val to = raw.coerceIn(0L, maxOf(0L, dur - 500))
                        if (to != raw) travel = (to - base).toFloat() / span * size.width
                        scrub = to to (to - base)
                        if (abs(to - lastTick) >= 5_000L) { lastTick = to; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick) }
                    }
                }
                .pointerInput(touch, st.durationMs, w, dtap) {
                    if (!touch) {
                        detectTapGestures(onTap = { bezelPlay = !playingNow; toggle(); bezelTick++ }, onDoubleTap = { app.setFull(!fullscreen) })
                        return@pointerInput
                    }
                    // as YouTube's app: a double tap on a side jumps 10 seconds, and each further tap
                    // on that side soon after jumps 10 more; held down, the video plays at 2x until let go
                    var runUntil = 0L
                    var runTo = -1L
                    fun sideOf(x: Float) = when {
                        x < w * 0.4f -> -1
                        x > w * 0.6f -> 1
                        else -> 0
                    }
                    fun jumpTo(side: Int, at: Offset, t: Long, more: Boolean) {
                        val s0 = player.state.value
                        val base = if (more && runTo >= 0) runTo else s0.positionMs
                        val to = (base + side * 10_000L).coerceIn(0, maxOf(0, s0.durationMs - 500))
                        player.seekTo(to)
                        runTo = to
                        runUntil = t + 700
                        jump = side to ((if (more && jump?.first == side) jump!!.second else 0) + 10)
                        jumpAt = at; jumpTick++
                        shown = false
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                    }
                    fun single(o: Offset) {
                        if (app.store.settings.value.tapToPause && o.x in w * 0.25f..w * 0.75f) { toggle(); shown = true; poke++ }
                        else { shown = !shown; poke++ }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val side = sideOf(down.position.x)
                        // in a run of jumps: the tap counts at once
                        if (dtap && side != 0 && jump?.first == side && down.uptimeMillis < runUntil) {
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume()
                            jumpTo(side, up.position, up.uptimeMillis, more = true)
                            return@awaitEachGesture
                        }
                        var moved = false
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation().also { if (it == null) moved = true }
                        }
                        if (moved) return@awaitEachGesture
                        if (up == null) {
                            // held: 2x while the finger stays down (not when paused or already faster)
                            val before = player.state.value.speed
                            if (!playingNow || before >= 2f) return@awaitEachGesture
                            player.setSpeed(2f)
                            fast = before
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            shown = false
                            try {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { if (it.pressed) it.consume() }
                                    if (e.changes.none { it.pressed }) break
                                }
                            } finally {
                                player.setSpeed(before)
                                fast = null
                            }
                            return@awaitEachGesture
                        }
                        up.consume()
                        if (!dtap) { single(up.position); return@awaitEachGesture }
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                        if (second == null) { single(up.position); return@awaitEachGesture }
                        val s2 = sideOf(second.position.x)
                        if (s2 == 0) toggle() else jumpTo(s2, second.position, second.uptimeMillis, more = false)
                        waitForUpOrCancellation()?.consume()
                    }
                }
                // full screen on a phone: swipe up or down on the left for brightness, on the right for volume;
                // beside the comments on a tablet the same, on the outer thirds (the middle still
                // swipes the video down into the mini player)
                // full screen: swipe down in the middle to leave it (#64)
                .pointerInput(touch, fullscreen, sideGestures, swipe) {
                    if (!touch || !(fullscreen || (swipe && sideGestures))) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = down.position.x
                        val middle = x in size.width / 3f..size.width * 2f / 3f
                        if (fullscreen && (middle || !swipe)) {
                            val start = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                            var pulled = 0f
                            verticalDrag(start.id) { c -> pulled += c.position.y - c.previousPosition.y; c.consume() }
                            if (pulled > 60.dp.toPx()) app.setFull(false)
                            return@awaitEachGesture
                        }
                        if (!swipe || (!fullscreen && middle)) return@awaitEachGesture
                        val kind = if (x < size.width / 2) "brightness" else "volume"
                        var level = (if (kind == "brightness") app.platform.brightness()?.let { perceived(it) } else app.platform.mediaVolume()) ?: return@awaitEachGesture
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                        val h = size.height * if (fullscreen) 0.8f else 1.2f
                        try {
                            verticalDrag(start.id) { c ->
                                level = (level - (c.position.y - c.previousPosition.y) / h).coerceIn(0f, 1f)
                                c.consume()
                                if (kind == "brightness") app.platform.setBrightness(actual(level)) else app.platform.setMediaVolume(level)
                                gesture = kind to level
                            }
                        } finally { gesture = null }
                    }
                },
        )
        // the level as a standing bar on the side away from the swiping thumb, so the hand does not
        // cover the number it is aiming for; it follows the finger on a spring and fades out
        var lastGesture by remember { mutableStateOf("volume" to 0f) }
        gesture?.let { lastGesture = it }
        AnimatedVisibility(gesture != null, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.MEDIUM)),
            modifier = Modifier.align(if (lastGesture.first == "brightness") Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 24.dp)) {
            LevelHud(lastGesture.first, lastGesture.second, tall = fullscreen)
        }
        // a double tap's jump, as YouTube's app shows it: a light half-moon on that side, a ripple
        // from the finger, arrows running the way of the jump and the seconds jumped so far
        jump?.let { (side, secs) -> JumpRipple(side, secs, jumpAt, jumpTick, w) }
        if (bezelTick > 0) {
            val grow = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(bezelTick) { grow.snapTo(0f); grow.animateTo(1f, tween(520, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
            if (grow.value < 1f) Box(
                Modifier.align(Alignment.Center).size(64.dp)
                    .graphicsLayer { val k = grow.value; alpha = 1f - k; scaleX = 1f + 0.5f * k; scaleY = 1f + 0.5f * k }
                    .clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) { Icon(if (bezelPlay) Sym.PlayArrow.fill else Sym.Pause.fill, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
        // the seek swipe's read-out in the middle of the picture
        var lastScrub by remember { mutableStateOf(0L to 0L) }
        scrub?.let { lastScrub = it }
        AnimatedVisibility(scrub != null, enter = fadeIn(tween(Motion.SHORT)) + androidx.compose.animation.scaleIn(initialScale = 0.92f),
            exit = fadeOut(tween(Motion.SHORT)) + androidx.compose.animation.scaleOut(targetScale = 0.94f), modifier = Modifier.align(Alignment.Center)) {
            val (to, by) = lastScrub
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x8F000000)).padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(durationMs(to), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text((if (by < 0) "-" else "+") + durationMs(abs(by)), color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // held down: "2x" at the top while the finger stays
        AnimatedVisibility(fast != null, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            Row(
                Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("2x", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Arrows(1, Modifier.padding(start = 6.dp).size(width = 22.dp, height = 10.dp))
            }
        }
        if (st.buffering && (!shown || !touch)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White, strokeWidth = 3.dp)
        }
        AnimatedVisibility(shown || st.ended || !st.playing && !touch, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (touch) TouchOverlay(app, st, fullscreen, dragging, { dragging = it; poke++ }, { dragging = null }, ::seekBy, { poke++ }, { settings = true })
            else MouseOverlay(app, st, fullscreen, dragging, { dragging = it; poke++ }, { dragging = null }, settings, { settings = it; poke++ }, playFocus.takeIf { tv }) { inBar = it }
        }
        SponsorPill(app, lifted = shown || st.ended || !st.playing)
        if (zoomNote > 0) {
            var showNote by remember { mutableStateOf(false) }
            LaunchedEffect(zoomNote) { showNote = true; delay(1100); showNote = false }
            androidx.compose.animation.AnimatedVisibility(showNote, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.MEDIUM)), modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) {
                Text(tr(if (zoom) "Zoomed to fill" else "Original size"), color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        // ended with a suggestion next: it is counted down, and can be stopped or started now
        LaunchedEffect(st.ended) { if (!st.ended) app.playback.cancelUpNext() }
        val upNext = app.playback.upNext
        androidx.compose.animation.AnimatedVisibility(upNext != null, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT)), modifier = Modifier.fillMaxSize()) {
            var held by remember { mutableStateOf<Video?>(null) }
            if (upNext != null) held = upNext
            held?.let { UpNextCard(app, it, fullscreen) }
        }
        // a thin progress line while the controls are hidden (not in full screen)
        if (!shown && !fullscreen && st.durationMs > 0 && st.playing) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(2.dp).background(Red))
            }
        }
    }
    if (touch && settings) {
        // opened all the way and scrolling: a phone held sideways has room for only a few rows
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { SettingsPages(app) { settings = false } }
        }
    }
}

// ---------------------------------------------------------------------- phones

@Composable
private fun TouchOverlay(
    app: AppState, st: PlayerState, fullscreen: Boolean, dragging: Float?,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit, seekBy: (Int) -> Unit, poke: () -> Unit, openSettings: () -> Unit,
) {
    val player = app.player
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var remaining by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0x3D000000)).drawWithCache {
        val edge = (if (fullscreen) 120.dp else 72.dp).toPx().coerceAtMost(size.height / 2)
        val top = Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent), endY = edge)
        val bottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000)), startY = size.height - edge, endY = size.height)
        onDrawBehind {
            drawRect(top, size = Size(size.width, edge))
            drawRect(bottom, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge))
        }
    }) {
        // top: a chevron that folds the video into the mini player (in full screen: back out of it),
        // the title and channel in full screen; captions and settings on the right
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 4.dp, vertical = if (fullscreen) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.back(hooks = false) }) { Icon(Sym.KeyboardArrowDown.fill, tr(if (fullscreen) "Exit full screen" else "Minimize"), tint = Color.White, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f)) {
                if (fullscreen) {
                    val v = app.playback.video
                    Text(v?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!v?.author.isNullOrEmpty()) Text(v!!.author, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            if (app.platform.canPip && app.casting.link == null)
                IconButton(onClick = { app.platform.startPip() }) { Icon(Sym.Pip.outline, tr("Picture in picture"), tint = Color.White) }
            CastButton(app) { poke() }
            CaptionsButton(app, st) { poke() }
            IconButton(onClick = openSettings) { Icon(Sym.Settings.outline, tr("Settings"), tint = Color.White) }
        }
        // centre: back 10s, play/pause, forward 10s (or next when it has ended)
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 56.dp else 28.dp)) {
            SkipButton(Sym.SkipPrevious.fill, tr("Previous")) { app.playback.previous(); poke() }
            PlayButton(st) {
                when {
                    st.ended -> { player.seekTo(0); player.play() }
                    st.playing -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff); player.pause() }
                    else -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn); player.play() }
                }
                poke()
            }
            SkipButton(Sym.SkipNext.fill, tr("Next"), enabled = app.playback.next != null) { app.playback.skipNext() }
        }
        // bottom: time, full screen, seek bar
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .let { if (fullscreen) it.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp) else it },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimePills(app, st, dragging, remaining) { remaining = !remaining; poke() }
                Spacer(Modifier.weight(1f))
                if (st.speed != 1f) Text("${st.speed}x", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp).height(28.dp).clip(CircleShape).background(PILL).clickable(onClick = openSettings).wrapContentHeight().padding(horizontal = 12.dp))
                FullscreenButton(app, fullscreen)
            }
            // outside full screen the bar ran to the video's edges and the handle stuck out past them (#56)
            Box(Modifier.padding(horizontal = if (fullscreen) 0.dp else 12.dp)) {
                SeekBar(app, st, dragging, onDrag) { f -> player.seekTo((f * st.durationMs).toLong()); onDragEnd() }
            }
            if (fullscreen) FullscreenActions(app) { poke() }
        }
    }
}

/** Full screen on a phone: like, dislike, watch later and share under the seek bar, without leaving the video */
@Composable
private fun FullscreenActions(app: AppState, poke: () -> Unit) {
    val v = app.playback.video ?: return
    val lib by app.store.lib.state.collectAsState()
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    val saved = lib.wl.any { it.id == v.id }
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Bar(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like")) {
            app.store.setInList("likes", v, !liked)
            if (!liked) Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0)
            poke()
        }
        Bar(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike")) {
            app.store.setInList("dislikes", v, !disliked)
            if (!disliked) Learn.nudgeChannel(app.store, v.channelId, v.author, -1.5)
            poke()
        }
        Bar(if (saved) Sym.WatchLater.fill else Sym.WatchLater.outline, tr("Watch later")) { app.store.setInList("wl", v, !saved); poke() }
        Bar(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") }
    }
}

/** brightness or volume while it is swiped: the number, a bar that fills upward, the icon */
@Composable
private fun LevelHud(kind: String, level: Float, tall: Boolean) {
    val shown by animateFloatAsState(level, androidx.compose.animation.core.spring(stiffness = 1400f))
    val icon = when {
        kind == "brightness" -> Sym.BrightnessMedium.fill
        level == 0f -> Sym.VolumeOff.fill
        else -> Sym.VolumeUp.fill
    }
    Column(
        Modifier.width(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0x8A000000)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(level * 100).toInt()}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(vertical = 12.dp).width(8.dp).height(if (tall) 150.dp else 84.dp).clip(CircleShape).background(Color(0x40FFFFFF)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(shown.coerceIn(0f, 1f)).clip(CircleShape).background(Color.White))
        }
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** what rests on the video: pills and round buttons (40 % black) */
private val PILL = Color(0x66000000)

/** shrinks a little while pressed and springs back */
@Composable
private fun Modifier.squeeze(src: androidx.compose.foundation.interaction.MutableInteractionSource, to: Float): Modifier {
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) to else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 700f))
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** previous and next: a white icon with no circle, dimmed when there is nowhere to go */
@Composable
private fun SkipButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier.size(52.dp).squeeze(src, 0.82f).clip(CircleShape)
            .clickable(src, androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Color.White else Color(0x4DFFFFFF), modifier = Modifier.size(36.dp)) }
}

/** play, pause, replay or the loading ring, in one round button; the icon pops from one to the next */
@Composable
private fun PlayButton(st: PlayerState, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val what = when { st.buffering && !st.ended -> 0; st.ended -> 1; st.playing -> 2; else -> 3 }
    Box(
        Modifier.size(66.dp).squeeze(src, 0.88f).clip(CircleShape).background(PILL)
            .clickable(src, androidx.compose.foundation.LocalIndication.current, enabled = what != 0, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(what, transitionSpec = {
            val sp = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.7f, stiffness = 900f)
            (androidx.compose.animation.scaleIn(sp, initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                (androidx.compose.animation.scaleOut(sp, targetScale = 0.6f) + fadeOut(tween(90)))
        }, contentAlignment = Alignment.Center) { w ->
            when (w) {
                0 -> CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                1 -> Icon(Sym.Replay.fill, tr("Replay"), tint = Color.White, modifier = Modifier.size(44.dp))
                2 -> Icon(Sym.Pause.fill, tr("Pause"), tint = Color.White, modifier = Modifier.size(44.dp))
                else -> Icon(Sym.PlayArrow.fill, tr("Play"), tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
    }
}

/** the time in a pill (a tap: what is left instead), and the chapter playing in another, which opens the list */
@Composable
private fun TimePills(app: AppState, st: PlayerState, dragging: Float?, remaining: Boolean, onTime: () -> Unit) {
    val pos = dragging?.let { (it * st.durationMs).toLong() } ?: st.positionMs
    var list by remember { mutableStateOf(false) }
    val chapter = app.playback.chapters.lastOrNull { it.startMs <= pos }
    val live = app.playback.video?.live == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(28.dp).clip(CircleShape).background(PILL).clickable(enabled = !live, onClick = onTime).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.size(8.dp).background(Red, CircleShape))
                Text(tr("LIVE"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp))
            } else {
                Text(if (remaining) "-" + durationMs(maxOf(0L, st.durationMs - pos)) else durationMs(pos), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(" / " + durationMs(st.durationMs), color = Color(0xB3FFFFFF), fontSize = 12.sp)
            }
        }
        if (chapter != null && chapter.title.isNotEmpty()) Row(
            Modifier.height(28.dp).clip(CircleShape).background(PILL).clickable { list = true }.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chapter.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            Icon(Sym.ChevronRight.outline, null, tint = Color(0xB3FFFFFF), modifier = Modifier.size(16.dp))
        }
    }
    if (list) ChaptersSheet(app) { list = false }
}

@Composable
private fun CenterButton(icon: ImageVector, label: String, size: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).graphicsLayer { alpha = if (enabled) 1f else 0.35f }.clip(CircleShape).background(Color(0x4D000000)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.58f).dp)) }
}

// ---------------------------------------------------------------------- mouse and keyboard

@Composable
private fun MouseOverlay(
    app: AppState, st: PlayerState, fullscreen: Boolean, dragging: Float?,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit, settings: Boolean, setSettings: (Boolean) -> Unit,
    playFocus: FocusRequester? = null,
    onBarFocus: (Boolean) -> Unit = {},
) {
    val player = app.player
    Box(Modifier.fillMaxSize()) {
        // full screen: the title over a shade at the top
        if (fullscreen) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.setFull(false) }) { Icon(Sym.ArrowBack.fill, tr("Back"), tint = Color.White) }
                Text(
                    app.playback.video?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (LocalSideComments.current) IconButton(onClick = { app.fsComments = !app.fsComments }) {
                    Icon(if (app.fsComments) Sym.Comment.fill else Sym.Comment.outline, tr("Comments"), tint = Color.White)
                }
            }
        }
        if (st.ended) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(st) { player.seekTo(0); player.play() }
                if (app.playback.next != null) SkipButton(Sym.SkipNext.fill, tr("Next")) { app.playback.skipNext() }
            }
        }
        // bottom: seek bar, then the buttons
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .onFocusChanged { onBarFocus(it.hasFocus) }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(horizontal = 12.dp).padding(top = 24.dp, bottom = 4.dp),
        ) {
            SeekBar(app, st, dragging, onDrag) { f -> player.seekTo((f * st.durationMs).toLong()); onDragEnd() }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Bar(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"),
                    playFocus?.let { Modifier.focusRequester(it) } ?: Modifier) {
                    if (st.ended) { player.seekTo(0); player.play() } else if (st.playing) player.pause() else player.play()
                }
                if (app.playback.next != null) Bar(Sym.SkipNext.fill, tr("Next")) { app.playback.skipNext() }
                if (player.hasVolume) Volume(app, st)
                Spacer(Modifier.width(8.dp))
                TimeText(app, st, dragging)
                Spacer(Modifier.weight(1f))
                if (st.speed != 1f) Text("${st.speed}x", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp).clip(CircleShape).background(PILL).clickable { setSettings(true) }.padding(horizontal = 10.dp, vertical = 3.dp))
                CastButton(app, iconSize = 26)
                CaptionsButton(app, st) {}
                Box {
                    Bar(Sym.Settings.fill, tr("Settings")) { setSettings(!settings) }
                    DropdownMenu(settings, { setSettings(false) }) {
                        Column(Modifier.width(260.dp)) { SettingsPages(app) { setSettings(false) } }
                    }
                }
                FullscreenButton(app, fullscreen)
            }
        }
    }
}

@Composable
private fun Bar(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun Volume(app: AppState, st: PlayerState) {
    val player = app.player
    Bar(if (st.muted || st.volume == 0f) Sym.VolumeOff.fill else Sym.VolumeUp.fill, tr(if (st.muted) "Unmute" else "Mute")) {
        player.setMuted(!st.muted)
    }
    Track(
        value = if (st.muted) 0f else st.volume, color = Color.White,
        onDrag = { v -> player.setMuted(false); player.setVolume(v) },
        onDone = { v -> if (app.casting.link == null) app.store.settings.update { it.copy(volume = v) } },
        modifier = Modifier.width(88.dp).padding(end = 4.dp),
        label = tr("Volume"), state = "${((if (st.muted) 0f else st.volume) * 100).toInt()}%",
    )
}

// ---------------------------------------------------------------------- shared pieces

/** the time, and the chapter playing (which opens the list of chapters), as on YouTube */
@Composable
private fun TimeText(app: AppState, st: PlayerState, dragging: Float?) {
    val pos = dragging?.let { (it * st.durationMs).toLong() } ?: st.positionMs
    var list by remember { mutableStateOf(false) }
    val chapter = app.playback.chapters.lastOrNull { it.startMs <= pos }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (app.playback.video?.live == true) "● " + tr("LIVE") else "${durationMs(pos)} / ${durationMs(st.durationMs)}",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
        if (chapter != null && chapter.title.isNotEmpty()) Text(
            " • " + chapter.title + " ›", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(4.dp)).clickable { list = true },
        )
    }
    if (list) ChaptersSheet(app) { list = false }
}

/** every chapter with its picture and time; the one playing is marked, a press jumps there */
@Composable
internal fun ChaptersSheet(app: AppState, onClose: () -> Unit) {
    val player = app.player
    val pos = player.state.collectAsState().value.positionMs
    val chapters = app.playback.chapters
    val now = chapters.lastOrNull { it.startMs <= pos }
    ModalBottomSheet(onDismissRequest = onClose) {
        Text(tr("Chapters"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(chapters) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { player.seekTo(c.startMs); onClose() }
                        .background(if (c == now) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (c.thumb.isNotEmpty()) Img(c.thumb, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(durationMs(c.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FullscreenButton(app: AppState, fullscreen: Boolean) {
    IconButton(onClick = { app.setFull(!fullscreen) }) {
        Icon(if (fullscreen) Sym.FullscreenExit.fill else Sym.Fullscreen.fill, tr(if (fullscreen) "Exit full screen" else "Full screen"), tint = Color.White)
    }
}

/** On or off with one press: the track in your language, or YouTube's translation of one. */
@Composable
private fun CaptionsButton(app: AppState, st: PlayerState, onPress: () -> Unit) {
    val player = app.player
    val caps = app.playback.stream?.captions ?: emptyList()
    if (caps.isEmpty()) return
    IconButton(onClick = {
        onPress()
        if (st.captions != null) {
            player.setCaptions(null, null); app.store.settings.update { it.copy(captions = false) }
        } else {
            val (c, translate) = app.playback.captionChoice() ?: return@IconButton
            player.setCaptions(c, translate)
            app.store.settings.update { it.copy(captions = true) }
        }
    }) {
        Icon(if (st.captions != null) Sym.ClosedCaption.fill else Sym.ClosedCaptionOff.outline, tr("Captions"), tint = Color.White)
    }
}

/** the seek bar: gaps between chapters, and while dragging the picture, chapter and time at that point */
@Composable
private fun SeekBar(app: AppState, st: PlayerState, dragging: Float?, onDrag: (Float) -> Unit, onDone: (Float) -> Unit) {
    if (st.durationMs <= 0) { Spacer(Modifier.height(24.dp)); return }
    val chapters = app.playback.chapters
    val board = app.playback.storyboard
    val live = app.playback.video?.live == true
    val heat = app.playback.details?.heat?.takeIf { app.store.settings.value.heatmap && !live }
    Track(
        value = dragging ?: (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
        buffered = (st.bufferedMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
        color = Red, onDrag = onDrag, onDone = onDone,
        modifier = Modifier.fillMaxWidth(),
        marks = chapters.drop(1).map { it.startMs.toFloat() / st.durationMs },
        heat = heat?.points ?: emptyList(),
        spans = if (live || !app.store.settings.value.sponsor) emptyList() else app.playback.segments.map { s ->
            (s.startMs.toFloat() / st.durationMs)..(s.endMs.toFloat() / st.durationMs) to Color(SponsorBlock.color(s.category))
        },
        heatSpan = (heat?.spanMs ?: 0L).toFloat() / st.durationMs,
        label = tr("Seek bar"), state = tr("{a} of {b}").replace("{a}", durationMs(st.positionMs)).replace("{b}", durationMs(st.durationMs)),
        above = if (live) null else { f ->
            val ms = (f * st.durationMs).toLong()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (board != null && !app.store.settings.value.hideThumbs) {
                    val fr = board.frame(ms, st.durationMs)
                    val w = 160.dp
                    val h = w * board.height / maxOf(1, board.width)
                    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).border(2.dp, Color.White, RoundedCornerShape(6.dp))) {
                        AsyncImage(
                            fr.url, null, contentScale = ContentScale.FillBounds,
                            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                                .requiredSize(w * board.cols, h * board.rows).offset(-w * fr.col, -h * fr.row),
                        )
                    }
                }
                // near the top of the wave: YouTube's own label for it
                val peak = heat?.peakMs
                val hot = peak != null && abs(ms - peak) <= maxOf(2000L, st.durationMs * 12 / 1000)
                val title = if (hot) tr("Most replayed") else chapters.lastOrNull { it.startMs <= ms }?.title ?: ""
                if (title.isNotEmpty()) Text(title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp).padding(top = 4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp))
                Text(durationMs(ms), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp))
            }
        },
    )
}

/**
 * A thin bar with a round handle, YouTube's: press or drag anywhere on it. The bar thickens and
 * the handle grows while the pointer is over it or a finger holds it.
 */
@Composable
internal fun Track(
    value: Float, color: Color, onDrag: (Float) -> Unit, onDone: (Float) -> Unit,
    modifier: Modifier = Modifier, buffered: Float = 0f,
    /** where chapters begin (0..1): small gaps in the bar */
    marks: List<Float> = emptyList(),
    /** drawn above the handle while it is held (the seek bar's preview) */
    above: (@Composable (Float) -> Unit)? = null,
    /** "Most replayed": 0..1 for equal slices of the first [heatSpan] of the bar */
    heat: List<Float> = emptyList(),
    heatSpan: Float = 1f,
    /** parts drawn in a colour of their own (SponsorBlock's); a single point shows as a short mark */
    spans: List<Pair<ClosedFloatingPointRange<Float>, Color>> = emptyList(),
    /** for screen readers: what the bar is ("Seek bar") and where it is now ("1:23 of 14:28") */
    label: String = "",
    state: String = "",
    /** the bar under the played part; rounded ends (the music player's) */
    rail: Color = Color(0x44FFFFFF),
    rounded: Boolean = false,
) {
    var hover by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Float?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val active = hover || held != null
    val shown = held ?: value
    BoxWithConstraints(
        modifier.height(24.dp)
            .semantics {
                if (label.isNotEmpty()) contentDescription = label
                if (state.isNotEmpty()) stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress { v -> val f = v.coerceIn(0f, 1f); onDrag(f); onDone(f); true }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent(PointerEventPass.Initial).type) {
                            PointerEventType.Enter -> hover = true
                            PointerEventType.Exit -> hover = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var f = at(down.position.x)
                    held = f; onDrag(f)
                    if (down.type == androidx.compose.ui.input.pointer.PointerType.Touch) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        f = at(c.position.x)
                        held = f; onDrag(f)
                    }
                    held = null
                    onDone(f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thick by androidx.compose.animation.core.animateDpAsState(if (active) 5.dp else 3.dp, androidx.compose.animation.core.spring(stiffness = 600f))
        val wide = maxWidth
        // "Most replayed": YouTube's wave standing on the bar while the pointer is on it or a finger drags
        if (heat.size >= 2) {
            val grow by animateFloatAsState(if (active) 1f else 0f, tween(180))
            if (grow > 0f) Canvas(
                Modifier.fillMaxWidth(heatSpan.coerceIn(0.05f, 1f)).requiredHeight(HEAT_H).offset(y = -(HEAT_H / 2 + thick / 2))
                    .graphicsLayer { alpha = grow; scaleY = 0.3f + 0.7f * grow; transformOrigin = TransformOrigin(0.5f, 1f) },
            ) { drawPath(heatPath(heat, size), Color(0x47FFFFFF)) }
        }
        Box(Modifier.fillMaxWidth().height(thick).let { if (rounded) it.clip(RoundedCornerShape(50)) else it }.background(rail)) {
            if (buffered > 0f) Box(Modifier.fillMaxWidth(buffered).height(thick).background(Color(0x77FFFFFF)))
            Box(Modifier.fillMaxWidth(shown).height(thick).background(color))
            for ((r, c) in spans) {
                val a = r.start.coerceIn(0f, 1f)
                Box(Modifier.offset(x = wide * a).width(maxOf(3.dp, wide * (r.endInclusive.coerceIn(a, 1f) - a))).height(thick).background(c))
            }
            for (m in marks) if (m in 0.01f..0.99f) Box(Modifier.offset(x = wide * m - 1.dp).width(2.dp).height(thick).background(Color(0xCC000000)))
        }
        val full = constraints.maxWidth
        val h = held
        if (above != null && h != null) Box(Modifier.layout { m, _ ->
            val p = m.measure(Constraints())
            layout(0, 0) { p.place((full * h - p.width / 2f).toInt().coerceIn(0, maxOf(0, full - p.width)), -p.height - 16.dp.roundToPx()) }
        }) { above(h) }
        // the handle: 12dp, 20dp while dragging (YouTube's sizes)
        val knob by androidx.compose.animation.core.animateDpAsState(if (held != null) 20.dp else if (active) 14.dp else 12.dp, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 700f))
        Box(Modifier.offset(x = maxWidth * shown - knob / 2).size(knob).background(color, CircleShape))
    }
}

/** SponsorBlock on the video: "Skip sponsor" (or "Jump to the highlight"), and "Sponsor skipped · Undo" after one went by itself */
@Composable
internal fun BoxScope.SponsorPill(app: AppState, lifted: Boolean) {
    val pb = app.playback
    val done = pb.justSkipped
    val seg = done ?: pb.skippable ?: return
    val name = tr(SponsorBlock.cat(seg.category)?.label ?: "Sponsor")
    Row(
        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = if (lifted) 76.dp else 16.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xD91F1F1F)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
            .clickable { if (done != null) pb.undoSkip() else pb.skipSegment() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color(SponsorBlock.color(seg.category)), CircleShape))
        Spacer(Modifier.width(8.dp))
        val text = when {
            done != null -> tr("{n} skipped").replace("{n}", name)
            seg.category == SponsorBlock.HIGHLIGHT -> tr("Jump to the highlight")
            else -> tr("Skip {n}").replace("{n}", name)
        }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (done != null) Text("   " + tr("Undo"), color = Color(0xFF3EA6FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        else Icon(Sym.SkipNext.fill, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

private val HEAT_H = 36.dp

/** the wave through the slices' middles: flat curves between them, as YouTube draws it */
private fun heatPath(pts: List<Float>, s: Size): Path = Path().apply {
    val n = pts.size
    fun x(i: Int) = s.width * (i + 0.5f) / n
    fun y(i: Int) = s.height * (1f - maxOf(0.04f, pts[i]) * 0.92f)
    moveTo(0f, s.height)
    lineTo(0f, y(0))
    lineTo(x(0), y(0))
    for (i in 1 until n) {
        val mx = (x(i - 1) + x(i)) / 2
        cubicTo(mx, y(i - 1), mx, y(i), x(i), y(i))
    }
    lineTo(s.width, y(n - 1))
    lineTo(s.width, s.height)
    close()
}

/**
 * Quality, speed, audio track, captions, loop, sleep timer, ambient mode and stats for nerds:
 * a sheet on phones, a menu on the desktop.
 */
@Composable
internal fun ColumnScope.SettingsPages(app: AppState, pageState: androidx.compose.runtime.MutableState<String>? = null, dismiss: () -> Unit) {
    val player = app.player
    val st by player.state.collectAsState()
    val own = remember { mutableStateOf("") }
    var page by (pageState ?: own)
    when (page) {
        "" -> {
            val pb = app.playback
            val codec = app.store.settings.state.collectAsState().value.codec
            val families = pb.stream?.video?.map { it.codecFamily }?.distinct().orEmpty()
            SheetHeader(tr("Video"))
            SheetGroup(app) {
            SheetRow(Sym.HighQuality.outline, tr("Quality"), (if (st.height > 0) "${st.height}p" else tr("Auto")) + codecName(codec).let { if (it.isEmpty()) "" else " · $it" }) { page = "q" }
            if (families.size > 1) SheetRow(Sym.Tune.outline, tr("Video codec"), codecName(codec).ifEmpty { tr("Auto") }) { page = "k" }
            if (st.audioTracks.size > 1) {
                SheetRow(Sym.VolumeUp.outline, tr("Audio track"), st.audioTracks.firstOrNull { it.first == st.audioTrack }?.second ?: "") { page = "a" }
            }
            if (app.platform.name == "android") {
                val ambient = app.store.settings.state.collectAsState().value.ambient
                SheetRow(Sym.BrightnessMedium.outline, tr("Ambient mode"), tr(if (ambient) "On" else "Off")) {
                    app.store.settings.update { it.copy(ambient = !ambient) }
                }
            }
            if (!app.playback.stream?.captions.isNullOrEmpty()) {
                SheetRow(Sym.ClosedCaption.fill, tr("Captions"), st.captions?.let { c -> st.captionsTranslated?.let { l -> tr("Auto-translate") + " (" + langName(l) + ")" } ?: c.name } ?: tr("Off")) { page = "c" }
                if (!pb.music && !app.platform.tv) SheetRow(Sym.Transcript.outline, tr("Transcript"), "") { pb.transcriptAsked = true; dismiss() }
            }
            }
            SheetHeader(tr("Playback"))
            SheetGroup(app) {
            SheetRow(Sym.Speed.outline, tr("Speed"), if (st.speed == 1f) tr("Normal") else "${st.speed}x") { page = "s" }
            SheetRow(Sym.Replay.outline, tr("Loop video"), tr(if (pb.loop) "On" else "Off")) { pb.loopVideo(!pb.loop); dismiss() }
            SheetRow(Sym.SwapVert.outline, tr("A-B repeat"), when {
                pb.abB > 0 -> durationMs(pb.abA) + "–" + durationMs(pb.abB)
                pb.abA >= 0 -> "A " + durationMs(pb.abA)
                else -> tr("Off")
            }) { pb.abStep(); dismiss() }
            SheetRow(Sym.WatchLater.outline, tr("Sleep timer"), when {
                pb.sleepAt == Playback.END -> tr("End of video")
                pb.sleepAt > 0 -> tr("{n} min left").replace("{n}", "${((pb.sleepAt - Sys.nowMs()) / 60_000 + 1)}")
                else -> tr("Off")
            }) { page = "t" }
            }
            SheetHeader(tr("More"))
            SheetGroup(app) {
            SheetRow(Sym.Bolt.outline, tr("Stats for nerds"), "") { page = "n" }
            }
            Spacer(Modifier.height(8.dp))
        }
        "k" -> {
            val now = app.store.settings.value.codec
            val families = app.playback.stream?.video?.map { it.codecFamily }?.distinct().orEmpty()
            Pick(tr("Auto"), now.isEmpty()) { setCodec(app, ""); dismiss() }
            for (f in listOf("h264", "vp9", "av1")) if (f in families) Pick(codecName(f), now == f) { setCodec(app, f); dismiss() }
        }
        "t" -> {
            val pb = app.playback
            Pick(tr("Off"), pb.sleepAt == 0L) { pb.sleep(0); dismiss() }
            for (m in listOf(10, 15, 20, 30, 45, 60, 90)) Pick(tr("{n} minutes").replace("{n}", "$m"), false) { pb.sleep(m); dismiss() }
            Pick(tr("End of video"), pb.sleepAt == Playback.END) { pb.sleep(-1); dismiss() }
        }
        "n" -> {
            val s = app.playback.stream
            val v = s?.video?.filter { st.height == 0 || it.height == st.height }?.maxByOrNull { it.height * 1000 + it.bitrate / 1000 }
            val a = s?.audio?.firstOrNull { st.audioTrack == null || it.audioTrack == null || it.audioTrack.startsWith(st.audioTrack!!) }
            val lines = listOfNotNull(
                tr("Video ID") to (s?.id ?: st.videoId ?: ""),
                tr("Through") to (s?.client ?: ""),
                tr("Resolution") to (v?.let { "${it.width}x${it.height}@${it.fps}" + if (st.height == 0) " (" + tr("Auto") + ")" else "" } ?: ""),
                tr("Video codec") to (v?.let { "${it.codecs} (itag ${it.itag}, ${it.bitrate / 1000} kbps)" } ?: ""),
                tr("Audio codec") to (a?.let { "${it.codecs} (itag ${it.itag}, ${it.bitrate / 1000} kbps, ${it.sampleRate} Hz)" } ?: ""),
                tr("Buffer health") to "${(st.bufferedMs - st.positionMs).coerceAtLeast(0) / 100 / 10.0} s",
                tr("Speed") to "${st.speed}x",
                s?.live?.takeIf { it }?.let { tr("LIVE") to (s.hls?.let { "HLS" } ?: "") },
            ).filter { it.second.isNotEmpty() }
            SelectableInSheet(app) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    for ((k, x) in lines) Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(x, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        "q" -> {
            Pick(tr("Auto"), st.height == 0) { player.setHeight(0); dismiss() }
            for (h in st.heights.sortedDescending()) Pick("${h}p", st.height == h) { player.setHeight(h); dismiss() }
        }
        "a" -> for ((lang, name) in st.audioTracks) {
            Pick(name, st.audioTrack == lang) { player.setAudioTrack(lang); dismiss() }
        }
        "s" -> for (x in SPEEDS) {
            Pick(if (x == 1f) tr("Normal") else "${x}x", st.speed == x) {
                app.playback.setSpeed(x); dismiss()
            }
        }
        "c" -> {
            val caps = app.playback.stream?.captions ?: emptyList()
            val lang = app.store.settings.value.let { s -> s.captionLang.ifEmpty { s.lang.ifEmpty { "en" } } }
            Pick(tr("Off"), st.captions == null) {
                player.setCaptions(null, null); app.store.settings.update { it.copy(captions = false) }; dismiss()
            }
            for (c in caps) Pick(c.name, st.captions == c && st.captionsTranslated == null) {
                player.setCaptions(c, null); app.store.settings.update { it.copy(captions = true) }; dismiss()
            }
            if (caps.none { it.lang.substringBefore('-') == lang }) caps.firstOrNull()?.let { c ->
                Pick(tr("Auto-translate") + " (" + langName(lang) + ")", st.captions != null && st.captionsTranslated == lang) {
                    player.setCaptions(c, lang); app.store.settings.update { it.copy(captions = true) }; dismiss()
                }
            }
            // YouTube translates into any of these, not only the app's language (#62)
            if (caps.isNotEmpty() && app.playback.stream?.client != "offline") SheetRow(Sym.Language.outline, tr("Translate to"), langName(lang)) { page = "l" }
        }
        "l" -> {
            val now = app.store.settings.value.let { s -> s.captionLang.ifEmpty { s.lang.ifEmpty { "en" } } }
            for ((code, name) in CAPTION_LANGS) Pick(name, code == now) {
                app.store.settings.update { it.copy(captionLang = code, captions = true) }
                app.playback.captionChoice()?.let { (c, t) -> player.setCaptions(c, t) }
                dismiss()
            }
        }
    }
}

/** the languages captions can be read or translated in: the app's own, then more that YouTube translates into */
private val CAPTION_LANGS = LANGS + listOf(
    "ar" to "العربية", "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski", "uk" to "Українська",
    "vi" to "Tiếng Việt", "th" to "ไทย", "fa" to "فارسی", "he" to "עברית", "ur" to "اردو", "sv" to "Svenska",
    "el" to "Ελληνικά", "cs" to "Čeština", "ro" to "Română", "hu" to "Magyar", "ms" to "Bahasa Melayu",
    "fil" to "Filipino", "ta" to "தமிழ்",
)

private fun langName(code: String) = CAPTION_LANGS.firstOrNull { it.first == code }?.second ?: code

private fun codecName(c: String) = when (c) { "h264" -> "H.264"; "vp9" -> "VP9"; "av1" -> "AV1"; else -> "" }

/** the codec picked here is kept (Settings → Quality) and the video reloads where it is */
private fun setCodec(app: AppState, c: String) {
    if (app.store.settings.value.codec == c) return
    app.store.settings.update { it.copy(codec = c) }
    app.playback.replay(app.player.state.value.positionMs)
}

/** a small heading in the accent colour between the groups of the sheet */
@Composable
private fun SheetHeader(t: String) {
    if (LocalSegmented.current || LocalCompact.current) SectionLabel(t, Modifier.padding(start = 0.dp))
    else Text(t, Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** rows in a group: on a touch screen, segmented pieces; the desktop's menu and a TV keep plain rows */
private val LocalSegmented = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun SheetGroup(app: AppState, content: @Composable () -> Unit) {
    if (!app.platform.touch || app.platform.tv) { content(); return }
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSegmented provides true) { content() }
    }
}

/** a row of the settings sheet; with a remote or keyboard the focused one turns solid, so you can see where you are */
@Composable
private fun SheetLine(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val cs = MaterialTheme.colorScheme
    val seg = LocalSegmented.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (focused) cs.surface else androidx.compose.material3.LocalContentColor.current) {
        Row(
            Modifier.fillMaxWidth().then(if (seg) Modifier.clip(RoundedCornerShape(6.dp)) else Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)))
                .background(if (focused) cs.onSurface else if (seg) cs.surfaceContainerHigh else Color.Transparent)
                .clickable(src, androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(focused) }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    SheetLine(onClick) { focused ->
        Icon(icon, null, tint = if (LocalSegmented.current && !focused) accent else androidx.compose.material3.LocalContentColor.current)
        Text(label, Modifier.weight(1f).padding(start = 20.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false)
        // the value stays on one line and gives way first (a long audio track name squeezed the label, #70)
        Text(value, Modifier.padding(start = 12.dp).widthIn(max = 170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pick(label: String, on: Boolean, onClick: () -> Unit) {
    SheetLine(onClick) {
        Box(Modifier.width(40.dp)) { if (on) Icon(Sym.Check.fill, null) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** the half-moon of a double-tap jump over one side of the video ([w] the video's width) */
@Composable
private fun BoxScope.JumpRipple(side: Int, secs: Int, at: Offset, tick: Int, w: Float) {
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(tick) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(500, easing = Motion.out))
    }
    val shade = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { shade.animateTo(1f, tween(Motion.SHORT)) }
    Box(
        Modifier.fillMaxHeight().fillMaxWidth(0.42f).align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
            .clipToBounds()
            .drawBehind {
                // the half-moon: an oval much taller than the video, cut by its edge
                val oval = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(
                        if (side < 0) -size.width else 0f, -size.height * 0.35f,
                        if (side < 0) size.width else size.width * 2f, size.height * 1.35f,
                    ))
                }
                drawPath(oval, Color.White.copy(alpha = 0.16f * shade.value))
                val local = if (side < 0) at else Offset(at.x - (w - size.width), at.y)
                clipPath(oval) {
                    drawCircle(Color.White.copy(alpha = 0.22f * (1f - grow.value)), radius = size.maxDimension * (0.15f + grow.value), center = local)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Arrows(side, Modifier.size(width = 36.dp, height = 16.dp), running = true)
            Text(tr("{n} seconds").replace("{n}", "$secs"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun Modifier.clipToBounds() = this.then(Modifier.clipToBounds())

/** three small triangles pointing the way of [dir]; [running]: lit one after another, as YouTube's */
@Composable
private fun Arrows(dir: Int, modifier: Modifier, running: Boolean = false) {
    val t = if (running) {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition()
        inf.animateFloat(0f, 3f, androidx.compose.animation.core.infiniteRepeatable(tween(750, easing = androidx.compose.animation.core.LinearEasing))).value
    } else -1f
    Canvas(modifier) {
        val cw = size.width / 3f
        for (i in 0 until 3) {
            // the arrow nearest the jump's way lights last
            val order = if (dir > 0) i else 2 - i
            val a = if (t < 0f) 1f else (1f - (((t - order) % 3f + 3f) % 3f).coerceAtMost(1f)).coerceIn(0.3f, 1f)
            val x0 = i * cw
            val tri = Path().apply {
                if (dir > 0) { moveTo(x0 + cw * 0.15f, 0f); lineTo(x0 + cw * 0.95f, size.height / 2); lineTo(x0 + cw * 0.15f, size.height) }
                else { moveTo(x0 + cw * 0.85f, 0f); lineTo(x0 + cw * 0.05f, size.height / 2); lineTo(x0 + cw * 0.85f, size.height) }
                close()
            }
            drawPath(tri, Color.White.copy(alpha = a))
        }
    }
}

/**
 * The countdown on a finished video, as YouTube's: the next one's picture with a ring
 * that fills over five seconds, its title, and Cancel / Play now. Taps elsewhere are swallowed.
 */
@Composable
private fun UpNextCard(app: AppState, n: Video, fullscreen: Boolean) {
    val ring = remember(n.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(n.id) { ring.animateTo(1f, tween(UP_NEXT_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
    val left = ((1f - ring.value) * UP_NEXT_MS / 1000f).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(0)
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Up next") + " · $left", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(if (fullscreen) 168.dp else 112.dp).aspectRatio(16f / 9f)) {
                    Thumb(n, app, Modifier.fillMaxSize(), rounded = 10)
                    // the ring: a red line round the picture's edge, filling clockwise
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val r = 10.dp.toPx()
                        val path = androidx.compose.ui.graphics.Path().apply {
                            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r)))
                        }
                        val m = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                        val part = androidx.compose.ui.graphics.Path()
                        m.getSegment(0f, m.length * ring.value, part, true)
                        drawPath(part, Red, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(n.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (n.author.isNotEmpty()) Text(n.author, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { app.playback.cancelUpNext() },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66FFFFFF)),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text(tr("Cancel")) }
                androidx.compose.material3.Button(
                    onClick = { app.playback.playUpNext() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text(tr("Play now")) }
            }
        }
    }
}
