// The people on one device: adding someone, and the PIN asked before switching to a person who set one
package app.tubehaus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tubehaus.data.Store

/** a person's name as shown ("" is the first person) */
fun personLabel(app: AppState, entry: String): String {
    val p = app.store.people.value
    return if (entry.isEmpty()) p.firstName.ifEmpty { tr("First person") } else Store.personName(entry)
}

/** switches to [id], asking for their PIN first when they set one; [done] after it switched */
@Composable
fun PersonSwitch(app: AppState, id: String?, cancel: () -> Unit, done: () -> Unit = {}) {
    if (id == null) return
    if (!app.store.hasPin(id) || id == app.store.people.value.current) {
        androidx.compose.runtime.LaunchedEffect(id) { app.switchPerson(id); done() }
        return
    }
    val who = app.store.people.value.names.firstOrNull { Store.personId(it) == id }?.let { personLabel(app, it) } ?: ""
    PinDialog(app, tr("PIN for {n}").replace("{n}", who), cancel) { pin ->
        if (app.store.pinOk(id, pin)) { app.switchPerson(id); done(); true } else false
    }
}

/** four to eight digits; [check] says whether it was right (wrong: the box empties and says so) */
@Composable
fun PinDialog(app: AppState, title: String, cancel: () -> Unit, check: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr("PIN")) }, isError = wrong,
                supportingText = if (wrong) ({ Text(tr("Wrong PIN")) }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { if (!check(pin)) { wrong = true; pin = "" } }) { Text(tr("OK")) }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(tr("Cancel")) } },
    )
}

/** a new PIN for the person using the app now, typed twice; or none, which takes theirs away */
@Composable
fun SetPinDialog(app: AppState, close: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val me = app.store.people.value.current
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("PIN for {n}").replace("{n}", personLabel(app, app.store.people.value.names.firstOrNull { Store.personId(it) == me } ?: ""))) },
        text = {
            Column {
                Text(tr("Asked before switching to you, so others can't open your library"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("PIN")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    again, { again = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("PIN again")) }, isError = again.length >= 4 && again != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4 && pin == again, onClick = { app.store.setPin(me, pin); app.toast(tr("PIN set")); close() }) { Text(tr("OK")) }
        },
        dismissButton = {
            if (app.store.hasPin(me)) TextButton(onClick = { app.store.setPin(me, null); app.toast(tr("PIN removed")); close() }) { Text(tr("Remove PIN")) }
            else TextButton(onClick = close) { Text(tr("Cancel")) }
        },
    )
}

/** a name for someone new; the first time, a name for the person whose library is here already too */
@Composable
fun AddPersonDialog(app: AppState, close: () -> Unit) {
    val p = app.store.people.value
    var name by remember { mutableStateOf("") }
    var first by remember { mutableStateOf(p.firstName) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Add a person")) },
        text = {
            Column {
                if (p.names.isEmpty()) {
                    Text(tr("Your name (the library on this device now)"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(first, { first = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                }
                Text(tr("Their name"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(30).replace(":", "") }, Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                if (p.names.isEmpty()) app.store.people.update { it.copy(firstName = first.trim()) }
                val id = app.store.addPerson(name.trim())
                close()
                app.switchPerson(id)
                app.toast(tr("Now using the app: {n}").replace("{n}", name.trim()))
            }) { Text(tr("Add")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Cancel")) } },
    )
}
