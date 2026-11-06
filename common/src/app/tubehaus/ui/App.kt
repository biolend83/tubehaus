package app.tubehaus.ui

import app.tubehaus.engine.Sys
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubehaus.engine.InnerTube
import app.tubehaus.engine.Net
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.request.crossfade

/** true on a phone-sized touch screen: the bottom bar, edge-to-edge cards; false on tablets and desktops */
val LocalCompact = compositionLocalOf { true }

@Composable
fun App(app: AppState) {
    val settings by app.store.settings.state.collectAsState()
    val lang = settings.lang.ifEmpty { "en" }
    L.lang = lang
    InnerTube.hl = if (lang == "zh") "zh-CN" else lang
    InnerTube.gl = settings.region.ifEmpty { app.platform.deviceRegion() }.uppercase()
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { Sys.imageNetwork(this) }
            .diskCache { DiskCache.Builder().directory(app.platform.cacheDir / "images").maxSizeBytes(150L shl 20).build() }
            .crossfade(true)
            .build()
    }
    key(lang, settings.tvMode) {
        TubeTheme(settings.dark, app.platform::wallpaperColors) {
            val bg = MaterialTheme.colorScheme.background
            LaunchedEffect(bg) { app.platform.titleBar(bg) }
            val tv = app.platform.tv
            CompositionLocalProvider(
                LocalPlatform provides app.platform,
                // TV: buttons, tabs and menu rows the remote has selected glow teal, clearly enough for the sofa
                LocalRippleConfiguration provides if (tv) RippleConfiguration(Teal, RippleAlpha(0.16f, 0.34f, 0.08f, 0.2f)) else LocalRippleConfiguration.current,
            ) {
                Surface(
                    Modifier.fillMaxSize().let { if (tv) it.onPreviewKeyEvent { e -> tvKey(app, e) } else it },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // phones get the phone layout; tablets and the desktop the wide one
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val d = LocalDensity.current
                        CompositionLocalProvider(
                            LocalCompact provides (app.platform.touch && maxWidth < 600.dp),
                            LocalDensity provides Density(d.density, d.fontScale * settings.textScale),
                        ) {
                            when {
                                settings.lang.isEmpty() -> LanguagePicker(app)
                                !settings.welcomed && app.store.lib.value.subs.isEmpty() -> Welcome(app)
                                app.pip -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                                    app.player.View(Modifier.fillMaxSize())
                                }
                                // a TV: its own big-screen layout and player
                                tv -> TvApp(app)
                                app.fullscreen && app.current is Screen.Watch -> FullscreenWatch(app)
                                else -> Shell(app)
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        app.fillSubInfo()
                        app.sync.refreshSoon()
                        app.whatsNewAfterUpdate()
                        // at start, and every hour for a window left open (Android also asks each time
                        // the app comes back to the front: MainActivity.onStart)
                        while (true) { app.checkForUpdates(); kotlinx.coroutines.delay(3_600_000) }
                    }
                    LaunchedEffect(settings.hideBars) { app.platform.setImmersive(settings.hideBars) }
                    LaunchedEffect(settings.longBuffer) { app.player.setReadAhead(settings.longBuffer) }
                    LaunchedEffect(settings.audioDevice) { app.player.setAudioDevice(settings.audioDevice) }
                    app.update?.let { UpdateDialog(app, it) }
                    if (app.casting.picking) CastDialog(app)
                    app.whatsNew?.let { WhatsNewDialog(app, it) }
                    app.offline.asking?.let { OfflineDialog(app, it) }
                }
            }
        }
    }
}

/** TV remote keys for every screen: the search key, and Shorts (up and down change the Short, OK pauses) */
private fun tvKey(app: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.Search) { app.go(Screen.Search()); return true }
    val shorts = app.shortsKeys
    if (app.current is Screen.Shorts && shorts != null) return when (e.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> playerKey(app, Key.Spacebar)
        else -> shorts(e.key)
    }
    return false
}

private data class Tab(val screen: Screen, val label: String, val on: ImageVector, val off: ImageVector)

/**
 * The tabs: on a phone, as in YouTube's app now, the bottom bar is Home, Shorts and Library, and
 * Subscriptions moved to the tabs at the top (TopTabs); the side menu of tablets, desktops and TVs keeps all four.
 */
