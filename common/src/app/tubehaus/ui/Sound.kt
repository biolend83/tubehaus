package app.tubehaus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tubehaus.data.Settings
import app.tubehaus.engine.Stream

/**
 * The sound: loudness matching (YouTube's own loudness number for each video turns a loud one down), and for
 * those who want them an equalizer, stereo width, balance and a night mode. The player applies a [SoundFx]
 * (Android: an audio processor in ExoPlayer, the desktop: mpv's filters); players that can't leave it.
 */
data class SoundFx(
    /** turned up (+) or down (-) by this many dB, before everything else */
    val gainDb: Float = 0f,
    /** one gain in dB per [SOUND_BANDS]; empty when the equalizer is off */
    val eq: List<Float> = emptyList(),
    /** 0 mono, 100 as recorded, 200 wider */
    val width: Int = 100,
    /** -100 all left .. 100 all right */
    val balance: Int = 0,
    /** quiet parts up, loud parts down */
    val night: Boolean = false,
) {
    val neutral: Boolean get() = gainDb == 0f && eq.all { it == 0f } && width == 100 && balance == 0 && !night
    /** headroom for what the equalizer raises, so a raised band doesn't clip */
    val headroomDb: Float get() = -(eq.maxOrNull() ?: 0f).coerceAtLeast(0f) * 0.75f
}

