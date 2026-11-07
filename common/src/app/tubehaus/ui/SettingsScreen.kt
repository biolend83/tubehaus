package app.tubehaus.ui

import app.tubehaus.engine.SponsorBlock
import app.tubehaus.engine.Sys
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.tubehaus.data.Settings
import app.tubehaus.data.Transfer
import app.tubehaus.rec.Learn
import app.tubehaus.rec.Profiles
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** the parts under a video that Settings → Focus can hide one by one */
val UNDER_PARTS = listOf(
    "title" to "Title", "views" to "View count", "date" to "Upload date", "channel" to "Channel",
    "subscribers" to "Subscriber count", "subscribe" to "Subscribe button", "likes" to "Like and dislike",
    "later" to "Watch later", "save" to "Save to playlist", "share" to "Share", "download" to "Download",
    "next" to "Next", "description" to "Description",
)

const val VERSION = "0.5.19"

/** Settings' categories, as cards on its first page: key, icon, title, what is inside */
private class Cat(val key: String, val icon: Symbol, val title: String, val sub: String)

private fun cats(app: AppState) = listOfNotNull(
    Cat("display", Sym.Palette, "Display & theme", "Theme, text size, tabs"),
    Cat("language", Sym.Language, "Language & region", "The app's language, the region of videos"),
    Cat("player", Sym.PlayCircle, "Player & playback", "Speed, autoplay, captions, background play"),
    Cat("quality", Sym.HighQuality, "Quality", "Resolution, mobile data, video codec"),
    Cat("music", Sym.Music, "Music", "Sound only, lyrics"),
    Cat("focus", Sym.Feed, "Feeds & focus", "Home, Shorts, thumbnails, what's under the video"),
    Cat("integrations", Sym.Extension, "Integrations", "SponsorBlock, DeArrow, dislikes"),
    Cat("wellbeing", Sym.Wellbeing, "Wellbeing", "Watch time and break reminders"),
    Cat("data", Sym.Database, "Your data", "History, import and export, TubeHaus server"),
    Cat("about", Sym.Info, "About", "Version, updates, source code"),
)

fun catTitleOf(key: String) = catTitle(key)

private fun catTitle(key: String): String = when (key) {
    "display" -> tr("Display & theme"); "language" -> tr("Language & region"); "player" -> tr("Player & playback")
    "quality" -> tr("Quality"); "music" -> tr("Music"); "focus" -> tr("Feeds & focus"); "integrations" -> tr("Integrations")
    "wellbeing" -> tr("Wellbeing"); "data" -> tr("Your data"); "about" -> tr("About"); else -> tr("Settings")
}

/** Settings' first page: a search box over every setting, and the categories as cards. */
@Composable
fun SettingsScreen(app: AppState) {
    var q by remember { mutableStateOf("") }
    Narrow {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true, shape = RoundedCornerShape(26.dp),
                placeholder = { Text(tr("Search settings...")) },
                leadingIcon = { Icon(Sym.Search.outline, null) },
                trailingIcon = if (q.isNotEmpty()) ({ IconButton(onClick = { q = "" }) { Icon(Sym.Close.fill, tr("Clear")) } }) else null,
            )
            if (q.isBlank()) {
                val today = app.store.stats.state.collectAsState().value.days[Sys.today()]
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cats(app), key = { it.key }) { c ->
                        val sub = if (c.key == "wellbeing" && today != null && today.video + today.music >= 60)
                            tr("Today: {t}").replace("{t}", minutesText((today.video + today.music) / 60)) else tr(c.sub)
                        CatCard(c.icon, tr(c.title), sub) { app.go(Screen.SettingsPage(c.key)) }
                    }
                }
            } else SettingsRows(app, null, q.trim())
        }
    }
}

/** One category of Settings on its own page */
@Composable
fun SettingsPageScreen(app: AppState, key: String) {
    Narrow {
        Column(Modifier.fillMaxSize()) {
            if (!app.platform.tv) SectionTitle(catTitle(key))
            SettingsRows(app, key, "")
        }
    }
}

@Composable
private fun CatCard(icon: Symbol, title: String, sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
            .tvClickable(RoundedCornerShape(16.dp), onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Teal.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon.outline, null, Modifier.size(24.dp), tint = if (c.background.luminance() < 0.5f) Teal else Color(0xFF0F766E))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2)
        }
        Icon(Sym.KeyboardArrowUp.outline, null, Modifier.rotate(90f), tint = c.onSurfaceVariant)
    }
}