@Composable
private fun tabs(app: AppState, phone: Boolean): List<Tab> {
    return listOfNotNull(
        Tab(Screen.Home, tr("Home"), Sym.Home.fill, Sym.Home.outline),
        if (app.noShorts) null else Tab(Screen.Shorts, tr("Shorts"), Sym.Bolt.fill, Sym.Bolt.outline),
        if (phone) null else Tab(Screen.Subs, tr("Subscriptions"), Sym.Subscriptions.fill, Sym.Subscriptions.outline),
        Tab(Screen.Library, tr("Library"), Sym.VideoLibrary.fill, Sym.VideoLibrary.outline),
    )
}

@Composable
private fun Shell(app: AppState) {
    val cur = app.current
    val compact = LocalCompact.current
    val root = app.stack.firstOrNull()
    val showTop = cur !is Screen.Watch && cur !is Screen.Shorts && cur !is Screen.Search
    // the desktop: while you browse, the video keeps playing in a small window at the bottom right
    val floating = !app.platform.touch && !app.platform.tv
    // dragged with the mouse: how far from that corner it was moved, kept inside the window
    var floatAt by remember { mutableStateOf(Offset.Zero) }
    var floatArea by remember { mutableStateOf(IntSize.Zero) }
    val floatMargin = with(LocalDensity.current) { 32.dp.toPx() }
    val moveFloat = { d: Offset, card: IntSize ->
        val maxX = (floatArea.width - card.width - floatMargin).coerceAtLeast(0f)
        val maxY = (floatArea.height - card.height - floatMargin).coerceAtLeast(0f)
        floatAt = Offset((floatAt.x + d.x).coerceIn(-maxX, 0f), (floatAt.y + d.y).coerceIn(-maxY, 0f))
    }
    // touch screens: the tabs (bottom on phones, the side rail on tablets) and the top bar slide away
    // while scrolling down and come back scrolling up
    val autoHide = app.platform.touch && app.store.settings.state.collectAsState().value.autoHideNav
    var navShown by remember { mutableStateOf(true) }
    // the side menu folded away: a small menu button brings it back (in the top bar, or above the video)
    val railCollapsed = !compact && app.store.settings.state.collectAsState().value.railCollapsed
    val unfold = { app.store.settings.update { it.copy(railCollapsed = false) } }
    val navScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) navShown = false else if (available.y > 6f) navShown = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(cur) {
        navShown = true
        // Shorts has its own player; a video playing in the background stops
        if (cur is Screen.Shorts) app.playback.close()
    }
    Scaffold(
        topBar = { if (showTop) AnimatedVisibility(navShown || !autoHide, enter = expandVertically(), exit = shrinkVertically()) { TopBar(app, if (railCollapsed) unfold else null) } },
        bottomBar = {
            Column {
                // the mini player rises from the bottom edge and sinks back into it (phones, tablets, TVs;
                // the desktop has a floating one with the picture, below)
                AnimatedVisibility(
                    app.playback.video != null && cur !is Screen.Watch && cur !is Screen.Shorts && !floating,
                    enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } + fadeIn(tween(Motion.SHORT)),
                    exit = slideOutVertically(tween(Motion.SHORT, easing = Motion.emphasizedOut)) { it } + fadeOut(tween(Motion.SHORT)),
                ) { MiniPlayer(app) }
                if (compact && cur !is Screen.Watch) AnimatedVisibility(navShown || !autoHide, enter = expandVertically(), exit = shrinkVertically()) {
                    PhoneBar(app, root)
                }
            }
        },
        snackbarHost = { SnackbarHost(app.snackbar) },
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad).let { if (autoHide) it.nestedScroll(navScroll) else it }) {
            // tablets: the side rail steps aside while watching, so the video gets the room
            if (!compact && !(cur is Screen.Watch && app.platform.touch) && !railCollapsed) AnimatedVisibility(navShown || !autoHide, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.background,
                    header = {
                        IconButton(onClick = { app.store.settings.update { it.copy(railCollapsed = true) } }) {
                            Icon(Sym.MenuOpen.fill, tr("Fold the menu"))
                        }
                    },
                ) {
                    for (t in tabs(app, phone = false)) {
                        val sel = t.screen.lit(root)
                        NavigationRailItem(
                            selected = sel, onClick = { app.go(t.screen) },
                            icon = { Icon(if (sel) t.on else t.off, null) },
                            // YouTube's narrow menu: small plain labels, the filled icon alone marks the open page
                            label = { Text(t.label, fontSize = if (app.platform.tv) 11.sp else 10.sp, maxLines = 1,
                                fontWeight = if (sel && app.platform.tv) FontWeight.SemiBold else FontWeight.Normal) },
                            // as on YouTube: the filled icon says which page is open, no pill behind it
                            // (a TV keeps the pill, it is easier to see from the sofa)
                            colors = if (app.platform.tv) NavigationRailItemDefaults.colors() else NavigationRailItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface, unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                    // below a line, smaller: back to the last video, and the settings
                    HorizontalDivider(Modifier.width(40.dp).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                    val last = app.playback.video ?: app.store.lib.state.collectAsState().value.hist.firstOrNull()?.video()
                    if (last != null) SmallRailItem(Sym.History.outline, tr("Resume"), cur is Screen.Watch) {
                        if (app.playback.video?.id == last.id) app.go(Screen.Watch(last.id)) else app.watch(last)
                    }
                    SmallRailItem(Sym.Settings.outline, tr("Settings"), cur == Screen.Settings) { app.go(Screen.Settings) }
                    // the video page has no top bar, so search sits here too
                    if (!showTop) SmallRailItem(Sym.Search.outline, tr("Search"), cur is Screen.Search) { app.go(Screen.Search()) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
              // the watch page has no top bar: the folded menu's button sits in a slim row above the video
              if (railCollapsed && cur is Screen.Watch && !app.platform.touch) {
                  IconButton(onClick = unfold, modifier = Modifier.padding(start = 4.dp).size(36.dp)) { Icon(Sym.Menu.fill, tr("Menu")) }
              }
              Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { floatArea = it }) {
                key(cur) {
                  // a new page fades in: going forward it slides in from the right, going back from the
                  // left, a tab settles from a touch smaller (not the watch page or Shorts: a video
                  // surface shouldn't be see-through or moving while it starts)
                  val move = remember { app.lastMove }
                  val shown = remember { Animatable(if (cur is Screen.Watch || cur is Screen.Shorts) 1f else 0f) }
                  LaunchedEffect(Unit) { shown.animateTo(1f, tween(if (move == 0) Motion.SHORT + 50 else Motion.MEDIUM, easing = Motion.emphasizedIn)) }
                  Box(Modifier.fillMaxSize().graphicsLayer {
                      val rest = 1f - shown.value
                      alpha = shown.value
                      when (move) {
                          1 -> translationX = rest * 40.dp.toPx()
                          -1 -> translationX = -rest * 24.dp.toPx()
                          else -> { scaleX = 1f - rest * 0.03f; scaleY = scaleX }
                      }
                  }) {
                    when (cur) {
                        Screen.Home -> HomeScreen(app)
                        Screen.Shorts -> ShortsScreen(app)
                        Screen.Subs -> SubsScreen(app)
                        Screen.Library -> LibraryScreen(app)
                        Screen.Settings -> SettingsScreen(app)
                        Screen.Learned -> LearnedScreen(app)
                        Screen.ManageSubs -> ManageSubsScreen(app)
                        Screen.History -> HistoryScreen(app)
                        is Screen.Search -> SearchScreen(app, cur.query)
                        is Screen.Watch -> WatchScreen(app)
                        is Screen.Channel -> ChannelScreen(app, cur.id, cur.name)
                        is Screen.Playlist -> PlaylistScreen(app, cur.id, cur.title)
                        is Screen.Local -> LocalListScreen(app, cur.key)
                        Screen.Downloads -> DownloadsScreen(app)
                        is Screen.Topic -> TopicScreen(app, cur.key)
                    }
                  }
                }
                // the plain one: inside a Box that sits in a Column, the Column version would be picked
                androidx.compose.animation.AnimatedVisibility(
                    floating && app.playback.video != null && cur !is Screen.Watch && cur !is Screen.Shorts,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).offset { IntOffset(floatAt.x.roundToInt(), floatAt.y.roundToInt()) },
                    enter = fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM, easing = Motion.emphasizedIn), initialScale = 0.9f),
                    exit = fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.9f),
                ) { FloatingPlayer(app, moveFloat) }
              }
            }
        }
    }
}

