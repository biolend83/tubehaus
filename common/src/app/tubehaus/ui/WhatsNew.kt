package app.tubehaus.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Remote

/**
 * What each version changed, in the app: the "What's new" list of its release on GitHub. Shown
 * once after an update (the versions since the last one opened) and from Settings → About.
 */
@Composable
fun WhatsNewDialog(app: AppState, notes: List<Remote.Notes>) {
    AlertDialog(
        onDismissRequest = { app.whatsNew = null },
        title = { Text(tr("What's new")) },
        text = {
            if (notes.isEmpty()) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(notes, key = { it.version }) { n ->
                    // focusable: a TV remote moves through the versions, and the list scrolls with it
                    Column(Modifier.fillMaxWidth().focusable().padding(bottom = 16.dp)) {
                        Text("TubeHaus ${n.version}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                        NoteLines(n.lines)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { app.whatsNew = null }) { Text(tr("Close")) } },
        dismissButton = {
            TextButton(onClick = { app.platform.openExternal("https://github.com/biolend83/tubehaus/releases"); app.whatsNew = null }) { Text(tr("All releases")) }
        },
    )
}

/** a release's notes: "- " lines as bullets, "  - " as smaller ones under them, **bold** as bold */
@Composable
fun NoteLines(lines: List<String>) {
    Column {
        for (line in lines) {
            val sub = line.startsWith("  - ")
            val bullet = sub || line.startsWith("- ")
            val text = when { sub -> line.drop(4); bullet -> line.drop(2); else -> line }
            Text(
                bold(if (bullet) (if (sub) "◦ " else "• ") + text else text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = if (sub) 28.dp else if (bullet) 8.dp else 0.dp, bottom = 6.dp),
            )
        }
    }
}

private fun bold(s: String): AnnotatedString = buildAnnotatedString {
    s.split("**").forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}
