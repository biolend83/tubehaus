package app.tubehaus.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import app.tubehaus.engine.Sys
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Item
import app.tubehaus.engine.Video
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The TubeHaus 1.5 home (Settings → Design → TubeHaus 1.5): a few picks large across the top, turning
 * by themselves, with the colours of the one on show spread softly behind the page; then what you were
 * watching, rows by where the videos come from (your subscriptions, what you watched, the topics you
 * like), and the rest as boxed cards that keep coming as you scroll.
 */

/** Home's list: the 1.5 home in that design (not on a TV, not for Discover), the grid otherwise */
@Composable
fun HomeFeed(
    items: List<Item>, app: AppState, loading: Boolean, onEnd: suspend () -> Unit,
    hero: Boolean, header: @Composable () -> Unit,
) {
    if (LocalLook.current == 3 && !app.discover && !app.platform.tv) Home2(app, items, loading, onEnd, header)
    else ItemGrid(items, app, loading = loading, onEnd = onEnd, hero = hero, header = header)
}

/** the colours of a picture, blurred, behind the top of a page; it fades to the page's background below */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    val ctx = LocalPlatformContext.current
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    Box(modifier) {
        Crossfade(url, animationSpec = tween(900), modifier = Modifier.matchParentSize()) { u ->
            // a picture a few pixels wide, stretched: soft by itself where blur isn't drawn (older Android)
            if (u != null) AsyncImage(
                ImageRequest.Builder(ctx).data(u).size(32, 24).build(), null,
                Modifier.fillMaxSize().blur(48.dp), contentScale = ContentScale.Crop, alpha = if (dark) 0.62f else 0.42f,
            )
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to bg.copy(alpha = 0.15f), 0.5f to bg.copy(alpha = 0.6f), 1f to bg)))
    }
}

private const val PICK_MS = 7000L

/** the details under a video rise in a little when it opens (a new video, a new rise) */
@Composable
fun Modifier.riseIn(key: Any): Modifier {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(480, easing = Motion.out)) }
    return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36.dp.toPx() }
}

private data class Shelf(val key: String, val title: String, val videos: List<Video>)

@Composable
private fun shelfTitle(src: String, tag: String?): String = when (src) {
    "sub" -> tr("New from your subscriptions")
    "rel" -> tr("More like what you watched")
    "chan" -> tr("Channels you might like")
    else -> if (tag.isNullOrEmpty()) tr("For you") else tr("Picked for “{t}”").replace("{t}", tr(tag))
}

@Composable
private fun Home2(app: AppState, items: List<Item>, loading: Boolean, onEnd: suspend () -> Unit, header: @Composable () -> Unit) {
    val videos = items.filterIsInstance<Video>().filter { app.kidsAllow(it.channelId) }.distinctBy { it.id }
    val heroes = videos.take(5)
    // rows from the first batch only, so they keep their places while more arrives below
    val firstBatch = videos.drop(5).take(45)
    val labels = HashMap<String, String>()
    for (v in firstBatch) {
        val (src, tag) = app.rec.sourceOf(v.id) ?: continue
        val key = src + ":" + (if (src == "kw" || src == "pop") tag ?: "" else "")
        if (key !in labels) labels[key] = shelfTitle(src, tag)
    }
    val shelves = remember(firstBatch.size, firstBatch.firstOrNull()?.id) {
        val groups = LinkedHashMap<String, MutableList<Video>>()
        for (v in firstBatch) {
            val (src, tag) = app.rec.sourceOf(v.id) ?: continue
            val key = src + ":" + (if (src == "kw" || src == "pop") tag ?: "" else "")
            groups.getOrPut(key) { ArrayList() } += v
        }
        groups.filter { it.value.size >= 4 }.entries.take(6).map { Shelf(it.key, "", it.value.take(12)) }
    }
    val inShelf = shelves.flatMap { s -> s.videos.map { it.id } }.toSet()
    val rest = videos.filter { it.id !in inShelf }
    val phone = LocalCompact.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(list, items.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= list.layoutInfo.totalItemsCount - 3) scope.launch { onEnd() }
        }
    }
    // the rows already shown arrive once, not again each time they scroll back into view
    val seen = remember { HashSet<Any>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = if (phone) 16.dp else 28.dp
        val cols = if (phone) 1 else ((maxWidth - edge * 2) / 320.dp).toInt().coerceAtLeast(1)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") { Column(Modifier.padding(horizontal = if (phone) 0.dp else edge - 12.dp)) { header() } }
            item(key = "hello") { Greeting(app, edge) }
            item(key = "continue") { Column(Modifier.padding(start = if (phone) 0.dp else edge - 4.dp, top = 8.dp)) { ContinueRow(app, forYou = false) } }
            item(key = "words") { Words(app, edge) }
            for ((n, s) in shelves.withIndex()) {
                item(key = "shelf:" + s.key) {
                    Column(Modifier.arrive("shelf:" + s.key, n, seen)) { ShelfRow(app, labels[s.key] ?: "", s.videos, edge) }
                }
                // the Shorts after the first row
                if (n == 0) item(key = "shorts") { ShortsShelf(app, edge) }
            }
            if (shelves.isEmpty()) item(key = "shorts") { ShortsShelf(app, edge) }
            if (rest.isNotEmpty()) item(key = "more") { Heading(tr("More for you"), edge) }
            val rows = rest.chunked(cols)
            items(rows.size, key = { "row:" + rows[it].first().id }) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 0.dp else edge - 5.dp).arrive("row:" + rows[r].first().id, r, seen)) {
                    for (v in rows[r]) Box(Modifier.weight(1f)) { GlassCard(v, app, solid = true) }
                    repeat(cols - rows[r].size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (loading) item(key = "busy") { Busy() }
        }
    }
}

