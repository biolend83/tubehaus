package app.tubehaus.ui

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
fun VideoArea(app: AppState, modifier: Modifier = Modifier, fullscreen: Boolean = false, sideGestures: Boolean = false) {
    Box(modifier.background(Color.Black)) {
        app.platform.player.View(Modifier.fillMaxSize())
        Controls(app, fullscreen, sideGestures)
    }
}

/** Keyboard shortcuts while a video is on screen (YouTube's). True when the key was used. */
fun playerKey(app: AppState, key: Key): Boolean {
    val player = app.platform.player
    val st = player.state.value
    if (st.videoId == null) return false
    fun seekBy(sec: Int) = player.seekTo((st.positionMs + sec * 1000L).coerceIn(0, maxOf(0, st.durationMs - 500)))
    fun volumeBy(d: Float) {
        val v = (st.volume + d).coerceIn(0f, 1f)
        player.setVolume(v); player.setMuted(false)
        app.store.settings.update { it.copy(volume = v) }
    }
    when (key) {
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

/** brightness the swipe works in (what the eye sees, gamma 2.2) and the screen's own value */
private fun perceived(b: Float) = Math.pow(b.coerceIn(0f, 1f).toDouble(), 1 / 2.2).toFloat()
private fun actual(p: Float) = Math.pow(p.coerceIn(0f, 1f).toDouble(), 2.2).toFloat().coerceAtLeast(0.004f)

@Composable
private fun Controls(app: AppState, fullscreen: Boolean, sideGestures: Boolean) {
    val touch = app.platform.touch
    val player = app.platform.player
    val st by player.state.collectAsState()
    var shown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }          // any touch or mouse move restarts the hide timer
    var dragging by remember { mutableStateOf<Float?>(null) }
    var jump by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // side (-1/1), seconds
    var settings by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf<Pair<String, Float>?>(null) }   // "brightness"/"volume", level
    // TV: the video itself holds the remote's focus (OK, left/right); up or down moves it onto the buttons
    val tv = app.platform.tv
    val videoFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var toBar by remember { mutableStateOf(false) }
    var eatBack by remember { mutableStateOf(false) }
    // read inside the pointer loops, which outlive the composition they started in
    val playingNow by rememberUpdatedState(st.playing)
    val settingsNow by rememberUpdatedState(settings)

    LaunchedEffect(shown, poke, st.playing, dragging, settings) {
        if (shown && st.playing && dragging == null && !settings) {
            delay(if (touch) 3000 else 2500)
            shown = false
        }
    }
    LaunchedEffect(jump) {
        if (jump != null) { delay(700); jump = null }
    }
    if (tv) {
        LaunchedEffect(Unit) { runCatching { videoFocus.requestFocus() } }
        // the buttons fade away: the focus goes back to the video instead of getting lost
        LaunchedEffect(shown) { if (!shown) runCatching { videoFocus.requestFocus() } }
        LaunchedEffect(toBar, shown) {
            if (toBar && shown) { delay(60); runCatching { playFocus.requestFocus() }; toBar = false }
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
            }
            Key.DirectionUp, Key.DirectionDown -> if (fullscreen) toBar = true else return false
            else -> return playerKey(app, key)
        }
        shown = true; poke++
        return true
    }

    val blank = app.platform.blankPointer
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
        // taps and clicks anywhere on the video
        Box(
            Modifier.fillMaxSize()
                .let { if (tv) it.onKeyEvent { e -> e.type == KeyEventType.KeyDown && remote(e.key) }.focusRequester(videoFocus).focusable() else it }
                .pointerInput(touch, st.durationMs, w) {
                    detectTapGestures(
                        onTap = { o ->
                            when {
                                !touch -> toggle()
                                // tap to pause: the middle of the video; the outer quarters only show or hide the controls
                                app.store.settings.value.tapToPause && o.x in w * 0.25f..w * 0.75f -> { toggle(); shown = true; poke++ }
                                else -> { shown = !shown; poke++ }
                            }
                        },
                        onDoubleTap = { o ->
                            if (!touch) { app.setFull(!fullscreen); return@detectTapGestures }
                            val side = when {
                                o.x < w * 0.4f -> -1
                                o.x > w * 0.6f -> 1
                                else -> 0
                            }
                            if (side == 0) toggle()
                            else {
                                seekBy(side * 10)
                                jump = side to ((if (jump?.first == side) jump!!.second else 0) + 10)
                            }
                        },
                    )
                }
                // full screen on a phone: swipe up or down on the left for brightness, on the right for volume;
                // beside the comments on a tablet the same, on the outer thirds (the middle still
                // swipes the video down into the mini player)
                .pointerInput(touch, fullscreen, sideGestures) {
                    if (!touch || !(fullscreen || sideGestures)) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = down.position.x
                        if (!fullscreen && x in size.width / 3f..size.width * 2f / 3f) return@awaitEachGesture
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
        // a small round meter on the side being swiped, out of the middle of the picture
        gesture?.let { (kind, level) ->
            Column(
                Modifier.align(if (kind == "brightness") Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 40.dp)
                    .background(Color(0x80000000), RoundedCornerShape(36.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val icon = when {
                    kind == "brightness" -> Icons.Filled.BrightnessMedium
                    level == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { level }, color = Color.White, trackColor = Color(0x40FFFFFF), strokeWidth = 4.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text("${(level * 100).toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // the "10 seconds" bubble of a double-tap
        jump?.let { (side, secs) ->
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(0.35f).align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.background(Color(0x66000000), CircleShape).padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(if (side < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10, null, tint = Color.White)
                    Text(tr("{n} seconds").replace("{n}", "$secs"), color = Color.White, fontSize = 12.sp)
                }
            }
        }
        if (st.buffering && (!shown || !touch)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White, strokeWidth = 3.dp)
        }
        AnimatedVisibility(shown || st.ended || !st.playing && !touch, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (touch) TouchOverlay(app, st, fullscreen, dragging, { dragging = it; poke++ }, { dragging = null }, ::seekBy, { poke++ }, { settings = true })
            else MouseOverlay(app, st, fullscreen, dragging, { dragging = it; poke++ }, { dragging = null }, settings, { settings = it; poke++ }, playFocus.takeIf { tv })
        }
        // a thin progress line while the controls are hidden (not in full screen)
        if (!shown && !fullscreen && st.durationMs > 0 && st.playing) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(2.dp).background(Red))
            }
        }
    }
    if (touch && settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { SettingsPages(app) { settings = false } }
        }
    }
}

