package app.tubehaus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.SearchOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(app: AppState, initial: String) {
    var text by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf(SearchOptions()) }
    var filters by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // read as state, so switching list/grid or moving the box redraws at once
    val prefs by app.store.settings.state.collectAsState()

    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        suggestions = emptyList()
        // a pasted link opens what it points to
        if (t.startsWith("http") || t.startsWith("@") || t.contains("youtu")) {
            scope.launch {
                val r = runCatching { YouTube.resolve(t) }.getOrNull()
                when (r?.first) {
                    "video" -> app.watch(Video(r.second, ""))
                    "playlist" -> app.go(Screen.Playlist(r.second))
                    "channel" -> app.go(Screen.Channel(r.second))
                    else -> { query = t }
                }
            }
            return
        }
        query = t
        app.stack[app.stack.size - 1] = Screen.Search(t)
        if (!app.store.settings.value.historyOff) app.store.settings.update { s ->
            s.copy(recentSearches = (listOf(t) + s.recentSearches.filter { !it.equals(t, true) }).take(30))
        }
    }

    fun forget(q: String) = app.store.settings.update { s -> s.copy(recentSearches = s.recentSearches - q) }

    LaunchedEffect(text) {
        if (text == query || text.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200)
        suggestions = runCatching { YouTube.suggestions(text) }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { if (initial.isEmpty()) runCatching { focus.requestFocus() } }

    @Composable
    fun SearchBox(modifier: Modifier = Modifier) {
        Row(modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.back() }) { Icon(Sym.ArrowBack.fill, tr("Back")) }
            OutlinedTextField(
                text, { text = it },
                Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(tr("Search YouTube")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; runCatching { focus.requestFocus() } }) { Icon(Sym.Close.outline, tr("Clear")) }
                },
            )
            IconButton(onClick = { submit(text) }) { Icon(Sym.Search.outline, tr("Search")) }
        }
    }

    val bottom = prefs.searchAtBottom && app.platform.touch
    Column(Modifier.fillMaxSize()) {
        if (!bottom) SearchBox()
        // the suggestions or the results; with the box at the bottom it sits under them
        Column(Modifier.weight(1f).fillMaxWidth()) {
        // recent searches: all of them in an empty box, the ones that match above the suggestions while typing
        val recent = prefs.recentSearches.filter { text.isBlank() || (it.startsWith(text.trim(), true) && !it.equals(text.trim(), true)) }
            .take(if (text.isBlank()) 30 else 3)
        val typing = text.isNotBlank() && text != query
        if (suggestions.isNotEmpty() || (recent.isNotEmpty() && (text.isBlank() || typing))) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (text.isBlank()) item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Recent searches"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { app.store.settings.update { it.copy(recentSearches = emptyList()) } }) { Text(tr("Clear all")) }
                    }
                }
                items(recent, key = { "r$it" }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { text = s; submit(s) }.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.History.outline, null)
                        Text(s, Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { forget(s) }) { Icon(Sym.Close.outline, tr("Remove")) }
                    }
                }
                items(suggestions.filter { s -> recent.none { it.equals(s, true) } }, key = { "s$it" }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { text = s; submit(s) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.Search.outline, null)
                        Text(s, Modifier.padding(start = 16.dp))
                    }
                }
            }
            return@Column
        }
        if (query.isEmpty()) return@Column
        val compact = LocalCompact.current
        val grid = prefs.searchGrid
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // the kind of result, then the rest of YouTube's filters in a panel
                item {
                    FilterChip(filter.count > 0, onClick = { filters = true },
                        label = { Text(tr("Filters") + if (filter.count > 0) " (${filter.count})" else "") })
                }
                items(listOf(0 to "All", 1 to "Videos", 2 to "Channels", 3 to "Playlists", 4 to "Movies")) { (t, label) ->
                    FilterChip(filter.type == t, onClick = { filter = filter.copy(type = t) }, label = { Text(tr(label)) })
                }
            }
            // phones always show one card per row; wider screens can pick a list or a grid
            if (!compact) IconButton(onClick = { app.store.settings.update { it.copy(searchGrid = !grid) } }) {
                if (grid) Icon(Sym.ViewList.outline, tr("Show as a list"))
                else Icon(Sym.GridView.outline, tr("Show as a grid"))
            }
        }
        if (filters) SearchFilters(filter, { filter = it }) { filters = false }
        val pager = rememberPager(query to filter, { YouTube.search(query, filter) }, { YouTube.searchMore(it) })
        when {
            pager.items.isEmpty() && pager.loading -> Busy()
            pager.items.isEmpty() && pager.error != null -> Problem(pager.error!!) { scope.launch { pager.load() } }
            pager.items.isEmpty() && pager.started -> Empty(tr("No results"))
            else -> ItemGrid(
                pager.items.filter { !(app.noShorts && it is Video && it.short) }, app,
                loading = pager.loading, onEnd = { pager.loadMore() }, rows = !compact && !grid,
            )
        }
        }
        if (bottom) SearchBox(Modifier.imePadding())
    }
}

/** YouTube's Filters panel: upload date, length, features and the order of the results. */
@Composable
private fun SearchFilters(o: SearchOptions, set: (SearchOptions) -> Unit, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            @Composable
            fun group(title: String, options: List<Pair<Int, String>>, now: Int, pick: (Int) -> Unit) {
                Text(tr(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((v, label) in options) FilterChip(now == v, onClick = { pick(v) }, label = { Text(tr(label)) })
                }
            }
            group("Upload date", listOf(0 to "Any time", 1 to "Last hour", 2 to "Today", 3 to "This week", 4 to "This month", 5 to "This year"), o.date) { set(o.copy(date = it)) }
            group("Duration", listOf(0 to "Any length", 1 to "Under 4 minutes", 3 to "4 - 20 minutes", 2 to "Over 20 minutes"), o.duration) { set(o.copy(duration = it)) }
            group("Features", listOf(0 to "Any", 1 to "Live"), if (o.live) 1 else 0) { set(o.copy(live = it == 1)) }
            group("Sort by", listOf(0 to "Relevance", 2 to "Upload date", 3 to "View count", 1 to "Rating"), o.sort) { set(o.copy(sort = it)) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { set(SearchOptions(type = o.type)) }) { Text(tr("Reset")) }
                TextButton(onClick = close) { Text(tr("Done")) }
            }
        }
    }
}
