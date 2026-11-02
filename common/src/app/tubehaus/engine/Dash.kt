package app.tubehaus.engine

/**
 * Builds a DASH manifest from a player response's adaptive formats, so the player gets
 * adaptive quality switching for free. YouTube's streams are single files with an index
 * (SegmentBase: the init segment and the sidx/cues byte ranges), which is exactly what the
 * on-demand DASH profile describes.
 *
 * [codecs] limits the video to the codec families this device decodes well, best first;
 * [plays] leaves out sizes its decoder can't take (some TVs freeze on 8K rather than fail).
 */
object Dash {
    fun manifest(s: Stream, codecs: List<String>, maxHeight: Int = Int.MAX_VALUE, plays: (Format) -> Boolean = { true }): String {
        val usable = s.video.filter { it.indexRange != null && plays(it) }.ifEmpty { s.video.filter { it.indexRange != null }.sortedBy { it.height }.take(1) }
        val family = codecs.firstOrNull { c -> usable.any { it.codecFamily == c } }
        val video = usable.filter { it.codecFamily == family && it.height <= maxHeight }
            .ifEmpty { usable.filter { it.codecFamily == family }.sortedBy { it.height }.take(1) }
        val audio = s.audio.filter { it.indexRange != null }
        val dur = s.lengthSec.coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${dur}S">""")
        sb.append("<Period>")
        var set = 0
        for ((mime, list) in video.groupBy { it.mime }) {
            sb.append("""<AdaptationSet id="${set++}" contentType="video" mimeType="$mime" subsegmentAlignment="true" startWithSAP="1">""")
            for (f in list.sortedBy { it.bitrate }) {
                sb.append("""<Representation id="${f.itag}" codecs="${x(f.codecs)}" width="${f.width}" height="${f.height}" bandwidth="${f.bitrate}"""")
                if (f.fps > 0) sb.append(""" frameRate="${f.fps}"""")
                sb.append(">").append(base(f)).append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        // one adaptation set per container and audio language, so the player can pick a dub
        for ((key, list) in audio.groupBy { it.mime to (it.audioTrack ?: "") }) {
            val (mime, track) = key
            val lang = track.substringBefore('.').takeIf { it.isNotEmpty() }
            sb.append("""<AdaptationSet id="${set++}" contentType="audio" mimeType="$mime" subsegmentAlignment="true"""")
            if (lang != null) sb.append(""" lang="${x(lang)}"""")
            if (list.first().audioTrackName != null) sb.append(""" label="${x(list.first().audioTrackName!!)}"""")
            sb.append(">")
            sb.append("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="${if (list.first().audioDefault) "main" else "dub"}"/>""")
            for (f in list.sortedBy { it.bitrate }) {
                sb.append("""<Representation id="${f.itag}${if (track.isNotEmpty()) "-$track" else ""}" codecs="${x(f.codecs)}" bandwidth="${f.bitrate}" audioSamplingRate="${f.sampleRate}">""")
                sb.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${f.channels}"/>""")
                sb.append(base(f)).append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        sb.append("</Period></MPD>")
        return sb.toString()
    }

    private fun base(f: Format): String {
        val init = f.initRange
        val idx = f.indexRange!!
        return "<BaseURL>${x(f.url)}</BaseURL>" +
            """<SegmentBase indexRange="${idx.first}-${idx.last}">""" +
            (if (init != null) """<Initialization range="${init.first}-${init.last}"/>""" else "") +
            "</SegmentBase>"
    }

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