// ---------------------------------------------------------------------- phones

@Composable
private fun TouchOverlay(
    app: AppState, st: PlayerState, fullscreen: Boolean, dragging: Float?,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit, seekBy: (Int) -> Unit, poke: () -> Unit, openSettings: () -> Unit,
) {
    val player = app.platform.player
    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        // top: back and title in full screen; captions and settings on the right
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 4.dp, vertical = if (fullscreen) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White) }
            Text(
                if (fullscreen) app.playback.video?.title ?: "" else "",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CaptionsButton(app, st) { poke() }
            IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, tr("Settings"), tint = Color.White) }
        }
        // centre: back 10s, play/pause, forward 10s (or next when it has ended)
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            CenterButton(Icons.Filled.Replay10, tr("Back 10 seconds"), 40) { seekBy(-10); poke() }
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                when {
                    st.buffering && !st.ended -> CircularProgressIndicator(Modifier.size(48.dp), color = Color.White, strokeWidth = 3.dp)
                    st.ended -> CenterButton(Icons.Filled.Replay, tr("Replay"), 56) { player.seekTo(0); player.play() }
                    else -> CenterButton(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, tr(if (st.playing) "Pause" else "Play"), 56) {
                        if (st.playing) player.pause() else player.play(); poke()
                    }
                }
            }
            if (st.ended && app.playback.next != null) CenterButton(Icons.Filled.SkipNext, tr("Next"), 40) { app.playback.skipNext() }
            else CenterButton(Icons.Filled.Forward10, tr("Forward 10 seconds"), 40) { seekBy(10); poke() }
        }
        // bottom: time, full screen, seek bar
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .let { if (fullscreen) it.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp) else it },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeText(app, st, dragging)
                Spacer(Modifier.weight(1f))
                if (st.speed != 1f) Text("${st.speed}x", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
                FullscreenButton(app, fullscreen)
            }
            SeekBar(st, dragging, onDrag) { f -> player.seekTo((f * st.durationMs).toLong()); onDragEnd() }
        }
    }
}

