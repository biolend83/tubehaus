package app.tubehaus.ui

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A saved video's two files (YouTube's DASH: picture and sound apart) put into one file that any
 * player opens, without re-encoding and without ffmpeg: fragmented MP4 + M4A into one MP4, WebM +
 * WebM into one WebM. The pieces are copied as they are and laid out in time order.
 */
object Mux {
    fun join(video: File, audio: File, out: File) {
        val webm = video.name.endsWith(".webm")
        val tmp = File(out.path + ".part")
        try {
            tmp.outputStream().buffered(1 shl 20).use { o -> if (webm) webm(video, audio, o) else mp4(video, audio, o) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) error("rename")
        } finally { tmp.delete() }
    }

    private fun RandomAccessFile.bytes(at: Long, n: Int): ByteArray { seek(at); return ByteArray(n).also { readFully(it) } }

    private fun copy(f: RandomAccessFile, at: Long, len: Long, o: OutputStream) {
        f.seek(at)
        val buf = ByteArray(1 shl 16)
        var left = len
        while (left > 0) {
            val n = f.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) error("short file")
            o.write(buf, 0, n); left -= n
        }
    }

    private fun u32(b: ByteArray, i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
    private fun put32(b: ByteArray, i: Int, v: Long) { for (k in 0..3) b[i + k] = (v shr (24 - 8 * k)).toByte() }
    private fun u64(b: ByteArray, i: Int) = (u32(b, i) shl 32) or u32(b, i + 4)

    // ------------------------------------------------------------------ MP4

    private class Box(val type: String, val at: Long, val size: Long, val head: Int)

    private fun boxes(f: RandomAccessFile, from: Long, to: Long): List<Box> {
        val l = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val h = f.bytes(p, 8)
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L) { size = u64(f.bytes(p + 8, 8), 0); head = 16 } else if (size == 0L) size = to - p
            if (size < head) break
            l += Box(type, p, size, head); p += size
        }
        return l
    }

    /** children of a box held in memory: (type, start, end) */
    private fun kids(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val l = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(b, p).toInt()
            if (size < 8 || p + size > to) break
            l += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p, p + size); p += size
        }
        return l
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val n = 8 + parts.sumOf { it.size }
        val b = ByteArray(n); put32(b, 0, n.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(b, 4)
        var p = 8
        for (x in parts) { x.copyInto(b, p); p += x.size }
        return b
    }

    private class Track(val f: RandomAccessFile, val trak: ByteArray, val trex: ByteArray, val scale: Long, val frags: List<Pair<Box, Box>>)

    private fun track(f: RandomAccessFile, id: Int): Pair<Track, ByteArray> {
        val top = boxes(f, 0, f.length())
        val moovBox = top.first { it.type == "moov" }
        val moov = f.bytes(moovBox.at, moovBox.size.toInt())
        val mk = kids(moov, 8, moov.size)
        val trakR = mk.first { it.first == "trak" }
        val trak = moov.copyOfRange(trakR.second, trakR.third)
        val mvhdR = mk.first { it.first == "mvhd" }
        val mvhd = moov.copyOfRange(mvhdR.second, mvhdR.third)
        val mvexR = mk.first { it.first == "mvex" }
        val trexR = kids(moov, mvexR.second + 8, mvexR.third).first { it.first == "trex" }
        val trex = moov.copyOfRange(trexR.second, trexR.third)
        // tkhd and trex take the new track number
        for ((t, s, _) in kids(trak, 8, trak.size)) if (t == "tkhd") put32(trak, s + 12 + if (trak[s + 8].toInt() == 1) 16 else 8, id.toLong())
        put32(trex, 12, id.toLong())
        var scale = 1000L
        for ((t, s, e) in kids(trak, 8, trak.size)) if (t == "mdia") for ((t2, s2, _) in kids(trak, s + 8, e)) if (t2 == "mdhd")
            scale = u32(trak, s2 + 12 + if (trak[s2 + 8].toInt() == 1) 16 else 8)
        val frags = ArrayList<Pair<Box, Box>>()
        var moof: Box? = null
        for (b in top) when (b.type) {
            "moof" -> moof = b
            "mdat" -> moof?.let { frags += it to b; moof = null }
        }
        return Track(f, trak, trex, scale, frags) to mvhd
    }

    private fun mp4(video: File, audio: File, o: OutputStream) {
        RandomAccessFile(video, "r").use { vf -> RandomAccessFile(audio, "r").use { af ->
            val (v, mvhd) = track(vf, 1)
            val (a, _) = track(af, 2)
            put32(mvhd, mvhd.size - 4, 3) // next_track_ID
            val ftyp = boxes(vf, 0, vf.length()).first { it.type == "ftyp" }
            o.write(vf.bytes(ftyp.at, ftyp.size.toInt()))
            o.write(box("moov", mvhd, v.trak, a.trak, box("mvex", v.trex, a.trex)))
            // each fragment with its start in seconds, both tracks in time order
            class Frag(val t: Track, val moof: Box, val mdat: Box, val time: Double)
            fun times(t: Track) = t.frags.map { (moof, mdat) ->
                val m = t.f.bytes(moof.at, moof.size.toInt())
                var time = 0L
                for ((k, s, e) in kids(m, 8, m.size)) if (k == "traf") for ((k2, s2, _) in kids(m, s + 8, e)) if (k2 == "tfdt")
                    time = if (m[s2 + 8].toInt() == 1) u64(m, s2 + 12) else u32(m, s2 + 12)
                Frag(t, moof, mdat, time.toDouble() / t.scale)
            }
            var seq = 1L
            for (fr in (times(v) + times(a)).sortedBy { it.time }) {
                val m = fr.t.f.bytes(fr.moof.at, fr.moof.size.toInt())
                val id = if (fr.t === v) 1L else 2L
                for ((k, s, e) in kids(m, 8, m.size)) {
                    if (k == "mfhd") put32(m, s + 12, seq++)
                    if (k == "traf") for ((k2, s2, _) in kids(m, s + 8, e)) if (k2 == "tfhd") {
                        // offsets from the file's start would point into the old file
                        if (m[s2 + 11].toInt() and 1 != 0) error("base-data-offset")
                        put32(m, s2 + 12, id)
                    }
                }
                o.write(m)
                copy(fr.t.f, fr.mdat.at, fr.mdat.size, o)
            }
        } }
    }

    // ------------------------------------------------------------------ WebM

    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val ENTRY = 0xAEL
    private const val NUMBER = 0xD7L
    private const val UID = 0x73C5L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMESTAMP = 0xE7L
    private const val SIMPLE = 0xA3L
    private const val GROUP = 0xA0L
    private const val BLOCK = 0xA1L

    /** an element: id, where its data starts, its data length (-1 unknown), where it ends */
    private class El(val id: Long, val at: Long, val data: Long, val len: Long) { val end get() = data + len }

    private fun vint(f: RandomAccessFile, p: Long, keepMarker: Boolean): Pair<Long, Int> {
        f.seek(p)
        val first = f.read()
        var n = 1
        while (n <= 8 && first and (0x80 shr (n - 1)) == 0) n++
        var v = if (keepMarker) first.toLong() else (first and (0xff shr n)).toLong()
        var ones = (first and (0xff shr n)) == (0xff shr n)
        for (i in 1 until n) { val b = f.read(); v = (v shl 8) or b.toLong(); if (b != 0xff) ones = false }
        return (if (!keepMarker && ones) -1L else v) to n
    }

    private fun el(f: RandomAccessFile, p: Long): El {
        val (id, a) = vint(f, p, true)
        val (len, b) = vint(f, p + a, false)
        return El(id, p, p + a + b, len)
    }

    private fun children(f: RandomAccessFile, from: Long, to: Long): List<El> {
        val l = ArrayList<El>()
        var p = from
        while (p < to) {
            val e = el(f, p)
            if (e.len < 0) { l += El(e.id, e.at, e.data, to - e.data); break }
            l += e; p = e.end
        }
        return l
    }

    private fun idBytes(id: Long): ByteArray {
        val n = when { id > 0xFFFFFF -> 4; id > 0xFFFF -> 3; id > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
    }
    private fun sizeBytes(len: Long): ByteArray {
        val b = ByteArray(8); b[0] = 0x01
        for (i in 1..7) b[i] = (len shr (8 * (7 - i))).toByte()
        return b
    }
    private fun ebml(id: Long, data: ByteArray) = idBytes(id) + sizeBytes(data.size.toLong()) + data

    /** a TrackEntry with its number and UID changed */
    private fun entry(f: RandomAccessFile, e: El, number: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in children(f, e.data, e.end)) when (c.id) {
            NUMBER -> out.write(ebml(NUMBER, byteArrayOf(number.toByte())))
            UID -> out.write(ebml(UID, byteArrayOf(number.toByte())))
            else -> out.write(f.bytes(c.at, (c.end - c.at).toInt()))
        }
        return ebml(ENTRY, out.toByteArray())
    }

    private class Clu(val f: RandomAccessFile, val e: El, val time: Long, val number: Int)

    private fun webm(video: File, audio: File, o: OutputStream) {
        RandomAccessFile(video, "r").use { vf -> RandomAccessFile(audio, "r").use { af ->
            fun parts(f: RandomAccessFile, number: Int): Triple<El, List<El>, ByteArray> {
                val top = children(f, 0, f.length())
                val seg = top.first { it.id == SEGMENT }
                val kids = children(f, seg.data, seg.end)
                val tracks = kids.first { it.id == TRACKS }
                val entryEl = children(f, tracks.data, tracks.end).first { it.id == ENTRY }
                return Triple(top.first(), kids, entry(f, entryEl, number))
            }
            val (head, vk, ve) = parts(vf, 1)
            val (_, ak, ae) = parts(af, 2)
            o.write(vf.bytes(head.at, (head.end - head.at).toInt()))
            o.write(idBytes(SEGMENT)); o.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)) // size unknown
            vk.firstOrNull { it.id == INFO }?.let { o.write(vf.bytes(it.at, (it.end - it.at).toInt())) }
            o.write(ebml(TRACKS, ve + ae))
            fun clusters(f: RandomAccessFile, k: List<El>, n: Int) = k.filter { it.id == CLUSTER }.map { c ->
                val t = children(f, c.data, c.end).firstOrNull { it.id == TIMESTAMP }?.let { ts ->
                    var v = 0L; f.seek(ts.data); repeat(ts.len.toInt()) { v = (v shl 8) or f.read().toLong() }; v
                } ?: 0L
                Clu(f, c, t, n)
            }
            for (c in (clusters(vf, vk, 1) + clusters(af, ak, 2)).sortedBy { it.time }) {
                val start = c.e.data
                val b = c.f.bytes(start, c.e.len.toInt())
                // every block's track number: one byte (0x81, 0x82), so it changes in place
                fun mark(from: Long, to: Long) {
                    for (x in children(c.f, from, to)) when (x.id) {
                        SIMPLE, BLOCK -> b[(x.data - start).toInt()] = (0x80 or c.number).toByte()
                        GROUP -> mark(x.data, x.end)
                    }
                }
                mark(start, start + c.e.len)
                o.write(ebml(CLUSTER, b))
            }
        } }
    }
}
