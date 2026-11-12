package app.tubehaus.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Sys
import kotlinx.coroutines.delay

/*
 * Save new videos by themselves: on a channel's page, "Save new videos" downloads each video the
 * channel puts out from then on (not the older ones), in the download quality from Settings, so they
 * are there to watch offline. Checked when the app opens and every half hour while it is open; never
 * on mobile data, and at most three a channel at a time.
 */

/** turns it on or off for a channel; on: only videos from now on */
fun AppState.setAutoSave(cid: String, on: Boolean) = store.settings.update { s ->
    if (on) s.copy(autoSave = s.autoSave + (cid to Sys.nowMs() / 1000))
    else s.copy(autoSave = s.autoSave - cid)
}

/** at the top of the app: the half-hourly look */
@Composable
fun AutoSaveRunner(app: AppState) {
    if (!app.platform.canDownload) return
    val any = app.store.settings.state.collectAsState().value.autoSave.isNotEmpty()
    LaunchedEffect(any) {
        if (!any) return@LaunchedEffect
        delay(20_000)              // after the app has settled
        while (true) {
            runCatching { autoSaveOnce(app) }
            delay(30 * 60_000L)
        }
    }
}

private suspend fun autoSaveOnce(app: AppState) {
    if (app.platform.metered()) return
    val s = app.store.settings.value
    for ((cid, since) in s.autoSave) {
        val fresh = runCatching { app.feed.channel(cid, 1800) }.getOrNull() ?: continue
        fresh.asSequence()
            .filter { it.publishedAt > since && !it.short && !it.live && !it.members }
            .filter { !app.offline.has(it.id) && !app.offline.downloading(it.id) }
            .sortedBy { it.publishedAt }.take(3)
            .forEach { app.offline.start(it, s.offlineHeight) }
    }
}

/** on a channel's page, beside Subscribe */
@Composable
fun AutoSaveButton(app: AppState, cid: String) {
    if (!app.platform.canDownload || cid.isEmpty()) return
    val on = cid in app.store.settings.state.collectAsState().value.autoSave
    val content: @Composable () -> Unit = {
        Icon(if (on) Sym.DownloadDone.outline else Sym.DownloadForOffline.outline, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (on) tr("Saving new videos") else tr("Save new videos"))
    }
    if (on) FilledTonalButton(onClick = { app.setAutoSave(cid, false); app.toast(tr("New videos won't be saved anymore")) },
        contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { content() }
    else OutlinedButton(onClick = { app.setAutoSave(cid, true); app.toast(tr("New videos from this channel will be saved to watch offline")) },
        contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { content() }
}
