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
import app.tubehaus.data.SubGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import app.tubehaus.engine.Video
import app.tubehaus.engine.Sys
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
    // groups: one tab each; "All" first unless it's switched off
    val set by app.store.settings.state.collectAsState()
    val hideAll = set.subsHideAll && lib.groups.isNotEmpty()
    val group = lib.groups.firstOrNull { it.name == set.subsGroup } ?: if (hideAll) lib.groups.first() else null
    val shown: Set<String> = group?.channels?.filter { it in lib.subs }?.toSet() ?: lib.subs.keys
    val tabs: @Composable () -> Unit = { GroupTabs(app, lib.groups, group?.name, hideAll) }
    // typed words narrow the feed as you type (title or channel)
    var query by remember { mutableStateOf("") }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
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
            if (lib.groups.isNotEmpty()) item { tabs() }
            items(lib.subs.entries.filter { it.key in shown }.sortedBy { it.value.name.lowercase() }.toList(), key = { it.key }) { (cid, s) ->
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
    val base = items.filter { (!it.short || !app.noShorts) && it.channelId in shown }
    // a group: its own short feed (unwatched, the last 7 days, every channel's newest first, at most 20)
    val feed = if (group != null) groupFeed(base, lib.hist.mapTo(HashSet()) { it.id }) else base
    ItemGrid(
        feed.filter { v -> words.all { w -> w in v.title.lowercase() || w in v.author.lowercase() } }, app, loading = loading,
        header = {
            Column {
                if (lib.groups.isNotEmpty()) tabs()
                if (group != null) Text(
                    tr("Unwatched, from the last 7 days: each channel's newest first, up to {n}").replace("{n}", "$GROUP_FEED_MAX"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    placeholder = { Text(tr("Search this feed")) },
                    leadingIcon = { Icon(Sym.Search.outline, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Sym.Close.outline, tr("Clear")) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.subs.entries.filter { it.key in shown }.sortedByDescending { it.value.at }.toList(), key = { it.key }) { (cid, s) ->
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

private const val GROUP_FEED_MAX = 20

/**
 * A group's feed (asked for by a PocketTube user): only what you haven't watched from the last
 * 7 days, the newest video of every channel first, then each one's second newest and so on, so one
 * busy channel can't fill it; at most [GROUP_FEED_MAX], so it ends instead of scrolling on.
 */
private fun groupFeed(items: List<Video>, watched: Set<String>): List<Video> {
    val since = Sys.nowMs() / 1000 - 7 * 86400
    val byChannel = items.filter { it.id !in watched && (it.publishedAt == 0L || it.publishedAt >= since) }
        .distinctBy { it.id }.groupBy { it.channelId }.values.map { l -> l.sortedByDescending { it.publishedAt } }
    val out = ArrayList<Video>()
    var round = 0
    while (out.size < GROUP_FEED_MAX) {
        val layer = byChannel.mapNotNull { it.getOrNull(round) }.sortedByDescending { it.publishedAt }
        if (layer.isEmpty()) break
        out += layer.take(GROUP_FEED_MAX - out.size)
        round++
    }
    return out
}

/** The groups as tabs above the subscriptions: All (unless switched off), then each group. */
@Composable
private fun GroupTabs(app: AppState, groups: List<SubGroup>, selected: String?, hideAll: Boolean) {
    fun pick(name: String) = app.store.settings.update { it.copy(subsGroup = name) }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hideAll) item { FilterChip(selected == null, onClick = { pick("") }, label = { Text(tr("All")) }) }
        items(groups, key = { "g" + it.name }) { g ->
            FilterChip(selected == g.name, onClick = { pick(g.name) }, label = { Text(g.name) })
        }
    }
}

/** Every subscribed channel, to remove one or all of them, and the groups they are sorted into. */
@Composable
fun ManageSubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.fillSubInfo() }
    val subs = lib.subs.entries.sortedBy { it.value.name.lowercase() }.toList()
    var newGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var sorting by remember { mutableStateOf<String?>(null) }    // the channel whose groups are being picked
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Groups"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newGroup = true }) { Text(tr("New group")) }
            }
            if (lib.groups.isEmpty()) Text(
                tr("Sort channels into groups and Subscriptions gets a tab for each. A PocketTube backup brings its groups along (Library → Import)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(lib.groups, key = { "g" + it.name }) { g ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(tr("{n} channels").replace("{n}", "${g.channels.count { it in lib.subs }}"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { renaming = g.name }) { Text(tr("Rename")) }
                IconButton(onClick = { deleting = g.name }) { Icon(Sym.Close.outline, tr("Delete")) }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline) }
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
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(s.name.ifEmpty { cid }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val inGroups = lib.groups.filter { cid in it.channels }.joinToString(", ") { it.name }
                    if (inGroups.isNotEmpty()) Text(inGroups, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { sorting = cid }) { Icon(Sym.LibraryAdd.outline, tr("Groups")) }
                IconButton(onClick = { app.store.subscribe(cid, s.name, s.thumb, false) }) {
                    Icon(Sym.Close.outline, tr("Unsubscribe"))
                }
            }
        }
    }
    if (newGroup) NameDialog(tr("New group"), "", { newGroup = false }) { app.store.newGroup(it); newGroup = false }
    renaming?.let { old -> NameDialog(tr("Rename"), old, { renaming = null }) { app.store.renameGroup(old, it); renaming = null } }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete the group \"{g}\"?").replace("{g}", name)) },
            text = { Text(tr("The channels stay subscribed.")) },
            confirmButton = { TextButton(onClick = { app.store.deleteGroup(name); deleting = null }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
    sorting?.let { cid -> GroupPicker(app, cid) { sorting = null } }
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

/** Ticks the groups a channel is in; a new group can be made right here. */
@Composable
private fun GroupPicker(app: AppState, cid: String, dismiss: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(lib.subs[cid]?.name?.ifEmpty { null } ?: tr("Groups")) },
        text = {
            Column {
                for (g in lib.groups) Row(
                    Modifier.fillMaxWidth().clickable { app.store.setInGroup(g.name, cid, cid !in g.channels) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(cid in g.channels, onCheckedChange = { on -> app.store.setInGroup(g.name, cid, on) })
                    Text(g.name)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(tr("New group")) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val n = name.trim()
                        if (n.isNotEmpty()) { app.store.newGroup(n); app.store.setInGroup(n, cid, true); name = "" }
                    }) { Text(tr("Add")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Done")) } },
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