/** whether this tab is the open one: Home also for the pages of the top tabs that sit under it (Music, Live; Subscriptions on phones) */
private fun Screen.lit(root: Screen?): Boolean = when {
    root == null -> false
    this == Screen.Home -> root == Screen.Home || (root is Screen.Topic && root.key in TOP_TOPICS)
    else -> root == this
}

/**
 * The phone's bottom bar as YouTube draws it: a hairline on top, the icons and small labels close
 * together, the open one filled; no pill. Subscriptions lights Home, as it is one of Home's top tabs here.
 */
@Composable
private fun PhoneBar(app: AppState, root: Screen?) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.background).windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
        HorizontalDivider(thickness = 0.5.dp, color = c.outline.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in tabs(app, phone = true)) {
                val sel = t.screen.lit(root) || (t.screen == Screen.Home && root == Screen.Subs)
                // pressed as in YouTube's app: a grey circle swells behind the icon, which dips and
                // springs back when let go
                val touch = remember { MutableInteractionSource() }
                val pressed by touch.collectIsPressedAsState()
                val dip by animateFloatAsState(if (pressed) 0.86f else 1f,
                    if (pressed) tween(Motion.SHORT) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                val shade by animateFloatAsState(if (pressed) 0.12f else 0f, tween(if (pressed) 90 else Motion.LONG))
                val ink = c.onSurface
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .drawBehind { if (shade > 0f) drawCircle(ink.copy(alpha = shade), radius = 26.dp.toPx() * (0.7f + 0.3f * (shade / 0.12f))) }
                        .selectable(sel, interactionSource = touch, indication = null, role = Role.Tab, onClick = { app.go(t.screen) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (sel) t.on else t.off, null, Modifier.size(24.dp).graphicsLayer { scaleX = dip; scaleY = dip }, tint = c.onSurface)
                    Text(t.label, fontSize = 10.sp, maxLines = 1, color = c.onSurface,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** A quieter item at the bottom of the side menu (Resume, Settings): a smaller icon and label */
@Composable
private fun SmallRailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Full screen; on a wide screen the comments can open beside the video (the button at the top right). */
@Composable
private fun FullscreenWatch(app: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val token = app.playback.details?.commentsToken?.takeIf { !app.store.settings.value.hideComments }
        val canSide = maxWidth > 600.dp && token != null
        val panel = minOf(400.dp, maxWidth * 0.35f)
        CompositionLocalProvider(LocalSideComments provides canSide) {
            Row(Modifier.fillMaxSize()) {
                VideoArea(app, Modifier.weight(1f).fillMaxHeight(), fullscreen = true)
                if (canSide && app.fsComments) {
                    Surface(Modifier.width(panel).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                        CommentsPanel(app, token!!) { app.fsComments = false }
                    }
                }
            }
        }
    }
}

/** true when full screen can show the comments beside the video (a wide screen, a video with comments) */
val LocalSideComments = compositionLocalOf { false }

/** The TubeHaus mark (web/y/icon.svg): a teal house with an arched door and a play triangle in it */
@Composable
fun HouseMark(modifier: Modifier = Modifier) {
    val house = remember { PathParser().parsePathString("M72 244 256 82l184 162h-42v184H114V244z").toPath() }
    val door = remember { PathParser().parsePathString("M204 428V312a52 52 0 0 1 104 0v116z").toPath() }
    val play = remember { PathParser().parsePathString("m226 286 58 26-58 26z").toPath() }
    Canvas(modifier) {
        // the house spans x 72..440, y 82..428 of the 512 grid
        val s = minOf(size.width / 368f, size.height / 346f)
        withTransform({
            translate((size.width - 368f * s) / 2f, (size.height - 346f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
            translate(-72f, -82f)
        }) {
            drawPath(house, Color(0xFF2DD4BF))
            drawPath(door, Color(0xFF14283F))
            drawPath(play, Color.White)
        }
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HouseMark(Modifier.size(width = 26.dp, height = 22.dp))
        Spacer(Modifier.width(6.dp))
        Text("TubeHaus", fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = (-0.5).sp)
    }
}

@Composable
private fun TopBar(app: AppState, onMenu: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) IconButton(onClick = onMenu) { Icon(Sym.Menu.fill, tr("Menu")) }
            if (app.stack.size > 1) {
                IconButton(onClick = { app.back() }) { Icon(Sym.ArrowBack.fill, tr("Back")) }
            }
            Logo(Modifier.clickable { app.go(Screen.Home) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { app.go(Screen.Search()) }) { Icon(Sym.Search.outline, tr("Search")) }
            if (LocalCompact.current || onMenu != null) IconButton(onClick = { app.go(Screen.Settings) }) { Icon(Sym.Settings.outline, tr("Settings")) }
        }
        // every device: Home, Subscriptions, Music, Live and More under the logo, on those pages themselves
        if (app.stack.size == 1 && app.current.underHome()) TopTabs(app)
        if (!LocalCompact.current) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** A newer version is out: get it now, later, or never for this one. */
@Composable
private fun UpdateDialog(app: AppState, r: app.tubehaus.engine.Remote.Release) {
    // the download in the app (Android): null before it starts, then 0..1
    var got by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (got == null) app.update = null },
        title = { Text(tr("TubeHaus {v} is out").replace("{v}", r.version)) },
        text = {
            Column {
                Text(tr("You have {v}.").replace("{v}", VERSION) + " " + tr(r.download?.let { "Download it and install it over this one; your library stays." } ?: "Pick the file for your system on the release page."))
                if (r.notes.isNotEmpty()) {
                    Text(tr("What's new"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    // focusable: a TV remote can reach the notes and scroll them
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).focusable()) { NoteLines(r.notes) }
                }
                got?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                    Text(if (p >= 1f) tr("Opening the installer...") else "${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (failed) Text(tr("The download failed. Opening the release page instead."), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = got == null, onClick = {
                val url = r.download
                val inApp = url != null && app.platform.installUpdate(url, { got = it }) { ok ->
                    if (ok) app.update = null else { failed = true; app.platform.openExternal(r.page); app.update = null }
                }
                if (inApp) got = 0f else { app.platform.openExternal(url ?: r.page); app.update = null }
            }) { Text(tr(if (app.platform.name == "android" && r.download != null) "Update now" else "Download")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { app.store.settings.update { it.copy(skipVersion = r.version) }; app.update = null }) { Text(tr("Skip this version")) }
                TextButton(onClick = { app.update = null }) { Text(tr("Later")) }
            }
        },
    )
}

/** The bar above the tabs while a video plays and you look at something else. */
/** The desktop's mini player: the playing video, small, over the page; a click goes back to it. */
@Composable
private fun FloatingPlayer(app: AppState, move: (Offset, IntSize) -> Unit) {
    val v = app.playback.video ?: return
    val st by app.player.state.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        // drag it anywhere by the picture or the title; a click (no drag) still opens the video
        Modifier.width(360.dp).onSizeChanged { size = it }
            .pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); move(d, size) } },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).clickable { app.go(Screen.Watch(v.id)) }) {
                if (st.videoId == v.id) app.player.View(Modifier.fillMaxSize())
                else if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.fillMaxSize())
                if (st.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Red))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp).clickable { app.go(Screen.Watch(v.id)) }) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                    Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
                }
                IconButton(onClick = { app.playback.close() }) { Icon(Sym.Close.fill, tr("Close")) }
            }
        }
    }
}

@Composable
private fun MiniPlayer(app: AppState) {
    val v = app.playback.video ?: return
    val st by app.player.state.collectAsState()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (st.durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline)) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(2.dp).background(Red))
            }
        }
        Row(
            Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(0.dp)) { app.go(Screen.Watch(v.id)) }.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.width(96.dp).aspectRatio(16f / 9f))
            else Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
            }
            IconButton(onClick = { app.playback.close() }) { Icon(Sym.Close.fill, tr("Close")) }
        }
    }
}
