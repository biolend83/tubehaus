package app.tubehaus.engine

/**
 * A format's table of contents, from the first SABR piece of it: YouTube sends the header and the index together
 * (an mp4 sidx box, a WebM Cues element), so where every segment starts, in bytes and in time, is known exactly.
 * A byte a player asks for is then a segment, and a segment a time to ask SABR from: nothing is guessed.
 */
class SabrIndex(val offsets: LongArray, val startsMs: LongArray) {
    val size get() = offsets.size

    /** the segment holding byte [n] (-1 before the first) */
    fun segmentAt(n: Long): Int {
        var lo = 0; var hi = offsets.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (offsets[m] <= n) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    /** the start time of the segment holding byte [n] */
    fun timeOf(n: Long): Long = segmentAt(n).let { if (it < 0) 0 else startsMs[it] }

    companion object {
        /** the index in [head] (the header piece, from byte 0); null when it has none this reads */
        fun parse(head: ByteArray): SabrIndex? = runCatching { mp4(head) ?: webm(head) }.getOrNull()?.takeIf { it.size > 0 }

        private fun u32(b: ByteArray, p: Int): Long = ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

        private fun mp4(b: ByteArray): SabrIndex? {
            var o = 0
            while (o + 8 <= b.size) {
                val size = u32(b, o).toInt()
                val type = b.decodeToString(o + 4, o + 8)
                if (size < 8) return null
                if (type == "sidx") {
                    var p = o + 8
                    val version = b[p].toInt(); p += 4
                    p += 4                                   // reference id
                    val scale = u32(b, p); p += 4
                    val first: Long
                    var t: Long
                    if (version == 0) { t = u32(b, p); first = u32(b, p + 4); p += 8 }
                    else { t = (u32(b, p) shl 32) or u32(b, p + 4); first = (u32(b, p + 8) shl 32) or u32(b, p + 12); p += 16 }
                    p += 2
                    val n = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff); p += 2
                    var at = o + size + first                // offsets count from the end of the sidx box
                    val offs = LongArray(n); val ms = LongArray(n)
                    for (i in 0 until n) {
                        val ref = u32(b, p) and 0x7fffffff; val dur = u32(b, p + 4); p += 12
                        offs[i] = at; ms[i] = t * 1000 / scale
                        at += ref; t += dur
                    }
                    return SabrIndex(offs, ms)
                }
                o += size
            }
            return null
        }

        // ---------------------------------------------------------------- WebM (EBML)

        private class El(val id: Long, val data: Int, val end: Int)

        private fun vint(b: ByteArray, p: Int, keepMarker: Boolean): Pair<Long, Int> {
            val first = b[p].toInt() and 0xff
            var len = 1
            while (len <= 8 && first and (0x80 shr (len - 1)) == 0) len++
            var v = (if (keepMarker) first else first and (0xff shr len)).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[p + i].toLong() and 0xff)
            return v to len
        }

        private fun el(b: ByteArray, p: Int): El? {
            if (p >= b.size) return null
            val (id, il) = vint(b, p, true)
            val (size, sl) = vint(b, p + il, false)
            val data = p + il + sl
            val unknown = size == (1L shl (7 * sl)) - 1
            return El(id, data, if (unknown) b.size else minOf(b.size.toLong(), data + size).toInt())
        }

        private fun uint(b: ByteArray, e: El): Long { var v = 0L; for (i in e.data until e.end) v = (v shl 8) or (b[i].toLong() and 0xff); return v }

        private fun children(b: ByteArray, from: Int, to: Int) = sequence {
            var p = from
            while (p < to) { val e = el(b, p) ?: break; yield(e); if (e.end <= p) break; p = e.end }
        }

        private fun webm(b: ByteArray): SabrIndex? {
            val ebml = el(b, 0) ?: return null
            if (ebml.id != 0x1A45DFA3L) return null
            val seg = el(b, ebml.end) ?: return null
            if (seg.id != 0x18538067L) return null
            var scale = 1_000_000L
            val offs = ArrayList<Long>(); val ms = ArrayList<Long>()
            for (e in children(b, seg.data, b.size)) {
                when (e.id) {
                    0x1549A966L -> children(b, e.data, e.end).firstOrNull { it.id == 0x2AD7B1L }?.let { scale = uint(b, it) }
                    0x1C53BB6BL -> for (cp in children(b, e.data, e.end)) {
                        if (cp.id != 0xBBL) continue
                        var time = -1L; var pos = -1L
                        for (c in children(b, cp.data, cp.end)) {
                            if (c.id == 0xB3L) time = uint(b, c)
                            if (c.id == 0xB7L) children(b, c.data, c.end).firstOrNull { it.id == 0xF1L }?.let { pos = uint(b, it) }
                        }
                        if (time >= 0 && pos >= 0) { offs.add(seg.data + pos); ms.add(time * scale / 1_000_000) }
                    }
                }
            }
            return SabrIndex(offs.toLongArray(), ms.toLongArray())
        }
    }
}
