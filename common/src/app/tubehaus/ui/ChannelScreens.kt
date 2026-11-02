package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.data.meta
import app.tubehaus.engine.Channel
import app.tubehaus.engine.ChannelAbout
import app.tubehaus.engine.ChannelPage
import app.tubehaus.engine.ChannelTab
import app.tubehaus.engine.Item
import app.tubehaus.engine.Page
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.PlaylistPage
import app.tubehaus.engine.Post
import app.tubehaus.engine.Shelf
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** tabs whose items are listed one per row (playlists and the like) rather than as video cards */
private val ROW_TABS = setOf("playlists", "courses", "releases", "podcasts", "search")

/**
 * A channel as on YouTube: banner, name, the start of the description ("...more" opens the rest),
 * its links, then the tabs the channel has (Home, Videos, Shorts, Live, Courses, Playlists, Posts)
 * and a search inside the channel.
 */
@Composable
fun ChannelScreen(app: AppState, id: String, name: String) {
    var head by remember { mutableStateOf<ChannelPage?>(null) }
    // the open tab's params; null is the channel's first tab (Home)
    var tab by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    head?.let { h -> if (about) ChannelAboutSheet(app, h) { about = false } }
    val tabs = head?.tabs.orEmpty().filter { it.kind != "store" && !(app.noShorts && it.kind == "shorts") }
    val kind = when {
        query != null -> "search"
        tab == null -> head?.tabs?.firstOrNull()?.kind ?: "featured"
        else -> tabs.firstOrNull { it.params == tab }?.kind ?: "videos"
    }
    // the first tab is always asked for as null, so the page that fills the header is the Home one
    fun pick(t: ChannelTab) {
        tab = if (t == head?.tabs?.firstOrNull()) null else t.params
        query = null; searching = false
    }
    suspend fun load(): ChannelPage {
        val c = YouTube.channel(id, tab)
        if (head == null || tab == null) head = c
        return c
    }
    val header: @Composable () -> Unit = {
        ChannelHeader(app, id, name, head, onAbout = { about = true })
        if (searching) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searching = false; query = null; draft = "" }) { Icon(Sym.ArrowBack.outline, tr("Back")) }
            OutlinedTextField(
                draft, { draft = it }, singleLine = true,
                placeholder = { Text(tr("Search this channel")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (draft.isNotBlank()) query = draft.trim() }),
                trailingIcon = { IconButton(onClick = { if (draft.isNotBlank()) query = draft.trim() }) { Icon(Sym.Search.outline, tr("Search")) } },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        } else if (tabs.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            val open = if (tab == null) 0 else tabs.indexOfFirst { it.params == tab }.coerceAtLeast(0)
            PrimaryScrollableTabRow(selectedTabIndex = open, edgePadding = 8.dp, modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { i, t -> Tab(i == open, onClick = { pick(t) }, text = { Text(t.title) }) }
            }
            if (head?.searchable == true) IconButton(onClick = { searching = true }) { Icon(Sym.Search.outline, tr("Search this channel")) }
        }
    }
    when (kind) {
        "featured" -> ChannelHome(app, id, tab, ::load, header) { p -> tabs.firstOrNull { it.params == p }?.let(::pick) ?: run { tab = p } }
        "posts" -> ChannelPosts(app, id, tab, ::load, header)
        else -> {
            val scope = rememberCoroutineScope()
            val pager = rememberPager(Triple(id, tab, query), {
                val q = query
                if (q != null) YouTube.channelSearch(id, q) else load().items
            }, { YouTube.browseMore(it) })
            if (pager.items.isEmpty() && pager.error != null && head == null) Problem(pager.error!!) { scope.launch { pager.load() } }
            else ItemGrid(
                pager.items, app, loading = pager.loading, onEnd = { pager.loadMore() },
                rows = kind in ROW_TABS,
                header = {
                    Column {
                        header()
                        if (pager.items.isEmpty() && pager.started && !pager.loading) Empty(tr(if (query != null) "No results" else "Nothing here"))
                    }
                },
            )
        }
    }
}

private fun channelLink(id: String, handle: String) = "https://www.youtube.com/" + (handle.takeIf { it.startsWith("@") } ?: "channel/$id")

