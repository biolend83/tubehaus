package app.tubehaus.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Channel
import app.tubehaus.engine.Item
import app.tubehaus.engine.Page
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.Video
import app.tubehaus.rec.Learn
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * TV: the remote moves a focus from card to card, and the selected one has to show from across
 * the room: it gets a teal frame and grows a little. Elsewhere this does nothing.
 */
@Composable
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    if (!LocalPlatform.current.tv) return this
    var on by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (on) 1.04f else 1f)
    return this.graphicsLayer { scaleX = grow; scaleY = grow }
        .onFocusChanged { on = it.hasFocus }
        .let { if (on) it.border(3.dp, Teal, shape) else it }
}

/**
 * clickable, and on a TV with [tvFocus]'s frame instead of a tint over the whole card;
 * [onLongClick] is holding OK on the remote (the card's ⋮ menu)
 */
@Composable
fun Modifier.tvClickable(shape: Shape = RoundedCornerShape(12.dp), onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    if (!LocalPlatform.current.tv) return clickable(onClick = onClick)
    return tvFocus(shape).combinedClickable(remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
}

/** on a TV the remote skips this (a button inside a card, reached by holding OK instead) */
@Composable
fun Modifier.notOnTv(): Modifier = if (LocalPlatform.current.tv) focusProperties { canFocus = false } else this

@Composable
fun Img(url: String, modifier: Modifier = Modifier, crop: Boolean = true) {
    AsyncImage(
        model = url.ifEmpty { null },
        contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
    )
}

@Composable
fun Avatar(url: String, size: Int = 36, modifier: Modifier = Modifier) {
    Img(url, modifier.size(size.dp).clip(CircleShape))
}

/** LIVE, SHORTS or the length: on the thumbnail, or before the details when thumbnails are off */
@Composable
fun Video.badge(): String = when {
    live -> tr("LIVE")
    short -> tr("SHORTS")
    else -> duration(lengthSec)
}

/** The line under a title; with thumbnails switched off it starts with the length they would show. */
@Composable
private fun Video.details(app: AppState, calm: Boolean): String =
    listOf(badge().takeIf { app.store.settings.value.hideThumbs } ?: "", subline(calm)).filter { it.isNotEmpty() }.joinToString(" · ")

/** Thumbnail with the length badge and a red bar for how much you've watched. */
@Composable
fun Thumb(v: Video, app: AppState, modifier: Modifier = Modifier, rounded: Int = 12) {
    val pos = app.store.lib.state.value.pos[v.id]
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(rounded.dp))) {
        Img(v.thumb, Modifier.fillMaxSize())
        val label = v.badge()
        if (label.isNotEmpty()) {
            Text(
                label,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(if (v.live) Red else Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (pos != null && (pos.getOrNull(1) ?: 0) > 0) {
            val f = (pos[0].toFloat() / pos[1]).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(f).height(3.dp).background(Red))
            }
        }
    }
}

/** The ⋮ menu of a video: save, add to playlist, not interested, share. */
@Composable
fun VideoMenu(v: Video, app: AppState, extra: List<Pair<String, () -> Unit>> = emptyList(), openState: MutableState<Boolean>? = null) {
    val opener = openState ?: remember { mutableStateOf(false) }
    var open by opener
    var pick by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).notOnTv()) {
            Icon(Icons.Default.MoreVert, tr("More"), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val inWl = app.store.inList("wl", v.id)
            DropdownMenuItem(text = { Text(tr(if (inWl) "Remove from Watch later" else "Save to Watch later")) }, onClick = {
                app.store.setInList("wl", v, !inWl); open = false
            })
            DropdownMenuItem(text = { Text(tr("Save to playlist")) }, onClick = { open = false; pick = true })
            DropdownMenuItem(text = { Text(tr("Share")) }, onClick = {
                open = false; app.share(v.title, "https://youtu.be/${v.id}")
            })
            if (app.platform.name == "android") DropdownMenuItem(text = { Text(tr("Download")) }, onClick = {
                open = false; app.download(v)
            })
            for ((label, act) in extra) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() })
            DropdownMenuItem(text = { Text(tr("Not interested")) }, onClick = {
                open = false; Learn.notInterested(app.store, v, channel = false); app.toast(tr("Got it. You'll see less like this"))
            })
            if (v.channelId.isNotEmpty()) DropdownMenuItem(text = { Text(tr("Don't recommend this channel")) }, onClick = {
                open = false; Learn.notInterested(app.store, v, channel = true); app.toast(tr("This channel won't be recommended"))
            })
        }
    }
    if (pick) PlaylistPicker(v, app) { pick = false }
}

/** Big card: thumbnail on top, avatar + title + details below (home, subscriptions, search). */
@Composable
fun VideoCard(v: Video, app: AppState, queue: List<Video> = emptyList()) {
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().tvClickable(onLongClick = { menu.value = true }) { app.watch(v, queue) }.padding(bottom = 12.dp)) {
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = if (LocalCompact.current) 0 else 12)
        Row(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp)) {
            if (v.channelId.isNotEmpty()) {
                Avatar(v.avatar.ifEmpty { app.store.lib.value.subs[v.channelId]?.thumb ?: "" }, 36,
                    Modifier.notOnTv().clickable { app.go(Screen.Channel(v.channelId, v.author)) })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            VideoMenu(v, app, openState = menu)
        }
    }
}

