package app.tubehaus.ui

import app.tubehaus.engine.Dislikes
import app.tubehaus.engine.Details
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Channel
import app.tubehaus.engine.Comment
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.rec.Learn
import kotlinx.coroutines.launch

@Composable
fun WatchScreen(app: AppState) {
    val pb = app.playback
    val v = pb.video
    if (v == null) {
        Empty(tr("Nothing is playing"))
        return
    }
    val set = app.store.settings.value
    val token = pb.details?.commentsToken?.takeIf { !set.hideComments }
    val hasRelated = !set.hideRelated || pb.queue.isNotEmpty()
    // the comments open beside (wide) or under (phone) the video, which keeps playing
    var side by remember(v.id) { mutableStateOf(if (hasRelated && set.sideTab != "comments") "next" else "comments") }
    var commentsOpen by remember(v.id) { mutableStateOf(false) }
    // the transcript: a tab beside the video (wide) or a panel over the space under it (phone)
    var transcriptOpen by remember(v.id) { mutableStateOf(false) }
    val hasCaptions = !pb.stream?.captions.isNullOrEmpty()
    // TV: the video plays on this page, beside what's next; OK on the video fills the screen
    // and Back comes back here (it used to fill the screen by itself, which got in the way)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // the 2.0 design: the video's colours, soft, behind the top of the page
        if (LocalLook.current == 3 && !app.platform.tv) Backdrop(v.thumb, Modifier.fillMaxWidth().height(560.dp))
        val tablet = app.platform.touch
        // a tablet held upright stacks like a phone (the video across the top, everything under it);
        // side by side would leave the video small in the middle of a tall screen
        val wide = maxWidth > 840.dp && !(tablet && maxHeight > maxWidth)
        val narrow = maxWidth < 600.dp
        // tablets and TVs: a narrower panel, so the video gets most of the width
        val sideWidth = if (tablet || app.platform.tv) minOf(380.dp, maxWidth * 0.32f) else minOf(420.dp, maxWidth * 0.4f)
        LaunchedEffect(pb.transcriptAsked) {
            if (!pb.transcriptAsked) return@LaunchedEffect
            pb.transcriptAsked = false
            if (wide) side = "transcript" else { commentsOpen = false; transcriptOpen = true }
        }
        if (wide) {
            // tablets and the desktop: the video and its details on the left; up next or the comments on the right
            Row(Modifier.fillMaxSize()) {
                if (tablet) {
                    // tablets: the video as large as the screen allows, with only its title under it;
                    // the description, buttons and channel move into the panel (Details)
                    Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.Center) {
                        PlayerBox(app, sideGestures = true)
                        if (set.underVideo != 2) Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                        item { Box(Modifier.clip(RoundedCornerShape(20.dp))) { PlayerBox(app) } }
                        under(app, v, flush = true)
                    }
                }
                val tabs = buildList {
                    if (hasRelated) add("next" to tr("Up next"))
                    if (token != null) add("comments" to tr("Comments"))
                    if (tablet && set.underVideo != 2) add("details" to tr("Details"))
                    if (hasCaptions) add("transcript" to tr("Transcript"))
                }
                if (tabs.isNotEmpty()) {
                    val shown = side.takeIf { s -> tabs.any { it.first == s } } ?: tabs.first().first
                    Column(Modifier.width(sideWidth).padding(top = 8.dp, end = 8.dp).riseIn(v.id)) {
                        if (tabs.size > 1) {
                            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((k, label) in tabs) Chip(shown == k, onClick = { side = k }, label = label)
                            }
                        }
                        when {
                            shown == "comments" && token != null -> Comments(app, token)
                            shown == "details" -> LazyColumn(Modifier.fillMaxSize()) { item { Info(app, v) } }
                            shown == "transcript" -> TranscriptPanel(app, null)
                            else -> LazyColumn(Modifier.fillMaxSize()) { related(app, big = false, thumb = if (sideWidth < 340.dp) 128.dp else 200.dp) }
                        }
                    }
                }
            }
        } else {
            // phones: pull the video down and the whole page follows the finger, shrinking toward the
            // corner as it goes, as YouTube's page folds into its mini player; let go past a quarter
            // of the screen (or with a flick) and it drops into the mini player, otherwise it springs back
            val scope = rememberCoroutineScope()
            val pull = remember(v.id) { Animatable(0f) }
            val tall = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            Column(Modifier.fillMaxSize().graphicsLayer {
                val f = (pull.value / tall).coerceIn(0f, 1f)
                translationY = pull.value * 0.85f
                val k = 1f - 0.45f * f
                scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.95f, 0f)
                alpha = 1f - 0.7f * f
            }) {
                PlayerBox(app,
                    onPull = { dy -> scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) } },
                    onPullEnd = { speed ->
                        scope.launch {
                            if (pull.value > tall * 0.25f || (speed > 1200f && pull.value > 24f)) {
                                pull.animateTo(tall, tween(Motion.SHORT + 40, easing = Motion.emphasizedOut))
                                app.back()
                            } else pull.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
                        }
                    })
                Box(Modifier.fillMaxSize().riseIn(v.id)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        under(app, v)
                        if (token != null) item { CommentsTeaser(token) { commentsOpen = true } }
                        // phones: full-width cards; an upright tablet: rows with a larger picture
                        if (narrow) related(app, big = true) else related(app, big = false, thumb = 240.dp)
                    }
                    // the comments slide up over the space under the video, as on YouTube, and back
                    // down when closed; the video keeps playing above
                    androidx.compose.animation.AnimatedVisibility(
                        commentsOpen && token != null,
                        enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                            androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
                        exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                            androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            if (token != null) CommentsPanel(app, token) { commentsOpen = false }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        transcriptOpen && hasCaptions,
                        enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                            androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
                        exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                            androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            TranscriptPanel(app) { transcriptOpen = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBox(
    app: AppState, sideGestures: Boolean = false, onPull: ((Float) -> Unit)? = null, onPullEnd: ((Float) -> Unit)? = null,
    // in the phone's watch sheet: the sheet sizes the box (it shrinks into the mini player) and moves it
    sheet: Modifier? = null, controls: Boolean = true,
) {
    val pb = app.playback
    val st by app.player.state.collectAsState()
    val touch = app.platform.touch
    // Ambient mode: in the dark theme the video's colours glow around it, a fresh frame each second
    val showing = pb.error == null && !pb.loading && st.videoId == pb.video?.id
    val ambient = app.store.settings.state.collectAsState().value.ambient && MaterialTheme.colorScheme.background.luminance() < 0.3f
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ambient && showing, st.videoId) {
        glow = null
        if (!ambient || !showing) return@LaunchedEffect
        while (true) {
            app.player.frame()?.let { glow = it }
            // paused: the picture only changes when you seek
            delay(if (app.player.state.value.playing) 1000 else 2500)
        }
    }
    Box(
        (sheet ?: Modifier.fillMaxWidth().aspectRatio(16f / 9f)).ambientGlow(glow).background(Color.Black)
            // touch screens: swipe the video down to keep it playing in the mini player and go back,
            // up to watch it full screen (#64)
            .pointerInput(touch, sheet != null) {
                if (!touch || sheet != null) return@pointerInput
                var pulled = 0f
                // how fast the finger was moving when it let go (pixels a second), for a flick
                var speed = 0f
                var last = 0L
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f; speed = 0f; last = 0L },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val t = change.uptimeMillis
                        if (last != 0L && t > last) speed = 0.6f * speed + 0.4f * (dy * 1000f / (t - last))
                        last = t
                        pulled += dy
                        // the page follows the finger down (not up: up is full screen)
                        if (onPull != null && (pulled > 0f || dy < 0f)) onPull(if (pulled > 0f) dy else 0f)
                    },
                    onDragCancel = { onPullEnd?.invoke(0f) },
                    onDragEnd = {
                        when {
                            pulled < -60.dp.toPx() -> { onPull?.invoke(-10_000f); app.setFull(true) }
                            onPullEnd != null -> onPullEnd(speed)
                            pulled > 80.dp.toPx() -> app.back()
                        }
                    },
                )
            },
    ) {
        val v = pb.video
        when {
            pb.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pb.error!!, color = Color.White, textAlign = TextAlign.Center)
                if (pb.errorAge) Button(onClick = { app.platform.openExternal("https://www.youtube.com/watch?v=" + (v?.id ?: "")) },
                    modifier = Modifier.padding(top = 12.dp)) { Text(tr("Open on YouTube")) }
                else Button(onClick = { pb.retry() }, modifier = Modifier.padding(top = 12.dp)) { Text(tr("Try again")) }
            }
            pb.loading || st.videoId != v?.id -> {
                if (v != null && !app.store.settings.value.hideThumbs) Img(v.thumbHigh, Modifier.fillMaxSize())
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            else -> VideoArea(app, Modifier.fillMaxSize(), sideGestures = sideGestures, controls = controls)
        }
        // while loading there are no controls yet: keep a way back
        if (controls && (pb.error != null || pb.loading || st.videoId != v?.id)) {
            IconButton(onClick = { app.back() }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Sym.ArrowBack.fill, tr("Back"), tint = Color.White)
            }
        }
    }
    if (sheet == null) PlayerNotes(app)
}

