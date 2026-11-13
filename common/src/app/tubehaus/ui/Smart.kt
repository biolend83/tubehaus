package app.tubehaus.ui

import app.tubehaus.engine.isWordChar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Chapter
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.Vtt
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * "This video at a glance": made on this device from the video's captions, nothing is sent anywhere.
 * The words the video keeps coming back to, the few moments that say the most (a tap jumps there),
 * and, for a long video without chapters, chapters of its own that the seek bar then shows.
 * Plain counting (how often a word comes up here and how rarely elsewhere in the video), no AI model.
 */

object Smart {
    data class Moment(val ms: Long, val text: String)
    data class Result(val keywords: List<String>, val moments: List<Moment>, val chapters: List<Chapter>)

    private val STOP = setOf(
        "the", "and", "for", "you", "that", "this", "with", "are", "was", "but", "not", "have", "has", "had", "they", "them",
        "his", "her", "she", "him", "its", "it's", "our", "your", "from", "what", "when", "where", "which", "who", "how",
        "all", "any", "can", "could", "would", "should", "will", "just", "like", "so", "um", "uh", "yeah", "okay", "ok",
        "really", "very", "about", "there", "here", "then", "than", "into", "out", "get", "got", "going", "gonna", "know",
        "think", "one", "two", "also", "some", "more", "now", "well", "right", "because", "been", "being", "were", "did",
        "does", "doing", "don't", "i'm", "we're", "you're", "that's", "let's", "there's", "music", "applause", "laughter",
        "그리고", "그래서", "그런데", "하지만", "그냥", "진짜", "정말", "이제", "이거", "저거", "그거", "여기", "거기", "저기",
        "우리", "저는", "제가", "나는", "내가", "너무", "많이", "약간", "조금", "그런", "이런", "저런", "어떤", "있는", "없는",
        "있어요", "있습니다", "합니다", "했어요", "하는", "해서", "하고", "에서", "으로", "그게", "이게", "뭐가", "같은", "같아요",
        "됩니다", "되는", "그럼", "근데", "아니", "네네", "음악", "박수",
    )
    // a Korean word's last syllable when it is only a particle (은, 는, 이, 가, ...): "영상은" counts as "영상"
    private val PARTICLES = listOf("에서", "으로", "에게", "까지", "부터", "처럼", "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "와", "과", "만")

    private fun words(text: String): List<String> =
        splitWords(text.lowercase()).mapNotNull { w0 ->
            var w = w0.trim('\'')
            if (w.length < 2 || w.all { it.isDigit() }) return@mapNotNull null
            if (w.first() in '가'..'힣' && w.length >= 3) PARTICLES.firstOrNull { w.endsWith(it) && w.length - it.length >= 2 }?.let { w = w.dropLast(it.length) }
            if (w.length < 2 || w in STOP || (w.first() in 'a'..'z' && w.length < 3)) null else w
        }

    // letters, digits, marks and ' make a word; no Regex \p{..} classes, which the iPhone and iPad's
    // Kotlin (Kotlin/Native) doesn't have (it crashed there, #67)
    private fun splitWords(s: String): List<String> {
        val out = ArrayList<String>()
        val b = StringBuilder()
        for (c in s) {
            if (c.isWordChar() || c == '\'') b.append(c) else if (b.isNotEmpty()) { out += b.toString(); b.clear() }
        }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    private class Win(val start: Long, var end: Long, val text: StringBuilder = StringBuilder()) {
        var terms: List<String> = emptyList()
    }

    fun analyze(lines: List<Vtt.Line>, lengthMs: Long, wantChapters: Boolean): Result {
        if (lines.size < 8) return Result(emptyList(), emptyList(), emptyList())
        // the lines joined into short passages: at a sentence's end, or after about 18 seconds
        val wins = ArrayList<Win>()
        var cur: Win? = null
        var lastText = ""
        for (l in lines) {
            val t = l.text.replace('\n', ' ').trim()
            if (t.isEmpty() || t == lastText) continue
            lastText = t
            val w = cur ?: Win(l.startMs, l.endMs).also { cur = it; wins += it }
            if (w.text.isNotEmpty()) w.text.append(' ')
            w.text.append(t)
            w.end = l.endMs
            if (l.endMs - w.start > 18_000 || t.endsWith('.') || t.endsWith('?') || t.endsWith('!') || t.endsWith('。')) cur = null
        }
        for (w in wins) w.terms = words(w.text.toString())
        val n = wins.size.toDouble()
        val tf = HashMap<String, Int>()
        val df = HashMap<String, Int>()
        for (w in wins) {
            for (t in w.terms) tf[t] = (tf[t] ?: 0) + 1
            for (t in w.terms.toSet()) df[t] = (df[t] ?: 0) + 1
        }
        fun idf(t: String) = ln(1.0 + n / (df[t] ?: 1))
        // the words: often here, but not in every passage (those are filler)
        val keywords = tf.entries.filter { it.value >= 3 && (df[it.key] ?: 0) < n * 0.6 }
            .sortedByDescending { it.value * idf(it.key) }.map { it.key }.take(8)
        val weight = HashMap<String, Double>()
        for ((t, c) in tf) weight[t] = ln(1.0 + c) * idf(t)
        // the moments: the passages richest in those words, spread across the video
        val len = lengthMs.takeIf { it > 0 } ?: wins.last().end
        val k = (wins.size / 14).coerceIn(3, 6)
        val gap = len / (k * 1.6)
        val scored = wins.filter { it.text.length >= 25 }.map { w ->
            val d = w.terms.toSet()
            w to (if (d.isEmpty()) 0.0 else d.sumOf { weight[it] ?: 0.0 } / sqrt(w.terms.size.toDouble() + 4))
        }.sortedByDescending { it.second }
        val picked = ArrayList<Win>()
        for ((w, _) in scored) {
            if (picked.size >= k) break
            if (picked.none { kotlin.math.abs(it.start - w.start) < gap }) picked += w
        }
        val moments = picked.sortedBy { it.start }.map { Moment(it.start, it.text.toString().let { s -> if (s.length > 160) s.take(157).trimEnd() + "…" else s }) }
        // chapters: about one every four minutes, each starting at the longest pause near its place
        val chapters = ArrayList<Chapter>()
        if (wantChapters && len >= 6 * 60_000 && wins.size >= 20) {
            val parts = (len / 240_000).toInt().coerceIn(3, 8)
            val starts = ArrayList<Int>()
            starts += 0
            for (i in 1 until parts) {
                val target = len * i / parts
                val reach = len / (parts * 2.5)
                var best = -1; var bestGap = -1L
                for (j in 1 until wins.size) {
                    if (kotlin.math.abs(wins[j].start - target) > reach) continue
                    val g = wins[j].start - wins[j - 1].end
                    if (g > bestGap) { bestGap = g; best = j }
                }
                if (best > (starts.lastOrNull() ?: 0)) starts += best
            }
            for ((ci, s) in starts.withIndex()) {
                val e = starts.getOrNull(ci + 1) ?: wins.size
                val local = HashMap<String, Int>()
                for (w in wins.subList(s, e)) for (t in w.terms) local[t] = (local[t] ?: 0) + 1
                val top = local.entries.filter { it.value >= 2 }.sortedByDescending { it.value * idf(it.key) }.map { it.key }.take(2)
                val title = top.joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } }
                    .ifEmpty { wins[s].text.toString().split(' ').take(5).joinToString(" ") }
                chapters += Chapter(title, if (ci == 0) 0L else wins[s].start)
            }
        }
        return Result(keywords, moments, if (chapters.size >= 3) chapters else emptyList())
    }

}