val SOUND_BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** the equalizer's presets (the same as the web's), in the order they are offered; "custom" is any other */
val SOUND_PRESETS: List<Pair<String, List<Float>>> = listOf(
    "flat" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    "bass" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
    "treble" to listOf(0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
    "vocal" to listOf(-2, -2, -1, 1, 3, 4, 3, 1, 0, -1),
    "pop" to listOf(-1, 1, 2, 3, 2, 0, -1, -1, 0, 1),
    "rock" to listOf(4, 3, 2, 0, -1, -1, 1, 2, 3, 4),
    "jazz" to listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
    "classical" to listOf(3, 2, 1, 0, 0, 0, -1, -1, 1, 2),
    "electronic" to listOf(5, 4, 1, 0, -2, 1, 0, 1, 4, 5),
    "acoustic" to listOf(3, 3, 2, 1, 1, 1, 2, 2, 2, 1),
    "hiphop" to listOf(5, 4, 2, 3, -1, -1, 1, 0, 1, 2),
    "speech" to listOf(-4, -3, -1, 1, 3, 4, 4, 2, 0, -2),
).map { (k, v) -> k to v.map { it.toFloat() } }

/** the English name of a preset (translated with tr) */
fun presetName(k: String): String = when (k) {
    "flat" -> "Flat"; "bass" -> "Bass boost"; "treble" -> "Treble boost"; "vocal" -> "Vocal"; "pop" -> "Pop"
    "rock" -> "Rock"; "jazz" -> "Jazz"; "classical" -> "Classical"; "electronic" -> "Electronic"; "acoustic" -> "Acoustic"
    "hiphop" -> "Hip-hop"; "speech" -> "Speech"; else -> "Custom"
}

/** the preset these gains are, else "custom" */
fun presetOf(gains: List<Float>): String = SOUND_PRESETS.firstOrNull { it.second == gains }?.first ?: "custom"

/** the equalizer's gains as set: ten values from -12 to 12 */
fun eqGains(set: Settings): List<Float> =
    set.eqGains.takeIf { it.size == SOUND_BANDS.size }?.map { it.coerceIn(-12f, 12f) } ?: List(SOUND_BANDS.size) { 0f }

/** what to play [s] with: its loudness turned down to YouTube's level, and the effects (music; videos when asked) */
fun soundFor(set: Settings, s: Stream?, music: Boolean): SoundFx {
    val loud = s?.loudnessDb
    val gain = if (set.soundNorm && loud != null && loud > 0f) -loud.coerceAtMost(20f) else 0f
    if (!music && !set.soundVideo) return SoundFx(gainDb = gain)
    return SoundFx(
        gainDb = gain,
        eq = if (set.eqOn) eqGains(set) else emptyList(),
        width = set.soundWidth.coerceIn(0, 200),
        balance = set.soundBalance.coerceIn(-100, 100),
        night = set.nightSound,
    )
}

/**
 * The sound panel: loudness matching, the equalizer (presets and ten bands) and the effects.
 * From Settings → Music and from the player's settings (music and videos).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
fun SoundDialog(app: AppState, close: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    fun set(f: (Settings) -> Settings) = app.store.settings.update(f)
    val can = app.platform.player.canSound
    val gains = eqGains(s)
    val preset = presetOf(gains)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        title = { androidx.compose.material3.Text(tr("Sound")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (!can) Text(tr("This player can't change the sound"), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                Switchy(tr("Loudness matching"), tr("Uses the loudness YouTube gives for each video: loud videos and songs are turned down so everything plays at a similar level"), s.soundNorm, can) { b -> set { it.copy(soundNorm = b) } }
                Switchy(tr("Equalizer"), "", s.eqOn, can) { b -> set { it.copy(eqOn = b) } }
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v) in SOUND_PRESETS) androidx.compose.material3.FilterChip(
                        selected = s.eqOn && preset == k, enabled = can,
                        onClick = { set { it.copy(eqOn = true, eqGains = v) } },
                        label = { Text(tr(presetName(k))) },
                    )
                    if (preset == "custom") androidx.compose.material3.FilterChip(selected = s.eqOn, onClick = { set { it.copy(eqOn = true) } }, label = { Text(tr("Custom")) }, enabled = can)
                }
                SOUND_BANDS.forEachIndexed { i, hz ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium)
                        androidx.compose.material3.Slider(
                            value = gains[i], valueRange = -12f..12f, steps = 23, enabled = can,
                            onValueChange = { g -> set { st -> st.copy(eqOn = true, eqGains = eqGains(st).toMutableList().also { it[i] = kotlin.math.round(g) }) } },
                            modifier = Modifier.weight(1f).height(36.dp),
                        )
                        Text((if (gains[i] > 0) "+" else "") + gains[i].toInt(), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Stereo width") + " · " + (if (s.soundWidth == 0) tr("Mono") else "${s.soundWidth}%"), style = MaterialTheme.typography.titleSmall)
                Text(tr("0 is mono, 100 as recorded, more is wider"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Slider(s.soundWidth.toFloat(), { w -> set { it.copy(soundWidth = (kotlin.math.round(w / 10) * 10).toInt()) } }, valueRange = 0f..200f, steps = 19, enabled = can)
                Text(tr("Balance") + " · " + when {
                    s.soundBalance == 0 -> tr("Center")
                    s.soundBalance < 0 -> tr("Left") + " " + -s.soundBalance
                    else -> tr("Right") + " " + s.soundBalance
                }, style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.Slider(s.soundBalance.toFloat(), { w -> set { it.copy(soundBalance = (kotlin.math.round(w / 10) * 10).toInt()) } }, valueRange = -100f..100f, steps = 19, enabled = can)
                Switchy(tr("Night mode"), tr("Quiet parts up, loud parts down. Good for listening softly at night"), s.nightSound, can) { b -> set { it.copy(nightSound = b) } }
                Switchy(tr("For videos too"), tr("Use the equalizer and effects for videos as well (off: music only)"), s.soundVideo, can) { b -> set { it.copy(soundVideo = b) } }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = close) { Text(tr("Done")) } },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                set { it.copy(soundNorm = true, soundVideo = false, eqOn = false, eqGains = emptyList(), soundWidth = 100, soundBalance = 0, nightSound = false) }
            }) { Text(tr("Reset")) }
        },
    )
}

@androidx.compose.runtime.Composable
private fun Switchy(title: String, sub: String, on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Switch) { set(it) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(on, null, enabled = enabled)
    }
}
