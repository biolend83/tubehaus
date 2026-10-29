package app.tubehaus.ui

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
import androidx.compose.runtime.Composable
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
import kotlin.math.abs

/** the parts under a video that Settings → Focus can hide one by one */
val UNDER_PARTS = listOf(
    "title" to "Title", "views" to "View count", "date" to "Upload date", "channel" to "Channel",
    "subscribers" to "Subscriber count", "subscribe" to "Subscribe button", "likes" to "Like and dislike",
    "later" to "Watch later", "save" to "Save to playlist", "share" to "Share", "download" to "Download",
    "next" to "Next", "description" to "Description",
)

const val VERSION = "0.5.5"

@Composable
fun SettingsScreen(app: AppState) {
    val s by app.store.settings.state.collectAsState()
    fun set(f: (Settings) -> Settings) = app.store.settings.update(f)
    var dialog by remember { mutableStateOf<String?>(null) }
    val heights = listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    fun hLabel(h: Int) = if (h == 0) tr("Best available") else "${h}p"
    Narrow {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Group(tr("General")) }
            item { Choice(tr("Language"), LANGS.firstOrNull { it.first == s.lang }?.second ?: "English", LANGS) { c -> set { it.copy(lang = c) } } }
            item {
                SItem(tr("Region"), s.region.ifEmpty { app.platform.deviceRegion() + " (" + tr("from this device") + ")" }) { dialog = "region" }
            }
            item {
                val themes = listOf("0" to tr("Follow the system"), "1" to tr("Light"), "2" to tr("Dark"), "3" to tr("Black (AMOLED)")) +
                    // Android 12+: Material You, the colours of the wallpaper
                    listOfNotNull(("4" to tr("Wallpaper colours")).takeIf { app.platform.wallpaperColors(false) != null })
                Choice(tr("Theme"), themes.getOrNull(s.dark)?.second ?: themes[0].second, themes) { c -> set { it.copy(dark = c.toInt()) } }
            }
            item {
                val sizes = listOf(1f to tr("Normal"), 1.15f to tr("Large"), 1.3f to tr("Larger"), 1.5f to tr("Largest"))
                Choice(tr("Text size"), sizes.firstOrNull { it.first == s.textScale }?.second ?: tr("Normal"),
                    sizes.map { "${it.first}" to it.second }) { c -> set { it.copy(textScale = c.toFloat()) } }
            }
            if (app.platform.name == "android") item {
                val modes = listOf("0" to tr("Automatic"), "1" to tr("On"), "2" to tr("Off"))
                Choice(tr("TV mode"), modes.getOrNull(s.tvMode)?.second ?: modes[0].second, modes) { c -> set { it.copy(tvMode = c.toInt()) } }
            }
            if (app.platform.touch) item {
                Toggle(tr("Hide the tabs while scrolling"), tr("The tabs and the top bar slide away as you scroll down"), s.autoHideNav) { b -> set { it.copy(autoHideNav = b) } }
            }
            if (app.platform.touch) item {
                Toggle(tr("Search box at the bottom"), tr("Within reach of your thumb"), s.searchAtBottom) { b -> set { it.copy(searchAtBottom = b) } }
            }
            if (app.platform.name == "android") item {
                Toggle(tr("Hide system bars"), tr("Hide the status and navigation bars everywhere. Swipe from the edge to see them."), s.hideBars) { b -> set { it.copy(hideBars = b) } }
            }
            item { Toggle(tr("Calm mode"), tr("No view counts, no Shorts, nothing plays by itself"), s.calm) { b -> set { it.copy(calm = b) } } }

            // one switch for each part of YouTube that pulls you in
            item { Group(tr("Focus")) }
            item { Toggle(tr("Hide thumbnails"), tr("Titles only, no pictures to click on"), s.hideThumbs) { b -> set { it.copy(hideThumbs = b) } } }
            item { Toggle(tr("Hide comments"), tr("No comments under videos"), s.hideComments) { b -> set { it.copy(hideComments = b) } } }
            item { Toggle(tr("Turn off the home feed"), tr("Home becomes a search page"), s.homeOff) { b -> set { it.copy(homeOff = b) } } }
            item { Toggle(tr("Turn off the subscription feed"), tr("Subscriptions shows your channels, not their new videos"), s.subsFeedOff) { b -> set { it.copy(subsFeedOff = b) } } }
            item { Toggle(tr("Hide Shorts"), tr("No Shorts tab, no Shorts in lists"), s.hideShorts || s.calm) { b -> set { it.copy(hideShorts = b) } } }
            item { Toggle(tr("Hide related videos"), tr("Nothing suggested next to or under a video"), s.hideRelated) { b -> set { it.copy(hideRelated = b) } } }
            // immersive mode: what is left under the video, down to just the player
            item { Group(tr("Immersive mode")) }
            item {
                Text(tr("Choose what shows under a video while you watch: everything, only the title, or just the player. With everything, switch off the parts you don't want one by one."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                val opts = listOf("0" to tr("Everything"), "1" to tr("Only the title"), "2" to tr("Nothing, just the player"))
                Choice(tr("Under the video"), opts.getOrNull(s.underVideo)?.second ?: opts[0].second, opts) { c -> set { it.copy(underVideo = c.toInt()) } }
            }
            // with "everything": each part on its own (grey while "nothing" or "only the title" is chosen)
            items(UNDER_PARTS) { (k, label) ->
                Box(Modifier.padding(start = 24.dp).alpha(if (s.underVideo == 0) 1f else 0.4f)) {
                    Toggle(tr(label), "", k !in s.hideUnder, enabled = s.underVideo == 0) { b ->
                        set { it.copy(hideUnder = if (b) it.hideUnder - k else (it.hideUnder + k).distinct()) }
                    }
                }
            }

            item { Group(tr("Playback")) }
            item { Choice(tr("Quality"), hLabel(s.maxHeight), heights.map { "$it" to hLabel(it) }) { c -> set { it.copy(maxHeight = c.toInt()) } } }
            if (app.platform.name == "android") item {
                Choice(tr("Quality on mobile data"), hLabel(s.maxHeightMobileData), heights.map { "$it" to hLabel(it) }) { c -> set { it.copy(maxHeightMobileData = c.toInt()) } }
            }
            item { Toggle(tr("Autoplay"), tr("Play the next video when one ends"), s.autoplay) { b -> set { it.copy(autoplay = b) } } }
            if (app.platform.touch) item {
                Toggle(tr("Tap the video to pause"), tr("A tap plays or pauses; the controls show with it"), s.tapToPause) { b -> set { it.copy(tapToPause = b) } }
            }
            item {
                Toggle(tr("Read further ahead"), tr("About 20 minutes of the video instead of 5, for fewer stops on a fast connection. Uses more memory") +
                    if (app.platform.name == "android") " " + tr("(from the next start)") else "", s.longBuffer) { b -> set { it.copy(longBuffer = b) } }
            }
            item {
                Choice(tr("Beside the video"), tr(if (s.sideTab == "comments") "Comments" else "Up next"),
                    listOf("next" to tr("Up next"), "comments" to tr("Comments"))) { c -> set { it.copy(sideTab = c) } }
            }
            item { Toggle(tr("Captions"), tr("Turn captions on when a video has them"), s.captions) { b -> set { it.copy(captions = b) } } }
            if (app.platform.name == "android") item {
                Toggle(tr("Background play"), tr("Keep playing with the screen off or in another app"), s.backgroundPlay) { b -> set { it.copy(backgroundPlay = b) } }
            }

            item { Group(tr("TubeHaus server")) }
            item { ServerItems(app) { dialog = "server" } }

            item { Group(tr("Your data")) }
            item { Toggle(tr("Pause watch history"), tr("Videos you watch aren't added to history and don't shape recommendations"), s.historyOff) { b -> set { it.copy(historyOff = b) } } }
            item { SItem(tr("What TubeHaus learned about you"), tr("See and change what your recommendations are based on")) { app.go(Screen.Learned) } }
            item { SItem(tr("Import"), tr("Subscriptions, history and playlists from YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML")) { importFile(app) } }
            item { SItem(tr("Manage subscriptions"), tr("Remove channels, or all of them")) { app.go(Screen.ManageSubs) } }
            item { SItem(tr("Export"), tr("A backup you can import here, into TubeHaus on a server, or into other apps")) { dialog = "export" } }
            item { SItem(tr("Clear watch history"), "") { dialog = "clear" } }

            item { Group(tr("About")) }
            item {
                Toggle(tr("Check for updates"), tr("Each time the app opens, on GitHub: a newer version, and fixes for when YouTube changes something. Nothing about you is sent."), s.checkUpdates) { b -> set { it.copy(checkUpdates = b) } }
            }
            item {
                SItem(tr("Check now"), tr("See if a newer version is out")) { app.checkForUpdates(now = true, quiet = false) }
            }
            item {
                SItem("TubeHaus $VERSION", tr("Everything happens on this device: it talks to YouTube directly, with no TubeHaus server, no account and no tracking. Your library and recommendations never leave it.")) {
                    app.platform.openExternal("https://github.com/biolend83/tubehaus/tree/android-app")
                }
            }
            item { SItem(tr("Source code and updates"), "github.com/biolend83/tubehaus") { app.platform.openExternal("https://github.com/biolend83/tubehaus/releases") } }
            item { SItem(tr("Disclaimer"), tr("TubeHaus is independent, free, open-source software, not affiliated with YouTube or Google. It doesn't host any videos and comes with no warranty. Downloaded videos are for your own viewing: sharing or re-uploading them is your own responsibility. Use it in line with your local laws and the terms of the services it connects to.")) { app.platform.openExternal("https://github.com/biolend83/tubehaus#disclaimer") } }
        }
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
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true,
                    placeholder = { Text(tr("Password")) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
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
    var confirm by remember { mutableStateOf(false) }
    Narrow {
        LazyColumn(Modifier.fillMaxSize()) {
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