/** good morning (afternoon, evening), and how long you watched today */
@Composable
private fun Greeting(app: AppState, edge: androidx.compose.ui.unit.Dp) {
    val stats by app.store.stats.state.collectAsState()
    val today = stats.days[Sys.today()]?.let { (it.video + it.music) / 60 } ?: 0L
    val hello = when (Sys.hour()) {
        in 5..10 -> tr("Good morning")
        in 11..16 -> tr("Good afternoon")
        in 17..21 -> tr("Good evening")
        else -> tr("Still up?")
    }
    Column(Modifier.padding(start = edge, end = edge, top = 6.dp, bottom = 14.dp)) {
        Text(hello, fontSize = if (LocalCompact.current) 26.sp else 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
        Text(
            if (today > 0) tr("You've watched {t} today").replace("{t}", minutesText(today)) else tr("Here's what's new for you"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** the words your recommendations like most, each a search */
@Composable
private fun Words(app: AppState, edge: androidx.compose.ui.unit.Dp) {
    val taste by app.store.taste.state.collectAsState()
    val words = remember(taste.kw) { taste.kw.entries.filter { it.value > 0.5 }.sortedByDescending { it.value }.map { it.key }.take(10) }
    if (words.isEmpty()) return
    Heading(tr("Your interests"), edge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in words) Text(
            "#$w", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(accentSoft).clickable { app.go(Screen.Search(w)) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** a row of Shorts, standing up; one opens in the player */
@Composable
private fun ShortsShelf(app: AppState, edge: androidx.compose.ui.unit.Dp) {
    val s = app.store.settings.value
    if (s.hideShorts || s.calm || s.kids) return
    val list = remember { mutableStateListOf<Video>() }
    LaunchedEffect(Unit) { if (list.isEmpty()) runCatching { app.rec.shorts(12) }.getOrNull()?.let { list.addAll(it) } }
    if (list.isEmpty()) return
    Heading("Shorts", edge) {
        androidx.compose.material3.TextButton(onClick = { app.go(Screen.Shorts) }) { Text(tr("See all")) }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { v ->
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.width(if (LocalCompact.current) 140.dp else 170.dp).aspectRatio(9f / 16f).clip(shape).tvClickable(shape) { app.watch(v) }) {
                Img(v.thumb, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xD9000000))))
                Text(titleOf(v, app), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: String, edge: androidx.compose.ui.unit.Dp, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = edge, end = edge - 8.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** the large picks across the top: swiped (or turning by themselves), with dots under them */
@Composable
private fun Picks(app: AppState, heroes: List<Video>, pager: PagerState, edge: androidx.compose.ui.unit.Dp) {
    val phone = LocalCompact.current
    val scope = rememberCoroutineScope()
    Column {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = edge), pageSpacing = 14.dp) { i ->
            Pick(app, heroes[i], i, heroes.size)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (!phone) IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + heroes.size) % heroes.size) } }) {
                Icon(Sym.ChevronRight.outline, tr("Previous"), Modifier.graphicsLayer { rotationZ = 180f })
            }
            for (i in heroes.indices) {
                val on = pager.currentPage == i
                val w by animateDpAsState(if (on) 30.dp else 7.dp, tween(Motion.MEDIUM))
                Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
                    .clickable { scope.launch { pager.animateScrollToPage(i) } }) {
                    // the one on show fills up until the next one comes
                    if (on) {
                        val fill = remember(pager.currentPage) { Animatable(0f) }
                        LaunchedEffect(pager.currentPage) { fill.animateTo(1f, tween(PICK_MS.toInt(), easing = LinearEasing)) }
                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (heroes.size > 1) fill.value else 1f).clip(CircleShape).background(accent))
                    }
                }
            }
            if (!phone) IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % heroes.size) } }) {
                Icon(Sym.ChevronRight.outline, tr("Next"))
            }
        }
    }
}

