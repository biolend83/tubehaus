package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/*
 * Nearby devices (Settings → Nearby devices, off until turned on): the TubeHaus apps on the same Wi-Fi
 * find each other and say what they are playing. From here you can carry on with what the TV is
 * playing, send the video you're watching to the computer, or use the phone as the TV's remote.
 * Everything stays on the home network (UDP to the other devices, nothing over the internet), and a
 * device only obeys another after you allowed that one on it, once.
 */

data class NearNow(val id: String, val title: String, val author: String, val posMs: Long, val lenMs: Long, val playing: Boolean)
data class NearDevice(val id: String, val name: String, val kind: String, val host: String, val port: Int, val now: NearNow?, val seenAt: Long)

/** what a platform supplies for it (Android and the desktop; none on iOS for now) */
interface Nearby {
    /** the devices heard lately (not this one) */
    val devices: StateFlow<List<NearDevice>>
    /** starts listening and saying hello; [message] gets the commands sent here, from a background thread */
    fun start(id: String, name: String, kind: String, message: (fromId: String, fromName: String, msg: Map<String, String>) -> Unit)
    fun stop()
    /** what this device plays now, sent with its next hello */
    fun announce(now: NearNow?)
    fun send(to: NearDevice, msg: Map<String, String>)
}

class NearbyHub(private val app: AppState) {
    private val net get() = app.platform.nearby
    private var running = false
    private var job: Job? = null
    /** a device not allowed yet asked for something: the question shown, and what it asked */
    var asking by mutableStateOf<Pair<String, String>?>(null)
    private var pending: Map<String, String>? = null
    private val none = MutableStateFlow<List<NearDevice>>(emptyList())
    val devices: StateFlow<List<NearDevice>> get() = net?.devices ?: none

    val available get() = net != null

    fun myId(): String {
        val s = app.store.settings.value
        if (s.nearId.isNotEmpty()) return s.nearId
        val id = (1..12).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
        app.store.settings.update { it.copy(nearId = id) }
        return id
    }

    fun kind() = when {
        app.platform.tv -> "tv"
        app.platform.name == "desktop" -> "pc"
        app.platform.name == "ios" -> "tablet"
        else -> "phone"
    }

    fun myName(): String {
        val s = app.store.settings.value
        if (s.nearName.isNotEmpty()) return s.nearName
        val base = when (kind()) { "tv" -> "TV"; "pc" -> "PC"; "tablet" -> "iPad"; else -> "Phone" }
        return base + " · " + myId().take(4).uppercase()
    }

    fun sync(on: Boolean) {
        val n = net ?: return
        if (on == running) return
        running = on
        if (!on) { job?.cancel(); n.stop(); return }
        n.start(myId(), myName(), kind()) { fromId, fromName, msg -> app.scope.launch { received(fromId, fromName, msg) } }
        job = app.scope.launch {
            while (true) {
                val v = app.playback.video
                val st = app.player.state.value
                n.announce(if (v == null || app.playback.music) null else NearNow(v.id, v.title, v.author, st.positionMs, st.durationMs, st.playing))
                delay(2500)
            }
        }
    }

    private fun received(fromId: String, fromName: String, msg: Map<String, String>) {
        if (fromId !in app.store.settings.value.nearAllowed) {
            pending = msg
            asking = fromId to fromName
            return
        }
        obey(msg)
    }

    fun answer(allow: Boolean) {
        val (id, _) = asking ?: return
        asking = null
        val msg = pending
        pending = null
        if (!allow) return
        app.store.settings.update { it.copy(nearAllowed = (it.nearAllowed + id).distinct()) }
        msg?.let(::obey)
    }

    private fun obey(m: Map<String, String>) {
        val p = app.player
        when (m["c"]) {
            "open" -> {
                val id = m["v"] ?: return
                if (!app.kidsAllow(m["ch"] ?: "")) return
                app.playback.open(Video(id, m["vt"] ?: "", m["va"] ?: "", m["ch"] ?: ""), startSec = (m["vp"]?.toLongOrNull() ?: 0L) / 1000)
                app.go(Screen.Watch(id))
            }
            "toggle" -> if (p.state.value.playing) p.pause() else p.play()
            "pause" -> p.pause()
            "play" -> p.play()
            "seek" -> m["vp"]?.toLongOrNull()?.let { p.seekTo(it.coerceAtLeast(0)) }
            "next" -> app.playback.skipNext()
        }
    }

    fun send(to: NearDevice, c: String, extra: Map<String, String> = emptyMap()) {
        net?.send(to, mapOf("c" to c) + extra)
    }

    /** plays [v] there from [posMs], and pauses it here */
    fun sendVideo(to: NearDevice, v: Video, posMs: Long) {
        send(to, "open", mapOf("v" to v.id, "vt" to v.title, "va" to v.author, "ch" to v.channelId, "vp" to "$posMs"))
        app.player.pause()
        app.toast(tr("Sent to {d}").replace("{d}", to.name))
    }

    /** carries on here with what [d] plays, and pauses it there */
    fun takeOver(d: NearDevice) {
        val now = d.now ?: return
        send(d, "pause")
        app.playback.open(Video(now.id, now.title, now.author), startSec = now.posMs / 1000)
        app.go(Screen.Watch(now.id))
    }
}

