package app.tubehaus.engine

import kotlinx.serialization.json.JsonElement

/**
 * YouTube's "Most replayed": how often each slice of the video is watched again (0..1, equal slices
 * covering [spanMs]), and the peak YouTube labels. Empty for videos too new or too small to have one.
 */
data class Heat(val points: List<Float> = emptyList(), val spanMs: Long = 0, val peakMs: Long? = null)

/** read from the watch page's answer (/next): frameworkUpdates' markers list of type HEATMAP */
fun heatOf(next: JsonElement?): Heat {
    for (m in next.at("frameworkUpdates.entityBatchUpdate.mutations").arr) {
        val ml = m.at("payload.macroMarkersListEntity.markersList") ?: continue
        if (ml.at("markerType").str != "MARKER_TYPE_HEATMAP") continue
        var end = 0L
        val pts = ml.at("markers").arr.take(400).map { mk ->
            val s = mk.at("startMillis").long ?: 0
            val d = mk.at("durationMillis").long ?: 0
            end = maxOf(end, s + d)
            (mk.at("intensityScoreNormalized").str?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        }
        val peak = ml.at("markersDecoration.timedMarkerDecorations.0.decorationTimeMillis").long
        if (pts.size >= 10 && end > 0) return Heat(pts, end, peak)
    }
    return Heat()
}
