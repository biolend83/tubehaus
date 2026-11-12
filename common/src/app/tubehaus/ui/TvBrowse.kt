package app.tubehaus.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * The TV's pages: rows of large pictures that slide under the remote, the chosen one grown a
 * little with a white frame; on Home the chosen video also fills the top of the screen.
 */

/** a list that slides so the chosen card stays first (rows) or the chosen row stays at the top */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvPinned(lead: Dp = 0.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { lead.toPx() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides remember(px) { TvScroll(px, 0f, pin = true) }, content = content)
}

/**
 * A video on the TV: the picture, the title under it and the channel and age. Chosen with the
 * remote it grows a little inside a white frame; holding OK opens its menu.
 */
@Composable
internal fun TvCard(
    v: Video, app: AppState, modifier: Modifier = Modifier, queue: List<Video> = emptyList(),
    onFocus: () -> Unit = {}, onClick: (() -> Unit)? = null,
) {
    if (!app.kidsAllow(v.channelId)) return
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val grow by animateFloatAsState(if (focused) 1.08f else 1f, tween(Motion.SHORT, easing = Motion.standard))
    val menu = remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    LaunchedEffect(focused) { if (focused) onFocus() }
    Column(
        modifier.zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = grow; scaleY = grow; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.3f) }
            .combinedClickable(src, indication = null, onLongClick = { menu.value = true }) { if (onClick != null) onClick() else app.watch(v, queue) },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .let { if (focused) it.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else it },
        ) {
            if (app.store.settings.value.hideThumbs) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(v.badge(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Thumb(v, app, Modifier.fillMaxSize(), rounded = 12)
            // the ⋮ menu, opened by holding OK; it has no button of its own on a TV
            Box(Modifier.align(Alignment.BottomEnd).size(0.dp)) { VideoMenu(v, app, openState = menu) }
        }
        Text(
            titleOf(v, app), fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            v.subline(calm), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** a "see all" tile at the end of a row, the size of a card's picture */
@Composable
private fun TvMoreTile(label: String, width: Dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color.Black else MaterialTheme.colorScheme.onSurface)
    }
}

private val CARD = 214.dp

/** a titled row of cards */
@Composable
internal fun TvShelf(
    title: String, items: List<Video>, app: AppState, modifier: Modifier = Modifier,
    first: FocusRequester? = null, onFocus: (Video) -> Unit = {}, more: (() -> Unit)? = null,
) {
    val items = items.distinctBy { it.id }
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = TV_EDGE, bottom = 12.dp))
        // the focused card sits where the row's title starts, the first one too
        TvPinned(lead = TV_EDGE) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(start = TV_EDGE, end = 120.dp, top = 8.dp, bottom = 8.dp),
            ) {
                items(items, key = { it.id }) { v ->
                    TvCard(
                        v, app, Modifier.width(CARD).let { if (first != null && v.id == items[0].id) it.focusRequester(first) else it },
                        queue = emptyList(), onFocus = { onFocus(v) },
                    )
                }
                if (more != null) item { TvMoreTile(tr("See all"), CARD, more) }
            }
        }
    }
}

internal val TV_EDGE = 40.dp

/** what Home showed last, kept while you watch so coming back finds the same rows */
private object TvHomeCache {
    val rec = mutableStateListOf<Video>()
    val topics = mutableStateListOf<Pair<String, List<Video>>>()
    var at = 0L
}

/**
 * Home on a TV, as the big-screen apps lay it out: the chosen video large at the top (its picture
 * across the screen, title, channel, and Watch), and under it rows: continue watching, recommended,
 * the newest from subscriptions, and topics.
 */