/** results already made, by video */
private val smartCache = HashMap<String, Smart.Result>()

/** the video's captions as lines, in the language the captions would show (cached with the transcript's) */
suspend fun captionLines(app: AppState, s: Stream): List<Vtt.Line>? {
    val (cap, to) = app.playback.captionChoice(s) ?: return null
    val url = YouTube.captionUrl(cap, to)
    transcripts[url]?.let { return it }
    val r = runCatching { Vtt.lines(Net.get(url)) }.getOrNull() ?: return null
    transcripts[url] = r
    return r
}

/** the card under the video: the words, the moments, and the chapters made for it */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartCard(app: AppState, v: Video) {
    if (!app.store.settings.value.smart) return
    val pb = app.playback
    val s = pb.stream ?: return
    if (s.id != v.id || s.captions.isEmpty()) return
    var result by remember(v.id) { mutableStateOf(smartCache[v.id]) }
    var open by remember(v.id) { mutableStateOf(false) }
    var lines by remember(v.id) { mutableStateOf<List<Vtt.Line>>(emptyList()) }
    var find by remember(v.id) { mutableStateOf("") }
    LaunchedEffect(v.id, s) {
        captionLines(app, s)?.let { lines = it }
        val have = result
        if (have != null) { if (have.chapters.isNotEmpty()) pb.autoChapters = v.id to have.chapters; return@LaunchedEffect }
        val lines = captionLines(app, s) ?: return@LaunchedEffect
        val len = v.lengthSec.takeIf { it > 0 }?.let { it * 1000L } ?: (s.lengthSec * 1000L)
        val own = pb.chapters.isEmpty()
        val a = withContext(Dispatchers.Default) { Smart.analyze(lines, len, own) }
        smartCache[v.id] = a
        result = a
        if (a.chapters.isNotEmpty() && pb.video?.id == v.id) pb.autoChapters = v.id to a.chapters
    }
    val a = result ?: return
    if (a.moments.isEmpty() && a.keywords.isEmpty()) return
    val c = MaterialTheme.colorScheme
    val tint = accent
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(shape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.20f), c.surfaceContainer, c.surfaceContainer)))
            .padding(16.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.Psychology.outline, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("This video at a glance"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(if (open) tr("Less") else tr("More"), style = MaterialTheme.typography.labelMedium, color = tint)
        }
        Text(tr("Made on this device from the captions"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp).clickable { open = !open })
        if (a.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in a.keywords.take(if (open) 8 else 5)) Text(
                "#$k", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceContainerHighest)
                    .clickable { pb.transcriptSearch = k; pb.transcriptAsked = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        val shown = if (open) a.moments else a.moments.take(1)
        if (!open && shown.isNotEmpty()) Text(tr("Tap to see the key moments and search inside the video"), style = MaterialTheme.typography.labelSmall,
            color = tint, modifier = Modifier.padding(top = 8.dp).clickable { open = true })
        if (shown.isNotEmpty()) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (open) Text(tr("Key moments"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            for (m in shown) Row(verticalAlignment = Alignment.Top) {
                Text(durationMs(m.ms), style = MaterialTheme.typography.labelMedium, color = c.onPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tint).clickable { app.player.seekTo(m.ms); app.player.play() }
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(m.text, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { app.player.seekTo(m.ms); app.player.play() })
            }
        }
        // the words said in the video: where each place it is said starts
        if (open && lines.isNotEmpty()) {
            OutlinedTextField(
                find, { find = it }, singleLine = true, label = { Text(tr("Find in this video")) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            val q = find.trim()
            if (q.length >= 2) {
                val hits = lines.filter { it.text.contains(q, ignoreCase = true) }.distinctBy { it.startMs / 4000 }
                if (hits.isEmpty()) Text(tr("Not said in this video"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                else {
                    Text(tr("Said {n} times").replace("{n}", "${hits.size}"), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    for (h in hits.take(8)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { app.player.seekTo(h.startMs); app.player.play() }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(durationMs(h.startMs), style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(52.dp))
                        Text(h.text.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (open && a.chapters.isNotEmpty()) Text(
            tr("Made {n} chapters for this video: the seek bar shows them").replace("{n}", "${a.chapters.size}"),
            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

