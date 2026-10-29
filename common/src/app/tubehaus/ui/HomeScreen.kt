package app.tubehaus.ui

import app.tubehaus.engine.Sys
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import app.tubehaus.engine.InnerTube
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Item
import app.tubehaus.rec.Learn
import kotlinx.coroutines.launch

/** Remembered across visits to the tab so going back to Home doesn't reshuffle it. */
private object HomeCache {
    val items = mutableStateListOf<Item>()
    var at = 0L
}

@Composable
fun HomeScreen(app: AppState) {
    if (app.store.settings.value.homeOff) {
        FeedOff(tr("The home feed is off"), tr("Search for what you came for, or open your subscriptions.")) {
            Button(onClick = { app.go(Screen.Search()) }) { Text(tr("Search")) }
            TextButton(onClick = { app.go(Screen.Subs) }) { Text(tr("Subscriptions")) }
        }
        return
    }
    var askTopics by remember { mutableStateOf(app.rec.needsTopics()) }
    if (askTopics) {
        TopicPicker(app) { askTopics = false }
        return
    }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true; error = null
        try {
            val skip = if (more) HomeCache.items.map { it.id }.toSet() else emptySet()
            val got = if (app.discover) app.rec.discover(40, skip) else app.rec.home(40, skip)
            if (!more) HomeCache.items.clear()
            HomeCache.items.addAll(got.filter { g -> HomeCache.items.none { it.id == g.id } })
            HomeCache.at = Sys.nowMs()
        } catch (e: Exception) {
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        if (HomeCache.items.isEmpty() || Sys.nowMs() - HomeCache.at > 30 * 60_000) load(false)
    }
    when {
        HomeCache.items.isEmpty() && loading -> Busy()
        HomeCache.items.isEmpty() && error != null -> Problem(error!!) { scope.launch { load(false) } }
        HomeCache.items.isEmpty() -> Empty(tr("Nothing to show yet. Search for something you like"))
        else -> ItemGrid(
            HomeCache.items, app, loading = loading,
            onEnd = { load(true) },
            header = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RegionPicker(app) { HomeCache.items.clear(); app.rec.clearCache(); scope.launch { load(false) } }
                    FilterChip(app.discover, onClick = {
                        app.discover = !app.discover; HomeCache.items.clear(); scope.launch { load(false) }
                    }, label = { Text(tr("Discover")) }, leadingIcon = { Icon(Sym.Explore.outline, null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { app.rec.clearCache(); scope.launch { load(false) } }) { Text(tr("Refresh")) }
                }
                if (app.discover) Text(tr("Videos from topics you don't usually watch. Your likes aren't used here."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            },
        )
    }
}

/** The region the feeds come from, switched from Home: a few common ones, or any two letters in Settings. */
@Composable
private fun RegionPicker(app: AppState, changed: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val now = s.region.ifEmpty { app.platform.deviceRegion() }.uppercase()
    Box {
        val name = Sys.countryName(now, InnerTube.hl)
        AssistChip(onClick = { open = true }, label = { Text(name) }, leadingIcon = { Icon(Sym.Public.outline, null, Modifier.size(18.dp)) })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val common = listOf("US", "GB", "CA", "AU", "IN", "DE", "FR", "ES", "IT", "NL", "TR", "BR", "MX", "JP", "KR", "ID")
            DropdownMenuItem(text = { Text(tr("This device") + " (" + app.platform.deviceRegion().uppercase() + ")") }, onClick = {
                open = false; app.store.settings.update { it.copy(region = "") }; changed()
            })
            for (c in common) DropdownMenuItem(
                text = { Text(Sys.countryName(c, InnerTube.hl) + "  ·  " + c) },
                onClick = { open = false; app.store.settings.update { it.copy(region = c) }; changed() },
            )
            DropdownMenuItem(text = { Text(tr("Other...")) }, onClick = { open = false; app.go(Screen.Settings) })
        }
    }
}

/** What a page shows when its feed is switched off in Settings → Focus. */
@Composable
fun FeedOff(title: String, sub: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            actions()
        }
    }
}

/** First start: a few topics give the recommender something to begin with. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicPicker(app: AppState, done: () -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tr("What do you like to watch?"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                tr("Pick a few topics to start. Your recommendations are worked out on this device, from what you watch - nothing leaves it."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in app.rec.topics) {
                    val on = t in picked
                    FilterChip(on, onClick = { if (on) picked.remove(t) else picked.add(t) }, label = { Text(tr(t)) })
                }
            }
            Button(
                onClick = { Learn.pickTopics(app.store, picked.toList()); done() },
                enabled = picked.isNotEmpty(), modifier = Modifier.padding(top = 24.dp),
            ) { Text(tr("Continue")) }
            OutlinedButton(onClick = { app.go(Screen.Settings) }, modifier = Modifier.padding(top = 8.dp), border = BorderStroke(0.dp, MaterialTheme.colorScheme.background)) {
                Text(tr("Import subscriptions from another app"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker(app: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(Modifier.padding(bottom = 24.dp))
            Text("Language", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((code, name) in LANGS) {
                    OutlinedButton(onClick = { app.store.settings.update { it.copy(lang = code) } }) { Text(name) }
                }
            }
        }
    }
}

/**
 * First start, right after the language: bring the subscriptions over from YouTube or another app.
 * Each source says in one line where its export is; one button opens the file, whatever it came from.
 */
@Composable
fun Welcome(app: AppState) {
    fun finish() = app.store.settings.update { it.copy(welcomed = true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(Modifier.padding(top = 16.dp, bottom = 24.dp))
            Text(tr("Bring your subscriptions"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                tr("Your channels are kept on this device, no account needed. Pick the file you exported from another app:"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            for ((name, how) in listOf(
                "YouTube" to "takeout.google.com → YouTube → subscriptions.csv",
                "NewPipe" to tr("Subscriptions → ⋮ → Export to → File"),
                "FreeTube" to tr("Settings → Data settings → Export subscriptions"),
                "Invidious" to tr("Settings → Import/Export → Export subscriptions"),
                "TubeHaus · OPML" to tr("a TubeHaus backup, or any feed reader's OPML"),
            )) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { importFile(app) { ok -> if (ok) finish() } }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                Text(tr("Choose a file"))
            }
            TextButton(onClick = { finish() }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Skip for now")) }
        }
    }
}