@Composable
fun TvHome(app: AppState) {
    val settings by app.store.settings.state.collectAsState()
    val profile = settings.profile
    var askTopics by remember(profile) { mutableStateOf(!settings.homeOff && app.rec.needsTopics()) }
    if (askTopics) { TopicPicker(app) { askTopics = false }; return }
    val lib by app.store.lib.state.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noShorts = app.noShorts
    suspend fun load() {
        if (loading || settings.homeOff) return
        loading = true; error = null
        try {
            coroutineScope {
                val topics = listOf("Music", "Live", "Gaming").map { k ->
                    async { k to runCatching { YouTube.channel(TOPICS[k]!!, null as String?).items.items.filterIsInstance<Video>() }.getOrDefault(emptyList()) }
                }
                val got = if (app.discover) app.rec.discover(48) else app.rec.home(48)
                TvHomeCache.rec.clear(); TvHomeCache.rec.addAll(got.filter { !(noShorts && it.short) })
                TvHomeCache.topics.clear()
                TvHomeCache.topics.addAll(topics.map { it.await() }.map { (k, l) -> k to l.filter { !it.short && !it.members } }.filter { it.second.isNotEmpty() })
                TvHomeCache.at = Sys.nowMs()
            }
        } catch (e: Exception) {
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        if (TvHomeCache.rec.isEmpty() || Sys.nowMs() - TvHomeCache.at > 30 * 60_000) load()
    }
    // the subscription feed, as the Subscriptions page keeps it
    var subsFeed by remember { mutableStateOf(app.feed.cached().filter { it.channelId in lib.subs }) }
    LaunchedEffect(lib.subs.size) {
        if (lib.subs.isNotEmpty() && !settings.subsFeedOff) runCatching { subsFeed = app.feed.all(false) }
    }
    val cont = remember(lib.hist, lib.pos) {
        lib.hist.asSequence().map { it.video() }.filter { v ->
            val p = lib.pos[v.id]
            p != null && p.size > 1 && p[1] > 0 && p[0] > 15 && p[0] < p[1] - 30
        }.take(20).toList()
    }
    val rec = TvHomeCache.rec
    val subs = subsFeed.filter { (!it.short || !noShorts) && it.channelId in lib.subs }.take(24)

    val shelves = buildList<Triple<String, List<Video>, (() -> Unit)?>> {
        if (cont.isNotEmpty()) add(Triple(tr("Continue watching"), cont, { app.go(Screen.History) }))
        if (rec.isNotEmpty()) add(Triple(tr("Recommended for you"), rec.take(16), null))
        if (subs.isNotEmpty()) add(Triple(tr("Latest from your subscriptions"), subs, { app.go(Screen.Subs) }))
        for ((k, l) in TvHomeCache.topics.take(1)) add(Triple(tr(k), l, { app.go(Screen.Topic(k)) }))
        if (rec.size > 16) add(Triple(tr("More to watch"), rec.drop(16).take(16), null))
        for ((k, l) in TvHomeCache.topics.drop(1)) add(Triple(tr(k), l, { app.go(Screen.Topic(k)) }))
        if (rec.size > 32) add(Triple(tr("More to watch"), rec.drop(32), null))
    }
    var hero by remember { mutableStateOf<Video?>(null) }
    val shown = hero ?: shelves.firstOrNull()?.second?.firstOrNull()

    if (shelves.isEmpty()) {
        when {
            loading || (!settings.homeOff && TvHomeCache.at == 0L && error == null) -> Busy(Modifier.fillMaxSize())
            error != null -> Problem(error!!) { scope.launch { load() } }
            else -> FeedOff(tr("The home feed is off"), tr("Search for what you came for, or open your subscriptions.")) {}
        }
        return
    }
    val first = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroH = maxHeight * 0.48f
        // the chosen video's picture, large behind the top of the page
        if (shown != null) Crossfade(shown.id, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).height(maxHeight * 0.66f), animationSpec = tween(Motion.LONG)) { id ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage("https://i.ytimg.com/vi/$id/hqdefault.jpg", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                AsyncImage("https://i.ytimg.com/vi/$id/maxresdefault.jpg", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        val bg = MaterialTheme.colorScheme.background
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).height(maxHeight * 0.66f)
            .background(Brush.horizontalGradient(0f to bg, 0.38f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).height(maxHeight * 0.66f)
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg)))
        Column(Modifier.fillMaxSize()) {
            // the chosen video's details
            Box(Modifier.fillMaxWidth().height(heroH)) {
                if (shown != null) TvHeroText(app, shown, Modifier.align(Alignment.BottomStart).padding(start = TV_EDGE, bottom = 18.dp).widthIn(max = 520.dp))
                TvSearchPill(app, Modifier.align(Alignment.TopStart).padding(start = TV_EDGE, top = 22.dp))
            }
            // a row comes up to the top with its title still showing
            TvPinned(lead = 44.dp) {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(bottom = 200.dp),
                ) {
                    items(shelves.size, key = { shelves[it].first + it }) { i ->
                        val (t, l, more) = shelves[i]
                        TvShelf(t, l, app, first = if (i == 0) first else null, onFocus = { hero = it }, more = more)
                    }
                    if (loading) item { Busy() }
                }
            }
        }
    }
    // the remote starts on the first card
    LaunchedEffect(shelves.isNotEmpty()) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
}