/** at the top of both apps: starts or stops it with the setting, and asks before obeying a new device */
@Composable
fun NearbyAsk(app: AppState) {
    val on = app.store.settings.state.collectAsState().value.nearby
    LaunchedEffect(on) { app.near.sync(on) }
    val ask = app.near.asking ?: return
    AlertDialog(
        onDismissRequest = { app.near.answer(false) },
        icon = { Icon(Sym.Cast.outline, null) },
        title = { Text(tr("Allow {d}?").replace("{d}", ask.second)) },
        text = { Text(tr("This device on your Wi-Fi wants to play videos and control the player here. Allow it from now on?")) },
        confirmButton = { TextButton(onClick = { app.near.answer(true) }) { Text(tr("Allow")) } },
        dismissButton = { TextButton(onClick = { app.near.answer(false) }) { Text(tr("Don't allow")) } },
    )
}

private fun kindIcon(kind: String) = when (kind) { "tv" -> Sym.LiveTv; "pc" -> Sym.GridView; else -> Sym.PlayCircle }

/** on Home: what the other devices are playing, to carry on here or to steer from here */
@Composable
fun NearbyBanner(app: AppState) {
    if (!app.store.settings.state.collectAsState().value.nearby) return
    val list by app.near.devices.collectAsState()
    val playing = list.filter { it.now != null && it.now.id != app.playback.video?.id }
    var remote by androidx.compose.runtime.remember { mutableStateOf<NearDevice?>(null) }
    val c = MaterialTheme.colorScheme
    val phone = LocalCompact.current
    for (d in playing.take(2)) {
        val now = d.now ?: continue
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (phone) 12.dp else 0.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
                .background(c.surfaceContainerHigh).padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Img(Video(now.id, now.title).thumb, Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(d.kind).outline, null, Modifier.size(15.dp), tint = accent)
                    Spacer(Modifier.width(5.dp))
                    Text(tr("Playing on {d}").replace("{d}", d.name), style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
                }
                Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(durationMs(now.posMs) + " / " + durationMs(now.lenMs), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            if (!phone) OutlinedButton(onClick = { remote = d }, modifier = Modifier.padding(end = 8.dp)) { Text(tr("Remote")) }
            Button(onClick = { app.near.takeOver(d) }) { Text(tr("Watch here")) }
        }
        if (phone) TextButton(onClick = { remote = d }, modifier = Modifier.padding(start = 12.dp)) { Text(tr("Use as remote")) }
    }
    remote?.let { d -> RemoteDialog(app, d.id) { remote = null } }
}

/** the other device's player, steered from here */
@Composable
fun RemoteDialog(app: AppState, id: String, close: () -> Unit) {
    val list by app.near.devices.collectAsState()
    val d = list.firstOrNull { it.id == id }
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(kindIcon(d?.kind ?: "").outline, null) },
        title = { Text(d?.name ?: tr("Not found")) },
        text = {
            val now = d?.now
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (d == null) Text(tr("This device isn't answering. Is TubeHaus open on it, with Nearby devices on?"))
                else if (now == null) Text(tr("Nothing is playing there."))
                else {
                    Text(now.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(durationMs(now.posMs) + " / " + durationMs(now.lenMs), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { app.near.send(d, "seek", mapOf("vp" to "${now.posMs - 10_000}")) }) { Icon(Sym.Replay.outline, tr("Back 10 seconds")) }
                        FilledTonalIconButton(onClick = { app.near.send(d, "toggle") }, modifier = Modifier.size(56.dp)) {
                            Icon(if (now.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr("Play or pause"), Modifier.size(30.dp))
                        }
                        FilledTonalIconButton(onClick = { app.near.send(d, "seek", mapOf("vp" to "${now.posMs + 10_000}")) }) {
                            Icon(Sym.Replay.outline, tr("Forward 10 seconds"), Modifier.graphicsLayer { scaleX = -1f })
                        }
                        FilledTonalIconButton(onClick = { app.near.send(d, "next") }) { Icon(Sym.SkipNext.outline, tr("Next")) }
                    }
                }
            }
        },
        confirmButton = {
            if (d?.now != null) TextButton(onClick = { app.near.takeOver(d); close() }) { Text(tr("Watch here")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Close")) } },
    )
}

/** under the video: send it to another device (it carries on there from here) */
@Composable
fun SendToDialog(app: AppState, v: Video, close: () -> Unit) {
    val list by app.near.devices.collectAsState()
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Sym.Cast.outline, null) },
        title = { Text(tr("Play on another device")) },
        text = {
            Column {
                if (list.isEmpty()) Text(tr("No other TubeHaus found on this Wi-Fi. Open TubeHaus there and turn on Nearby devices in its settings."))
                for (d in list) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tvClickable(RoundedCornerShape(12.dp)) {
                        app.near.sendVideo(d, v, app.player.state.value.positionMs); close()
                    }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(d.kind).outline, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text(d.now?.title ?: tr("Nothing playing"), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(tr("Close")) } },
    )
}

