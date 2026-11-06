package app.tubehaus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Item
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.launch

/**
 * YouTube's own topic pages, the ones its app opens from the tabs at the top (Music, Live) and from
 * "More": each is a channel of YouTube's whose first tab is one flat list of videos and playlists.
 * What they hold depends on the region (News is empty in some), so an empty page says so.
 */
val TOPICS = linkedMapOf(
    "Music" to "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
    "Live" to "UC4R8DWoMoI7CAwX8_LjQHig",
    "News" to "UCYfdidRxbB8Qhf0Nx7ioOYw",
    "Sports" to "UCEgdi0XIXXZ-qJOFPf4JSKw",
    "Learning" to "UCtFRv9O2AHqOZjjynzrv-xg",
    "Gaming" to "UCOpNcN46UbXVtpKMrmU4Abg",
    "Fashion & beauty" to "UCrpQ4p1Ql_hG8rKXIKM1MOQ",
)

/** the topics that are tabs at the top on a phone; the rest are under "More" */
val TOP_TOPICS = listOf("Music", "Live")

/** the screens the phone's top tabs switch between, all under Home in the bottom bar */
fun Screen.underHome(): Boolean = this == Screen.Home || this == Screen.Subs || (this is Screen.Topic && key in TOP_TOPICS)

@Composable
fun TopicScreen(app: AppState, key: String) {
    val id = TOPICS[key] ?: return
    val scope = rememberCoroutineScope()
    val pager = rememberPager("topic:$key", { YouTube.channel(id, null as String?).items }, { YouTube.browseMore(it) })
    val noShorts = app.noShorts
    val items: List<Item> = pager.items.filter { !(noShorts && it is Video && it.short) && !(it is Video && it.members) }
    // a topic opened from "More" has a title above it; the top tabs already say which one is open
    val titled = key !in TOP_TOPICS || app.platform.tv
    when {
        items.isEmpty() && pager.error != null -> Problem(pager.error!!) { scope.launch { pager.load() } }
        // grey shapes while the first page comes, as on YouTube
        items.isEmpty() && (pager.loading || !pager.started) -> Skeleton()
        items.isEmpty() && pager.started && !pager.loading -> Column {
            if (titled) SectionTitle(tr(key))
            Empty(tr("YouTube shows nothing here for your region"))
        }
        else -> ItemGrid(
            items, app, loading = pager.loading, onEnd = { pager.loadMore() },
            header = { if (titled) SectionTitle(tr(key)) },
        )
    }
}

/**
 * The phone's tabs under the top bar, as in YouTube's app: Home, Subscriptions, Music, Live and
 * More (the other topics). With the home feed turned off (focus) only Home and Subscriptions stay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopTabs(app: AppState) {
    val root = app.stack.firstOrNull() ?: Screen.Home
    val discover = !app.store.settings.value.homeOff
    val screens = buildList<Pair<Screen, String>> {
        add(Screen.Home to tr("Home"))
        add(Screen.Subs to tr("Subscriptions"))
        if (discover) for (k in TOP_TOPICS) add(Screen.Topic(k) to tr(k))
    }
    var more by remember { mutableStateOf(false) }
    val open = screens.indexOfFirst { it.first == root }.coerceAtLeast(0)
    val c = MaterialTheme.colorScheme
    PrimaryScrollableTabRow(
        selectedTabIndex = open, edgePadding = if (LocalCompact.current) 4.dp else 12.dp, modifier = Modifier.fillMaxWidth(),
        containerColor = c.background,
        divider = {},
        indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(open, matchContentSize = true), width = androidx.compose.ui.unit.Dp.Unspecified, height = 2.5.dp, color = Red) },
    ) {
        screens.forEachIndexed { i, (s, label) -> TopTab(i == open, label) { app.go(s) } }
        if (discover) Box {
            TopTab(false, tr("More")) { more = true }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                for (k in TOPICS.keys) if (k !in TOP_TOPICS) DropdownMenuItem(
                    text = { Text(tr(k)) },
                    onClick = { more = false; app.go(Screen.Topic(k)) },
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        }
    }
}

/** one of the top tabs, as in YouTube's app: a plain word, the open one bold (with a short red line under it, TopTabs) */
@Composable
private fun TopTab(selected: Boolean, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Tab(
        selected, onClick = onClick, modifier = Modifier.height(42.dp),
        selectedContentColor = c.onSurface, unselectedContentColor = c.onSurfaceVariant,
        text = { Text(label, fontSize = 15.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
    )
}