@Composable
private fun Pick(app: AppState, v: Video, i: Int, count: Int) {
    val phone = LocalCompact.current
    val shape = RoundedCornerShape(if (phone) 24.dp else 30.dp)
    val calm = app.store.settings.value.calm
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (phone) 16f / 9f else 2.5f).clip(shape)
            .tvClickable(shape) { app.watch(v) },
    ) {
        Img(v.thumb, Modifier.fillMaxSize())
        if (!phone) Img(v.thumbHigh, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(
            if (phone) Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xE6000000))
            else Brush.horizontalGradient(0f to Color(0xE6000000), 0.55f to Color(0x66000000), 1f to Color.Transparent),
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(start = if (phone) 16.dp else 36.dp, end = 16.dp, bottom = if (phone) 14.dp else 32.dp).widthIn(max = 620.dp)) {
            Text(tr("Today's pick") + "  ${i + 1}/$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF)).padding(horizontal = 9.dp, vertical = 3.dp))
            Text(titleOf(v, app), color = Color.White, fontSize = if (phone) 18.sp else 32.sp, lineHeight = if (phone) 23.sp else 38.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text(v.details(app, calm), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (!phone) Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { app.watch(v) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Icon(Sym.PlayArrow.fill, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Play"), fontWeight = FontWeight.Bold)
                }
                if (v.channelId.isNotEmpty()) Row(
                    Modifier.padding(start = 14.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
                        .clickable { app.go(Screen.Channel(v.channelId, v.author)) }.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(channelAvatar(app, v.channelId, v.avatar), 30)
                    Spacer(Modifier.width(8.dp))
                    Text(v.author, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** one row of videos from the same place, swiped across (with arrows on a computer) */
@Composable
private fun ShelfRow(app: AppState, title: String, videos: List<Video>, edge: androidx.compose.ui.unit.Dp) {
    val phone = LocalCompact.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cardW = if (phone) 250.dp else 300.dp
    val calm = app.store.settings.value.calm
    Heading(title, edge) {
        if (!phone) {
            IconButton(onClick = { scope.launch { state.animateScrollBy(-900f) } }) { Icon(Sym.ChevronRight.outline, tr("Previous"), Modifier.graphicsLayer { rotationZ = 180f }) }
            IconButton(onClick = { scope.launch { state.animateScrollBy(900f) } }) { Icon(Sym.ChevronRight.outline, tr("Next")) }
        }
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(videos, key = { it.id }) { v ->
            Column(Modifier.width(cardW).clip(RoundedCornerShape(20.dp)).tvClickable(RoundedCornerShape(20.dp)) { app.watch(v) }) {
                Thumb(v, app, Modifier.fillMaxWidth(), rounded = 20)
                Text(titleOf(v, app), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 10.dp))
            }
        }
    }
}
