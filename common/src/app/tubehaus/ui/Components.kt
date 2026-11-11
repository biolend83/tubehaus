package app.tubehaus.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.Sys
import app.tubehaus.engine.DeArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val grow by animateFloatAsState(if (on) 1.04f else 1f, tween(Motion.SHORT, easing = Motion.standard))
    return this.graphicsLayer { scaleX = grow; scaleY = grow }
        .onFocusChanged { on = it.hasFocus }
        .let { if (on) it.border(3.dp, Teal, shape) else it }
}

/**
 * clickable, and on a TV with [tvFocus]'s frame instead of a tint over the whole card;
 * [onLongClick] is holding OK on the remote (the card's ⋮ menu). On a touch screen or with a mouse
 * it is pressed as in YouTube's app: the card sinks a little and a soft grey shape comes up behind
 * it (no ripple), quickly in and slowly out.
 */
@Composable
fun Modifier.tvClickable(shape: Shape = RoundedCornerShape(12.dp), onLongClick: (() -> Unit)? = null, sink: Float = 0.97f, onClick: () -> Unit): Modifier {
    if (!LocalPlatform.current.tv) {
        val touch = remember { MutableInteractionSource() }
        val pressed by touch.collectIsPressedAsState()
        // let go: a button springs back (the web's buttons), a card settles
        val sink by animateFloatAsState(if (pressed) sink else 1f,
            if (pressed) tween(Motion.SHORT, easing = Motion.standard) else if (sink < 0.96f) spring(0.5f, Spring.StiffnessMedium) else tween(Motion.MEDIUM, easing = Motion.standard))
        val shade by animateFloatAsState(if (pressed) 0.1f else 0f, tween(if (pressed) 90 else Motion.LONG, easing = Motion.standard))
        val ink = MaterialTheme.colorScheme.onSurface
        return graphicsLayer { scaleX = sink; scaleY = sink }
            .drawBehind { if (shade > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), ink.copy(alpha = shade)) }
            .combinedClickable(touch, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
    }
    return tvFocus(shape).combinedClickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
}

/**
 * A chip as the web app 1.0 draws them: a pill, the chosen one filled with the text color (white on
 * dark, black on light), the others a quiet grey. Home, Subscriptions' groups, search, comments.
 */
