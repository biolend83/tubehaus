package app.tubehaus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.Sys
import kotlinx.coroutines.delay

/*
 * The TV: its own screens, made for a remote across the room rather than the tablet layout with a
 * focus frame added. A thin column of icons on the left (it opens with the names when the remote
 * moves onto it), large pictures in rows that slide so the chosen one stays in the same place, and
 * a player that fills the screen and answers the remote's keys the way a TV app does.
 */

/** where the remote's choice sits while a list slides under it: [lead] from the start, or kept between the edges */
@OptIn(ExperimentalFoundationApi::class)
internal class TvScroll(private val lead: Float, private val trail: Float, private val pin: Boolean) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        // rows: the chosen card always in the first place, the rest following it
        pin -> offset - lead
        offset < lead -> offset - lead
        offset + size > containerSize - trail -> minOf(offset + size - (containerSize - trail), offset - lead)
        else -> 0f
    }
}

private data class TvTab(val screen: Screen, val label: String, val icon: Symbol)

@Composable
private fun tvTabs(app: AppState): List<TvTab> {
    val calm = app.store.settings.value.homeOff
    return buildList {
        add(TvTab(Screen.Search(), tr("Search"), Sym.Search))
        add(TvTab(Screen.Home, tr("Home"), Sym.Home))
        if (!app.noShorts) add(TvTab(Screen.Shorts, tr("Shorts"), Sym.Bolt))
        add(TvTab(Screen.Subs, tr("Subscriptions"), Sym.Subscriptions))
        add(TvTab(Screen.Library, tr("Library"), Sym.VideoLibrary))
        if (!calm) {
            add(TvTab(Screen.Music, tr("Music"), Sym.Music))
            add(TvTab(Screen.Topic("Live"), tr("Live"), Sym.LiveTv))
            add(TvTab(Screen.Topic("Gaming"), tr("Gaming"), Sym.Gaming))
            add(TvTab(Screen.Topic("News"), tr("News"), Sym.News))
            add(TvTab(Screen.Topic("Sports"), tr("Sports"), Sym.Trophy))
            add(TvTab(Screen.Topic("Learning"), tr("Learning"), Sym.School))
            add(TvTab(Screen.Topic("Fashion & beauty"), tr("Fashion & beauty"), Sym.Apparel))
        }
    }
}

/** the whole app on a TV */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvApp(app: AppState) {
    val cur = app.current
    val root = app.stack.firstOrNull()
    val rail = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    var inRail by remember { mutableStateOf(false) }
    var anyFocus by remember { mutableStateOf(false) }
    var leaveAt by remember { mutableLongStateOf(0L) }
    var enter by remember { mutableLongStateOf(0L) }
    var enterPending by remember { mutableStateOf(false) }
    val watching = cur is Screen.Watch
    val focus = LocalFocusManager.current
    val desktop = app.platform.name == "desktop"

    // Back: from a page's content to the menu first; on the menu, Home; on Home's menu, once more to leave
    DisposableEffect(Unit) {
        app.tvBack = back@{
            if (app.current is Screen.Watch || app.stack.size > 1) return@back false
            if (!inRail) { runCatching { rail.requestFocus() }; return@back true }
            if (app.current != Screen.Home) return@back false
            val now = Sys.nowMs()
            if (now - leaveAt < 2500) false else { leaveAt = now; app.toast(tr("Press Back again to leave")); true }
        }
        onDispose { app.tvBack = null }
    }
    // a new page: the remote's focus goes into it as soon as there is something to stand on (its
    // items may still be loading), and never stays lost
    // (picked on the menu: the cursor leaves the menu for the page, even while the page is still empty)
    LaunchedEffect(cur, enter) {
        if (watching) return@LaunchedEffect
        for (i in 0 until 40) {
            delay(if (i == 0) 60 else 150)
            if (enterPending && anyFocus && !inRail) { enterPending = false; break }
            if (!anyFocus || (enterPending && inRail)) runCatching { content.requestFocus() }
        }
        enterPending = false
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides remember { TvScroll(160f, 160f, pin = false) }) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onFocusChanged { anyFocus = it.hasFocus }
                // a keyboard (the computer's TV layout): Esc is the remote's Back
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.Escape -> app.back()
                        // a computer moves the focus only with Tab: the arrows do it here, as a remote does on a TV
                        Key.DirectionUp -> desktop && focus.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> desktop && focus.moveFocus(FocusDirection.Down)
                        Key.DirectionLeft -> desktop && focus.moveFocus(FocusDirection.Left)
                        Key.DirectionRight -> desktop && focus.moveFocus(FocusDirection.Right)
                        else -> false
                    }
                },
        ) {
            if (watching) {
                TvWatch(app)
            } else {
                // the page, beside the closed menu (the open menu lies over it)
                Box(
                    Modifier.fillMaxSize().padding(start = RAIL)
                        .focusRequester(content).focusGroup(),
                ) {
                    when (cur) {
                        Screen.Home -> TvHome(app)
                        Screen.Subs -> TvSubs(app)
                        Screen.Library -> TvLibrary(app)
                        is Screen.Search -> SearchScreen(app, cur.query)
                        Screen.Shorts -> ShortsScreen(app)
                        Screen.Settings -> TvPage(tr("Settings")) { SettingsScreen(app) }
                        Screen.Learned -> TvPage(null) { LearnedScreen(app) }
                        Screen.ManageSubs -> TvPage(null) { ManageSubsScreen(app) }
                        Screen.History -> TvPage(tr("History")) { HistoryScreen(app) }
                        is Screen.Channel -> ChannelScreen(app, cur.id, cur.name)
                        is Screen.Playlist -> PlaylistScreen(app, cur.id, cur.title)
                        is Screen.Local -> TvPage(null) { LocalListScreen(app, cur.key) }
                        Screen.Downloads -> TvPage(tr("Downloads")) { DownloadsScreen(app) }
                        is Screen.Topic -> TopicScreen(app, cur.key)
                        is Screen.Watch -> {}
                        Screen.Music -> TvPage(tr("Music")) { MusicScreen(app) }
                        Screen.NowPlaying -> TvPage(null) { NowPlayingScreen(app) }
                        is Screen.Album -> TvPage(null) { AlbumScreen(app, cur.id, cur.title) }
                        is Screen.Artist -> TvPage(null) { ArtistScreen(app, cur.id, cur.name) }
                        is Screen.SettingsPage -> TvPage(catTitleOf(cur.key)) { SettingsPageScreen(app, cur.key) }
                        Screen.Stats -> TvPage(tr("Watch time")) { StatsScreen(app) }
                    }
                }
                TvRail(app, root, rail, inRail, onFocus = { inRail = it }, toContent = { enterPending = true; enter++; runCatching { content.requestFocus() } })
            }
            SnackbarHost(app.snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

private val RAIL = 84.dp

/** a menu page: the pages opened before it are dropped, as with the tabs */
private fun AppState.goRoot(s: Screen) { stack.clear(); stack.add(s) }

/** a page that came from the tablet layout, with a large title above it for the TV */
@Composable
private fun TvPage(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (title != null) Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 28.dp, top = 28.dp, bottom = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) { content() }
    }
}