/** banner, avatar, name, numbers, "...more", the link line, Subscribe and Share */
@Composable
private fun ChannelHeader(app: AppState, id: String, name: String, h: ChannelPage?, onAbout: () -> Unit) {
    val calm = app.store.settings.value.calm
    Column {
        if (h != null && h.banner.isNotEmpty()) Img(h.banner, Modifier.fillMaxWidth().aspectRatio(6.2f))
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(h?.avatar ?: "", 72)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(h?.name ?: name, style = MaterialTheme.typography.titleLarge)
                val line = listOf(h?.handle ?: "", if (calm) "" else h?.subsText ?: "", h?.videoCountText ?: "").filter { it.isNotEmpty() }
                Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // the first line of the description and "...more", which opens everything about the channel
        val first = h?.description?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: ""
        if (h != null && (first.isNotEmpty() || h.aboutToken != null)) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).tvClickable { onAbout() }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (first.isNotEmpty()) Text(first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text((if (first.isNotEmpty()) " " else "") + tr("...more"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        // "jeongmilssaem.com and 4 more links", which opens the same panel
        if (h != null && h.linkText.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).tvClickable { onAbout() }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.Link.outline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(h.linkText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
            if (h.linkMore.isNotEmpty()) Text(" " + h.linkMore, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SubscribeButton(id, h?.name ?: name, h?.avatar ?: "", app)
            OutlinedButton(onClick = { app.share(h?.name ?: name, channelLink(id, h?.handle ?: "")) },
                contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) {
                Icon(Sym.Share.outline, null, Modifier.size(18.dp))
                Text(tr("Share"), fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** loads the next page when the end of [state]'s list comes into view */
@Composable
private fun <T> MoreAtEnd(state: LazyListState, pager: Pager<T>) {
    LaunchedEffect(state, pager.items.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 3) pager.loadMore()
        }
    }
}

/** The Home tab: the featured video, then the channel's rows ("Videos", "Popular", its playlists...). */
@Composable
private fun ChannelHome(app: AppState, id: String, tab: String?, load: suspend () -> ChannelPage, header: @Composable () -> Unit, openTab: (String) -> Unit) {
    var page by remember { mutableStateOf<ChannelPage?>(null) }
    val scope = rememberCoroutineScope()
    val pager = rememberPager(id to "home:$tab", { load().also { page = it }.shelves }, { YouTube.shelvesMore(it, id) })
    val state = rememberLazyListState()
    MoreAtEnd(state, pager)
    LazyColumn(Modifier.fillMaxSize(), state) {
        item { header() }
        page?.featured?.let { f -> item { FeaturedVideo(app, f, page?.featuredText ?: "") } }
        items(pager.items) { s -> ShelfRow(app, s, openTab) }
        if (pager.loading) item { Busy() }
        if (pager.items.isEmpty() && !pager.loading && pager.started) item {
            if (pager.error != null) Problem(pager.error!!) { scope.launch { pager.load() } }
            else if (page?.featured == null) Empty(tr("Nothing here"))
        }
    }
}

@Composable
private fun FeaturedVideo(app: AppState, v: Video, text: String) {
    val calm = app.store.settings.value.calm
    val thumbs = !app.store.settings.value.hideThumbs
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(v.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.subline(calm, withAuthor = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (LocalCompact.current) Column(Modifier.fillMaxWidth().tvClickable { app.watch(v) }.padding(bottom = 8.dp)) {
        if (thumbs) Thumb(v, app, Modifier.fillMaxWidth().padding(horizontal = 12.dp), rounded = 12)
        info(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).tvClickable { app.watch(v) }) {
        if (thumbs) Thumb(v, app, Modifier.width(420.dp), rounded = 12)
        info(Modifier.weight(1f).padding(horizontal = 16.dp))
    }
}

/** one row of the Home tab: its title ("Play all" for a playlist), then its items side by side */
@Composable
private fun ShelfRow(app: AppState, s: Shelf, openTab: (String) -> Unit) {
    val videos = s.items.filterIsInstance<Video>()
    val pid = s.playlistId
    val tp = s.tabParams
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(if (pid != null) Modifier.clickable { app.go(Screen.Playlist(pid, s.title)) } else Modifier))
            when {
                pid != null && videos.isNotEmpty() -> TextButton(onClick = { app.watch(videos[0], videos.drop(1)) }) {
                    Icon(Sym.PlayArrow.fill, null, Modifier.size(18.dp))
                    Text(tr("Play all"), modifier = Modifier.padding(start = 4.dp))
                }
                tp != null -> TextButton(onClick = { openTab(tp) }) { Text(tr("View all")) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.items, key = { it.id }) { Tile(app, it, videos) }
        }
    }
}

/** a small card in a row: a video, a short (tall), a playlist or a channel */
@Composable
private fun Tile(app: AppState, item: Item, queue: List<Video>) {
    val calm = app.store.settings.value.calm
    val thumbs = !app.store.settings.value.hideThumbs
    when (item) {
        is Video -> if (item.short) Column(Modifier.width(150.dp).tvClickable(RoundedCornerShape(8.dp)) { app.watch(item) }) {
            Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp))) {
                if (thumbs) Img(item.thumb, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (!calm && item.viewsText.isNotEmpty()) Text(item.viewsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(Modifier.width(220.dp).tvClickable(RoundedCornerShape(8.dp)) { app.watch(item, queue.dropWhile { it.id != item.id }.drop(1)) }) {
            if (thumbs) Thumb(item, app, Modifier.fillMaxWidth(), rounded = 8)
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(item.subline(calm, withAuthor = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        is Playlist -> Column(Modifier.width(220.dp).tvClickable(RoundedCornerShape(8.dp)) { app.go(Screen.Playlist(item.id, item.title)) }) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                if (thumbs) Img(item.thumb, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                if (item.countText.isNotEmpty()) Row(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.PlaylistPlay.outline, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(item.countText, color = Color.White, fontSize = 12.sp)
                }
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        is Channel -> Column(Modifier.width(110.dp).tvClickable(RoundedCornerShape(8.dp)) { app.go(Screen.Channel(item.id, item.name)) },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(item.avatar, 88)
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The Posts tab: what the channel wrote, with its pictures, polls and shared videos. */
@Composable
private fun ChannelPosts(app: AppState, id: String, tab: String?, load: suspend () -> ChannelPage, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val pager = rememberPager(id to "posts:$tab", { load().posts }, { YouTube.postsMore(it) })
    val state = rememberLazyListState()
    MoreAtEnd(state, pager)
    LazyColumn(Modifier.fillMaxSize(), state) {
        item { header() }
        items(pager.items, key = { it.id }) { p -> Narrow { PostCard(app, p) } }
        if (pager.loading) item { Busy() }
        if (pager.items.isEmpty() && !pager.loading && pager.started) item {
            if (pager.error != null) Problem(pager.error!!) { scope.launch { pager.load() } } else Empty(tr("Nothing here"))
        }
    }
}

@Composable
private fun PostCard(app: AppState, p: Post) {
    val calm = app.store.settings.value.calm
    var whole by remember { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.avatar, 32)
            Text(p.author, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
            Text("  " + p.published, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.text.isNotEmpty()) {
            LinkedText(p.text, app, Modifier.padding(horizontal = 14.dp).padding(top = 10.dp), links = p.links,
                maxLines = if (whole) Int.MAX_VALUE else 6, onTextLayout = { if (!whole) cut = it.hasVisualOverflow })
            if (cut && !whole) Text(tr("Read more"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).clickable { whole = true })
        }
        if (p.images.size == 1) Img(p.images[0], Modifier.padding(horizontal = 14.dp).padding(top = 10.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
        else if (p.images.size > 1) LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(p.images) { Img(it, Modifier.size(260.dp).clip(RoundedCornerShape(10.dp))) }
        }
        p.video?.let { Box(Modifier.padding(top = 8.dp)) { VideoRow(it, app) } }
        p.playlist?.let { Box(Modifier.padding(top = 8.dp)) { PlaylistRow(it, app) } }
        if (p.poll.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.pollVotes.isNotEmpty()) Text(p.pollVotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (c in p.poll) Text(c, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Row(Modifier.padding(horizontal = 6.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!calm && p.likes.isNotEmpty()) {
                Icon(Sym.ThumbUp.outline, null, Modifier.padding(start = 8.dp).size(18.dp))
                Text(p.likes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp, end = 12.dp))
            }
            if (!calm && p.replies.isNotEmpty()) {
                Icon(Sym.Comment.outline, null, Modifier.size(18.dp))
                Text(p.replies, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp, end = 12.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { app.share(p.author, "https://www.youtube.com/post/${p.id}") }) { Icon(Sym.Share.outline, tr("Share"), Modifier.size(20.dp)) }
        }
    }
}

/**
 * What "...more" on a channel opens, as on YouTube: the whole description (its links work), the
 * channel's links, then its address, country, joined date, views, subscribers and videos, and Share.
 */
@Composable
private fun ChannelAboutSheet(app: AppState, h: ChannelPage, onClose: () -> Unit) {
    var info by remember { mutableStateOf<ChannelAbout?>(null) }
    var failed by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    LaunchedEffect(h.id) {
        val t = h.aboutToken ?: return@LaunchedEffect
        try { info = YouTube.channelAbout(t) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
    }
    ModalBottomSheet(onDismissRequest = onClose, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Text(h.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                val desc = info?.description?.ifEmpty { null } ?: h.description
                if (desc.isNotEmpty()) {
                    Heading(tr("Description"))
                    SelectionContainer {
                        LinkedText(desc, app, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp))
                    }
                }
                if (info == null && h.aboutToken != null && !failed) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                if (failed) Text(tr("Couldn't load the channel's details"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
            val a = info
            if (a != null && a.links.isNotEmpty()) item { Heading(tr("Links")) }
            if (a != null) items(a.links) { l ->
                Row(Modifier.fillMaxWidth().tvClickable { app.openLink(l.url) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (l.icon.isNotEmpty()) Img(l.icon, Modifier.size(20.dp), crop = false) else Icon(Sym.Link.outline, null, Modifier.size(20.dp))
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(l.title, style = MaterialTheme.typography.bodyLarge)
                        Text(l.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Heading(tr("More info"))
                if (a != null) {
                    if (a.url.isNotEmpty()) InfoRow(Sym.Public.outline, a.url) { app.share(h.name, if (a.url.startsWith("http")) a.url else "https://${a.url}") }
                    if (a.country.isNotEmpty()) InfoRow(Sym.Explore.outline, a.country)
                    if (a.joinedText.isNotEmpty()) InfoRow(Sym.History.outline, a.joinedText)
                    if (a.viewsText.isNotEmpty() && !calm) InfoRow(Sym.PlayArrow.outline, a.viewsText)
                    if (a.subsText.isNotEmpty() && !calm) InfoRow(Sym.Subscriptions.outline, a.subsText)
                    if (a.videoCountText.isNotEmpty()) InfoRow(Sym.VideoLibrary.outline, a.videoCountText)
                }
                InfoRow(Sym.Share.outline, tr("Share channel")) { app.share(h.name, channelLink(h.id, h.handle)) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.tvClickable(onClick = onClick) else Modifier).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A playlist: Play all, Shuffle, keep a copy, share. */
@Composable
fun PlaylistScreen(app: AppState, id: String, title: String) {
    var head by remember { mutableStateOf<PlaylistPage?>(null) }
    val scope = rememberCoroutineScope()
    val pager = rememberPager(id, {
        val p = YouTube.playlist(id)
        head = p
        Page(p.items.items, p.items.next)
    }, { YouTube.browseMore(it) })
    val videos = pager.items.filterIsInstance<Video>()
    if (pager.items.isEmpty() && pager.error != null) {
        Problem(pager.error!!) { scope.launch { pager.load() } }
        return
    }
    ItemGrid(
        pager.items, app, loading = pager.loading, onEnd = { pager.loadMore() }, rows = true,
        header = {
            Column(Modifier.padding(16.dp)) {
                Text(head?.title ?: title, style = MaterialTheme.typography.titleLarge)
                Text(listOf(head?.author ?: "", head?.countText ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { videos.firstOrNull()?.let { app.watch(it, videos.drop(1)) } }, enabled = videos.isNotEmpty()) { Text(tr("Play all")) }
                    OutlinedButton(onClick = { val s = videos.shuffled(); app.watch(s[0], s.drop(1)) }, enabled = videos.isNotEmpty()) { Text(tr("Shuffle")) }
                    OutlinedButton(onClick = {
                        // keep a copy on this device (the playlist on YouTube may change or vanish)
                        val pid = app.store.newPlaylist(head?.title ?: title, null)
                        app.store.lib.update { l ->
                            val pl = l.playlists[pid] ?: return@update l
                            l.copy(playlists = l.playlists + (pid to pl.copy(items = videos.map { it.meta() })))
                        }
                        app.toast(tr("Saved to your playlists"))
                    }, enabled = videos.isNotEmpty()) { Text(tr("Save to library")) }
                    IconButton(onClick = { app.share(head?.title ?: title, "https://www.youtube.com/playlist?list=$id") }) { Icon(Sym.Share.outline, tr("Share")) }
                }
            }
        },
    )
}
