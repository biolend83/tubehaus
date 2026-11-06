package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A TV or speaker on the local network that videos can be sent to (Google Cast). */
data class CastDevice(val id: String, val name: String, val model: String, val host: String, val port: Int)

/** An open connection to a [CastDevice], driven like the player on this device. */
interface CastLink : VideoPlayer {
    val device: CastDevice
    /** null while connected; why it ended when the device went away (switched off, another phone took over) */
    val lost: StateFlow<String?>
    /** leaves the device: what it plays stops, the connection closes */
    fun disconnect()
}

/** Finds Cast devices and connects to them (Android and the desktop; the iPad has AirPlay instead). */
interface Caster {
    val devices: StateFlow<List<CastDevice>>
    val scanning: StateFlow<Boolean>
    /** looks for devices for a few seconds; [devices] fills in as they answer */
    fun scan()
    suspend fun connect(d: CastDevice): CastLink
    /** casting to [d] now (null: stopped): Android keeps running meanwhile, with a notification */
    fun casting(d: CastDevice?) {}
}

/**
 * The player the app uses: this device's own, or a TV's while casting. Everything that drives
 * playback goes through this one, so the controls, the queue and the resume points work the
 * same either way.
 */
class SwitchPlayer(val local: VideoPlayer, scope: CoroutineScope) : VideoPlayer {
    val target = MutableStateFlow<VideoPlayer>(local)
    private val out = MutableStateFlow(local.state.value)
    override val state: StateFlow<PlayerState> = out

    init {
        scope.launch { target.collectLatest { p -> p.state.collect { out.value = it } } }
    }

    private val p get() = target.value
    override fun load(s: Stream, o: PlayOptions) = p.load(s, o)
    override fun play() = p.play()
    override fun pause() = p.pause()
    override fun seekTo(ms: Long) = p.seekTo(ms)
    override fun setSpeed(x: Float) = p.setSpeed(x)
    override fun setHeight(h: Int) = p.setHeight(h)
    override fun setCaptions(c: Caption?, translateTo: String?) = p.setCaptions(c, translateTo)
    override fun setAudioTrack(lang: String) = p.setAudioTrack(lang)
    override fun setLoop(on: Boolean) = p.setLoop(on)
    override fun setReadAhead(long: Boolean) = local.setReadAhead(long)
    override fun audioDevices() = local.audioDevices()
    override fun setAudioDevice(name: String) = local.setAudioDevice(name)
    override suspend fun frame() = p.frame()
    override fun stop() = p.stop()
    override val hasVolume: Boolean get() = p.hasVolume
    override fun setVolume(v: Float) = p.setVolume(v)
    override fun setMuted(on: Boolean) = p.setMuted(on)

    @Composable
    override fun View(modifier: Modifier) {
        val t by target.collectAsState()
        t.View(modifier)
    }
}

/** Casting as the app sees it: the device list, connecting, and handing the video over and back. */
class Casting(private val app: AppState) {
    val caster: Caster? get() = app.platform.caster
    var link by mutableStateOf<CastLink?>(null); private set
    var connecting by mutableStateOf<CastDevice?>(null); private set
    /** the list of devices is open */
    var picking by mutableStateOf(false)
    private var job: Job? = null

    fun open() {
        picking = true
        caster?.let { if (it.devices.value.isEmpty() || link == null) it.scan() }
    }

    fun connect(d: CastDevice) {
        val c = caster ?: return
        picking = false
        if (link?.device?.id == d.id) return
        job?.cancel()
        job = app.scope.launch {
            connecting = d
            val l = try {
                c.connect(d)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                connecting = null
                app.toast(tr("Couldn't connect to {d}").replace("{d}", d.name))
                return@launch
            }
            connecting = null
            val pos = app.player.state.value.positionMs
            link?.let { leave(it, resume = false) }
            link = l
            c.casting(d)
            app.player.target.value = l
            app.player.local.stop()
            app.playback.replay(pos)
            // until the device goes away by itself
            val why = l.lost.filterNotNull().first()
            if (link === l) {
                leave(l, resume = true)
                app.toast(tr("Lost the connection to {d}").replace("{d}", d.name) + " (" + tr(why) + ")")
            }
        }
    }

    /** back to this device, paused where the TV was */
    fun stop() { link?.let { leave(it, resume = true) } }

    private fun leave(l: CastLink, resume: Boolean) {
        if (link !== l) return
        val pos = l.state.value.positionMs
        link = null
        caster?.casting(null)
        app.player.target.value = app.player.local
        l.disconnect()
        if (resume) app.playback.replay(pos, autoplay = false)
    }
}

/** The cast button in the player's controls (AirPlay's on the iPad); nothing where neither exists */
@Composable
fun CastButton(app: AppState, tint: Color = Color.White, iconSize: Int = 24, poke: () -> Unit = {}) {
    val air = app.platform.airplay
    if (air != null) { air(Modifier.size(48.dp)); return }
    if (app.platform.caster == null || app.platform.tv) return
    val c = app.casting
    IconButton(onClick = { c.open(); poke() }) {
        if (c.connecting != null) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        else Icon(if (c.link != null) Sym.Cast.fill else Sym.Cast.outline, tr("Cast"), tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

/** The list of TVs on this Wi-Fi to cast to, and the way to stop casting */
@Composable
fun CastDialog(app: AppState) {
    val c = app.casting
    val caster = c.caster ?: return
    val devices by caster.devices.collectAsState()
    val scanning by caster.scanning.collectAsState()
    AlertDialog(
        onDismissRequest = { c.picking = false },
        title = { Text(tr("Cast to a TV")) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                c.link?.let { l ->
                    Text(
                        tr("Playing on {d}").replace("{d}", l.device.name),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                for (d in devices) {
                    val on = c.link?.device?.id == d.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { c.connect(d) }.padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (on) Sym.Cast.fill else Sym.Cast.outline, null, Modifier.size(24.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            if (d.model.isNotEmpty()) Text(d.model, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (scanning) {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Looking for TVs on this Wi-Fi..."), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (devices.isEmpty()) {
                    Text(tr("No TVs found. The TV (Chromecast, Google TV or a TV with Cast built in) has to be on the same Wi-Fi as this device."), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    tr("While casting, the video goes to the TV through this device: keep it on the same Wi-Fi, with TubeHaus open."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            if (c.link != null) TextButton(onClick = { c.stop(); c.picking = false }) { Text(tr("Stop casting")) }
            else TextButton(enabled = !scanning, onClick = { caster.scan() }) { Text(tr("Search again")) }
        },
        dismissButton = { TextButton(onClick = { c.picking = false }) { Text(tr("Close")) } },
    )
}

/** What the player area shows while the video plays on a TV */
@Composable
fun CastingView(d: CastDevice, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Sym.Cast.fill, null, tint = Color.White, modifier = Modifier.size(40.dp))
            Text(
                tr("Playing on {d}").replace("{d}", d.name), color = Color.White, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