/** Compact row: small thumbnail on the left (related videos, playlists, history). */
@Composable
fun VideoRow(v: Video, app: AppState, queue: List<Video> = emptyList(), extra: List<Pair<String, () -> Unit>> = emptyList(), thumbWidth: Dp = 168.dp, onClick: (() -> Unit)? = null) {
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(8.dp), onLongClick = { menu.value = true }) { if (onClick != null) onClick() else app.watch(v, queue) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.width(thumbWidth), rounded = 8)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        VideoMenu(v, app, extra, openState = menu)
    }
}

@Composable
fun ChannelRow(c: Channel, app: AppState) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(8.dp)) { app.go(Screen.Channel(c.id, c.name)) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(168.dp), contentAlignment = Alignment.Center) { Avatar(c.avatar, 88) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(c.handle, c.subsText).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SubscribeButton(c.id, c.name, c.avatar, app)
    }
}

@Composable
fun PlaylistRow(p: Playlist, app: AppState) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(8.dp)) { app.go(Screen.Playlist(p.id, p.title)) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // with thumbnails off the box stays (it carries the video count) but is left plain
        Box(Modifier.width(if (app.store.settings.value.hideThumbs) 72.dp else 168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            if (!app.store.settings.value.hideThumbs) Img(p.thumb, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(p.countText, color = Color.White, fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(p.author, tr("Playlist")).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SubscribeButton(cid: String, name: String, thumb: String, app: AppState) {
    val subbed = cid in app.store.lib.state.value.subs
    Button(
        onClick = { app.store.subscribe(cid, name, thumb, !subbed) },
        colors = if (subbed) androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
        ) else androidx.compose.material3.ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(tr(if (subbed) "Subscribed" else "Subscribe"), fontSize = 14.sp) }
}

@Composable
fun Busy(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
}

@Composable
fun Problem(msg: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) Button(onClick = retry, modifier = Modifier.padding(top = 12.dp)) { Text(tr("Try again")) }
    }
}

@Composable
fun Empty(msg: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun idOf(x: Any?): Any? = (x as? Item)?.id ?: (x as? app.tubehaus.engine.Comment)?.id ?: x

/** Loads a first page, then more pages as the end of the list comes into view. */
class Pager<T>(private val first: suspend () -> Page<T>, private val more: suspend (String) -> Page<T>) {
    val items = androidx.compose.runtime.mutableStateListOf<T>()
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var started = false

    suspend fun load() {
        if (loading) return
        loading = true; error = null
        try {
            val p = first()
            items.clear(); items.addAll(p.items); next = p.next
        } catch (e: Exception) {
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false; started = true }
    }

    suspend fun loadMore() {
        val t = next ?: return
        if (loading) return
        loading = true
        try {
            val p = more(t)
            val have = items.map(::idOf).toHashSet()
            items.addAll(p.items.filter { idOf(it) !in have })
            next = p.next.takeIf { it != t && p.items.isNotEmpty() }
        } catch (e: Exception) {
            next = null
        } finally { loading = false }
    }
}

@Composable
fun <T> rememberPager(key: Any?, first: suspend () -> Page<T>, more: suspend (String) -> Page<T>): Pager<T> {
    val p = remember(key) { Pager(first, more) }
    LaunchedEffect(key) { if (!p.started) p.load() }
    return p
}

/** A responsive list of items: one column on a phone, a grid of cards on a wide window. */
@Composable
fun ItemGrid(
    items: List<Item>,
    app: AppState,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    loading: Boolean = false,
    onEnd: (suspend () -> Unit)? = null,
    rows: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    if (onEnd != null) {
        LaunchedEffect(state, items.size) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                if (last >= state.layoutInfo.totalItemsCount - 4) scope.launch { onEnd() }
            }
        }
    }
    val list = items.distinctBy { it.id }
    // TV: the first card takes the remote's focus when the list arrives, so the arrows work at once
    val tv = LocalPlatform.current.tv
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (tv) LaunchedEffect(list.isNotEmpty()) {
        if (list.isNotEmpty() && !focused) { delay(150); focused = runCatching { first.requestFocus() }.isSuccess }
    }
    LazyVerticalGrid(
        columns = if (rows) GridCells.Adaptive(560.dp) else GridCells.Adaptive(320.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 0.dp else 16.dp),
        contentPadding = PaddingValues(horizontal = if (LocalCompact.current) 0.dp else 16.dp, vertical = 8.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(list, key = { it.id }, span = { if (it is Video && !rows) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
            Box(if (tv && it.id == list[0].id) Modifier.focusRequester(first) else Modifier) {
                when (it) {
                    is Video -> if (rows) VideoRow(it, app) else VideoCard(it, app)
                    is Channel -> ChannelRow(it, app)
                    is Playlist -> PlaylistRow(it, app)
                }
            }
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Busy() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
fun Narrow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) { content() }
    }
}