/** "1 h 25 min", "40 min" */
fun minutesText(min: Long): String =
    if (min >= 60) tr("{h} h {m} min").replace("{h}", "${min / 60}").replace("{m}", "${min % 60}")
    else tr("{m} min").replace("{m}", "$min")

/**
 * Adds the rows of one category ([cat]) or, while searching ([q]), the rows whose words match it,
 * under the name of their category.
 */
private class Rows(val scope: LazyListScope, val cat: String?, val q: String) {
    private var lastCat: String? = null
    var count = 0
    fun group(c: String, title: String) {
        if (q.isEmpty() && c == cat) scope.item { Group(title) }
    }
    fun row(c: String, words: String, content: @Composable () -> Unit) {
        val show = if (q.isEmpty()) c == cat else q.split(' ').filter { it.isNotBlank() }.all { words.contains(it, ignoreCase = true) }
        if (!show) return
        count++
        if (q.isNotEmpty() && c != lastCat) { lastCat = c; val t = catTitle(c); scope.item { Group(t) } }
        scope.item { content() }
    }
}

@Composable
private fun SettingsRows(app: AppState, cat: String?, q: String) {
    val s by app.store.settings.state.collectAsState()
    fun set(f: (Settings) -> Settings) = app.store.settings.update(f)
    var dialog by remember { mutableStateOf<String?>(null) }
    val heights = listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    fun hLabel(h: Int) = if (h == 0) tr("Best available") else "${h}p"
    val android = app.platform.name == "android"
    val touch = app.platform.touch
    // read once per page, for the sound output row
    val devices = remember { if (app.platform.player.hasVolume) app.platform.player.audioDevices() else emptyList() }
    LazyColumn(Modifier.fillMaxSize()) {
        val r = Rows(this, cat, q)
        // ------------------------------------------------------------------ display
        run {
            val themes = listOf("0" to tr("Follow the system"), "1" to tr("Light"), "2" to tr("Dark"), "3" to tr("Black (AMOLED)")) +
                // Android 12+: Material You, the colours of the wallpaper
                listOfNotNull(("4" to tr("Wallpaper colours")).takeIf { app.platform.wallpaperColors(false) != null })
            r.row("display", tr("Theme") + " dark light black") { Choice(tr("Theme"), themes.getOrNull(s.dark)?.second ?: themes[0].second, themes) { c -> set { it.copy(dark = c.toInt()) } } }
            val sizes = listOf(1f to tr("Normal"), 1.15f to tr("Large"), 1.3f to tr("Larger"), 1.5f to tr("Largest"))
            r.row("display", tr("Text size") + " font") {
                Choice(tr("Text size"), sizes.firstOrNull { it.first == s.textScale }?.second ?: tr("Normal"), sizes.map { "${it.first}" to it.second }) { c -> set { it.copy(textScale = c.toFloat()) } }
            }
        }
        if (android) r.row("display", tr("TV mode")) {
            val modes = listOf("0" to tr("Automatic"), "1" to tr("On"), "2" to tr("Off"))
            Choice(tr("TV mode"), modes.getOrNull(s.tvMode)?.second ?: modes[0].second, modes) { c -> set { it.copy(tvMode = c.toInt()) } }
        }
        if (touch) r.row("display", tr("Hide the tabs while scrolling") + tr("The tabs and the top bar slide away as you scroll down")) {
            Toggle(tr("Hide the tabs while scrolling"), tr("The tabs and the top bar slide away as you scroll down"), s.autoHideNav) { b -> set { it.copy(autoHideNav = b) } }
        }
        if (touch) r.row("display", tr("Search box at the bottom") + tr("Within reach of your thumb")) {
            Toggle(tr("Search box at the bottom"), tr("Within reach of your thumb"), s.searchAtBottom) { b -> set { it.copy(searchAtBottom = b) } }
        }
        if (android) r.row("display", tr("Hide system bars")) {
            Toggle(tr("Hide system bars"), tr("Hide the status and navigation bars everywhere. Swipe from the edge to see them."), s.hideBars) { b -> set { it.copy(hideBars = b) } }
        }
        r.row("display", tr("Beside the video") + tr("Comments") + tr("Up next")) {
            Choice(tr("Beside the video"), tr(if (s.sideTab == "comments") "Comments" else "Up next"),
                listOf("next" to tr("Up next"), "comments" to tr("Comments"))) { c -> set { it.copy(sideTab = c) } }
        }
        // ------------------------------------------------------------------ language
        r.row("language", tr("Language") + " language") { Choice(tr("Language"), LANGS.firstOrNull { it.first == s.lang }?.second ?: "English", LANGS) { c -> set { it.copy(lang = c) } } }
        r.row("language", tr("Region") + " region country") {
            SItem(tr("Region"), s.region.ifEmpty { app.platform.deviceRegion() + " (" + tr("from this device") + ")" }) { dialog = "region" }
        }
        // ------------------------------------------------------------------ player
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        r.row("player", tr("Default speed") + " speed") {
            Choice(tr("Default speed"), if (s.speed == 1f) tr("Normal") else "${s.speed}x", speeds.map { "$it" to if (it == 1f) tr("Normal") else "${it}x" }) { c -> set { it.copy(speed = c.toFloat()) } }
        }
        r.row("player", tr("Remember the speed for each channel") + " speed") {
            Toggle(tr("Remember the speed for each channel"), tr("A speed you pick while watching is kept for that channel's videos"), s.speedPerChannel) { b -> set { it.copy(speedPerChannel = b) } }
        }
        if (s.channelSpeed.isNotEmpty()) r.row("player", tr("Channel speeds") + " speed") {
            SItem(tr("Forget the channel speeds"), tr("{n} channels have a speed of their own").replace("{n}", "${s.channelSpeed.size}")) { set { it.copy(channelSpeed = emptyMap()) }; app.toast(tr("Done")) }
        }
        r.row("player", tr("Autoplay") + tr("Play the next video when one ends")) { Toggle(tr("Autoplay"), tr("Play the next video when one ends"), s.autoplay) { b -> set { it.copy(autoplay = b) } } }
        if (touch) r.row("player", tr("Tap the video to pause")) {
            Toggle(tr("Tap the video to pause"), tr("A tap plays or pauses; the controls show with it"), s.tapToPause) { b -> set { it.copy(tapToPause = b) } }
        }
        r.row("player", tr("Captions") + " subtitles") { Toggle(tr("Captions"), tr("Turn captions on when a video has them"), s.captions) { b -> set { it.copy(captions = b) } } }
        r.row("player", tr("Most replayed") + " heatmap") { Toggle(tr("Most replayed"), tr("A wave over the seek bar shows the parts people watch again"), s.heatmap) { b -> set { it.copy(heatmap = b) } } }
        if (android) r.row("player", tr("Ambient mode")) {
            Toggle(tr("Ambient mode"), tr("In the dark theme, the video's colours glow softly around it"), s.ambient) { b -> set { it.copy(ambient = b) } }
        }
        if (android) r.row("player", tr("Background play")) {
            Toggle(tr("Background play"), tr("Keep playing with the screen off or in another app"), s.backgroundPlay) { b -> set { it.copy(backgroundPlay = b) } }
        }
        r.row("player", tr("Read further ahead") + " buffer") {
            Toggle(tr("Read further ahead"), tr("About 20 minutes of the video instead of 5, for fewer stops on a fast connection. Uses more memory") +
                if (android) " " + tr("(from the next start)") else "", s.longBuffer) { b -> set { it.copy(longBuffer = b) } }
        }
        if (devices.isNotEmpty()) r.row("player", tr("Sound output") + " audio speaker headphones") {
            // the desktop: which speaker or headphones (#46)
            val opts = listOf("" to tr("System default")) + devices.filter { it.first != "auto" }.map { (n, d) -> n to d.ifEmpty { n } }
            Choice(tr("Sound output"), opts.firstOrNull { it.first == s.audioDevice }?.second ?: s.audioDevice, opts) { c -> set { it.copy(audioDevice = c) } }
        }
        // ------------------------------------------------------------------ quality
        r.row("quality", tr("Quality") + " resolution") { Choice(tr("Quality"), hLabel(s.maxHeight), heights.map { "$it" to hLabel(it) }) { c -> set { it.copy(maxHeight = c.toInt()) } } }
        if (android) r.row("quality", tr("Quality on mobile data")) {
            Choice(tr("Quality on mobile data"), hLabel(s.maxHeightMobileData), heights.map { "$it" to hLabel(it) }) { c -> set { it.copy(maxHeightMobileData = c.toInt()) } }
        }
        r.row("quality", tr("Video codec") + " codec h264 vp9 av1") {
            val opts = listOf("" to tr("Automatic"), "h264" to "H.264 (AVC)", "vp9" to "VP9", "av1" to "AV1")
            Choice(tr("Video codec"), opts.firstOrNull { it.first == s.codec }?.second ?: tr("Automatic"), opts) { c -> set { it.copy(codec = c) } }
        }
        r.row("quality", tr("Video codec") + " codec") {
            Note(tr("Automatic picks what this device decodes best. H.264 is the most compatible, VP9 and AV1 look better for the same data but need a newer device. A video without the codec plays in another."))
        }
        // ------------------------------------------------------------------ music
        r.row("music", tr("Sound only for music") + " audio data") {
            Toggle(tr("Sound only for music"), tr("Songs from the Music tab load only their sound, which saves data. \"Watch video\" brings the picture back."), s.musicAudioOnly) { b -> set { it.copy(musicAudioOnly = b) } }
        }
        r.row("music", tr("Lyrics") + " lyrics lrclib") {
            Note(tr("Lyrics come from LRCLIB (lrclib.net), a free database of lyrics. The song's title and artist are sent to it when you open the lyrics."))
        }
        r.row("music", tr("Clear music history") + " recent") {
            SItem(tr("Clear music history"), tr("Recently played songs, searches and opened albums")) {
                app.store.music.update { it.copy(recent = emptyList(), searches = emptyList(), opened = emptyList()) }; app.toast(tr("Done"))
            }
        }
        // ------------------------------------------------------------------ focus
        r.row("focus", tr("Calm mode") + tr("No view counts, no Shorts, nothing plays by itself")) { Toggle(tr("Calm mode"), tr("No view counts, no Shorts, nothing plays by itself"), s.calm) { b -> set { it.copy(calm = b) } } }
        r.row("focus", tr("Hide thumbnails")) { Toggle(tr("Hide thumbnails"), tr("Titles only, no pictures to click on"), s.hideThumbs) { b -> set { it.copy(hideThumbs = b) } } }
        r.row("focus", tr("Hide comments")) { Toggle(tr("Hide comments"), tr("No comments under videos"), s.hideComments) { b -> set { it.copy(hideComments = b) } } }
        r.row("focus", tr("Turn off the home feed")) { Toggle(tr("Turn off the home feed"), tr("Home becomes a search page"), s.homeOff) { b -> set { it.copy(homeOff = b) } } }
        r.row("focus", tr("Turn off the subscription feed")) { Toggle(tr("Turn off the subscription feed"), tr("Subscriptions shows your channels, not their new videos"), s.subsFeedOff) { b -> set { it.copy(subsFeedOff = b) } } }
        r.row("focus", tr("Only my groups in Subscriptions")) { Toggle(tr("Only my groups in Subscriptions"), tr("With subscription groups: no \"All\" tab, just the groups"), s.subsHideAll) { b -> set { it.copy(subsHideAll = b) } } }
        r.row("focus", tr("Hide Shorts")) { Toggle(tr("Hide Shorts"), tr("No Shorts tab, no Shorts in lists"), s.hideShorts || s.calm) { b -> set { it.copy(hideShorts = b) } } }
        r.row("focus", tr("Hide related videos")) { Toggle(tr("Hide related videos"), tr("Nothing suggested next to or under a video"), s.hideRelated) { b -> set { it.copy(hideRelated = b) } } }
        // immersive mode: what is left under the video, down to just the player
        r.group("focus", tr("Immersive mode"))
        r.row("focus", tr("Under the video") + tr("Immersive mode")) {
            Column {
                Note(tr("Choose what shows under a video while you watch: everything, only the title, or just the player. With everything, switch off the parts you don't want one by one."))
                val opts = listOf("0" to tr("Everything"), "1" to tr("Only the title"), "2" to tr("Nothing, just the player"))
                Choice(tr("Under the video"), opts.getOrNull(s.underVideo)?.second ?: opts[0].second, opts) { c -> set { it.copy(underVideo = c.toInt()) } }
            }
        }
        // with "everything": each part on its own (grey while "nothing" or "only the title" is chosen)
        for ((k, label) in UNDER_PARTS) r.row("focus", tr(label) + tr("Under the video")) {
            Box(Modifier.padding(start = 24.dp).alpha(if (s.underVideo == 0) 1f else 0.4f)) {
                Toggle(tr(label), "", k !in s.hideUnder, enabled = s.underVideo == 0) { b ->
                    set { it.copy(hideUnder = if (b) it.hideUnder - k else (it.hideUnder + k).distinct()) }
                }
            }
        }
        // ------------------------------------------------------------------ integrations
        r.group("integrations", "SponsorBlock")
        r.row("integrations", "SponsorBlock sponsor skip") {
            Column {
                Toggle("SponsorBlock", tr("Skips sponsors and other parts people marked inside videos (sponsor.ajay.app). Only 4 characters of a hash are sent, not the video"), s.sponsor) { b -> set { it.copy(sponsor = b) } }
                Note(tr("Switched on: skipped by itself. Off: a button offers to skip it."))
            }
        }
        for (c in SponsorBlock.CATS) r.row("integrations", tr(c.label) + " SponsorBlock") {
            Box(Modifier.padding(start = 24.dp).alpha(if (s.sponsor) 1f else 0.4f)) {
                Toggle(tr(c.label), "", c.key in s.sponsorCats, enabled = s.sponsor) { b ->
                    set { it.copy(sponsorCats = if (b) (it.sponsorCats + c.key).distinct() else it.sponsorCats - c.key) }
                }
            }
        }
        r.group("integrations", tr("Titles and votes"))
        // DeArrow: no clickbait; its thumbnails can be switched off on their own
        r.row("integrations", "DeArrow clickbait " + tr("Titles and thumbnails people chose instead of clickbait (dearrow.ajay.app). Only 4 characters of a hash are sent, not the video")) {
            Toggle("DeArrow", tr("Titles and thumbnails people chose instead of clickbait (dearrow.ajay.app). Only 4 characters of a hash are sent, not the video"), s.dearrow) { b -> set { it.copy(dearrow = b) } }
        }
        r.row("integrations", tr("DeArrow thumbnails") + " DeArrow") {
            Box(Modifier.padding(start = 24.dp).alpha(if (s.dearrow) 1f else 0.4f)) {
                Toggle(tr("DeArrow thumbnails"), tr("A frame people picked, straight from DeArrow's server"), s.dearrowThumbs, enabled = s.dearrow) { b -> set { it.copy(dearrowThumbs = b) } }
            }
        }
        r.row("integrations", tr("Show dislikes") + " Return YouTube Dislike") {
            Toggle(tr("Show dislikes"), tr("An estimate from Return YouTube Dislike (returnyoutubedislikeapi.com), which is sent the video's ID"), s.showDislikes) { b -> set { it.copy(showDislikes = b) } }
        }
        // ------------------------------------------------------------------ wellbeing
        r.row("wellbeing", tr("Watch time") + " stats statistics recap") {
            val st by app.store.stats.state.collectAsState()
            val d = st.days[Sys.today()]
            SItem(tr("Watch time"), tr("Today: {t}").replace("{t}", minutesText(((d?.video ?: 0) + (d?.music ?: 0)) / 60)) + " · " + tr("By day, channel and month")) { app.go(Screen.Stats) }
        }
        val mins = listOf(0, 15, 30, 45, 60, 90, 120)
        r.row("wellbeing", tr("Remind me to take a break") + " break reminder") {
            Choice(tr("Remind me to take a break"), if (s.breakMinutes == 0) tr("Off") else tr("Every {t}").replace("{t}", minutesText(s.breakMinutes.toLong())),
                mins.map { "$it" to if (it == 0) tr("Off") else tr("Every {t}").replace("{t}", minutesText(it.toLong())) }) { c -> set { it.copy(breakMinutes = c.toInt()) } }
        }
        val daily = listOf(0, 30, 60, 90, 120, 180, 240)
        r.row("wellbeing", tr("Daily reminder") + " limit") {
            Choice(tr("Daily reminder"), if (s.dailyMinutes == 0) tr("Off") else tr("After {t} a day").replace("{t}", minutesText(s.dailyMinutes.toLong())),
                daily.map { "$it" to if (it == 0) tr("Off") else tr("After {t} a day").replace("{t}", minutesText(it.toLong())) }) { c -> set { it.copy(dailyMinutes = c.toInt()) } }
        }
        r.row("wellbeing", tr("Watch time")) {
            Note(tr("Watch time is counted on this device only and is never sent anywhere. Pausing watch history pauses it too."))
        }
        // ------------------------------------------------------------------ data
        r.row("data", tr("Pause watch history") + " history") { Toggle(tr("Pause watch history"), tr("Videos you watch aren't added to history and don't shape recommendations"), s.historyOff) { b -> set { it.copy(historyOff = b) } } }
        r.row("data", tr("What TubeHaus learned about you") + " taste recommendations") { SItem(tr("What TubeHaus learned about you"), tr("See and change what your recommendations are based on")) { app.go(Screen.Learned) } }
        r.row("data", tr("Import") + " takeout newpipe freetube invidious opml") { SItem(tr("Import"), tr("Subscriptions, history and playlists from YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML")) { importFile(app) } }
        r.row("data", tr("Manage subscriptions")) { SItem(tr("Manage subscriptions"), tr("Remove channels, or all of them")) { app.go(Screen.ManageSubs) } }
        r.row("data", tr("Export") + " backup") { SItem(tr("Export"), tr("A backup you can import here, into TubeHaus on a server, or into other apps")) { dialog = "export" } }
        r.row("data", tr("Clear watch history") + " history") { SItem(tr("Clear watch history"), "") { dialog = "clear" } }
        r.group("data", tr("TubeHaus server"))
        r.row("data", tr("TubeHaus server") + " sync server") { ServerItems(app) { dialog = "server" } }
        // ------------------------------------------------------------------ about
        r.row("about", tr("Check for updates") + " update") {
            Toggle(tr("Check for updates"), tr("Each time the app opens, on GitHub: a newer version, and fixes for when YouTube changes something. Nothing about you is sent."), s.checkUpdates) { b -> set { it.copy(checkUpdates = b) } }
        }
        r.row("about", tr("Check now") + " update") { SItem(tr("Check now"), tr("See if a newer version is out")) { app.checkForUpdates(now = true, quiet = false) } }
        app.platform.engineVersion()?.let { first ->
            r.row("about", tr("Update the YouTube engine") + " engine") {
                var engine by remember { mutableStateOf(first) }
                var busy by remember { mutableStateOf(false) }
                SItem(
                    tr("Update the YouTube engine"),
                    if (busy) tr("Checking…") else tr("Version {n}. Fixes for when YouTube changes something, without reinstalling the app").replace("{n}", engine.toString()),
                ) {
                    if (!busy) {
                        busy = true
                        app.scope.launch {
                            val res = app.platform.updateEngine()
                            busy = false
                            when {
                                res > 0 -> { engine = res; app.toast(tr("Engine updated to version {n}").replace("{n}", res.toString())) }
                                res == 0 -> app.toast(tr("The engine is up to date"))
                                else -> app.toast(tr("Couldn't reach GitHub. Try again later."))
                            }
                        }
                    }
                }
            }
        }
        r.row("about", tr("What's new") + " changelog") { SItem(tr("What's new"), tr("What changed in the last versions")) { app.showWhatsNew() } }
        r.row("about", "TubeHaus version") {
            SItem("TubeHaus $VERSION", tr("Everything happens on this device: it talks to YouTube directly, with no TubeHaus server, no account and no tracking. Your library and recommendations never leave it.")) {
                app.platform.openExternal("https://github.com/biolend83/tubehaus/tree/android-app")
            }
        }
        r.row("about", tr("Source code and updates") + " github") { SItem(tr("Source code and updates"), "github.com/biolend83/tubehaus") { app.platform.openExternal("https://github.com/biolend83/tubehaus/releases") } }
        r.row("about", tr("Disclaimer")) { SItem(tr("Disclaimer"), tr("TubeHaus is independent, free, open-source software, not affiliated with YouTube or Google. It doesn't host any videos and comes with no warranty. Downloaded videos are for your own viewing: sharing or re-uploading them is your own responsibility. Use it in line with your local laws and the terms of the services it connects to.")) { app.platform.openExternal("https://github.com/biolend83/tubehaus#disclaimer") } }
        if (q.isNotEmpty() && r.count == 0) item { Empty(tr("No setting matches \"{q}\"").replace("{q}", q)) }
    }
    when (dialog) {
        "region" -> {
            var code by remember { mutableStateOf(s.region) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(tr("Region")) },
                text = {
                    Column {
                        Text(tr("Two letters, like US, KR, DE. Empty means this device's region."), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(code, { code = it.filter(Char::isLetter).take(2).uppercase() }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { set { it.copy(region = code) }; app.rec.clearCache(); dialog = null }) { Text(tr("Save")) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
            )
        }
        "export" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("Export")) },
            text = {
                Column {
                    for ((fmt, label) in listOf(
                        "tubehaus" to tr("TubeHaus backup (everything)"),
                        "invidious" to tr("Invidious (subscriptions, history, playlists)"),
                        "csv" to tr("CSV subscriptions (YouTube / Google Takeout format)"),
                        "opml" to tr("OPML subscriptions (feed readers)"),
                    )) {
                        Text(label, Modifier.fillMaxWidth().clickable {
                            dialog = null
                            val (name, bytes) = Transfer.exportFile(app.store.lib.value, fmt)
                            val mime = when (fmt) { "csv" -> "text/csv"; "opml" -> "text/x-opml"; else -> "application/json" }
                            app.platform.saveFile(name, mime, bytes) { ok -> if (ok) app.toast(tr("Saved")) }
                        }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
        )
        "server" -> ServerDialog(app) { dialog = null }
        "clear" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("Clear all watch history?")) },
            text = { Text(tr("Resume points are cleared too. This can't be undone.")) },
            confirmButton = { TextButton(onClick = { app.store.clearHistory(); dialog = null }) { Text(tr("Clear")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
        )
    }
}

/** a grey explanation under a setting */
@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** Not connected: one line that opens [ServerDialog]. Connected: where, how the last sync went, sync now, disconnect. */
@Composable
private fun ServerItems(app: AppState, open: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    val problem by app.sync.problem.collectAsState()
    val at by app.sync.syncedAt.collectAsState()
    if (s.server.isEmpty()) {
        SItem(tr("Connect to a TubeHaus server"),
            tr("Share subscriptions, history and playlists with TubeHaus on your own server and your other devices"), open)
        return
    }
    Column {
        val status = when {
            !problem.isNullOrEmpty() -> serverProblem(problem!!)
            at > 0 -> tr("Synced at {t}").replace("{t}", Sys.shortTime(at))
            else -> tr("Syncing…")
        }
        SItem(tr("Connected to {s}").replace("{s}", s.server.substringAfter("://")), status, open)
        SItem(tr("Sync now"), tr("Changes on this device go to the server within seconds; changes elsewhere arrive when the app opens and every few minutes")) {
            app.sync.refreshSoon()
        }
        SItem(tr("Disconnect"), tr("This device keeps its copy of the library")) { app.sync.disconnect() }
    }
}

private fun serverProblem(code: String) = when (code) {
    "address" -> tr("That doesn't look like a web address")
    "login" -> tr("The user name or password is wrong")
    "public" -> tr("This is a public instance: it keeps libraries in each browser, so there is nothing to sync")
    "notTubeHaus" -> tr("No TubeHaus server answered there (or its login is a web page the app can't fill in: use TUBEHAUS_AUTH=basic)")
    else -> tr("Couldn't reach the server")
}

@Composable
private fun ServerDialog(app: AppState, close: () -> Unit) {
    val s = app.store.settings.value
    var address by remember { mutableStateOf(s.server) }
    var user by remember { mutableStateOf(s.serverUser) }
    var pass by remember { mutableStateOf(s.serverPass) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(tr("TubeHaus server")) },
        text = {
            Column {
                Text(tr("The address of your TubeHaus server (the web version). Your subscriptions, history and playlists from here are added to it, and from then on both stay the same."),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(address, { address = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    placeholder = { Text("tube.example.com") })
                Text(tr("If the server asks for a login:"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(tr("User name")) })
                PasswordField(app, pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 4.dp))
                error?.let { Text(serverProblem(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && address.isNotBlank(), onClick = {
                busy = true; error = null
                app.scope.launch {
                    val e = app.sync.connect(address, user, pass)
                    busy = false
                    if (e == null) { app.toast(tr("Connected: the library is shared with the server")); close() } else error = e
                }
            }) { Text(if (busy) tr("Connecting…") else tr("Connect")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(tr("Cancel")) } },
    )
}

/**
 * A password box that takes keys as typed: on the desktop the input method is off while it has
 * the focus (with a Korean one on, a letter only arrived with the next key), and phones show
 * their password keyboard, without suggestions.
 */
@Composable
private fun PasswordField(app: AppState, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        // after the text field has started its own input, which turns the input method back on
        if (focused) { delay(80); app.platform.plainKeys(true) } else app.platform.plainKeys(false)
    }
    DisposableEffect(Unit) { onDispose { app.platform.plainKeys(false) } }
    OutlinedTextField(value, onChange, modifier.onFocusChanged { focused = it.isFocused }, singleLine = true,
        placeholder = { Text(tr("Password")) }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
}

@Composable
private fun Group(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = Blue, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SItem(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { set(!on) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // an "off" switch has to read as a switch on dark and black themes too (it nearly vanished)
        Switch(on, set, enabled = enabled, colors = SwitchDefaults.colors(
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ))
    }
}

@Composable
private fun Choice(title: String, value: String, options: List<Pair<String, String>>, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SItem(title, value) { open = true }
        DropdownMenu(open, { open = false }) {
            for ((k, label) in options) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; pick(k) })
        }
    }
}

/** Everything the recommenders learned, and a way to change or wipe it. */
@Composable
fun LearnedScreen(app: AppState) {
    val t by app.store.taste.state.collectAsState()
    val st by app.store.settings.state.collectAsState()
    val parked by app.store.profiles.state.collectAsState()
    val names = remember(st.profile, parked) { Profiles.names(app.store) }
    var confirm by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Narrow {
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle(tr("Profiles")) }
            item {
                Text(
                    tr("Separate tastes, such as one for documentaries and one for music. What you watch teaches only the profile in use; switch it from Home."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(names, key = { "p:" + it }) { n ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(profileLabel(n), Modifier.weight(1f), maxLines = 1)
                    if (n == st.profile) Text(tr("In use"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp))
                    else TextButton(onClick = { Profiles.switch(app.store, n); app.rec.clearCache(); clearHome() }) { Text(tr("Use")) }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        TextButton(onClick = { menu = true }) {
                            androidx.compose.material3.Icon(Sym.MoreVert.fill, tr("More"), Modifier.size(18.dp))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Export")) }, onClick = {
                                menu = false
                                val (file, bytes) = Profiles.export(app.store, n)
                                app.platform.saveFile(file, "application/json", bytes) { ok -> if (ok) app.toast(tr("Saved")) }
                            })
                            if (n.isNotEmpty()) {
                                DropdownMenuItem(text = { Text(tr("Rename")) }, onClick = { menu = false; renaming = n })
                                DropdownMenuItem(text = { Text(tr("Delete")) }, onClick = { menu = false; deleting = n })
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { naming = true }) { Text(tr("New profile")) }
                    TextButton(onClick = {
                        app.platform.openTextFile { text ->
                            if (text != null && importProfile(app, text) == null) app.toast(tr("This file isn't a TubeHaus profile"))
                        }
                    }) { Text(tr("Import a profile")) }
                }
            }
            if (names.size > 1) item { SectionTitle(tr("Profile: {p}").replace("{p}", profileLabel(st.profile))) }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("What TubeHaus learned about you"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        tr("Worked out on this device from what you watch, how much of it, and what you like, skip or hide. Old tastes fade by themselves. Nothing here is sent anywhere."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { confirm = true }) { Text(tr("Start over")) }
                }
            }
            item { SectionTitle(tr("Channels")) }
            val ch = t.ch.entries.sortedByDescending { it.value }
            if (ch.isEmpty()) item { Empty(tr("Nothing yet")) }
            items(ch.take(100), key = { "c" + it.key }) { (cid, score) ->
                Score(t.names[cid] ?: cid, score, onClick = { app.go(Screen.Channel(cid, t.names[cid] ?: "")) }) {
                    app.store.taste.update { x -> x.copy(ch = x.ch - cid) }
                }
            }
            item { SectionTitle(tr("Topics")) }
            val kw = t.kw.entries.sortedByDescending { it.value }
            if (kw.isEmpty()) item { Empty(tr("Nothing yet")) }
            items(kw.take(150), key = { "k" + it.key }) { (k, score) ->
                Score(k, score, onClick = null) { app.store.taste.update { x -> x.copy(kw = x.kw - k) } }
            }
            if (t.hiddenChannels.isNotEmpty()) {
                item { SectionTitle(tr("Channels you hid")) }
                items(t.hiddenChannels, key = { "h$it" }) { cid ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.names[cid] ?: cid, Modifier.weight(1f))
                        TextButton(onClick = {
                            app.store.taste.update { x -> x.copy(hiddenChannels = x.hiddenChannels - cid, ch = x.ch - cid) }
                        }) { Text(tr("Show again")) }
                    }
                }
            }
        }
    }
    if (naming) NameDialog(tr("New profile"), "", { naming = false }) { name ->
        if (name in names) app.toast(tr("That name is already used"))
        else { Profiles.switch(app.store, name); app.rec.clearCache(); clearHome() }
    }
    renaming?.let { old ->
        NameDialog(tr("Rename"), old, { renaming = null }) { name ->
            if (name != old && !Profiles.rename(app.store, old, name)) app.toast(tr("That name is already used"))
        }
    }
    deleting?.let { n ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete the profile “{p}”?").replace("{p}", n)) },
            text = { Text(tr("What it learned is forgotten. Your library stays.")) },
            confirmButton = { TextButton(onClick = { Profiles.delete(app.store, n); app.rec.clearCache(); clearHome(); deleting = null }) { Text(tr("Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Forget everything TubeHaus learned?")) },
        text = { Text(tr("Your subscriptions, history and playlists stay.")) },
        confirmButton = { TextButton(onClick = { Learn.reset(app.store); app.rec.clearCache(); confirm = false }) { Text(tr("Start over")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun Score(label: String, score: Double, onClick: (() -> Unit)?, remove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1)
        Box(Modifier.width(100.dp).height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((abs(score) / 10).toFloat().coerceIn(0.02f, 1f)).height(6.dp).background(if (score >= 0) Blue else Red))
        }
        TextButton(onClick = remove) { Text(tr("Forget")) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}
