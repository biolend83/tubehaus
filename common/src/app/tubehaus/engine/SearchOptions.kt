package app.tubehaus.engine

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * YouTube's search filters, as in its Filters panel: what kind of result, when it was uploaded,
 * how long it is, live only, and the order. [params] writes them the way YouTube's own search
 * sends them (a small protobuf message: sort = 1, filters = 2 {upload date = 1, type = 2,
 * duration = 3, live = 8}).
 */
data class SearchOptions(
    /** 0 any, 1 videos, 2 channels, 3 playlists, 4 movies */
    val type: Int = 0,
    /** 0 any, 1 last hour, 2 today, 3 this week, 4 this month, 5 this year */
    val date: Int = 0,
    /** 0 any, 1 under 4 minutes, 3 4-20 minutes, 2 over 20 minutes */
    val duration: Int = 0,
    /** 0 relevance, 2 upload date, 3 view count, 1 rating */
    val sort: Int = 0,
    val live: Boolean = false,
) {
    /** how many of the filters (other than the type) are set: shown on the Filters button */
    val count: Int get() = listOf(date != 0, duration != 0, sort != 0, live).count { it }

    @OptIn(ExperimentalEncodingApi::class)
    fun params(): String? {
        val f = ArrayList<Byte>()
        fun field(n: Int, v: Int) { if (v != 0) { f += (n shl 3).toByte(); f += v.toByte() } }
        field(1, date); field(2, type); field(3, duration); field(8, if (live) 1 else 0)
        val out = ArrayList<Byte>()
        if (sort != 0) { out += 0x08.toByte(); out += sort.toByte() }
        if (f.isNotEmpty()) { out += 0x12.toByte(); out += f.size.toByte(); out.addAll(f) }
        return if (out.isEmpty()) null else Base64.encode(out.toByteArray())
    }
}
