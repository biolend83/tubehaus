package app.tubehaus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS plays YouTube's HLS through AVPlayer, which can't play the two separate files the other
 * platforms save, so there are no downloads here (yet). The same members as the JVM Offline,
 * so the shared screens compile unchanged; starting one only says so.
 */
class Offline(private val app: AppState) {
    val saved: StateFlow<OfflineIndex> = MutableStateFlow(OfflineIndex())
    val progress = mutableStateMapOf<String, Float>()
    val pending = mutableStateMapOf<String, Video>()
    var asking by mutableStateOf<Video?>(null)

    fun has(id: String) = false
    fun downloading(id: String) = false
    fun stream(id: String): Stream? = null

    fun start(v: Video, maxHeight: Int) {
        app.toast(tr("Downloads aren't available on iPad"))
    }

    fun cancel(id: String) {}
    fun remove(id: String) {}
}