/** what the player has to say under the video: an error, or no sound */
@Composable
private fun PlayerNotes(app: AppState) {
    val st by app.player.state.collectAsState()
    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    st.soundIssue?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
}

/**
 * Draws [frame] (a small blurred picture of the video) stretched past the edges of what follows,
 * fading out a little way above and to the sides and further below; each new frame fades in over
 * the last one.
 */
@Composable
private fun Modifier.ambientGlow(frame: ImageBitmap?): Modifier {
    var last by remember { mutableStateOf<ImageBitmap?>(null) }
    var now by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(frame) {
        if (frame === now) return@LaunchedEffect
        last = now
        now = frame
        fade.snapTo(0f)
        fade.animateTo(1f, tween(900))
    }
    if (last == null && now == null) return this
    return drawBehind {
        val w = size.width
        val h = size.height
        val area = Rect(-w * 0.12f, -h * 0.3f, w * 1.12f, h * 1.8f)
        val p = fade.value
        drawIntoCanvas { c ->
            c.saveLayer(area, Paint())
            fun img(b: ImageBitmap?, alpha: Float) {
                if (b == null || alpha <= 0f) return
                drawImage(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(area.left.toInt(), area.top.toInt()),
                    IntSize(area.width.toInt(), area.height.toInt()), alpha = alpha * 0.6f, filterQuality = FilterQuality.Low)
            }
            img(last, 1f - p)
            img(now, p)
            // fade toward the edges: down, then across
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, (-area.top / area.height) to Color.Black,
                    ((h - area.top) / area.height) to Color.Black, 1f to Color.Transparent,
                    startY = area.top, endY = area.bottom,
                ),
                area.topLeft, area.size, blendMode = BlendMode.DstIn,
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, (-area.left / area.width) to Color.Black,
                    ((w - area.left) / area.width) to Color.Black, 1f to Color.Transparent,
                    startX = area.left, endX = area.right,
                ),
                area.topLeft, area.size, blendMode = BlendMode.DstIn,
            )
            c.restore()
        }
    }
}

