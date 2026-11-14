package app.tubehaus.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Lyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/*
 * Timed lyrics as the music apps draw them (Apple Music's, and Flow's, which the ideas here follow):
 *  - the sung line sits a little above the middle; when the next one starts, the lines move up one
 *    after another, each a moment after the one above, so the list ripples instead of jumping;
 *  - the sung line fills with colour from the left as it is sung (word times are guessed from the
 *    line's length, as neither source gives them), the rest of it dim;
 *  - a long pause in the singing (the intro, an instrumental) shows three dots that fill and breathe;
 *  - dragged, the list moves under the finger and stays a few seconds before it glides back;
 *    a tap on a line plays from there.
 */

/** one row: a line, or a pause (gap) that lasts until [end] */
private class Row(val ms: Long, val end: Long, val text: String, val gap: Boolean)

private const val LEAD_MS = 250L

private fun rowsOf(lines: List<Lyrics.Line>): List<Row> {
    val out = ArrayList<Row>()
    val sung = lines.filter { it.text.isNotBlank() && it.text.any { c -> c != '♪' && !c.isWhitespace() } }
    if (sung.isEmpty()) return out
    // a pause is any stretch of 4 seconds or more without a sung line starting: the intro, a "♪" line,
    // a blank line, or a long wait after a short line
    if (sung.first().ms > 4000) out += Row(0, sung.first().ms, "", true)
    for ((i, l) in sung.withIndex()) {
        val next = sung.getOrNull(i + 1)?.ms
        val wordsEnd = l.ms + l.text.length * 260L + 900
        // a "♪" or blank line between two sung ones marks where the pause starts
        val marker = lines.firstOrNull { m -> m.ms > l.ms && (next == null || m.ms < next) && m.text.none { c -> c != '♪' && !c.isWhitespace() } }?.ms
        val pauseAt = marker ?: wordsEnd
        if (next != null && next - pauseAt >= 4000) {
            out += Row(l.ms, pauseAt, l.text, false)
            out += Row(pauseAt, next, "", true)
        } else out += Row(l.ms, next ?: (l.ms + 6000), l.text, false)
    }
    return out
}

/**
 * [now] the song's position, smoothed between the player's reports (read only while drawing and in
 * derived state, so the frames don't recompose the page)
 */
@Composable
fun SyncedLyrics(lines: List<Lyrics.Line>, now: () -> Long, seek: (Long) -> Unit, footer: String, modifier: Modifier = Modifier) {
    val rows = remember(lines) { rowsOf(lines) }
    val active by remember(rows) { derivedStateOf { rows.indexOfLast { it.ms <= now() + LEAD_MS }.coerceAtLeast(0) } }
    // each row's own view of where the list is: they all head for the same place, at different moments
    val scroll = remember(rows) { List(rows.size + 1) { Animatable(0f) } }
    val tops = remember(rows) { FloatArray(rows.size + 1) }
    val user = remember(rows) { Animatable(0f) }
    var userAt by remember { mutableLongStateOf(0L) }
    var placed by remember(rows) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(active, rows) {
        // the rows are measured in the frame after they arrive
        withFrameMillis { }
        val target = tops[active.coerceIn(0, tops.size - 1)]
        if (!placed) { scroll.forEach { it.snapTo(target) }; placed = true; return@LaunchedEffect }
        val far = abs(scroll[active].value - target) > 1600f
        scroll.forEachIndexed { i, a ->
            // the rows above go first, each row below a little later: the ripple
            val wait = if (far) 0L else ((i - active + 1).coerceIn(0, 9) * 38L)
            launch {
                if (wait > 0) delay(wait)
                a.animateTo(target, if (far) tween(Motion.MEDIUM, easing = Motion.standard) else spring(dampingRatio = 0.86f, stiffness = 140f))
            }
        }
    }
    val c = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
        // Korean and Japanese break between words (phrases), not in the middle of one
        lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    Layout(
        content = {
            rows.forEachIndexed { i, r ->
                val on = i == active
                if (r.gap) Pause(r, on, now)
                else Line(r, on, past = i < active, now = now, ink = c.onBackground, style = textStyle) {
                    scope.launch { user.animateTo(0f, tween(Motion.MEDIUM)) }
                    userAt = 0L
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                    seek(r.ms)
                }
            }
            Text(footer, Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        },
        modifier = modifier.clipToBounds()
            // the lines fade in at the top and out at the bottom instead of being cut off
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
            .pointerInput(rows) {
            detectVerticalDragGestures(
                onVerticalDrag = { ch, dy ->
                    ch.consume()
                    scope.launch { user.snapTo(user.value + dy) }
                },
                onDragEnd = {
                    val at = app.tubehaus.engine.Sys.nowMs()
                    userAt = at
                    // read for a while, then the list glides back to the sung line
                    scope.launch {
                        delay(3500)
                        if (userAt == at) user.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 80f))
                    }
                },
            )
        },
    ) { measurables, cs ->
        val loose = Constraints(maxWidth = cs.maxWidth)
        val ps = measurables.map { it.measure(loose) }
        var y = 0f
        ps.forEachIndexed { i, p -> tops[i] = y; y += p.height + if (i < rows.size && rows[i].gap) gapPx * 0.4f else gapPx }
        val h = if (cs.hasBoundedHeight) cs.maxHeight else 1200
        val anchor = h * 0.3f
        layout(cs.maxWidth, h) {
            ps.forEachIndexed { i, p ->
                val top = anchor + tops[i] - scroll[i].value + user.value
                if (top + p.height > -80f && top < h + 80f) p.placeRelative(0, top.toInt())
            }
        }
    }
}

