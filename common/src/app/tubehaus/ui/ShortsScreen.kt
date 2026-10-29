package app.tubehaus.ui

import app.tubehaus.engine.Sys
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.YtError
import app.tubehaus.rec.Learn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shorts, one after another. The Shorts engine learns from every one: swiping away early,
 * watching to the end, watching it loop, liking - and picks the next ones from your
 * subscriptions, channels and words you like, and a share of popular ones to find new things.
 *
 * The streams of the next two (and the last one) are fetched ahead, so a swipe plays at once.
 * Touch: swipe, tap to pause, double-tap to like. Mouse and keyboard: the wheel, the arrow
 * buttons or the up/down keys move between them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(app: AppState) {
    val list = remember { mutableStateListOf<Video>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val player = app.platform.player
    suspend fun more() {
        if (loading) return
        loading = true
        try {
            val got = app.rec.shorts(10, list.map { it.id }.toSet())
            list.addAll(got.filter { g -> list.none { it.id == g.id } })
            error = if (list.isEmpty()) tr("No Shorts found") else null
        } catch (e: Exception) {
            if (list.isEmpty()) error = e.message
        } finally { loading = false }
    }
    LaunchedEffect(Unit) { more() }
    val pager = rememberPagerState { list.size }
    val streams = remember { mutableStateMapOf<String, Stream>() }
    val fetching = remember { HashMap<String, Deferred<Result<Stream>>>() }
    var current by remember { mutableStateOf<Video?>(null) }
    var watchStart by remember { mutableStateOf(0L) }

    /** one fetch per Short, shared by whoever asks first */
    suspend fun streamOf(id: String): Stream {
        streams[id]?.let { return it }
        // a failed fetch comes back as a Result: it must not cancel the screen's other work
        val d = fetching.getOrPut(id) { scope.async { runCatching { YouTube.stream(id) } } }
        return try { d.await().getOrThrow().also { streams[id] = it } } finally { fetching.remove(id) }
    }

    fun ahead(page: Int) {
        for (p in listOf(page + 1, page + 2, page - 1)) {
            val n = list.getOrNull(p) ?: continue
            if (n.id !in streams && n.id !in fetching) scope.launch { runCatching { streamOf(n.id) } }
        }
    }

    fun learnFrom(v: Video) {
        val s = streams[v.id] ?: return
        val st = player.state.value
        if (st.videoId != v.id) return
        // time actually spent on it, which counts the loops
        val spent = (Sys.nowMs() - watchStart) / 1000.0
        Learn.learn(
            app.store, v.copy(channelId = s.channelId, author = s.author), s.keywords, spent, s.lengthSec.toDouble().coerceAtLeast(1.0),
            liked = app.store.inList("likes", v.id), disliked = app.store.inList("dislikes", v.id), short = true,
        )
    }

    fun go(by: Int) {
        val to = (pager.currentPage + by).coerceIn(0, maxOf(0, list.size - 1))
        if (to != pager.currentPage) scope.launch { pager.animateScrollToPage(to) }
    }

    LaunchedEffect(pager, list.size) {
        snapshotFlow { pager.currentPage }.collect { page ->
            val v = list.getOrNull(page) ?: return@collect
            if (current?.id == v.id) return@collect
            current?.let { learnFrom(it) }
            current = v
            if (page >= list.size - 4) scope.launch { more() }
            ahead(page)
            try {
                val s = streamOf(v.id)
                if (current?.id != v.id) return@collect
                watchStart = Sys.nowMs()
                player.load(s, PlayOptions(maxHeight = 720, loop = true, volume = app.store.settings.value.volume, title = s.title, artist = s.author, artwork = v.thumb))
            } catch (e: Exception) {
                app.toast(if ((e as? YtError)?.kind == YtError.Kind.LOGIN) tr(BOT_CHECK) else e.message ?: tr("This video can't be played"))
                // one that can't be played is skipped
                if ((e as? YtError)?.kind != YtError.Kind.LOGIN) {
                    delay(1200)
                    if (current?.id == v.id) go(1)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        app.shortsKeys = { key ->
            when (key) {
                Key.DirectionDown, Key.PageDown -> { go(1); true }
                Key.DirectionUp, Key.PageUp -> { go(-1); true }
                else -> playerKey(app, key)
            }
        }
        onDispose {
            app.shortsKeys = null
            current?.let { learnFrom(it) }
            player.stop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).wheelPages(pager, scope)) {
        when {
            list.isEmpty() && error != null -> Problem(error!!) { scope.launch { more() } }
            list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else -> VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].id }) { page ->
                val v = list[page]
                ShortPage(app, v, streams[v.id], active = current?.id == v.id)
            }
        }
        // the desktop's way through them, beside the Short
        if (!app.platform.touch && list.isNotEmpty()) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundAction(Sym.KeyboardArrowUp.fill, tr("Previous")) { go(-1) }
                RoundAction(Sym.KeyboardArrowDown.fill, tr("Next")) { go(1) }
            }
        }
    }
}