@Composable
private fun CenterButton(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).background(Color(0x44000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp)) }
}

// ---------------------------------------------------------------------- mouse and keyboard

@Composable
private fun MouseOverlay(
    app: AppState, st: PlayerState, fullscreen: Boolean, dragging: Float?,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit, settings: Boolean, setSettings: (Boolean) -> Unit,
    playFocus: FocusRequester? = null,
) {
    val player = app.platform.player
    Box(Modifier.fillMaxSize()) {
        // full screen: the title over a shade at the top
        if (fullscreen) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.setFull(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White) }
                Text(
                    app.playback.video?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (LocalSideComments.current) IconButton(onClick = { app.fsComments = !app.fsComments }) {
                    Icon(if (app.fsComments) Icons.Filled.Comment else Icons.Outlined.Comment, tr("Comments"), tint = Color.White)
                }
            }
        }
        if (st.ended) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                CenterButton(Icons.Filled.Replay, tr("Replay"), 64) { player.seekTo(0); player.play() }
                if (app.playback.next != null) CenterButton(Icons.Filled.SkipNext, tr("Next"), 64) { app.playback.skipNext() }
            }
        }
        // bottom: seek bar, then the buttons
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(horizontal = 12.dp).padding(top = 24.dp, bottom = 4.dp),
        ) {
            SeekBar(st, dragging, onDrag) { f -> player.seekTo((f * st.durationMs).toLong()); onDragEnd() }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Bar(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, tr(if (st.playing) "Pause" else "Play"),
                    playFocus?.let { Modifier.focusRequester(it) } ?: Modifier) {
                    if (st.ended) { player.seekTo(0); player.play() } else if (st.playing) player.pause() else player.play()
                }
                if (app.playback.next != null) Bar(Icons.Filled.SkipNext, tr("Next")) { app.playback.skipNext() }
                if (player.hasVolume) Volume(app, st)
                Spacer(Modifier.width(8.dp))
                TimeText(app, st, dragging)
                Spacer(Modifier.weight(1f))
                if (st.speed != 1f) Text("${st.speed}x", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
                CaptionsButton(app, st) {}
                Box {
                    Bar(Icons.Filled.Settings, tr("Settings")) { setSettings(!settings) }
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
    val player = app.platform.player
    Bar(if (st.muted || st.volume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, tr(if (st.muted) "Unmute" else "Mute")) {
        player.setMuted(!st.muted)
    }
    Track(
        value = if (st.muted) 0f else st.volume, color = Color.White,
        onDrag = { v -> player.setMuted(false); player.setVolume(v) },
        onDone = { v -> app.store.settings.update { it.copy(volume = v) } },
        modifier = Modifier.width(88.dp).padding(end = 4.dp),
    )
}

// ---------------------------------------------------------------------- shared pieces

@Composable
private fun TimeText(app: AppState, st: PlayerState, dragging: Float?) {
    val pos = dragging?.let { (it * st.durationMs).toLong() } ?: st.positionMs
    Text(
        if (app.playback.video?.live == true) "● " + tr("LIVE") else "${durationMs(pos)} / ${durationMs(st.durationMs)}",
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun FullscreenButton(app: AppState, fullscreen: Boolean) {
    IconButton(onClick = { app.setFull(!fullscreen) }) {
        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, tr(if (fullscreen) "Exit full screen" else "Full screen"), tint = Color.White)
    }
}

/** On or off with one press: the track in your language, or YouTube's translation of one. */
@Composable
private fun CaptionsButton(app: AppState, st: PlayerState, onPress: () -> Unit) {
    val player = app.platform.player
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
        Icon(if (st.captions != null) Icons.Filled.ClosedCaption else Icons.Outlined.ClosedCaptionOff, tr("Captions"), tint = Color.White)
    }
}

@Composable
private fun SeekBar(st: PlayerState, dragging: Float?, onDrag: (Float) -> Unit, onDone: (Float) -> Unit) {
    if (st.durationMs <= 0) { Spacer(Modifier.height(24.dp)); return }
    Track(
        value = dragging ?: (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
        buffered = (st.bufferedMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
        color = Red, onDrag = onDrag, onDone = onDone,
        modifier = Modifier.fillMaxWidth(),
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
) {
    var hover by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Float?>(null) }
    val active = hover || held != null
    val shown = held ?: value
    BoxWithConstraints(
        modifier.height(24.dp)
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
        val thick = if (active) 5.dp else 3.dp
        Box(Modifier.fillMaxWidth().height(thick).background(Color(0x44FFFFFF))) {
            if (buffered > 0f) Box(Modifier.fillMaxWidth(buffered).height(thick).background(Color(0x77FFFFFF)))
            Box(Modifier.fillMaxWidth(shown).height(thick).background(color))
        }
        val knob = if (active) 14.dp else 10.dp
        Box(Modifier.offset(x = maxWidth * shown - knob / 2).size(knob).background(color, CircleShape))
    }
}

/** Quality, speed and captions: a sheet on phones, a menu on the desktop. */
@Composable
private fun ColumnScope.SettingsPages(app: AppState, dismiss: () -> Unit) {
    val player = app.platform.player
    val st by player.state.collectAsState()
    var page by remember { mutableStateOf("") }
    when (page) {
        "" -> {
            SheetRow(Icons.Outlined.HighQuality, tr("Quality"), if (st.height > 0) "${st.height}p" else tr("Auto")) { page = "q" }
            SheetRow(Icons.Outlined.Speed, tr("Speed"), if (st.speed == 1f) tr("Normal") else "${st.speed}x") { page = "s" }
            if (!app.playback.stream?.captions.isNullOrEmpty()) {
                SheetRow(Icons.Filled.ClosedCaption, tr("Captions"), st.captions?.name ?: tr("Off")) { page = "c" }
            }
        }
        "q" -> {
            Pick(tr("Auto"), st.height == 0) { player.setHeight(0); dismiss() }
            for (h in st.heights.sortedDescending()) Pick("${h}p", st.height == h) { player.setHeight(h); dismiss() }
        }
        "s" -> for (x in listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
            Pick(if (x == 1f) tr("Normal") else "${x}x", st.speed == x) {
                player.setSpeed(x); app.store.settings.update { it.copy(speed = x) }; dismiss()
            }
        }
        "c" -> {
            val caps = app.playback.stream?.captions ?: emptyList()
            val lang = app.store.settings.value.lang.ifEmpty { "en" }
            Pick(tr("Off"), st.captions == null) {
                player.setCaptions(null, null); app.store.settings.update { it.copy(captions = false) }; dismiss()
            }
            for (c in caps) Pick(c.name, st.captions == c) {
                player.setCaptions(c, null); app.store.settings.update { it.copy(captions = true) }; dismiss()
            }
            if (caps.none { it.lang.substringBefore('-') == lang }) caps.firstOrNull()?.let { c ->
                Pick(tr("Auto-translate") + " (" + (LANGS.firstOrNull { it.first == lang }?.second ?: lang) + ")", false) {
                    player.setCaptions(c, lang); app.store.settings.update { it.copy(captions = true) }; dismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Text(label, Modifier.weight(1f).padding(start = 20.dp), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pick(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp)) { if (on) Icon(Icons.Filled.Check, null) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
