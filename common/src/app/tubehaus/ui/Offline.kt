package app.tubehaus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.data.JsonFile
import app.tubehaus.engine.Format
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.oneDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** One downloaded file: the format it came from (with its index ranges) and where it lies. */
@Serializable
data class SavedFile(
    val itag: Int,
    val mime: String,
    val codecs: String,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val size: Long = 0,
    val init: List<Long>? = null,
    val index: List<Long>? = null,
    val sampleRate: Int = 0,
    val channels: Int = 2,
    /** relative to the offline folder */
    val file: String,
)

@Serializable
data class SavedVideo(
    val id: String,
    val title: String,
    val author: String = "",
    val channelId: String = "",
    val lengthSec: Int = 0,
    val video: SavedFile,
    val audio: SavedFile,
    val at: Long = 0,
) {
    val size: Long get() = video.size + audio.size
    fun asVideo() = Video(id, title, author, channelId, lengthSec)
}

@Serializable
data class OfflineIndex(val items: Map<String, SavedVideo> = emptyMap())

/** "123 MB", "1.4 GB" */
fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " GB"
    else -> "${(bytes shr 20).coerceAtLeast(1)} MB"
}

/** Which quality to save: asked once per download, the last choice is remembered. */
@Composable
fun OfflineDialog(app: AppState, v: Video) {
    val heights = listOf(2160, 1440, 1080, 720, 480, 360)
    var pick by remember { mutableStateOf(app.store.settings.value.offlineHeight) }
    AlertDialog(
        onDismissRequest = { app.offline.asking = null },
        title = { Text(tr("Download")) },
        text = {
            Column {
                Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp))
                for (h in heights) Row(
                    Modifier.fillMaxWidth().clickable { pick = h }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(pick == h, onClick = { pick = h })
                    Text(if (h == 360) "${h}p · " + tr("smallest") else "${h}p")
                }
                Text(tr("If the video isn't that sharp, the best it has is saved."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(tr("For your own viewing only. Sharing or re-uploading it is your own responsibility."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.store.settings.update { it.copy(offlineHeight = pick) }
                app.offline.asking = null
                app.offline.start(v, pick)
            }) { Text(tr("Download")) }
        },
        dismissButton = { TextButton(onClick = { app.offline.asking = null }) { Text(tr("Cancel")) } },
    )
}

/** Library → Downloads: what is being saved, and what is saved (it plays without internet). */
@Composable
fun DownloadsScreen(app: AppState) {
    val saved by app.offline.saved.collectAsState()
    val items = saved.items.values.sortedByDescending { it.at }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionTitle(tr("Downloads"))
            Text(
                if (items.isEmpty()) tr("Videos you download play here without internet.")
                else tr("{n} videos · {s} on this device").replace("{n}", "${items.size}").replace("{s}", sizeText(items.sumOf { it.size })),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        items(app.offline.pending.values.toList(), key = { "p" + it.id }) { v ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { app.offline.cancel(v.id) }) { Text(tr("Cancel")) }
                }
                val p = app.offline.progress[v.id] ?: 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(items, key = { "s" + it.id }) { s ->
            VideoRow(s.asVideo(), app, extra = listOf(tr("Remove download") to { app.offline.remove(s.id) }))
            Text(
                listOf("${s.video.height}p", sizeText(s.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
    }
}