@Composable
fun Chip(selected: Boolean, onClick: () -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    // the colours turn over smoothly when it is chosen
    val bg by animateColorAsState(if (selected) c.onSurface else c.surfaceVariant, tween(Motion.SHORT))
    val fg by animateColorAsState(if (selected) c.surface else c.onSurface, tween(Motion.SHORT))
    Row(
        Modifier.height(36.dp).tvClickable(shape, sink = 0.94f, onClick = onClick).semantics { this.selected = selected }.clip(shape).background(bg)
            .padding(horizontal = if (icon != null && label.isEmpty()) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        if (label.isNotEmpty()) Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
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

/**
 * A channel's picture for a card whose list came without one (YouTube leaves it out of many lists):
 * a subscription's, else the one its channel page shows, asked once and kept on the device.
 */
@Composable
fun channelAvatar(app: AppState, channelId: String, given: String): String {
    if (given.isNotEmpty() || channelId.isEmpty()) return given
    app.store.lib.value.subs[channelId]?.thumb?.takeIf { it.isNotEmpty() }?.let { return it }
    val known = app.store.avatars.state.collectAsState().value[channelId]
    if (known != null) return known
    LaunchedEffect(channelId) { ChannelAvatars.fetch(app, channelId) }
    return ""
}

private object ChannelAvatars {
    private val asking = Sys.concurrentMap<String, Boolean>()
    /** a home page of 40 cards asks a few channels at a time */
    private val gate = Semaphore(3)

    suspend fun fetch(app: AppState, id: String) {
        if (asking.containsKey(id)) return
        asking[id] = true
        try {
            val url = gate.withPermit { YouTube.channel(id).avatar }
            if (url.isNotEmpty()) app.store.avatars.update { m -> (if (m.size > 3000) m.entries.drop(500).associate { it.key to it.value } else m) + (id to url) }
        } catch (e: CancellationException) {
            asking.remove(id)      // the card went away: the next one showing this channel asks again
            throw e
        } catch (_: Exception) {
            // offline or YouTube said no: leave the circle empty until the app opens again
        }
    }
}

/** DeArrow's title and thumbnail for [v] while switched on: asked once per video, then kept */
@Composable
fun branded(v: Video, app: AppState): DeArrow.Brand? {
    if (!app.store.settings.value.dearrow || v.short || v.id.isEmpty()) return null
    var b by remember(v.id) { mutableStateOf(DeArrow.cached(v.id)) }
    LaunchedEffect(v.id) { if (b == null) b = DeArrow.brand(v.id) }
    return b
}

/** the title to show: DeArrow's when it has one */
@Composable
fun titleOf(v: Video, app: AppState): String = branded(v, app)?.title?.ifEmpty { null } ?: v.title

/** Thumbnail with the length badge and a red bar for how much you've watched. */
@Composable
fun Thumb(v: Video, app: AppState, modifier: Modifier = Modifier, rounded: Int = 16) {
    val pos = app.store.lib.state.value.pos[v.id]
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(rounded.dp))) {
        Img(v.thumb, Modifier.fillMaxSize())
        // DeArrow's frame laid over YouTube's picture: it shows once it has loaded, and not at all if it can't
        val sec = branded(v, app)?.timeSec?.takeIf { app.store.settings.value.dearrowThumbs && !v.live }
        if (sec != null) AsyncImage(DeArrow.thumbUrl(v.id, sec), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val label = v.badge()
        if (label.isNotEmpty()) {
            Text(
                label,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(if (rounded >= 12) 8.dp else 4.dp)
                    .background(if (v.live) Red else Color(0xB3000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (pos != null && (pos.getOrNull(1) ?: 0) > 0) {
            val f = (pos[0].toFloat() / pos[1]).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(Red))
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
            Icon(Sym.MoreVert.fill, tr("More"), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val inWl = app.store.inList("wl", v.id)
            DropdownMenuItem(text = { Text(tr(if (inWl) "Remove from Watch later" else "Save to Watch later")) }, onClick = {
                app.store.setInList("wl", v, !inWl); open = false
            })
            DropdownMenuItem(text = { Text(tr("Save to playlist")) }, onClick = { open = false; pick = true })
            // while a video plays: line this one up after it (#59)
            val pb = app.playback
            if (pb.video != null && !pb.music && pb.video?.id != v.id) {
                DropdownMenuItem(text = { Text(tr("Play next")) }, onClick = { open = false; pb.queueNext(v); app.toast(tr("Plays next")) })
                DropdownMenuItem(text = { Text(tr("Add to queue")) }, onClick = { open = false; pb.queueAdd(v); app.toast(tr("Added to the queue")) })
            }
            DropdownMenuItem(text = { Text(tr("Share")) }, onClick = {
                open = false; app.share(v.title, "https://youtu.be/${v.id}")
            })
            // saved on this device to watch without internet
            when {
                app.offline.has(v.id) -> DropdownMenuItem(text = { Text(tr("Remove download")) }, onClick = { open = false; app.offline.remove(v.id) })
                app.offline.downloading(v.id) -> DropdownMenuItem(text = { Text(tr("Cancel download")) }, onClick = { open = false; app.offline.cancel(v.id) })
                !v.live && app.platform.canDownload -> DropdownMenuItem(text = { Text(tr("Download")) }, onClick = { open = false; app.offline.asking = v })
            }
            if (app.platform.name == "android") DropdownMenuItem(text = { Text(tr("Download with another app")) }, onClick = {
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
    // as the web app 1.0 draws them: a rounded picture (on a phone inside the page's 12dp margin,
    // one video per row), the avatar lined up with its edge, a bold title, the rows well apart
    val phone = LocalCompact.current
    Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 12.dp else 0.dp)
        .tvClickable(RoundedCornerShape(18.dp), onLongClick = { menu.value = true }) { app.watch(v, queue) }.padding(bottom = if (phone) 22.dp else 32.dp)) {
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = 16)
        Row(Modifier.padding(top = if (phone) 10.dp else 12.dp)) {
            if (v.channelId.isNotEmpty()) {
                Avatar(channelAvatar(app, v.channelId, v.avatar), 36,
                    Modifier.notOnTv().clickable { app.go(Screen.Channel(v.channelId, v.author)) })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titleOf(v, app), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            VideoMenu(v, app, openState = menu)
        }
    }
}

/** Compact row: small thumbnail on the left (related videos, playlists, history). */
@Composable
fun VideoRow(v: Video, app: AppState, queue: List<Video> = emptyList(), extra: List<Pair<String, () -> Unit>> = emptyList(), thumbWidth: Dp = 168.dp, big: Boolean = false, onClick: (() -> Unit)? = null) {
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp), onLongClick = { menu.value = true }) { if (onClick != null) onClick() else app.watch(v, queue) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // big: YouTube's search results on a computer, a large picture and a larger title beside it
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.width(thumbWidth), rounded = if (big) 18 else 12)
        Column(Modifier.weight(1f).padding(start = if (big) 16.dp else 10.dp)) {
            val title = titleOf(v, app)
            if (big) Text(title, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        VideoMenu(v, app, extra, openState = menu)
    }
}

@Composable
fun ChannelRow(c: Channel, app: AppState) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp)) { app.go(Screen.Channel(c.id, c.name)) }.padding(horizontal = 12.dp, vertical = 10.dp),
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
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp)) { app.go(Screen.Playlist(p.id, p.title)) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // with thumbnails off the box stays (it carries the video count) but is left plain
        Box(Modifier.width(if (app.store.settings.value.hideThumbs) 72.dp else 168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            if (!app.store.settings.value.hideThumbs) Img(p.thumb, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.PlaylistPlay.outline, null, tint = Color.White, modifier = Modifier.size(14.dp))
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
    // collected, so the button turns over the moment you press it (it used to wait for the page to reload)
    val subbed = cid in app.store.lib.state.collectAsState().value.subs
    // from the bright pill to the grey one (and back) in a short fade, not a jump
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (subbed) c.surfaceVariant else c.primary, tween(Motion.MEDIUM))
    val fg by animateColorAsState(if (subbed) c.onSurface else c.onPrimary, tween(Motion.MEDIUM))
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            app.store.subscribe(cid, name, thumb, !subbed)
            if (!subbed) scope.launch { pop.snapTo(0.9f); pop.animateTo(1f, spring(0.4f, Spring.StiffnessMedium)) }
        },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.height(40.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
    ) { Text(tr(if (subbed) "Subscribed" else "Subscribe"), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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
    /** with rows: one column of large rows down the middle, as YouTube lists search results on a computer */
    wideRows: Boolean = false,
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
    val big = rows && wideRows
    // the cards already shown: those arrive once, not again each time they scroll back into view
    val seen = remember { HashSet<Any>() }
    if (list.isEmpty() && loading && header == null) { Skeleton(rows, wideRows); return }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    // the large rows' picture shrinks with the window, so a narrow one still leaves room for the title
    val bigThumb = when { maxWidth > 900.dp -> 360.dp; maxWidth > 640.dp -> 246.dp; else -> 168.dp }
    LazyVerticalGrid(
        columns = if (big) GridCells.Fixed(1) else if (rows) GridCells.Adaptive(560.dp) else if (tv) GridCells.Fixed(4) else GridCells.Adaptive(320.dp),
        state = state,
        modifier = Modifier.let { if (big) it.widthIn(max = 1120.dp) else it }.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 0.dp else if (tv) 22.dp else 20.dp),
        contentPadding = PaddingValues(horizontal = if (LocalCompact.current) 0.dp else if (tv) TV_EDGE else 24.dp, vertical = if (tv) 16.dp else 8.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(list, key = { _, it -> it.id }, span = { _, it -> if (it is Video && !rows) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { i, it ->
            Box((if (tv && it.id == list[0].id) Modifier.focusRequester(first) else Modifier).arrive(it.id, i, seen)) {
                when (it) {
                    is Video -> if (rows) VideoRow(it, app, thumbWidth = if (big) bigThumb else 168.dp, big = big)
                        // TV: the big-screen card, which grows in a white frame when chosen
                        else if (tv) TvCard(it, app, Modifier.fillMaxWidth().padding(bottom = 30.dp))
                        else VideoCard(it, app)
                    is Channel -> ChannelRow(it, app)
                    is Playlist -> PlaylistRow(it, app)
                }
            }
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Busy() }
    }
    }
}

/**
 * What the web app shows while a page loads: soft grey shapes where the pictures and the lines of text
 * will be, a light sweeping across them, instead of a spinner. rows: the list form (a picture on the
 * left); wide: the one large column of search results on a computer.
 */
@Composable
fun Skeleton(rows: Boolean = false, wide: Boolean = false) {
    val phone = LocalCompact.current
    val sweep by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
    )
    val grey = MaterialTheme.colorScheme.surfaceContainer
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    fun Modifier.bone(shape: Shape = RoundedCornerShape(6.dp)) = this.clip(shape).background(grey).drawBehind {
        val w = size.width
        val x = -0.5f * w + sweep * 2f * w
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x - w * 0.3f, endX = x + w * 0.3f))
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val big = rows && wide
        val thumb = when { !big -> 168.dp; maxWidth > 900.dp -> 360.dp; maxWidth > 640.dp -> 246.dp; else -> 168.dp }
        LazyVerticalGrid(
            columns = if (big) GridCells.Fixed(1) else if (rows) GridCells.Adaptive(560.dp) else GridCells.Adaptive(320.dp),
            modifier = Modifier.let { if (big) it.widthIn(max = 1120.dp) else it }.fillMaxSize(),
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 20.dp),
            contentPadding = PaddingValues(horizontal = if (phone) 0.dp else 24.dp, vertical = 8.dp),
        ) {
            items(12) {
                if (rows) Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Box(Modifier.width(thumb).aspectRatio(16f / 9f).bone(RoundedCornerShape(if (big) 18.dp else 12.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                        Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.5f).height(12.dp).bone())
                    }
                } else Column(Modifier.padding(horizontal = if (phone) 12.dp else 0.dp).padding(bottom = if (phone) 22.dp else 32.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).bone(RoundedCornerShape(16.dp)))
                    Row(Modifier.padding(top = 12.dp, end = 12.dp)) {
                        Box(Modifier.size(36.dp).bone(CircleShape))
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                            Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(12.dp).bone())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

// ---------------------------------------------------------------- rounded, tonal pieces
// The quieter look of music apps such as Flow: soft grey boxes with big round corners instead of
// lines and outlines, pill-shaped buttons, icons in circles.

private val PillShape = RoundedCornerShape(50)

/** a pill-shaped button: grey, or [filled] with the text colour (the main action, such as Play all) */
@Composable
fun PillButton(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, filled: Boolean = false,
    enabled: Boolean = true, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val fg = if (filled) c.surface else c.onSurface
    Row(
        modifier.height(height).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(PillShape)
            .background(if (filled) c.onSurface else c.surfaceVariant)
            .then(if (enabled) Modifier.tvClickable(PillShape, sink = 0.94f, onClick = onClick) else Modifier)
            .padding(horizontal = if (label.isEmpty()) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (label.isNotEmpty()) Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** a round grey button with an icon; [on] tints it teal (shuffle or repeat switched on) */
@Composable
fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, size: Dp = 44.dp, on: Boolean = false,
    tint: Color = Color.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.size(size).tvClickable(CircleShape, sink = 0.9f, onClick = onClick).clip(CircleShape).background(if (on) accentSoft else c.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size * 0.5f), tint = if (on) accent else if (tint != Color.Unspecified) tint else c.onSurface)
    }
}

/** a small grey label above a group of rows ("Your content", "Playlists") */
@Composable
fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 18.dp, bottom = 4.dp))
}

/** rows in one rounded grey box */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 4.dp),
        content = content,
    )
}