@Composable
private fun Info(app: AppState, v: Video, flush: Boolean = false) {
    val pb = app.playback
    val d = pb.details
    val lib by app.store.lib.state.collectAsState()
    val calm = app.store.settings.value.calm
    val hidden = app.store.settings.state.collectAsState().value.hideUnder
    fun has(part: String) = part !in hidden
    var sheet by remember(v.id) { mutableStateOf(false) }
    var open by remember(v.id) { mutableStateOf(false) }
    val viewsText = d?.viewsText?.takeIf { it.isNotEmpty() } ?: v.views.takeIf { it > 0 }?.let { tr("{n} views").replace("{n}", compact(it)) } ?: ""
    val line = listOfNotNull(
        viewsText.takeIf { !calm && has("views") },
        d?.publishedText?.takeIf { has("date") },
    ).filter { it.isNotEmpty() }.joinToString("  ")
    val desc = pb.stream?.description?.takeIf { has("description") } ?: ""
    // a computer (not a phone or a tablet) lays it out as YouTube's website does: a larger title, a
    // larger channel line, and the views, date and the start of the description in a grey box below
    // flush: beside the up-next panel, where the page's margin already lines it up with the video
    val desk = !LocalCompact.current && !app.platform.touch
    Column(Modifier.padding(horizontal = if (flush) 0.dp else 12.dp, vertical = 10.dp)) {
        if (desk) {
            if (has("title")) SelectionContainer {
                Text(titleOf(v, app), fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            // the title and one quiet line under it; either opens the description
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { sheet = true }) {
                if (has("title")) Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (desc.isNotEmpty())
                        Text(tr("...more"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        // the channel and its subscribe button side by side, then the buttons
        val showSub = v.channelId.isNotEmpty() && has("subscribe")
        if (has("channel") || showSub) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (has("channel")) {
                val open = { if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) }
                Avatar(d?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null } ?: lib.subs[v.channelId]?.thumb ?: "", if (desk) 40 else 36, Modifier.clickable { open() })
                Column(Modifier.weight(1f, fill = false).padding(start = if (desk) 12.dp else 10.dp).clickable { open() }) {
                    Text(v.author, style = if (desk) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!calm && has("subscribers") && !d?.subsText.isNullOrEmpty())
                        Text(d!!.subsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.width(if (desk) 24.dp else 12.dp))
            }
            if (showSub) SubscribeButton(v.channelId, v.author, d?.avatar?.ifEmpty { null } ?: v.avatar, app)
        }
        Actions(app, v, ::has)
        // a computer opens the description in place, as YouTube's website does (a sheet from the bottom
        // in the middle of a wide window looked like a phone's); "Show less" folds it back
        if (desk && (line.isNotEmpty() || desc.isNotEmpty())) Column(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .let { if (open) it else it.clickable { open = true } }
                .animateContentSize(tween(Motion.MEDIUM, easing = Motion.standard))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (desc.isNotEmpty()) {
                if (!open) {
                    Text(desc, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(tr("...more"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                } else {
                    SelectionContainer {
                        LinkedText(desc, app, Modifier.fillMaxWidth().padding(top = 2.dp), onTime = { s -> app.player.seekTo(s * 1000L) })
                    }
                    ChapterCards(app, Modifier.padding(top = 20.dp)) {}
                    Text(tr("Show less"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(6.dp)).clickable { open = false }.padding(vertical = 2.dp))
                }
            }
        }
    }
    if (sheet) DescriptionSheet(app, v, viewsText) { sheet = false }
}

/** The description in a sheet from the bottom: the title, the channel, likes, views and date, then the full text. */
@Composable
private fun DescriptionSheet(app: AppState, v: Video, viewsText: String, onClose: () -> Unit) {
    val d = app.playback.details
    val calm = app.store.settings.value.calm
    val desc = app.playback.stream?.description ?: ""
    // open all the way at once, as the player's settings sheet does
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                // the title can be copied too, as the description under it (#72)
                SelectableInSheet(app) { Text(v.title, style = MaterialTheme.typography.titleLarge) }
                if (v.author.isNotEmpty()) Row(
                    Modifier.padding(vertical = 12.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { onClose(); if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(d?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null } ?: app.store.lib.value.subs[v.channelId]?.thumb ?: "", 28)
                    Text(v.author, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                }
                // likes, views and the date side by side, the numbers large
                val stats = listOfNotNull(
                    d?.likesText?.takeIf { it.isNotEmpty() && !calm }?.let { it to tr("Likes") },
                    (if (v.views > 0) compact(v.views) else viewsText).takeIf { it.isNotEmpty() && !calm }?.let { it to tr("Views") },
                    d?.publishedText?.takeIf { it.isNotEmpty() }?.let { it to tr("Uploaded") },
                )
                if (stats.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((big, small) in stats) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(big, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // links open, #tags search, and a time like 3:25 jumps there in the video
                if (desc.isNotEmpty()) SelectableInSheet(app) {
                    LinkedText(desc, app,
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(14.dp),
                        onTime = { s -> app.player.seekTo(s * 1000L); onClose() })
                }
            }
            // the chapters as cards in a row, as under YouTube's description; a tap jumps there
            if (app.playback.chapters.isNotEmpty()) item { ChapterCards(app, Modifier.padding(bottom = 32.dp), onClose) }
        }
    }
}

/** The chapters as cards in a row, as under YouTube's description; a click jumps there. */
@Composable
private fun ChapterCards(app: AppState, modifier: Modifier, after: () -> Unit) {
    val chapters = app.playback.chapters
    if (chapters.isEmpty()) return
    Column(modifier) {
        Text(tr("Chapters"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapters) { c ->
                Column(Modifier.width(160.dp).clip(RoundedCornerShape(8.dp)).clickable { app.player.seekTo(c.startMs); after() }) {
                    if (c.thumb.isNotEmpty()) Img(c.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(durationMs(c.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * The buttons under the video: like (with its count) and dislike in one pill, then watch later,
 * save, share, download and next, whichever Settings → Under the video keeps.
 */
@Composable
private fun Actions(app: AppState, v: Video, has: (String) -> Boolean) {
    val pb = app.playback
    val lib by app.store.lib.state.collectAsState()
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    val saved = lib.wl.any { it.id == v.id }
    var pick by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    // the dislike count YouTube hides: Return YouTube Dislike's estimate
    var votes by remember(v.id) { mutableStateOf<Dislikes.Votes?>(null) }
    val showVotes = app.store.settings.value.showDislikes && !calm
    if (showVotes) LaunchedEffect(v.id) { votes = Dislikes.votes(v.id) }
    // the thumb pops when you press it, as on YouTube: squeezed, then springing past its size and back
    val scope = rememberCoroutineScope()
    val upPop = remember { Animatable(1f) }
    val downPop = remember { Animatable(1f) }
    fun pop(a: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) = scope.launch {
        a.animateTo(0.7f, tween(80))
        a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has("likes")) item {
          Box(Modifier.clip(RoundedCornerShape(20.dp))) {
            Row(Modifier.height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                    app.store.setInList("likes", v, !liked)
                    if (!liked) { Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0); pop(upPop) }
                }.padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), Modifier.size(20.dp).graphicsLayer { scaleX = upPop.value; scaleY = upPop.value; rotationZ = (1f - upPop.value) * -40f })
                    // YouTube's count; where the channel hides it, the estimate
                    val n = pb.details?.likesText?.takeIf { !calm && it.any(Char::isDigit) }
                        ?: votes?.likes?.takeIf { showVotes && it > 0 }?.let(::compact) ?: pb.details?.likesText?.takeIf { !calm } ?: ""
                    if (n.isNotEmpty()) Text(n, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
                Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
                Box(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                    app.store.setInList("dislikes", v, !disliked)
                    if (!disliked) { Learn.nudgeChannel(app.store, v.channelId, v.author, -1.5); pop(downPop) }
                }.padding(start = 10.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike"), Modifier.size(20.dp).graphicsLayer { scaleX = downPop.value; scaleY = downPop.value })
                        val d = votes?.dislikes?.takeIf { showVotes }
                        if (d != null) Text(compact(d), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            // how many of the votes are likes: a thin line along the bottom of the pill, as YouTube once had
            val vt = votes?.takeIf { showVotes && it.likes + it.dislikes > 0 }
            if (vt != null) Box(Modifier.matchParentSize().padding(horizontal = 14.dp), contentAlignment = Alignment.BottomStart) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline)) {
                    Box(Modifier.fillMaxWidth(vt.likes.toFloat() / (vt.likes + vt.dislikes)).height(2.dp).background(MaterialTheme.colorScheme.onSurface))
                }
            }
          }
        }
        if (has("later")) item { Chip(if (saved) Sym.WatchLater.fill else Sym.WatchLater.outline, tr("Watch later")) { app.store.setInList("wl", v, !saved) } }
        if (has("save")) item { Chip(Sym.LibraryAdd.outline, tr("Save")) { pick = true } }
        if (has("share")) item { Chip(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") } }
        // nearby devices: carry on with this video on the TV or the computer
        if (app.store.settings.value.nearby && app.near.available) item {
            var open by remember { mutableStateOf(false) }
            Chip(Sym.Cast.outline, tr("Other device")) { open = true }
            if (open) SendToDialog(app, v) { open = false }
        }
        if (has("download") && !v.live && app.platform.canDownload) item { DownloadChip(app, v) }
        if (!pb.stream?.captions.isNullOrEmpty() && !app.platform.tv) item { Chip(Sym.Transcript.outline, tr("Transcript")) { pb.transcriptAsked = true } }
        if (pb.next != null && has("next")) item { Chip(Sym.SkipNext.outline, tr("Next")) { pb.skipNext() } }
    }
    if (pick) PlaylistPicker(v, app) { pick = false }
}

/** Download → the percentage while it saves → Downloaded (it plays without internet) */
@Composable
private fun DownloadChip(app: AppState, v: Video) {
    val saved by app.offline.saved.collectAsState()
    val p = app.offline.progress[v.id]
    when {
        v.id in saved.items -> Chip(Sym.DownloadDone.fill, tr("Downloaded")) { app.go(Screen.Downloads) }
        p != null -> Chip(Sym.Downloading.outline, "${(p * 100).toInt()}%") { app.go(Screen.Downloads) }
        else -> Chip(Sym.Download.outline, tr("Download")) { app.offline.asking = v }
    }
}

/** A grey pill, 36dp tall: an icon and a short label */
@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).tvClickable(RoundedCornerShape(20.dp), sink = 0.94f, onClick = onClick).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/** What sits under the video, by Settings → Under the video: everything, only the title, or nothing. */
private fun LazyListScope.under(app: AppState, v: Video, flush: Boolean = false) {
    when (app.store.settings.value.underVideo) {
        0 -> { item { Info(app, v, flush) }; item { SmartCard(app, v) } }
        1 -> item {
            Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

/** The comments with a title and a close button: under the video on a phone, beside it in full screen. */
@Composable
fun CommentsPanel(app: AppState, token: String, info: Details? = app.playback.details, handle: Modifier = Modifier, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // a phone: a small grip on top, as YouTube's sheet; the header drags the panel down
        if (handle != Modifier) Box(Modifier.fillMaxWidth().then(handle).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
        }
        Row(Modifier.fillMaxWidth().then(handle).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = info?.takeIf { it.commentsToken == token }?.commentsCount ?: ""
            Text(tr("Comments"), style = MaterialTheme.typography.titleMedium)
            Text(count, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            IconButton(onClick = onClose) { Icon(Sym.Close.fill, tr("Close")) }
        }
        HorizontalDivider()
        Comments(app, token, info)
    }
}

/** A scrolling list of comments that loads the next page by itself when you reach the end. */
@Composable
private fun Comments(app: AppState, token: String, info: Details? = app.playback.details) {
    // "Top" and "Newest", as YouTube offers them; each is its own list
    val sorts = info?.takeIf { it.commentsToken == token }?.commentSorts ?: emptyList()
    var sorted by remember(token) { mutableStateOf(token) }
    val pager = rememberPager(sorted, { YouTube.comments(sorted) }, { YouTube.comments(it) })
    LazyColumn(Modifier.fillMaxSize()) {
        if (sorts.size > 1) item("sorts") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sorts.forEachIndexed { i, (title, t) ->
                    val on = if (sorted == token) i == 0 else sorted == t
                    Chip(on, onClick = { sorted = if (i == 0) token else t }, label = title)
                }
            }
        }
        if (pager.items.isEmpty() && pager.loading) items(6) { BoneComment() }
        if (pager.items.isEmpty() && pager.started && !pager.loading) item { Empty(tr("No comments")) }
        items(pager.items, key = { "c" + it.id }) { CommentView(app, it) }
        if (pager.next != null) item("more") {
            // reaching the end asks for the next page
            LaunchedEffect(pager.items.size) { pager.loadMore() }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
    }
}

/** big: phones show related videos as full-width cards, wide screens as rows with larger pictures */
private fun LazyListScope.related(app: AppState, big: Boolean, thumb: Dp = 200.dp) {
    val pb = app.playback
    val queue = pb.queue
    if (queue.isNotEmpty()) {
        item { SectionTitle(tr("Up next")) }
        items(queue, key = { "q" + it.id }) { v ->
            VideoRow(v, app, extra = listOf(tr("Remove from queue") to { pb.unqueue(v) }), thumbWidth = minOf(thumb, 168.dp)) { pb.open(v, queue.dropWhile { it.id != v.id }.drop(1)); app.stack[app.stack.size - 1] = Screen.Watch(v.id) }
        }
    }
    if (app.store.settings.value.hideRelated) return
    val d = pb.details ?: run { items(4) { BoneVideo(big, thumb) }; return }
    items(d.related.filter { !(app.noShorts && it is Video && it.short) }, key = { "r" + it.id }) {
        when (it) {
            is Video -> if (big) VideoCard(it, app) else VideoRow(it, app, thumbWidth = thumb)
            is Playlist -> PlaylistRow(it, app)
            is Channel -> ChannelRow(it, app)
        }
    }
}

/** A card under the buttons: "Comments" and two lines of the first one; a tap opens them all. */
@Composable
private fun CommentsTeaser(token: String, open: () -> Unit) {
    val pager = rememberPager(token, { YouTube.comments(token) }, { YouTube.comments(it) })
    val first = pager.items.firstOrNull()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .tvClickable(onClick = open).padding(12.dp),
    ) {
        Text(tr("Comments"), style = MaterialTheme.typography.titleSmall)
        if (first != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(first.avatar, 24)
            Text(first.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}


@Composable
private fun CommentView(app: AppState, c: Comment, reply: Boolean = false) {
    var showReplies by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    Row(Modifier.fillMaxWidth().padding(start = if (reply) 52.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.avatar, if (reply) 24 else 32, Modifier.clickable { if (c.channelId.isNotEmpty()) app.go(Screen.Channel(c.channelId, c.author)) })
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            // as YouTube: the name in the text colour, the time after it in grey
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.bodySmall, fontWeight = if (c.byCreator) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.published.isNotEmpty()) Text(c.published, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
            val translated = app.translations.shownText(c.id)
            SelectionContainer { Text(translated ?: c.text, style = MaterialTheme.typography.bodyMedium) }
            // as YouTube: Translate and the likes right under the text, then the replies as a small blue
            // button with an arrow that turns when they open (a full-height text button left a gap above)
            val lang = L.lang
            val canTr = app.platform.canTranslate && remember(c.text, lang) { !needsNoTranslation(c.text, lang) }
            val likes = !calm && c.likes.isNotEmpty()
            if (canTr || likes) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canTr) {
                    val scope = rememberCoroutineScope()
                    val busy = app.translations.busy(c.id)
                    // plain text, not a TextButton: its minimum width would push the word off the comment's left edge
                    Text(
                        tr(when { busy -> "Translating…"; translated != null -> "Show original"; else -> "Translate" }),
                        style = MaterialTheme.typography.labelLarge, color = Blue,
                        modifier = Modifier
                            .clickable(enabled = !busy) { scope.launch { app.translations.toggle(app.platform, c.id, c.text, lang, app::toast) } }
                            .padding(vertical = 4.dp),
                    )
                    if (likes) Spacer(Modifier.width(16.dp))
                }
                if (likes) {
                    Icon(Sym.ThumbUp.outline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.likes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (c.replies > 0 && c.repliesToken != null) {
                val turn by androidx.compose.animation.core.animateFloatAsState(if (showReplies) -90f else 90f, tween(Motion.SHORT))
                Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).clickable { showReplies = !showReplies }
                        .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.ChevronRight.outline, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = Blue)
                    Text(tr("{n} replies").replace("{n}", "${c.replies}"), color = Blue, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    if (showReplies && c.repliesToken != null) {
        val pager = rememberPager(c.repliesToken, { YouTube.comments(c.repliesToken) }, { YouTube.comments(it) })
        val scope = rememberCoroutineScope()
        for (r in pager.items) CommentView(app, r, reply = true)
        if (pager.loading) Busy()
        if (pager.next != null && !pager.loading) TextButton(onClick = { scope.launch { pager.loadMore() } }, modifier = Modifier.padding(start = 52.dp)) {
            Text(tr("More replies"))
        }
    }
}


// ---------------------------------------------------------------------- the phone's watch sheet

private val MINI_H = 60.dp

/**
 * Phones: the video page as a sheet over the app, as in YouTube's app (and Flow's). The page under it
 * stays where it was. Pull the video down and it shrinks toward the bottom, following the finger, into
 * the mini player above the tabs; let go and it settles the way it was flung. Tap the mini player or
 * pull it up and it grows back; swipe it sideways and it closes.
 *
 * The video is laid out each frame at its in-between size (not scaled as a picture), so the video
 * surface underneath, a native view on every system, follows it exactly.
 */
@Composable
fun WatchSheet(app: AppState, expanded: Boolean, barPx: Int) {
    val pb = app.playback
    val v = pb.video ?: return
    val scope = rememberCoroutineScope()
    // 0: the whole page; 1: the mini player
    val f = remember { Animatable(if (expanded) 0f else 1f) }
    var held by remember { mutableStateOf(false) }
    val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
    LaunchedEffect(expanded) { if (!held) f.animateTo(if (expanded) 0f else 1f, settle) }
    // the mini player swiped sideways
    val swipe = remember(v.id) { Animatable(0f) }
    val list = remember(v.id) { androidx.compose.foundation.lazy.LazyListState() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    val top = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(d)
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(d)
    val full by remember { androidx.compose.runtime.derivedStateOf { f.value < 0.02f } }
    val mini by remember { androidx.compose.runtime.derivedStateOf { f.value > 0.98f } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val miniH = with(d) { MINI_H.toPx() }
        val miniW = miniH * 16f / 9f
        val videoH = w * 9f / 16f
        val miniTop = constraints.maxHeight - maxOf(barPx, nav) - miniH
        val range = (miniTop - top).coerceAtLeast(1f)
        val fling = with(d) { 900.dp.toPx() }

        fun land(velocity: Float) {
            held = false
            val toMini = when {
                velocity > fling -> true
                velocity < -fling -> false
                else -> f.value > 0.5f
            }
            scope.launch { f.animateTo(if (toMini) 1f else 0f, settle, initialVelocity = velocity / range) }
            if (toMini == (app.current is Screen.Watch)) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
            if (toMini && app.current is Screen.Watch) app.back(hooks = false)
            else if (!toMini && app.current !is Screen.Watch) app.go(Screen.Watch(v.id))
        }

        /**
         * One handler for the whole sheet, so a drag that starts on the open page and ends in the
         * mini player (or the other way) is one gesture: up and down the sheet follows the finger;
         * sideways, from the mini player only, it slides away and closes
         */
        fun Modifier.drag(): Modifier = pointerInput(range, v.id) {
            val track = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var acc = Offset.Zero
            var sideways: Boolean? = null
            var fromMini = false
            detectDragGestures(
                onDragStart = { acc = Offset.Zero; sideways = null; fromMini = f.value > 0.98f; track.resetTracking() },
                onDrag = { ch, delta ->
                    ch.consume()
                    acc += delta
                    if (sideways == null) {
                        sideways = fromMini && kotlin.math.abs(acc.x) > kotlin.math.abs(acc.y)
                        if (sideways == false) held = true
                    }
                    track.addPosition(ch.uptimeMillis, acc)
                    scope.launch {
                        if (sideways == true) swipe.snapTo(swipe.value + delta.x)
                        else f.snapTo((f.value + delta.y / range).coerceIn(0f, 1f))
                    }
                },
                onDragCancel = { if (sideways == true) scope.launch { swipe.animateTo(0f) } else land(0f) },
                onDragEnd = {
                    val vel = track.calculateVelocity()
                    when {
                        sideways == true -> scope.launch {
                            if (kotlin.math.abs(swipe.value) > w * 0.35f || kotlin.math.abs(vel.x) > fling) {
                                val dir = if (swipe.value + vel.x * 0.1f > 0) 1f else -1f
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                                swipe.animateTo(dir * w, tween(Motion.SHORT, easing = Motion.emphasizedOut), initialVelocity = vel.x)
                                pb.close()
                            } else swipe.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 600f), initialVelocity = vel.x)
                        }
                        // pushed up on the open page: full screen, as before (#64)
                        !fromMini && f.value < 0.01f && acc.y < -60.dp.toPx() -> { held = false; app.setFull(true) }
                        else -> land(vel.y)
                    }
                },
            )
        }

        val c = MaterialTheme.colorScheme
        val fade = { 1f - kotlin.math.abs(swipe.value) / w * 0.9f }
        // the page behind the video: fades as the sheet folds away, and holds the touches while open
        if (!mini) Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = (1f - f.value * 1.15f).coerceIn(0f, 1f) }.background(c.background)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (LocalLook.current == 3) Backdrop(v.thumb, Modifier.fillMaxWidth().height(560.dp))
        }
        // what is under the video: comes along down and fades out early
        if (!mini) Box(
            Modifier.fillMaxSize().padding(top = with(d) { (top + videoH).toDp() })
                .graphicsLayer { translationY = f.value * range; alpha = (1f - f.value * 2.5f).coerceIn(0f, 1f) },
        ) { PhoneBody(app, v, list) }
        // the mini player's bar; the video sits on its left end
        if (!full) Column(
            Modifier.fillMaxWidth().height(MINI_H)
                .graphicsLayer {
                    translationY = miniTop
                    translationX = swipe.value
                    alpha = ((f.value - 0.6f) / 0.4f).coerceIn(0f, 1f) * fade()
                }
                .background(c.surfaceContainer)
                .drag().clickable(enabled = mini) { app.go(Screen.Watch(v.id)) },
        ) {
            val st by app.player.state.collectAsState()
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = with(d) { miniW.toDp() }, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.author, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                    Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
                }
                IconButton(onClick = { pb.close() }) { Icon(Sym.Close.fill, tr("Close")) }
            }
            if (st.durationMs > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(c.outlineVariant)) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(2.dp).background(Red))
            }
        }
        // the video: its size and place for this frame, worked out while laying out (nothing recomposes)
        val box = Modifier.layout { m, _ ->
            val k = f.value
            val bw = (w + (miniW - w) * k).toInt().coerceAtLeast(1)
            val bh = (videoH + (miniH - 2.dp.toPx() - videoH) * k).toInt().coerceAtLeast(1)
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(bw, bh))
            layout(p.width, p.height) {
                p.placeWithLayer(0, (top + (miniTop - top) * k).toInt()) { translationX = swipe.value; alpha = fade() }
            }
        }
        Box(box.drag()) {
            PlayerBox(app, sheet = Modifier.fillMaxSize(), controls = full)
            // folded away: the picture is part of the mini player (a tap opens it)
            if (!full) Box(Modifier.fillMaxSize().clickable(enabled = mini) { app.go(Screen.Watch(v.id)) })
        }
    }
}

/** Under the video on a phone: the details, the comments' card, what's next; comments and transcript slide up over it. */
@Composable
private fun PhoneBody(app: AppState, v: Video, list: androidx.compose.foundation.lazy.LazyListState) {
    val pb = app.playback
    val set = app.store.settings.value
    val token = pb.details?.commentsToken?.takeIf { !set.hideComments }
    var commentsOpen by remember(v.id) { mutableStateOf(false) }
    var transcriptOpen by remember(v.id) { mutableStateOf(false) }
    val hasCaptions = !pb.stream?.captions.isNullOrEmpty()
    LaunchedEffect(pb.transcriptAsked) {
        if (!pb.transcriptAsked) return@LaunchedEffect
        pb.transcriptAsked = false
        commentsOpen = false; transcriptOpen = true
    }
    // Back closes an open panel before it folds the video away; only while the video page is in
    // front (folded into the mini player, its panels are put away and Back is the page's)
    val front = app.current is Screen.Watch
    LaunchedEffect(front) { if (!front) { commentsOpen = false; transcriptOpen = false } }
    BackHook(app, front && (commentsOpen || transcriptOpen)) { commentsOpen = false; transcriptOpen = false }
    Box(Modifier.fillMaxSize().riseIn(v.id)) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item { PlayerNotes(app) }
            under(app, v)
            if (token != null) item { CommentsTeaser(token) { commentsOpen = true } }
            related(app, big = true)
        }
        androidx.compose.animation.AnimatedVisibility(
            commentsOpen && token != null,
            enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
            exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
        ) {
            // pulled down by its top, it follows the finger and closes past a quarter (or flung)
            val scope = rememberCoroutineScope()
            val down = remember { Animatable(0f) }
            var tall by remember { mutableStateOf(1f) }
            val grip = Modifier.pointerInput(Unit) {
                val track = androidx.compose.ui.input.pointer.util.VelocityTracker()
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f; track.resetTracking() },
                    onVerticalDrag = { ch, dy ->
                        ch.consume(); acc += dy; track.addPosition(ch.uptimeMillis, Offset(0f, acc))
                        scope.launch { down.snapTo((down.value + dy).coerceAtLeast(0f)) }
                    },
                    onDragCancel = { scope.launch { down.animateTo(0f) } },
                    onDragEnd = {
                        val vy = track.calculateVelocity().y
                        scope.launch {
                            if (down.value > tall * 0.25f || vy > 900.dp.toPx()) { commentsOpen = false; delay(400); down.snapTo(0f) }
                            else down.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f), initialVelocity = vy)
                        }
                    },
                )
            }
            Box(Modifier.fillMaxSize().onSizeChanged { tall = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { translationY = down.value }.background(MaterialTheme.colorScheme.background)) {
                if (token != null) CommentsPanel(app, token, handle = grip) { commentsOpen = false }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            transcriptOpen && hasCaptions,
            enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
            exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TranscriptPanel(app) { transcriptOpen = false }
            }
        }
    }
}
