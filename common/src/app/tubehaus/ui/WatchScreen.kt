package app.tubehaus.ui

import androidx.compose.material.icons.outlined.Download
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Channel
import app.tubehaus.engine.Comment
import app.tubehaus.engine.Playlist
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.rec.Learn
import kotlinx.coroutines.launch

@Composable
fun WatchScreen(app: AppState) {
    val pb = app.playback
    val v = pb.video
    if (v == null) {
        Empty(tr("Nothing is playing"))
        return
    }
    val set = app.store.settings.value
    val token = pb.details?.commentsToken?.takeIf { !set.hideComments }
    val hasRelated = !set.hideRelated || pb.queue.isNotEmpty()
    // the comments open beside (wide) or under (phone) the video, which keeps playing
    var side by remember(v.id) { mutableStateOf(if (hasRelated && set.sideTab != "comments") "next" else "comments") }
    var commentsOpen by remember(v.id) { mutableStateOf(false) }
    // TV: the video fills the screen once it plays; Back comes here, to its details and what's next
    if (app.platform.tv) {
        val st by app.platform.player.state.collectAsState()
        var filled by remember(v.id) { mutableStateOf(false) }
        val playing = st.videoId == v.id && st.playing
        LaunchedEffect(v.id, playing) { if (playing && !filled) { filled = true; app.setFull(true) } }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        val tablet = app.platform.touch
        // tablets: a narrower panel, so the video gets most of the width
        val sideWidth = if (tablet) minOf(380.dp, maxWidth * 0.32f) else minOf(420.dp, maxWidth * 0.4f)
        if (wide) {
            // tablets and the desktop: the video and its details on the left; up next or the comments on the right
            Row(Modifier.fillMaxSize()) {
                if (tablet) {
                    // tablets: the video as large as the screen allows, with only its title under it;
                    // the description, buttons and channel move into the panel (Details)
                    Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.Center) {
                        PlayerBox(app)
                        if (set.underVideo != 2) Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).padding(16.dp)) {
                        item { PlayerBox(app) }
                        under(app, v)
                    }
                }
                val tabs = buildList {
                    if (hasRelated) add("next" to tr("Up next"))
                    if (token != null) add("comments" to tr("Comments"))
                    if (tablet && set.underVideo != 2) add("details" to tr("Details"))
                }
                if (tabs.isNotEmpty()) {
                    val shown = side.takeIf { s -> tabs.any { it.first == s } } ?: tabs.first().first
                    Column(Modifier.width(sideWidth).padding(top = 8.dp, end = 8.dp)) {
                        if (tabs.size > 1) {
                            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((k, label) in tabs) FilterChip(shown == k, onClick = { side = k }, label = { Text(label) })
                            }
                        }
                        when {
                            shown == "comments" && token != null -> Comments(app, token)
                            shown == "details" -> LazyColumn(Modifier.fillMaxSize()) { item { Info(app, v) } }
                            else -> LazyColumn(Modifier.fillMaxSize()) { related(app, big = false) }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PlayerBox(app)
                if (commentsOpen && token != null) {
                    // the comments take the space under the video; the video keeps playing above
                    CommentsPanel(app, token) { commentsOpen = false }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        under(app, v)
                        if (token != null) item { CommentsTeaser { commentsOpen = true } }
                        related(app, big = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBox(app: AppState) {
    val pb = app.playback
    val st by app.platform.player.state.collectAsState()
    val touch = app.platform.touch
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            // touch screens: swipe the video down to keep it playing in the mini player and go back
            .pointerInput(touch) {
                if (!touch) return@pointerInput
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onVerticalDrag = { change, dy -> change.consume(); pulled += dy },
                    onDragEnd = { if (pulled > 80.dp.toPx()) app.back() },
                )
            },
    ) {
        val v = pb.video
        when {
            pb.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pb.error!!, color = Color.White, textAlign = TextAlign.Center)
                if (pb.errorAge) Button(onClick = { app.platform.openExternal("https://www.youtube.com/watch?v=" + (v?.id ?: "")) },
                    modifier = Modifier.padding(top = 12.dp)) { Text(tr("Open on YouTube")) }
                else Button(onClick = { pb.retry() }, modifier = Modifier.padding(top = 12.dp)) { Text(tr("Try again")) }
            }
            pb.loading || st.videoId != v?.id -> {
                if (v != null && !app.store.settings.value.hideThumbs) Img(v.thumbHigh, Modifier.fillMaxSize())
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            else -> VideoArea(app, Modifier.fillMaxSize())
        }
        // while loading there are no controls yet: keep a way back
        if ((pb.error != null || pb.loading || st.videoId != v?.id)) {
            IconButton(onClick = { app.back() }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White)
            }
        }
    }
    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
}

@Composable
private fun Info(app: AppState, v: Video) {
    val pb = app.playback
    val d = pb.details
    val s = pb.stream
    val lib by app.store.lib.state.collectAsState()
    val calm = app.store.settings.value.calm
    val hidden = app.store.settings.state.collectAsState().value.hideUnder
    fun has(part: String) = part !in hidden
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (has("title")) Text(v.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(
            (d?.viewsText?.takeIf { it.isNotEmpty() } ?: v.views.takeIf { it > 0 }?.let { tr("{n} views").replace("{n}", compact(it)) }).takeIf { !calm && has("views") },
            d?.publishedText?.takeIf { it.isNotEmpty() && has("date") },
        ).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        // channel
        val showSub = v.channelId.isNotEmpty() && has("subscribe")
        if (has("channel") || showSub) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (has("channel")) Row(Modifier.weight(1f).clickable { if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(d?.avatar ?: lib.subs[v.channelId]?.thumb ?: "", 40)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(v.author, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!calm && has("subscribers") && !d?.subsText.isNullOrEmpty()) Text(d!!.subsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Spacer(Modifier.weight(1f))
            if (showSub) SubscribeButton(v.channelId, v.author, d?.avatar ?: "", app)
        }
        Actions(app, v, ::has)
        // description
        val desc = s?.description?.takeIf { has("description") } ?: ""
        if (desc.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }.padding(12.dp).animateContentSize(),
            ) {
                if (expanded) SelectionContainer { Text(desc, style = MaterialTheme.typography.bodyMedium) }
                else Text(desc, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(tr(if (expanded) "Show less" else "...more"), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Actions(app: AppState, v: Video, has: (String) -> Boolean) {
    val pb = app.playback
    val lib by app.store.lib.state.collectAsState()
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    val saved = lib.wl.any { it.id == v.id }
    var pick by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has("likes")) item {
            Chip(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, (pb.details?.likesText?.takeIf { !calm } ?: "").ifEmpty { tr("Like") }) {
                app.store.setInList("likes", v, !liked)
                if (!liked) Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0)
            }
        }
        if (has("likes")) item {
            Chip(if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, tr("Dislike")) {
                app.store.setInList("dislikes", v, !disliked)
                if (!disliked) Learn.nudgeChannel(app.store, v.channelId, v.author, -1.5)
            }
        }
        if (has("later")) item { Chip(if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater, tr("Watch later")) { app.store.setInList("wl", v, !saved) } }
        if (has("save")) item { Chip(Icons.Outlined.LibraryAdd, tr("Save")) { pick = true } }
        if (has("share")) item { Chip(Icons.Outlined.Share, tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") } }
        if (app.platform.name == "android" && has("download")) item { Chip(Icons.Outlined.Download, tr("Download")) { app.download(v) } }
        if (pb.next != null && has("next")) item { Chip(Icons.Outlined.SkipNext, tr("Next")) { pb.skipNext() } }
    }
    if (pick) PlaylistPicker(v, app) { pick = false }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick, label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        shape = RoundedCornerShape(18.dp),
        border = null,
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** What sits under the video, by Settings → Under the video: everything, only the title, or nothing. */
private fun LazyListScope.under(app: AppState, v: Video) {
    when (app.store.settings.value.underVideo) {
        0 -> item { Info(app, v) }
        1 -> item {
            Text(v.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

/** The comments with a title and a close button: under the video on a phone, beside it in full screen. */
@Composable
fun CommentsPanel(app: AppState, token: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Comments"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Close")) }
        }
        HorizontalDivider()
        Comments(app, token)
    }
}

/** A scrolling list of comments that loads the next page by itself when you reach the end. */
@Composable
private fun Comments(app: AppState, token: String) {
    val pager = rememberPager(token, { YouTube.comments(token) }, { YouTube.comments(it) })
    LazyColumn(Modifier.fillMaxSize()) {
        if (pager.items.isEmpty() && pager.loading) item { Busy() }
        if (pager.items.isEmpty() && pager.started && !pager.loading) item { Empty(tr("No comments")) }
        items(pager.items, key = { "c" + it.id }) { CommentView(app, it) }
        if (pager.next != null) item("more") {
            // reaching the end asks for the next page
            LaunchedEffect(pager.items.size) { pager.loadMore() }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
    }
}

/** big: phones show related videos as full-width cards, wide screens as rows with larger pictures */
private fun LazyListScope.related(app: AppState, big: Boolean) {
    val pb = app.playback
    val queue = pb.queue
    if (queue.isNotEmpty()) {
        item { SectionTitle(tr("Up next")) }
        items(queue, key = { "q" + it.id }) { v ->
            VideoRow(v, app) { pb.open(v, queue.dropWhile { it.id != v.id }.drop(1)); app.stack[app.stack.size - 1] = Screen.Watch(v.id) }
        }
    }
    if (app.store.settings.value.hideRelated) return
    val d = pb.details ?: run { item { Busy() }; return }
    items(d.related.filter { !(app.noShorts && it is Video && it.short) }, key = { "r" + it.id }) {
        when (it) {
            is Video -> if (big) VideoCard(it, app) else VideoRow(it, app, thumbWidth = 200.dp)
            is Playlist -> PlaylistRow(it, app)
            is Channel -> ChannelRow(it, app)
        }
    }
}

@Composable
private fun CommentsTeaser(open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .tvClickable(onClick = open).padding(12.dp),
    ) { Text(tr("Comments"), style = MaterialTheme.typography.titleSmall) }
}


@Composable
private fun CommentView(app: AppState, c: Comment, reply: Boolean = false) {
    var showReplies by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    Row(Modifier.fillMaxWidth().padding(start = if (reply) 52.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.avatar, if (reply) 24 else 32, Modifier.clickable { if (c.channelId.isNotEmpty()) app.go(Screen.Channel(c.channelId, c.author)) })
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                listOf(c.author, c.published).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (c.byCreator) FontWeight.Bold else null,
            )
            SelectionContainer { Text(c.text, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!calm && c.likes.isNotEmpty()) {
                    Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.likes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.width(16.dp))
                }
                if (c.replies > 0 && c.repliesToken != null) TextButton(onClick = { showReplies = !showReplies }, contentPadding = PaddingValues(0.dp)) {
                    Text(tr("{n} replies").replace("{n}", "${c.replies}"), color = Blue)
                }
            }
        }
    }
    if (showReplies && c.repliesToken != null) {
        val pager = rememberPager(c.repliesToken, { YouTube.comments(c.repliesToken) }, { YouTube.comments(it) })
        val scope = rememberCoroutineScope()
        for (r in pager.items) CommentView(app, r, reply = true)
        if (pager.loading) Busy()
        if (pager.next != null && !pager.loading) TextButton(onClick = { scope.launch { pager.loadMore() } }, modifier = Modifier.padding(start = 52.dp)) {
            Text(tr("More replies"))
        }
    }
}