/** One wheel notch is one Short (a free-scrolling pager would stop between two). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.wheelPages(pager: PagerState, scope: CoroutineScope): Modifier = pointerInput(pager) {
    var last = 0L
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type != PointerEventType.Scroll) continue
            val dy = e.changes.sumOf { it.scrollDelta.y.toDouble() }
            e.changes.forEach { it.consume() }
            val now = Sys.nowMs()
            if (dy == 0.0 || now - last < 350 || pager.isScrollInProgress) continue
            last = now
            val to = (pager.currentPage + if (dy > 0) 1 else -1).coerceIn(0, maxOf(0, pager.pageCount - 1))
            scope.launch { pager.animateScrollToPage(to) }
        }
    }
}

@Composable
private fun ShortPage(app: AppState, v: Video, s: Stream?, active: Boolean) {
    val player = app.platform.player
    val st by player.state.collectAsState()
    val lib by app.store.lib.state.collectAsState()
    val full = s?.asVideo() ?: v
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    var hearts by remember { mutableIntStateOf(0) }
    var heart by remember { mutableStateOf(false) }
    LaunchedEffect(hearts) { if (hearts > 0) { heart = true; delay(650); heart = false } }
    val mine = active && st.videoId == v.id

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .pointerInput(v.id) {
                    detectTapGestures(
                        onTap = { if (player.state.value.playing) player.pause() else player.play() },
                        // YouTube's double-tap like: it only ever likes, never unlikes
                        onDoubleTap = {
                            if (!app.store.inList("likes", v.id)) app.store.setInList("likes", full, true)
                            hearts++
                        },
                    )
                },
        ) {
            if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.fillMaxSize())
            if (mine) player.View(Modifier.fillMaxSize())
            if (mine && !st.playing && !st.buffering) {
                Icon(Sym.PlayArrow.fill, null, tint = Color.White, modifier = Modifier.size(72.dp).align(Alignment.Center))
            }
            if (active && st.buffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
            }
            AnimatedVisibility(heart, Modifier.align(Alignment.Center), enter = scaleIn(initialScale = 0.4f), exit = fadeOut()) {
                Icon(Sym.Favorite.fill, null, tint = Color.White, modifier = Modifier.size(96.dp))
            }
            // the bottom: channel, title, and a seek bar you can drag
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x55000000)).padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 4.dp)) {
                val author = s?.author ?: v.author
                val cid = s?.channelId ?: v.channelId
                if (author.isNotEmpty()) Text(
                    "@$author", color = Color.White, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { if (cid.isNotEmpty()) app.go(Screen.Channel(cid, author)) },
                )
                Text(s?.title ?: v.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (mine && st.durationMs > 0) {
                    Track(
                        value = (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f), color = Red,
                        onDrag = {}, onDone = { f -> player.seekTo((f * st.durationMs).toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundAction(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like")) { app.store.setInList("likes", full, !liked) }
                RoundAction(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike")) { app.store.setInList("dislikes", full, !disliked) }
                RoundAction(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtube.com/shorts/${v.id}") }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row {
        IconButton(onClick = onClick, modifier = Modifier.background(Color(0x55000000), CircleShape)) {
            Icon(icon, label, tint = Color.White)
        }
    }
}
