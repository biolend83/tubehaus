package app.tubehaus.engine

import kotlin.math.ceil
import kotlin.math.max

/**
 * HLS lists made from YouTube's adaptive mp4 formats, for players that play only HLS (AVPlayer on the
 * iPad): each format is an fMP4 file whose index (the sidx box at [Format.indexRange]) says where every
 * segment is and how long it plays, so a media list is that index written out with byte ranges
 * (EXT-X-BYTERANGE) into the same file, and the master list names the H.264 videos and AAC audio.
 */
object Hls {
    /** the formats AVPlayer plays: H.264 video and AAC audio in mp4, with the ranges the lists need */
    fun video(s: Stream) = s.video.filter { it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.initRange != null && it.indexRange != null }
        .sortedByDescending { it.height * 1000 + it.fps }
    fun audio(s: Stream) = s.audio.filter { it.mime == "audio/mp4" && it.codecs.startsWith("mp4a") && it.initRange != null && it.indexRange != null }

    fun playable(s: Stream) = video(s).isNotEmpty() && audio(s).isNotEmpty()

    /**
     * The master list. [media] gives the address of a format's media list. The first video named is the one
     * AVPlayer starts with: the one closest above 720p, so it starts sharp but quickly.
     */
    fun master(s: Stream, media: (Format) -> String): String {
        val videos = video(s)
        // one audio per track (dubbed videos have several), the best AAC of each
        val audios = audio(s).groupBy { it.audioTrack ?: "" }.values.map { g -> g.maxBy { it.bitrate } }
        val main = audios.firstOrNull { it.audioContent == "original" } ?: audios.firstOrNull { it.audioDefault } ?: audios.first()
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        for (a in audios) {
            val name = (a.audioTrackName ?: a.audioLang ?: "Audio").replace("\"", "'")
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"").append(name).append('"')
            a.audioLang?.let { sb.append(",LANGUAGE=\"").append(it).append('"') }
            sb.append(",DEFAULT=").append(if (a === main) "YES" else "NO").append(",AUTOSELECT=YES,CHANNELS=\"").append(a.channels).append('"')
            sb.append(",URI=\"").append(media(a)).append("\"\n")
        }
        val first = videos.filter { it.height >= 720 }.minByOrNull { it.height } ?: videos.first()
        for (v in listOf(first) + videos.filter { it !== first }) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(max(1, v.bitrate + main.bitrate))
            if (v.width > 0 && v.height > 0) sb.append(",RESOLUTION=").append(v.width).append('x').append(v.height)
            if (v.fps > 0) sb.append(",FRAME-RATE=").append(v.fps)
            sb.append(",CODECS=\"").append(v.codecs).append(',').append(main.codecs).append("\",AUDIO=\"a\"\n")
            sb.append(media(v)).append('\n')
        }
        return sb.toString()
    }

    /** a segment of a format: its bytes [offset, offset + size) play for [seconds] */
    class Segment(val offset: Long, val size: Long, val seconds: Double)

    /** the segments of format [f] from its index, [sidx] being the bytes of [Format.indexRange]; null when it isn't one */
    fun segments(f: Format, sidx: ByteArray): List<Segment>? {
        val index = f.indexRange ?: return null
        var p = 0
        fun u32(): Long { val v = ((sidx[p].toLong() and 0xff) shl 24) or ((sidx[p + 1].toLong() and 0xff) shl 16) or ((sidx[p + 2].toLong() and 0xff) shl 8) or (sidx[p + 3].toLong() and 0xff); p += 4; return v }
        fun u64(): Long { val hi = u32(); return (hi shl 32) or u32() }
        if (sidx.size < 32) return null
        var size = u32()
        if (u32() != 0x73696478L) return null          // "sidx"
        if (size == 1L) size = u64()
        val version = sidx[p].toInt(); p += 4             // version, flags
        u32()                                             // reference id
        val timescale = u32().takeIf { it > 0 } ?: return null
        val firstOffset: Long
        if (version == 0) { u32(); firstOffset = u32() } else { u64(); firstOffset = u64() }
        p += 2                                            // reserved
        val count = ((sidx[p].toInt() and 0xff) shl 8) or (sidx[p + 1].toInt() and 0xff); p += 2
        if (sidx.size < p + count * 12) return null
        var offset = index.last + 1 + firstOffset
        val out = ArrayList<Segment>(count)
        repeat(count) {
            val ref = u32()
            if (ref and 0x80000000L != 0L) return null      // an index of indexes: not what YouTube writes
            val bytes = ref and 0x7fffffffL
            val dur = u32()
            u32()                                         // SAP
            out.add(Segment(offset, bytes, dur.toDouble() / timescale))
            offset += bytes
        }
        return out
    }

    /** the media list of format [f] at [url] (where the player reads its bytes, with byte ranges) */
    fun media(f: Format, url: String, segs: List<Segment>): String {
        val init = f.initRange!!
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(ceil(segs.maxOfOrNull { it.seconds } ?: 1.0).toInt()).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-MAP:URI=\"").append(url).append("\",BYTERANGE=\"").append(init.last + 1 - init.first).append('@').append(init.first).append("\"\n")
        for (s in segs) {
            sb.append("#EXTINF:").append((kotlin.math.round(s.seconds * 1000) / 1000).toString()).append(",\n")
            sb.append("#EXT-X-BYTERANGE:").append(s.size).append('@').append(s.offset).append('\n')
            sb.append(url).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }
}
