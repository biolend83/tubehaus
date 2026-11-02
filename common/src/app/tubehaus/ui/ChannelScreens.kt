package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.data.meta
import app.tubehaus.engine.ChannelAbout
import app.tubehaus.engine.ChannelPage
import app.tubehaus.engine.Page
import app.tubehaus.engine.PlaylistPage
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChannelScreen(app: AppState, id: String, name: String) {
    var tab by remember { mutableStateOf(YouTube.Tab.VIDEOS) }
    var head by remember { mutableStateOf<ChannelPage?>(null) }
    var about by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val calm = app.store.settings.value.calm
    head?.let { h -> if (about) ChannelAboutSheet(app, h) { about = false } }
    val tabs = listOfNotNull(
        YouTube.Tab.VIDEOS to "Videos",
        if (app.noShorts) null else YouTube.Tab.SHORTS to "Shorts",
        YouTube.Tab.LIVE to "Live",
        YouTube.Tab.PLAYLISTS to "Playlists",
    )
    val pager = rememberPager(id to tab, {
        val c = YouTube.channel(id, tab)
        if (head == null || tab == YouTube.Tab.VIDEOS) head = c
        c.items
    }, { YouTube.browseMore(it) })
    when {
        pager.items.isEmpty() && pager.error != null && head == null -> Problem(pager.error!!) { scope.launch { pager.load() } }
        else -> ItemGrid(
            pager.items, app, loading = pager.loading, onEnd = { pager.loadMore() },
            rows = tab == YouTube.Tab.PLAYLISTS,
            header = {
                Column {
                    val h = head
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
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).tvClickable { about = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (first.isNotEmpty()) Text(first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text((if (first.isNotEmpty()) " " else "") + tr("...more"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.padding(horizontal = 16.dp)) { SubscribeButton(id, h?.name ?: name, h?.avatar ?: "", app) }
                    PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0), edgePadding = 8.dp) {
                        for ((t, label) in tabs) Tab(tab == t, onClick = { tab = t }, text = { Text(tr(label)) })
                    }
                    if (pager.items.isEmpty() && pager.started && !pager.loading) Empty(tr("Nothing here"))
                }
            },
        )
    }
}

/**
 * What "...more" on a channel opens, as on YouTube: the whole description, the channel's links
 * (each opens in the browser), then its address, country, joined date, views, subscribers and videos.
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
                        Text(desc, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp))
                    }
                }
                if (info == null && h.aboutToken != null && !failed) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                if (failed) Text(tr("Couldn't load the channel's details"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
            val a = info ?: return@LazyColumn
            if (a.links.isNotEmpty()) item { Heading(tr("Links")) }
            items(a.links) { l ->
                Row(Modifier.fillMaxWidth().tvClickable { app.platform.openExternal(l.url) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (l.icon.isNotEmpty()) Img(l.icon, Modifier.size(20.dp), crop = false) else Icon(Sym.Public.outline, null, Modifier.size(20.dp))
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(l.title, style = MaterialTheme.typography.bodyLarge)
                        Text(l.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Heading(tr("More info"))
                if (a.url.isNotEmpty()) InfoRow(Sym.Share.outline, a.url) { app.share(h.name, if (a.url.startsWith("http")) a.url else "https://${a.url}") }
                if (a.country.isNotEmpty()) InfoRow(Sym.Public.outline, a.country)
                if (a.joinedText.isNotEmpty()) InfoRow(Sym.History.outline, a.joinedText)
                if (a.viewsText.isNotEmpty() && !calm) InfoRow(Sym.PlayArrow.outline, a.viewsText)
                if (a.subsText.isNotEmpty() && !calm) InfoRow(Sym.Subscriptions.outline, a.subsText)
                if (a.videoCountText.isNotEmpty()) InfoRow(Sym.VideoLibrary.outline, a.videoCountText)
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
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { videos.firstOrNull()?.let { app.watch(it, videos.drop(1)) } }, enabled = videos.isNotEmpty()) { Text(tr("Play all")) }
                    OutlinedButton(onClick = {
                        // keep a copy on this device (the playlist on YouTube may change or vanish)
                        val pid = app.store.newPlaylist(head?.title ?: title, null)
                        app.store.lib.update { l ->
                            val pl = l.playlists[pid] ?: return@update l
                            l.copy(playlists = l.playlists + (pid to pl.copy(items = videos.map { it.meta() })))
                        }
                        app.toast(tr("Saved to your playlists"))
                    }, enabled = videos.isNotEmpty()) { Text(tr("Save to library")) }
                }
            }
        },
    )
}