/** the big title over Home's picture: channel, title, views and age, length */
@Composable
private fun TvHeroText(app: AppState, v: Video, modifier: Modifier) {
    Column(modifier) {
        if (v.channelId.isNotEmpty() || v.author.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            val pic = channelAvatar(app, v.channelId, v.avatar)
            if (pic.isNotEmpty()) Avatar(pic, 28, Modifier.padding(end = 10.dp))
            Text(v.author, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            titleOf(v, app), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        val meta = listOf(v.subline(app.store.settings.value.calm, withAuthor = false), if (v.live) tr("LIVE") else duration(v.lengthSec))
            .filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** the search box at the top of Home: a pill the remote can choose; it opens the search page */
@Composable
private fun TvSearchPill(app: AppState, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier.height(44.dp).widthIn(min = 260.dp).clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .combinedClickable(src, indication = null) { app.go(Screen.Search()) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Search.outline, null, Modifier.size(22.dp), tint = if (focused) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr("Search"), fontSize = 15.sp, color = if (focused) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * Subscriptions on a TV: the channels in a row of circles at the top, the newest videos in a
 * grid of cards below.
 */
@Composable
fun TvSubs(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    if (lib.subs.isEmpty() || app.store.settings.value.subsFeedOff) { SubsScreen(app); return }
    var items by remember { mutableStateOf(app.feed.cached().filter { it.channelId in lib.subs }) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(lib.subs.size) {
        loading = true
        try { items = app.feed.all(false) } catch (_: Exception) {} finally { loading = false }
    }
    // the channels that posted last first, as YouTube's row
    val last = remember(items) { items.groupBy { it.channelId }.mapValues { (_, l) -> l.maxOf { it.publishedAt } } }
    val chans = remember(lib.subs, last) { lib.subs.entries.sortedByDescending { last[it.key] ?: it.value.at }.toList() }
    val feed = items.filter { (!it.short || !app.noShorts) && it.channelId in lib.subs }
    ItemGrid(
        feed, app, loading = loading,
        header = {
            Column {
                Text(tr("Subscriptions"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
                TvPinned {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                        items(chans, key = { it.key }) { (cid, s) -> TvChannelBubble(s.name, s.thumb) { app.go(Screen.Channel(cid, s.name)) } }
                        item { TvChannelBubble(tr("Manage"), "", icon = Sym.Settings) { app.go(Screen.ManageSubs) } }
                    }
                }
            }
        },
    )
}

@Composable
private fun TvChannelBubble(name: String, pic: String, icon: Symbol? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val grow by animateFloatAsState(if (focused) 1.1f else 1f, tween(Motion.SHORT))
    Column(
        Modifier.width(92.dp).graphicsLayer { scaleX = grow; scaleY = grow }.combinedClickable(src, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .let { if (focused) it.border(3.dp, Color.White, CircleShape) else it },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon.outline, null, Modifier.size(28.dp)) else Avatar(pic, 72)
        }
        Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Library on a TV: a row for each list (history, watch later, liked, your playlists) */
@Composable
fun TvLibrary(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    val first = remember { FocusRequester() }
    val rows = buildList<Triple<String, List<Video>, () -> Unit>> {
        if (lib.hist.isNotEmpty()) add(Triple(tr("History"), lib.hist.take(24).map { it.video() }, { app.go(Screen.History) }))
        if (lib.wl.isNotEmpty()) add(Triple(tr("Watch later"), lib.wl.take(24).map { it.video() }, { app.go(Screen.Local("wl")) }))
        if (lib.likes.isNotEmpty()) add(Triple(tr("Liked videos"), lib.likes.take(24).map { it.video() }, { app.go(Screen.Local("likes")) }))
        for ((id, p) in lib.playlists.entries.sortedByDescending { it.value.updated }) {
            if (p.items.isNotEmpty()) add(Triple(p.title, p.items.take(24).map { it.video() }, { app.go(Screen.Local(id)) }))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(tr("Library"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = TV_EDGE, top = 28.dp, bottom = 12.dp))
        if (rows.isEmpty()) {
            Empty(tr("Videos you watch, save and like show up here"))
            return@Column
        }
        TvPinned(lead = 44.dp) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 240.dp)) {
                items(rows.size, key = { rows[it].first + it }) { i ->
                    val (t, l, more) = rows[i]
                    TvShelf(t, l, app, first = if (i == 0) first else null, more = more)
                }
            }
        }
    }
    LaunchedEffect(rows.isNotEmpty()) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
}

