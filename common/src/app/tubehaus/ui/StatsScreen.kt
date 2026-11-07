package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.data.DayStats
import app.tubehaus.data.WatchStats
import app.tubehaus.engine.Sys

// ------------------------------------------------------------------ days as "2026-11-07"

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468
}

private fun civil(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + if (mp < 10) 3 else -9
    val y = yoe + era.toInt() * 400 + if (m <= 2) 1 else 0
    return Triple(y, m, d)
}

/** the day [n] days before [day] */
fun dayBefore(day: String, n: Int): String {
    val p = day.split('-').mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return day
    val (y, m, d) = civil(daysFromCivil(p[0], p[1], p[2]) - n)
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/** 0 Monday ... 6 Sunday */
private fun weekday(day: String): Int {
    val p = day.split('-').mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return 0
    return (((daysFromCivil(p[0], p[1], p[2]) % 7 + 7 + 3) % 7)).toInt()
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

private fun DayStats.total() = video + music

/** one month added up, for the recap */
class MonthSum(val month: String, val video: Long, val music: Long, val started: Int, val days: Int,
               val topChannels: List<Pair<String, Long>>, val topArtists: List<Pair<String, Long>>)

fun monthSums(st: WatchStats): List<MonthSum> =
    st.days.entries.groupBy { it.key.take(7) }.map { (m, ds) ->
        val ch = HashMap<String, Long>(); val art = HashMap<String, Long>()
        for ((_, d) in ds) { d.ch.forEach { (k, v) -> ch[k] = (ch[k] ?: 0) + v }; d.art.forEach { (k, v) -> art[k] = (art[k] ?: 0) + v } }
        MonthSum(m, ds.sumOf { it.value.video }, ds.sumOf { it.value.music }, ds.sumOf { it.value.n }, ds.count { it.value.total() > 0 },
            ch.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
            art.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value })
    }.sortedByDescending { it.month }

fun monthName(m: String): String {
    val i = m.drop(5).toIntOrNull() ?: return m
    return tr(MONTHS[(i - 1).coerceIn(0, 11)]) + " " + m.take(4)
}

private fun hm(sec: Long) = minutesText(sec / 60)

/** Settings → Wellbeing → Watch time: today, the last two weeks, who you watched, month by month. */
@Composable
fun StatsScreen(app: AppState) {
    val st by app.store.stats.state.collectAsState()
    val today = Sys.today()
    val last14 = (13 downTo 0).map { dayBefore(today, it) }
    val week = last14.takeLast(7).sumOf { st.days[it]?.total() ?: 0 }
    val month = (0 until 30).map { dayBefore(today, it) }.mapNotNull { st.days[it] }
    val ch = HashMap<String, Long>(); val art = HashMap<String, Long>()
    for (d in month) { d.ch.forEach { (k, v) -> ch[k] = (ch[k] ?: 0) + v }; d.art.forEach { (k, v) -> art[k] = (art[k] ?: 0) + v } }
    val c = MaterialTheme.colorScheme
    Narrow {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item { SectionTitle(tr("Watch time"), Modifier.padding(start = 0.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(tr("Today"), hm(st.days[today]?.total() ?: 0), Modifier.weight(1f))
                    StatTile(tr("Last 7 days"), hm(week), Modifier.weight(1f))
                    StatTile(tr("A day on average"), hm(week / 7), Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(12.dp)) {
                    Text(tr("The last two weeks"), style = MaterialTheme.typography.titleSmall)
                    val max = last14.maxOf { st.days[it]?.total() ?: 0 }.coerceAtLeast(60)
                    Row(Modifier.fillMaxWidth().height(140.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        for (day in last14) {
                            val d = st.days[day]
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                                val m = (d?.music ?: 0).toFloat() / max
                                val v = (d?.video ?: 0).toFloat() / max
                                if (m > 0) Box(Modifier.fillMaxWidth().weight(m.coerceAtLeast(0.001f), fill = true).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Teal))
                                if (v > 0) Box(Modifier.fillMaxWidth().weight(v.coerceAtLeast(0.001f), fill = true)
                                    .clip(if (m > 0) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Red))
                                val rest = 1f - m - v
                                if (rest > 0.001f) Spacer(Modifier.weight(rest))
                                if (m + v == 0f) Box(Modifier.fillMaxWidth().height(2.dp).background(c.outlineVariant))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in last14) Text(if (day == today) tr("Today") else tr(WEEKDAYS[weekday(day)]).take(3), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Red)); Text(" " + tr("Videos") + "   ", style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Teal)); Text(" " + tr("Music"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (ch.isNotEmpty()) {
                item { SectionTitle(tr("Channels you watched most (30 days)"), Modifier.padding(start = 0.dp)) }
                val top = ch.entries.sortedByDescending { it.value }.take(5)
                items(top, key = { "c" + it.key }) { (id, sec) ->
                    TopLine(st.names[id] ?: id, sec, top.first().value) { app.go(Screen.Channel(id, st.names[id] ?: "")) }
                }
            }
            if (art.isNotEmpty()) {
                item { SectionTitle(tr("Artists you listened to most (30 days)"), Modifier.padding(start = 0.dp)) }
                val top = art.entries.sortedByDescending { it.value }.take(5)
                items(top, key = { "a" + it.key }) { (name, sec) -> TopLine(name, sec, top.first().value, Teal) {} }
            }
            val months = monthSums(st)
            if (months.isNotEmpty()) {
                item { SectionTitle(tr("Recap"), Modifier.padding(start = 0.dp)) }
                items(months.take(12), key = { "m" + it.month }) { m -> MonthCard(m, st) }
            }
            if (st.days.isEmpty()) item { Empty(tr("Nothing counted yet. Watch time shows up here as you watch and listen.")) }
            item {
                Text(tr("Watch time is counted on this device only and is never sent anywhere. Pausing watch history pauses it too."),
                    Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                TextButton(onClick = { app.go(Screen.SettingsPage("wellbeing")) }) { Text(tr("Break reminders")) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun TopLine(name: String, sec: Long, max: Long, color: Color = Red, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(8.dp), onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Row {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hm(sec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((sec.toFloat() / max.coerceAtLeast(1)).coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
private fun MonthCard(m: MonthSum, st: WatchStats) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(14.dp)) {
        Text(monthName(m.month), style = MaterialTheme.typography.titleMedium)
        Text(tr("{t} in all · {v} videos, {m} music").replace("{t}", hm(m.video + m.music)).replace("{v}", hm(m.video)).replace("{m}", hm(m.music)),
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Text(tr("{n} started, on {d} days").replace("{n}", "${m.started}").replace("{d}", "${m.days}"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        if (m.topChannels.isNotEmpty()) Text(tr("Most watched: {x}").replace("{x}", m.topChannels.joinToString(", ") { st.names[it.first] ?: it.first }),
            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (m.topArtists.isNotEmpty()) Text(tr("Most listened: {x}").replace("{x}", m.topArtists.joinToString(", ") { it.first }),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
