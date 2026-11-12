package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Net
import app.tubehaus.engine.Vtt
import app.tubehaus.engine.YouTube

/** transcripts already fetched, by their captions' address */
internal val transcripts = HashMap<String, List<Vtt.Line>>()

/**
 * The video's captions as a transcript: every line with its time, the one being said marked,
 * a search through them; a line takes the video there.
 */
@Composable
fun TranscriptPanel(app: AppState, onClose: (() -> Unit)?) {
    val pb = app.playback
    val s = pb.stream
    val st by app.player.state.collectAsState()
    val choice = remember(s) { pb.captionChoice(s) }
    // a language picked here, else the captions on screen if there are any, else the track the captions button would pick
    var picked by remember(s) { mutableStateOf<Caption?>(null) }
    val cap = picked ?: st.captions ?: choice?.first
    val to = if (picked != null) null else if (st.captions != null) st.captionsTranslated else choice?.second
    val url = cap?.let { YouTube.captionUrl(it, to) }
    var lines by remember(url) { mutableStateOf(url?.let { transcripts[it] }) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (url == null || lines != null) return@LaunchedEffect
        failed = false
        val r = runCatching { Vtt.lines(Net.get(url)) }.getOrNull()
        if (r == null) failed = true else { transcripts[url] = r; lines = r }
    }
    // a word tapped in "at a glance" arrives already searched for
    var q by remember { mutableStateOf(pb.transcriptSearch.also { pb.transcriptSearch = "" }) }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Transcript"), style = MaterialTheme.typography.titleMedium)
                // the language: any of the video's caption tracks
                var langs by remember { mutableStateOf(false) }
                val all = s?.captions ?: emptyList()
                if (cap != null) Box {
                    Text(cap.name + (to?.let { t -> " → " + (LANGS.firstOrNull { it.first == t }?.second ?: t) } ?: "") + if (all.size > 1) " ▾" else "",
                        Modifier.then(if (all.size > 1) Modifier.tvClickable(RoundedCornerShape(6.dp)) { langs = true } else Modifier),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                    DropdownMenu(langs, onDismissRequest = { langs = false }) {
                        for (x in all) DropdownMenuItem(text = { Text(x.name) }, onClick = { langs = false; picked = x })
                    }
                }
            }
            // the transcript as a file: text, text with times, SubRip or WebVTT
            var saving by remember { mutableStateOf(false) }
            val got = lines
            if (got != null && got.isNotEmpty() && cap != null) Box {
                IconButton(onClick = { saving = true }) { Icon(Sym.Download.outline, tr("Download transcript")) }
                DropdownMenu(saving, onDismissRequest = { saving = false }) {
                    for ((fmt, label) in listOf("txt" to tr("Text (.txt)"), "tt" to tr("Text with timestamps (.txt)"), "srt" to "SubRip (.srt)", "vtt" to "WebVTT (.vtt)")) {
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            saving = false
                            val (name, body) = transcriptFile(got, fmt, s?.title ?: "", to ?: cap.lang)
                            app.platform.saveFile(name, if (fmt == "srt") "application/x-subrip" else if (fmt == "vtt") "text/vtt" else "text/plain",
                                body.encodeToByteArray()) { ok -> if (ok) app.toast(tr("Saved")) }
                        })
                    }
                }
            }
            if (onClose != null) IconButton(onClick = onClose) { Icon(Sym.Close.fill, tr("Close")) }
        }
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            singleLine = true, shape = RoundedCornerShape(24.dp),
            placeholder = { Text(tr("Search the transcript")) },
            leadingIcon = { Icon(Sym.Search.outline, null) },
            trailingIcon = if (q.isNotEmpty()) ({ IconButton(onClick = { q = "" }) { Icon(Sym.Close.fill, tr("Clear")) } }) else null,
        )
        val l = lines
        when {
            url == null -> Empty(tr("This video has no captions"))
            failed && l == null -> Problem(tr("Couldn't load the transcript.")) { attempt++ }
            l == null -> Busy(Modifier.fillMaxWidth().padding(32.dp))
            else -> {
                val list = rememberLazyListState()
                val term = q.trim()
                val shown = if (term.isEmpty()) l.withIndex().toList() else l.withIndex().filter { it.value.text.contains(term, ignoreCase = true) }
                val cur = l.indexOfLast { it.startMs <= st.positionMs + 200 }
                // follows the video while you are not searching or scrolling yourself
                LaunchedEffect(cur, term.isEmpty()) {
                    if (term.isEmpty() && cur >= 0 && !list.isScrollInProgress) list.animateScrollToItem((cur - 3).coerceAtLeast(0))
                }
                if (shown.isEmpty()) Empty(tr("No line has \"{q}\"").replace("{q}", term))
                else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (term.isNotEmpty()) item {
                        Text(tr("{n} lines").replace("{n}", "${shown.size}"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    items(shown, key = { it.index }) { (i, line) ->
                        val now = i == cur
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                .background(if (now) c.secondaryContainer else c.background.copy(alpha = 0f), RoundedCornerShape(10.dp))
                                .tvClickable(RoundedCornerShape(10.dp)) { app.player.seekTo(line.startMs); app.player.play() }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Box(Modifier.width(64.dp)) {
                                Text(durationMs(line.startMs), Modifier.background(c.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                            }
                            Text(highlight(line.text, term), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (now) FontWeight.SemiBold else null)
                        }
                    }
                }
            }
        }
    }
}

private fun pad(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
private fun stamp(ms: Long, sep: Char) = "${pad(ms / 3_600_000)}:${pad(ms / 60_000 % 60)}:${pad(ms / 1000 % 60)}$sep${pad(ms % 1000, 3)}"

/** the transcript as a file: (name, text); [fmt] = txt, tt (text with times), srt, vtt */
fun transcriptFile(lines: List<Vtt.Line>, fmt: String, title: String, lang: String): Pair<String, String> {
    val body = when (fmt) {
        "txt" -> lines.joinToString("\n") { it.text }
        "tt" -> lines.joinToString("\n") { "[${durationMs(it.startMs)}] ${it.text}" }
        "srt" -> lines.withIndex().joinToString("\n") { (i, l) -> "${i + 1}\n${stamp(l.startMs, ',')} --> ${stamp(l.endMs, ',')}\n${l.text}\n" }
        else -> "WEBVTT\n\n" + lines.joinToString("\n") { "${stamp(it.startMs, '.')} --> ${stamp(it.endMs, '.')}\n${it.text}\n" }
    }
    val base = title.map { if (it < ' ' || it in "\\/:*?\"<>|") ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "transcript" }
    return "$base [$lang]${if (fmt == "tt") " (timestamps)" else ""}.${if (fmt == "tt") "txt" else fmt}" to body.trimEnd() + "\n"
}

/** [text] with every [term] in it in bold */
private fun highlight(text: String, term: String) = buildAnnotatedString {
    if (term.isEmpty()) { append(text); return@buildAnnotatedString }
    var at = 0
    while (true) {
        val i = text.indexOf(term, at, ignoreCase = true)
        if (i < 0) { append(text.substring(at)); break }
        append(text.substring(at, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Teal.copy(alpha = 0.3f))) { append(text.substring(i, i + term.length)) }
        at = i + term.length
    }
}