/**
 * The menu: icons only while the remote is on the page; moving onto it opens it over the page with
 * the names, the open page marked. OK goes there and back into the page.
 */
@Composable
private fun TvRail(app: AppState, root: Screen?, req: FocusRequester, open: Boolean, onFocus: (Boolean) -> Unit, toContent: () -> Unit) {
    val width by animateDpAsState(if (open) 248.dp else RAIL, tween(Motion.MEDIUM, easing = Motion.standard))
    val tabs = tvTabs(app)
    val selected = tabs.indexOfFirst { t ->
        when (val s = t.screen) {
            is Screen.Search -> root is Screen.Search
            else -> s == root
        }
    }.let { if (it < 0 && root == Screen.Settings) -2 else it }
    val first = remember { FocusRequester() }
    val pick = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val settingsReq = remember { FocusRequester() }
    Box(
        Modifier.width(width).fillMaxHeight()
            .background(
                if (open) Brush.horizontalGradient(listOf(Color(0xF2101010), Color(0xE6101010), Color(0x00101010)))
                else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)),
            )
            .onFocusChanged { onFocus(it.hasFocus) }
            .focusRequester(req)
            .focusGroup(),
    ) {
        // the open page's item takes the focus when the remote comes onto the menu
        LaunchedEffect(open) {
            if (open) runCatching { if (selected >= 0) pick[selected].requestFocus() else if (selected == -2) settingsReq.requestFocus() }
        }
        Column(Modifier.fillMaxHeight().padding(vertical = 20.dp, horizontal = 14.dp)) {
            Row(Modifier.height(48.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HouseMark(Modifier.size(30.dp))
                if (open) Text("TubeHaus", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            tabs.forEachIndexed { i, t ->
                RailItem(t.icon, t.label, i == selected, open, Modifier.focusRequester(pick[i]).let { if (i == 0) it.focusRequester(first) else it }) {
                    app.goRoot(t.screen); toContent()
                }
            }
            Spacer(Modifier.weight(1f))
            RailItem(Sym.Settings, tr("Settings"), selected == -2, open, Modifier.focusRequester(settingsReq)) {
                app.goRoot(Screen.Settings); toContent()
            }
        }
    }
}

@Composable
private fun RailItem(icon: Symbol, label: String, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val fg = when {
        focused -> Color.Black
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.padding(vertical = 2.dp).height(44.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (selected || focused) icon.fill else icon.outline, label, Modifier.size(24.dp), tint = fg)
        }
        if (open) Text(
            label, color = fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 18.dp),
        )
        else if (selected) Box(Modifier.padding(start = 2.dp).size(5.dp).clip(CircleShape).background(Teal))
    }
}

