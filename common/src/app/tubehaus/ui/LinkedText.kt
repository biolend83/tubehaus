package app.tubehaus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import app.tubehaus.engine.TextLink
import app.tubehaus.engine.secondsOf

// Only plain patterns: no lookbehind and no \p{..} classes, which the iPad's Kotlin (Kotlin/Native) may not take
// (a pattern it can't read throws the first time this file is used, and "...more" closed the app).
private val URL = Regex("""(https?://|www\.)[^\s<>"]+""")
private val TIME = Regex("""(\d{1,2}:)?\d{1,2}:\d{2}""")

/** #tags: a # not right after a letter, a digit, # or &, then letters, digits and _ (any script) */
private fun tags(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = text.indexOf('#')
    while (i >= 0) {
        val before = text.getOrNull(i - 1)
        var end = i + 1
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        if (end > i + 1 && (before == null || !(before.isLetterOrDigit() || before == '_' || before == '#' || before == '&'))) out += i until end
        i = text.indexOf('#', end.coerceAtLeast(i + 1))
    }
    return out
}

/** 1:23 or 1:02:03, not part of a longer number like a date or a time of day with seconds */
private fun times(text: String): List<MatchResult> = TIME.findAll(text).filter { m ->
    val before = text.getOrNull(m.range.first - 1)
    val after = text.getOrNull(m.range.last + 1)
    (before == null || !(before.isDigit() || before == ':' || before == '.')) && (after == null || !(after.isDigit() || after == ':'))
}.toList()

/**
 * Text with its links in the link colour, as in YouTube's descriptions and posts: addresses open
 * ([AppState.openLink]), #tags search, and with [onTime] a time like 3:25 jumps there in the video.
 * [links] are the links YouTube marked itself; addresses and tags written in the text are found here.
 */
@Composable
fun LinkedText(
    text: String,
    app: AppState,
    modifier: Modifier = Modifier,
    links: List<TextLink> = emptyList(),
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    onTime: ((Int) -> Unit)? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val color = MaterialTheme.colorScheme.primary
    val spans = ArrayList<Triple<Int, Int, () -> Unit>>()
    fun free(a: Int, b: Int) = spans.none { a < it.second && it.first < b }
    for (l in links) if (l.start < l.end && l.end <= text.length && free(l.start, l.end)) spans += Triple(l.start, l.end) { app.openLink(l.url) }
    for (m in URL.findAll(text)) {
        val s = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '"', '\'')
        if (free(m.range.first, m.range.first + s.length)) spans += Triple(m.range.first, m.range.first + s.length) { app.openLink(s) }
    }
    for (r in tags(text)) if (free(r.first, r.last + 1)) {
        val tag = text.substring(r.first, r.last + 1)
        spans += Triple(r.first, r.last + 1) { app.go(Screen.Search(tag)) }
    }
    if (onTime != null) for (m in times(text)) if (free(m.range.first, m.range.last + 1)) {
        val sec = secondsOf(m.value)
        spans += Triple(m.range.first, m.range.last + 1) { onTime(sec) }
    }
    val s = buildAnnotatedString {
        append(text)
        val look = TextLinkStyles(SpanStyle(color = color))
        for ((a, b, act) in spans) addLink(LinkAnnotation.Clickable("link", look) { act() }, a, b)
    }
    Text(s, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
}

/**
 * Text in a bottom sheet that can be selected and copied. Not on the iPad: there, selection
 * inside a sheet closes the app (Compose on iOS), so the text is plain.
 */
@Composable
fun SelectableInSheet(app: AppState, content: @Composable () -> Unit) {
    if (app.platform.name == "ios") content()
    else androidx.compose.foundation.text.selection.SelectionContainer { content() }
}