/** a row of a [RowGroup]: the icon in a circle, a title, a line under it, a count, an arrow */
@Composable
fun GroupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String = "", trailing: String = "",
    arrow: Boolean = true, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp), onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (arrow) Icon(Sym.ChevronRight.outline, null, Modifier.padding(start = 4.dp).size(22.dp), tint = c.onSurfaceVariant)
    }
}

/** a shelf's title, with its icon; when it opens a page, the whole line does, with an arrow at the end */
@Composable
fun ShelfTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onMore: (() -> Unit)? = null, end: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onMore != null) Modifier.tvClickable(RoundedCornerShape(12.dp), onClick = onMore) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 10.dp).size(22.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (end != null) end()
        if (onMore != null) Icon(Sym.ChevronRight.outline, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** a search box as a grey pill, without the outline */
@Composable
fun SearchField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    leading: androidx.compose.ui.graphics.vector.ImageVector = Sym.Search.outline, onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.TextField(
        value, onChange, modifier.fillMaxWidth().heightIn(min = 52.dp), singleLine = true,
        shape = PillShape,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(leading, null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Sym.Close.outline, tr("Clear")) }
                if (trailing != null) trailing()
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * The top of a list of videos (a playlist, Watch later, liked videos): its picture large and
 * rounded, the name, a line about it, Play all and Shuffle, and whatever else [more] adds.
 */
@Composable
fun ListHeader(
    cover: String, title: String, line: String, enabled: Boolean, play: () -> Unit, shuffle: () -> Unit,
    more: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val wide = maxWidth > 700.dp
        val pic: @Composable (Modifier) -> Unit = { m ->
            Box(m.aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (cover.isNotEmpty()) Img(cover, Modifier.fillMaxSize())
                else Icon(Sym.PlaylistPlay.outline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val words: @Composable () -> Unit = {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(tr("Play all"), Sym.PlayArrow.fill, filled = true, enabled = enabled, onClick = play)
                PillButton(tr("Shuffle"), Sym.Shuffle.fill, enabled = enabled, onClick = shuffle)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { more() }
        }
        if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
            pic(Modifier.width(360.dp))
            Column(Modifier.weight(1f).padding(start = 24.dp)) { words() }
        } else Column {
            pic(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            words()
        }
    }
}

/**
 * The web's cardIn: a card rises 16dp into place, fades in and grows to its size, the first dozen one
 * after another (30 ms apart), each card once (seen holds the ones that have arrived). Not on a TV.
 */
@Composable
fun Modifier.arrive(key: Any, order: Int, seen: MutableSet<Any>): Modifier {
    if (LocalPlatform.current.tv) return this
    val fresh = remember(key) { seen.add(key) }
    if (!fresh) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (order in 1..11) delay(order * 30L)
        a.animateTo(1f, tween(500, easing = Motion.out))
    }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 16.dp.toPx()
        val s = 0.985f + 0.015f * a.value
        scaleX = s; scaleY = s
    }
}

@Composable
fun Narrow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) { content() }
    }
}
