package app.tubehaus.engine

/**
 * Search refinements YouTube's search doesn't offer: fewest views first, oldest first, and a view
 * count range. They work on the results loaded so far (YouTube still does the finding), so they
 * keep videos only. (From Vildor Pierre's fork of TubeHaus.)
 */
data class SearchRefine(
    /** 0 none, 1 fewest views first, 2 oldest first */
    val sort: Int = 0,
    val minViews: Long? = null,
    val maxViews: Long? = null,
) {
    val ranged: Boolean get() = minViews != null || maxViews != null
    val active: Boolean get() = sort != 0 || ranged
    /** how many are set: added to the count on the Filters button */
    val count: Int get() = listOf(sort != 0, ranged).count { it }

    /** [items] with these refinements applied; [nowSec] = epoch seconds, to read "3 days ago" */
    fun apply(items: List<Item>, nowSec: Long): List<Item> {
        if (!active) return items
        val out = items.filterIsInstance<Video>().filter { v ->
            if (!ranged) return@filter true
            val n = views(v) ?: return@filter false
            (minViews == null || n >= minViews) && (maxViews == null || n <= maxViews)
        }
        if (sort == 0) return out
        // an unknown count or date goes last
        val key: (Video) -> Long? = if (sort == 1) ::views else { v -> uploaded(v, nowSec) }
        return out.sortedWith { a, b ->
            val x = key(a); val y = key(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> x.compareTo(y)
            }
        }
    }

    companion object {
        /** the view count when the card says it ("No views" is 0); null when it doesn't */
        fun views(v: Video): Long? = when {
            v.views > 0 -> v.views
            v.viewsText.isNotBlank() && v.viewsText.none { it.isDigit() } -> 0
            else -> null
        }

        /** when it was uploaded (epoch seconds), from the exact time or the card's "3 days ago" */
        fun uploaded(v: Video, nowSec: Long): Long? =
            v.publishedAt.takeIf { it > 0 } ?: ago(v.publishedText)?.let { nowSec - it }

        // (unit, seconds); a unit matches the start of the word after the number. Short Latin ones
        // only match the whole word ("an" in French, "ay" in Turkish)
        private val units = listOf(
            "second" to 1L, "segundo" to 1L, "seconde" to 1L, "sekunde" to 1L, "секунд" to 1L, "detik" to 1L, "saniye" to 1L,
            "초" to 1L, "秒" to 1L,
            "minute" to 60L, "minuto" to 60L, "minut" to 60L, "минут" to 60L, "menit" to 60L, "dakika" to 60L,
            "분" to 60L, "分" to 60L,
            "hour" to 3600L, "hora" to 3600L, "heure" to 3600L, "stunde" to 3600L, "час" to 3600L, "jam" to 3600L, "saat" to 3600L,
            "시간" to 3600L, "時間" to 3600L, "小时" to 3600L, "小時" to 3600L,
            "day" to 86400L, "día" to 86400L, "dia" to 86400L, "jour" to 86400L, "tag" to 86400L, "день" to 86400L, "дн" to 86400L,
            "дня" to 86400L, "hari" to 86400L, "gün" to 86400L, "일" to 86400L, "日" to 86400L, "天" to 86400L,
            "week" to 604800L, "semana" to 604800L, "semaine" to 604800L, "woche" to 604800L, "недел" to 604800L, "minggu" to 604800L,
            "hafta" to 604800L, "주" to 604800L, "週" to 604800L, "周" to 604800L,
            "month" to 2592000L, "mes" to 2592000L, "mês" to 2592000L, "mois" to 2592000L, "monat" to 2592000L, "месяц" to 2592000L,
            "bulan" to 2592000L, "ay" to 2592000L, "개월" to 2592000L, "달" to 2592000L, "か月" to 2592000L, "ヶ月" to 2592000L,
            "ヵ月" to 2592000L, "个月" to 2592000L, "個月" to 2592000L,
            "year" to 31536000L, "año" to 31536000L, "ano" to 31536000L, "an" to 31536000L, "ans" to 31536000L, "jahr" to 31536000L,
            "год" to 31536000L, "лет" to 31536000L, "tahun" to 31536000L, "yıl" to 31536000L, "년" to 31536000L, "年" to 31536000L,
        )
        private val number = Regex("""(\d+)\s*([^\s\d]+)""")

        /** "3 days ago", "3일 전", "vor 3 Tagen", "3日前"... → seconds; null when it can't be read */
        fun ago(text: String): Long? {
            for (m in number.findAll(text.lowercase())) {
                val n = m.groupValues[1].toLongOrNull() ?: continue
                val word = m.groupValues[2]
                val unit = units.firstOrNull { (u, _) ->
                    word == u || (word.startsWith(u) && (u.length >= 3 || u[0].code >= 0x2E80 || u[0] in 'а'..'я'))
                } ?: continue
                return n * unit.second
            }
            return null
        }
    }
}
