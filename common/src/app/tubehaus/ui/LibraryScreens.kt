package app.tubehaus.ui

import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.data.Transfer
import app.tubehaus.engine.Video
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- subscriptions
@Composable
fun SubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(app.feed.cached().filter { it.channelId in lib.subs }) }
    var loading by remember { mutableStateOf(false) }
    suspend fun load(force: Boolean) {
        loading = true
        try { items = app.feed.all(force) } finally { loading = false }
    }
    LaunchedEffect(lib.subs.size) { if (!app.store.settings.value.subsFeedOff) load(false) }
    if (lib.subs.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Channels you subscribe to show up here. Subscriptions are kept on this device - no account needed."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { importFile(app) }, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Import subscriptions")) }
            Text(tr("From YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or any OPML file"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    // feed switched off: just the channels, each opens its own page
    if (app.store.settings.value.subsFeedOff) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(tr("The subscription feed is off. Open a channel to see its videos."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            item {
                TextButton(onClick = { app.go(Screen.ManageSubs) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("Manage subscriptions")) }
            }
            items(lib.subs.entries.sortedBy { it.value.name.lowercase() }.toList(), key = { it.key }) { (cid, s) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.go(Screen.Channel(cid, s.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(s.thumb, 40)
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
        return
    }
    ItemGrid(
        items.filter { !it.short || !app.noShorts }, app, loading = loading,
        header = {
            Column {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.subs.entries.sortedByDescending { it.value.at }.toList(), key = { it.key }) { (cid, s) ->
                        Column(Modifier.width(64.dp).tvClickable { app.go(Screen.Channel(cid, s.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(s.thumb, 56)
                            Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { app.go(Screen.ManageSubs) }) { Text(tr("Manage")) }
                    TextButton(onClick = { scope.launch { load(true) } }) { Text(tr("Refresh")) }
                }
            }
        },
    )
}

/** Every subscribed channel, to remove one or all of them. */
@Composable
fun ManageSubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.fillSubInfo() }
    val subs = lib.subs.entries.sortedBy { it.value.name.lowercase() }.toList()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("{n} channels").replace("{n}", "${subs.size}"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (subs.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text(tr("Unsubscribe from all"), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (subs.isEmpty()) item {
            Text(tr("No subscriptions"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        items(subs, key = { it.key }) { (cid, s) ->
            Row(
                Modifier.fillMaxWidth().clickable { app.go(Screen.Channel(cid, s.name)) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(s.thumb, 40)
                Text(s.name.ifEmpty { cid }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp).weight(1f))
                IconButton(onClick = { app.store.subscribe(cid, s.name, s.thumb, false) }) {
                    Icon(Sym.Close.outline, tr("Unsubscribe"))
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Unsubscribe from all {n} channels?").replace("{n}", "${subs.size}")) },
        text = { Text(tr("Your history and playlists stay. You can import subscriptions again at any time.")) },
        confirmButton = {
            TextButton(onClick = { app.store.unsubscribeAll(); confirm = false }) { Text(tr("Unsubscribe from all"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

// ---------------------------------------------------------------- library
@Composable
fun LibraryScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var newPl by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionTitle(tr("History"), Modifier.clickable { app.go(Screen.History) })
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(lib.hist.take(20), key = { it.id }) { m ->
                    val v = m.video()
                    Column(Modifier.width(160.dp).tvClickable { app.watch(v) }) {
                        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = 8)
                        Text(v.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (lib.hist.isEmpty()) Text(tr("Videos you watch show up here"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item { LibRow(Sym.History.outline, tr("History"), "${lib.hist.size}") { app.go(Screen.History) } }
        item { LibRow(Sym.WatchLater.outline, tr("Watch later"), "${lib.wl.size}") { app.go(Screen.Local("wl")) } }
        item { LibRow(Sym.ThumbUp.outline, tr("Liked videos"), "${lib.likes.size}") { app.go(Screen.Local("likes")) } }
        item {
            val saved by app.offline.saved.collectAsState()
            val busy = app.offline.progress.size
            if (app.platform.canDownload) LibRow(Sym.DownloadForOffline.outline, tr("Downloads"), "${saved.items.size}" + if (busy > 0) " (+$busy)" else "") { app.go(Screen.Downloads) }
        }
        item { SectionTitle(tr("Playlists")) }
        items(lib.playlists.entries.sortedByDescending { it.value.updated }.toList(), key = { it.key }) { (id, p) ->
            LibRow(Sym.PlaylistPlay.outline, p.title, "${p.items.size}") { app.go(Screen.Local(id)) }
        }
        item { LibRow(Sym.Add.outline, tr("New playlist"), "") { newPl = true } }
        item { SectionTitle(tr("Your data")) }
        item { LibRow(Sym.Psychology.outline, tr("What TubeHaus learned about you"), "") { app.go(Screen.Learned) } }
        item { LibRow(Sym.SwapVert.outline, tr("Import and export"), "") { app.go(Screen.Settings) } }
    }
    if (newPl) NameDialog(tr("New playlist"), "", { newPl = false }) { app.store.newPlaylist(it, null) }
}

@Composable
private fun LibRow(icon: ImageVector, label: String, count: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Text(label, Modifier.weight(1f).padding(start = 20.dp), style = MaterialTheme.typography.titleSmall)
        Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HistoryScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val list = lib.hist.filter { q.isBlank() || (it.title + " " + it.author).contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text(tr("Search your history")) }, singleLine = true)
            TextButton(onClick = { confirm = true }) { Text(tr("Clear all")) }
        }
        if (list.isEmpty()) Empty(tr("Nothing here")) else LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { m ->
                VideoRow(m.video(), app, extra = listOf(tr("Remove from history") to { app.store.removeFromHistory(m.id) }))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Clear all watch history?")) },
        text = { Text(tr("Resume points are cleared too. This can't be undone.")) },
        confirmButton = { TextButton(onClick = { app.store.clearHistory(); confirm = false }) { Text(tr("Clear")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

/** Watch later, liked videos or one of your playlists. */
@Composable
fun LocalListScreen(app: AppState, key: String) {
    val lib by app.store.lib.state.collectAsState()
    val pl = lib.playlists[key]
    val (title, items) = when (key) {
        "wl" -> tr("Watch later") to lib.wl
        "likes" -> tr("Liked videos") to lib.likes
        else -> (pl?.title ?: "") to (pl?.items ?: emptyList())
    }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val videos = items.map { it.video() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(tr("{n} videos").replace("{n}", "${items.size}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { videos.firstOrNull()?.let { app.watch(it, videos.drop(1)) } }, enabled = videos.isNotEmpty()) { Text(tr("Play all")) }
                    OutlinedButton(onClick = { videos.shuffled().let { s -> s.firstOrNull()?.let { app.watch(it, s.drop(1)) } } }, enabled = videos.isNotEmpty()) { Text(tr("Shuffle")) }
                    if (pl != null) {
                        TextButton(onClick = { rename = true }) { Text(tr("Rename")) }
                        TextButton(onClick = { delete = true }) { Text(tr("Delete")) }
                    }
                }
            }
        }
        if (items.isEmpty()) item { Empty(tr("Nothing here")) }
        items(videos.withIndex().toList(), key = { it.value.id }) { (i, v) ->
            val extra = buildList {
                add(tr("Remove from this list") to { app.store.setInList(key, v, false) })
                if (pl != null && i > 0) add(tr("Move up") to { app.store.movePlaylistItem(key, i, i - 1) })
                if (pl != null && i < videos.size - 1) add(tr("Move down") to { app.store.movePlaylistItem(key, i, i + 1) })
            }
            VideoRow(v, app, extra = extra) { app.watch(v, videos.drop(i + 1)) }
        }
    }
    if (rename && pl != null) NameDialog(tr("Rename playlist"), pl.title, { rename = false }) { app.store.renamePlaylist(key, it) }
    if (delete && pl != null) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text(tr("Delete this playlist?")) },
        confirmButton = { TextButton(onClick = { delete = false; app.back(); app.store.deletePlaylist(key) }) { Text(tr("Delete")) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(150) }, singleLine = true, placeholder = { Text(tr("Name")) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { ok(name.trim()); dismiss() } }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancel")) } },
    )
}

/** Save to playlist: tick the playlists the video should be in, or make a new one. */
@Composable
fun PlaylistPicker(v: Video, app: AppState, dismiss: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    var making by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Save to playlist")) },
        text = {
            Column {
                for ((id, p) in lib.playlists.entries.sortedByDescending { it.value.updated }) {
                    val on = p.items.any { it.id == v.id }
                    Row(Modifier.fillMaxWidth().clickable { app.store.setInList(id, v, !on) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { app.store.setInList(id, v, !on) })
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { making = true }) {
                    Icon(Sym.Add.outline, null, Modifier.size(18.dp)); Text(tr("New playlist"), Modifier.padding(start = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Done")) } },
    )
    if (making) NameDialog(tr("New playlist"), "", { making = false }) { app.store.newPlaylist(it, v) }
}

/** Asks for a file and merges whatever is in it into the library. */
fun importFile(app: AppState, done: (Boolean) -> Unit = {}) {
    app.platform.openTextFile { text ->
        if (text == null) return@openTextFile done(false)
        val p = Transfer.parse(text)
        if (p.format == null || p.empty) {
            app.toast(tr("Couldn't find anything to import in this file"))
            return@openTextFile done(false)
        }
        val r = Transfer.merge(app.store, p)
        app.fillSubInfo()
        app.sync.importedLocally()
        app.toast(
            tr("Imported from {f}: {s} subscriptions, {h} history, {p} playlists")
                .replace("{f}", r.format).replace("{s}", "${r.subs}").replace("{h}", "${r.hist}").replace("{p}", "${r.playlists}"),
        )
        done(true)
    }
}
