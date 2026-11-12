package app.tubehaus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Sys

/*
 * Kids mode: only videos from the channels a parent picked, an optional daily time limit, and a PIN to
 * leave it. While it is on, the Music and Shorts tabs and the comments are hidden (the switches as they
 * were come back when it is turned off), Home shows the picked channels' new videos, and Settings is
 * locked behind the PIN.
 */

/** whether a video or channel may be seen: always, unless kids mode is on and the channel isn't picked */
fun AppState.kidsAllow(channelId: String): Boolean {
    val s = store.settings.value
    return !s.kids || channelId in s.kidsChannels
}

/** kids mode with a daily limit, and today's watching has reached it */
fun AppState.kidsTimeUp(): Boolean {
    val s = store.settings.value
    if (!s.kids || s.kidsMinutes <= 0) return false
    val d = store.stats.value.days[Sys.today()] ?: return false
    return d.video + d.music >= s.kidsMinutes * 60L
}

/** turns kids mode on with these channels, PIN and limit, or off (the hidden tabs and comments as before) */
fun AppState.setKids(on: Boolean, pin: String = "", channels: List<String> = emptyList(), minutes: Int = 0) {
    store.settings.update { s ->
        if (on) s.copy(
            kids = true, kidsPin = pin, kidsChannels = channels, kidsMinutes = minutes,
            kidsSaved = listOf(s.hideMusicTab, s.hideShorts, s.hideComments),
            hideMusicTab = true, hideShorts = true, hideComments = true,
        ) else {
            val b = s.kidsSaved
            s.copy(
                kids = false, kidsPin = "",
                hideMusicTab = b.getOrElse(0) { s.hideMusicTab }, hideShorts = b.getOrElse(1) { s.hideShorts },
                hideComments = b.getOrElse(2) { s.hideComments }, kidsSaved = emptyList(),
            )
        }
    }
    if (on && playback.video?.channelId?.let { it !in channels } == true) playback.close()
}

/** a page kids mode keeps closed */
@Composable
fun KidsBlocked(app: AppState) {
    FeedOff(tr("Not in kids mode"), tr("This channel isn't one of the channels picked for kids mode.")) {
        Button(onClick = { app.go(Screen.Home) }) { Text(tr("Home")) }
    }
}

/** Settings while kids mode is on: what it allows, and the PIN to turn it off */
@Composable
fun KidsLock(app: AppState) {
    val s by app.store.settings.state.collectAsState()
    val stats by app.store.stats.state.collectAsState()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val today = stats.days[Sys.today()]?.let { (it.video + it.music) / 60 } ?: 0
    FeedOff(
        tr("Kids mode is on"),
        tr("Only the {n} chosen channels can be watched.").replace("{n}", "${s.kidsChannels.size}") + "\n" +
            (if (s.kidsMinutes > 0) tr("Today: {t} of {m} minutes").replace("{t}", "$today").replace("{m}", "${s.kidsMinutes}")
            else tr("Today: {t}").replace("{t}", minutesText(today))),
    ) {
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, singleLine = true,
            label = { Text(tr("PIN")) }, isError = wrong,
            supportingText = if (wrong) ({ Text(tr("Wrong PIN")) }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(
            onClick = { if (pin == s.kidsPin) app.setKids(false) else { wrong = true; pin = "" } },
            enabled = pin.length >= 4, modifier = Modifier.padding(top = 12.dp),
        ) { Text(tr("Turn off kids mode")) }
    }
}

/** Settings → Wellbeing → Kids mode: pick the channels from your subscriptions, a daily limit and a PIN */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KidsSetupDialog(app: AppState, close: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    val subs = remember(lib.subs) { lib.subs.entries.sortedBy { it.value.name.lowercase() } }
    val picked = remember { mutableStateOf(setOf<String>()) }
    var minutes by remember { mutableStateOf(60) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = picked.value.isNotEmpty() && pin.length >= 4 && pin == again
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Sym.Wellbeing.outline, null) },
        title = { Text(tr("Kids mode")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Only videos from the channels you pick can be watched. Music, Shorts and comments are hidden, and Settings needs the PIN."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("Channels"), style = MaterialTheme.typography.titleSmall)
                if (subs.isEmpty()) Text(tr("Subscribe to the channels first, then pick them here."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                else LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(subs, key = { it.key }) { (id, sub) ->
                        val on = id in picked.value
                        Row(Modifier.fillMaxWidth().clickable { picked.value = if (on) picked.value - id else picked.value + id }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { picked.value = if (on) picked.value - id else picked.value + id })
                            if (sub.thumb.isNotEmpty()) { Avatar(sub.thumb, 28); Spacer(Modifier.width(8.dp)) }
                            Text(sub.name.ifEmpty { id }, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(tr("Daily limit"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 30, 60, 90, 120)) FilterChip(minutes == m, { minutes = m },
                        { Text(if (m == 0) tr("No limit") else minutesText(m.toLong())) })
                }
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text(tr("PIN (4 to 8 digits)")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(again, { again = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text(tr("PIN again")) },
                    isError = again.isNotEmpty() && again != pin,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = {
            TextButton(onClick = { app.setKids(true, pin, picked.value.toList(), minutes); close(); app.go(Screen.Home) }, enabled = ok) {
                Text(tr("Turn on"))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Cancel")) } },
    )
}

const val KIDS_TIME_UP = "That's all for today: the watch time for kids mode is used up."