/** a sung line; the sung one fills from the left as the song goes */
@Composable
private fun Line(r: Row, on: Boolean, past: Boolean, now: () -> Long, ink: Color, style: androidx.compose.ui.text.TextStyle, tap: () -> Unit) {
    val lit by animateFloatAsState(if (on) 1f else 0f, tween(420, easing = Motion.standard))
    val grow by animateFloatAsState(if (on) 1f else 0.94f, spring(dampingRatio = 0.75f, stiffness = 220f))
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // how long the words take: guessed from the length, never past the next line
    val span = remember(r) { minOf(r.end - r.ms - 120, r.text.length * 230L + 500).coerceAtLeast(400) }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    Text(
        r.text, style = style, color = ink,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = tap)
            .padding(horizontal = 24.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = grow; scaleY = grow
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = (if (past) 0.38f else 0.5f) + (1f - (if (past) 0.38f else 0.5f)) * lit
            }
            .drawWithContent {
                val tl = layout
                if (!on || tl == null) { drawContent(); return@drawWithContent }
                val p = ((now() - r.ms).toFloat() / span).coerceIn(0f, 1f)
                val chars = p * r.text.length
                val all = Rect(0f, 0f, size.width, size.height)
                drawIntoCanvas { cv ->
                    // the words not sung yet, dim
                    cv.saveLayer(all, Paint().apply { alpha = 0.45f })
                    drawContent()
                    cv.restore()
                    // the sung part, bright, with a soft edge where the singing is
                    cv.saveLayer(all, Paint())
                    drawContent()
                    for (ln in 0 until tl.lineCount) {
                        val s = tl.getLineStart(ln)
                        val e = tl.getLineEnd(ln, visibleEnd = true)
                        val band = Rect(0f, tl.getLineTop(ln), size.width, tl.getLineBottom(ln))
                        when {
                            chars >= e -> {}
                            chars <= s -> drawRect(Color.Black, band.topLeft, band.size, blendMode = BlendMode.Clear)
                            else -> {
                                val k = chars.toInt().coerceIn(s, e - 1)
                                val x0 = tl.getHorizontalPosition(k, true)
                                val x1 = tl.getHorizontalPosition((k + 1).coerceAtMost(e), true)
                                val x = x0 + (x1 - x0) * (chars - k)
                                drawRect(
                                    Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - edge, endX = x + edge),
                                    band.topLeft, band.size, blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                    }
                    cv.restore()
                }
            },
    )
}

/** a pause in the singing: three dots that light one by one over it and breathe, then shrink away */
@Composable
private fun Pause(r: Row, on: Boolean, now: () -> Long) {
    val shown by animateFloatAsState(if (on) 1f else 0f, tween(Motion.MEDIUM))
    val ink = MaterialTheme.colorScheme.onBackground
    Box(Modifier.padding(horizontal = 24.dp).height(if (on) 36.dp else 18.dp)) {
        Canvas(Modifier.size(width = 64.dp, height = 36.dp)) {
            val t = now()
            val p = ((t - r.ms).toFloat() / (r.end - r.ms).coerceAtLeast(1)).coerceIn(0f, 1f)
            // the last moment: they shrink away as the singing comes back
            val out = if (p > 0.92f) 1f - (p - 0.92f) / 0.08f else 1f
            val breathe = 1f + 0.08f * sin(t / 420f)
            val k = (0.6f + 0.4f * shown) * breathe * out
            val rad = size.height * 0.16f * k
            for (i in 0 until 3) {
                val lit = ((p * 3f) - i).coerceIn(0f, 1f) * shown
                drawCircle(ink.copy(alpha = (0.3f + 0.7f * lit) * (0.5f + 0.5f * shown)), rad,
                    androidx.compose.ui.geometry.Offset(size.height * 0.3f + i * size.height * 0.62f, size.height / 2))
            }
        }
    }
}

/** the song's position for the lyrics: the player's, moved on every frame between its reports */
@Composable
fun rememberSongClock(positionMs: Long, playing: Boolean, speed: Float): () -> Long {
    val clock = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing, speed) {
        // a report a little behind the smoothed time doesn't pull it back (that would flicker the fill)
        val base = if (abs(positionMs - clock.longValue) < 600) maxOf(positionMs, clock.longValue) else positionMs
        clock.longValue = base
        if (!playing) return@LaunchedEffect
        val t0 = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = base + ((it - t0) * speed).toLong() }
    }
    return remember { { clock.longValue } }
}
