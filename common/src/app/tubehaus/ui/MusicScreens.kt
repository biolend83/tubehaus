package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.data.CardMeta
import app.tubehaus.engine.AlbumPage
import app.tubehaus.engine.ArtistPage
import app.tubehaus.engine.Lyrics
import app.tubehaus.engine.Music
import app.tubehaus.engine.MusicCard
import app.tubehaus.engine.MusicResults
import app.tubehaus.engine.Song
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------- pieces

/** a song's cover, square; a video's picture has black bars above and below, which are cut off */
@Composable
fun SongCover(s: Song, modifier: Modifier = Modifier, round: Int = 6) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(round.dp))) {
        if (s.art.isNotEmpty()) Img(s.art, Modifier.fillMaxSize())
        else Img(s.cover, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.34f; scaleY = 1.34f })
    }
}

private fun Song.line(): String = listOf(artist, album, if (lengthSec > 0) duration(lengthSec) else "").filter { it.isNotEmpty() }.joinToString(" • ")

/** one song in a list: its cover (or its number on an album), title, artist; a menu with the queue and links */
@Composable
fun SongRow(app: AppState, s: Song, number: Int? = null, playing: Boolean = false, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(if (playing) c.secondaryContainer else c.background.copy(alpha = 0f))
            .tvClickable(RoundedCornerShape(8.dp), onLongClick = { menu = true }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) Text("$number", Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        else SongCover(s, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else null)
            Text(if (number != null) listOf(s.artist, if (s.lengthSec > 0) duration(s.lengthSec) else "").filter { it.isNotEmpty() }.joinToString(" • ") else s.line(),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Sym.Close.outline, tr("Remove")) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Sym.MoreVert.fill, tr("More")) }
            DropdownMenu(menu, { menu = false }) {
                val pb = app.playback
                DropdownMenuItem(text = { Text(tr("Play next")) }, leadingIcon = { Icon(Sym.QueueMusic.outline, null) }, onClick = { menu = false; pb.playNext(s); app.toast(tr("Plays next")) })
                DropdownMenuItem(text = { Text(tr("Add to queue")) }, leadingIcon = { Icon(Sym.PlaylistPlay.outline, null) }, onClick = { menu = false; pb.addToQueue(s); app.toast(tr("Added to the queue")) })
                DropdownMenuItem(text = { Text(tr("Start radio")) }, leadingIcon = { Icon(Sym.Explore.outline, null) }, onClick = { menu = false; pb.playRadio(s) })
                if (s.albumId.isNotEmpty()) DropdownMenuItem(text = { Text(tr("Go to album")) }, leadingIcon = { Icon(Sym.Album.outline, null) }, onClick = { menu = false; app.go(Screen.Album(s.albumId, s.album)) })
                if (s.artistId.startsWith("UC")) DropdownMenuItem(text = { Text(tr("Go to artist")) }, leadingIcon = { Icon(Sym.Person.outline, null) }, onClick = { menu = false; app.go(Screen.Artist(s.artistId, s.artist)) })
                DropdownMenuItem(text = { Text(tr("Watch video")) }, leadingIcon = { Icon(Sym.MusicVideo.outline, null) }, onClick = { menu = false; pb.open(s.video()); app.go(Screen.Watch(s.id)) })
            }
        }
    }
}

/** an album, a playlist or an artist in a row of cards; artists are round */
@Composable
private fun CardTile(c: MusicCard, onClick: () -> Unit) {
    val round = c.kind == "artist"
    Column(Modifier.width(148.dp).tvClickable(RoundedCornerShape(10.dp), onClick = onClick).padding(6.dp)) {
        Img(c.art, Modifier.fillMaxWidth().aspectRatio(1f).clip(if (round) CircleShape else RoundedCornerShape(8.dp)))
        Text(c.title, Modifier.padding(top = 6.dp).fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else null)
        if (c.subtitle.isNotEmpty()) Text(c.subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else null)
    }
}

@Composable
private fun CardShelf(app: AppState, title: String, cards: List<MusicCard>) {
    if (cards.isEmpty()) return
    Column {
        SectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(cards, key = { it.id }) { c -> CardTile(c) { openCard(app, c) } }
        }
    }
}

/** where a card goes: an album, an artist, a playlist */
fun openCard(app: AppState, c: MusicCard) {
    when {
        c.kind == "artist" || c.id.startsWith("UC") -> app.go(Screen.Artist(c.id, c.title))
        c.kind == "playlist" || c.id.startsWith("VL") -> app.go(Screen.Playlist(c.id.removePrefix("VL"), c.title))
        else -> app.go(Screen.Album(c.id, c.title))
    }
}

private fun CardMeta.card() = MusicCard(id, title, subtitle, art, kind)

// ---------------------------------------------------------------------------------------------- the tab

/** quick picks are a radio of the last song; asked once per song, not each time the tab opens */
private var picksFor = ""
private var picks: List<Song> = emptyList()
/** the last search's results, kept while you go into an album and come back */
private var lastTerm = ""
private var lastResults: MusicResults? = null

/** The music tab: a search over songs, artists and albums; what you played and opened. */
@Composable
fun MusicScreen(app: AppState) {
    var q by rememberSaveable { mutableStateOf(lastTerm) }
    var term by rememberSaveable { mutableStateOf(lastTerm) }
    var res by remember { mutableStateOf(lastResults.takeIf { term.isNotEmpty() && term == lastTerm }) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val md by app.store.music.state.collectAsState()
    fun submit(t: String) {
        val x = t.trim()
        q = x
        if (x.isEmpty()) { term = ""; return }
        if (x != term) { term = x; res = null }
        app.store.rememberMusicSearch(x)
    }
    LaunchedEffect(term, attempt) {
        if (term.isEmpty() || (res != null && term == lastTerm)) return@LaunchedEffect
        busy = true; failed = false
        val r = runCatching { Music.search(term) }.getOrNull()
        busy = false
        if (r == null) failed = true else { res = r; lastResults = r; lastTerm = term }
    }
    Narrow {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                q, { q = it; if (it.isEmpty()) term = "" },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true, shape = RoundedCornerShape(26.dp),
                placeholder = { Text(tr("Songs, artists, albums")) },
                leadingIcon = { Icon(Sym.Music.outline, null) },
                trailingIcon = {
                    Row {
                        if (q.isNotEmpty()) IconButton(onClick = { q = ""; term = "" }) { Icon(Sym.Close.fill, tr("Clear")) }
                        IconButton(onClick = { submit(q) }) { Icon(Sym.Search.outline, tr("Search")) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(q) }),
            )
            when {
                term.isEmpty() -> MusicHome(app, md.recent.map { it.song() }, md.opened.map { it.card() }, md.searches) { submit(it) }
                busy && res == null -> Busy(Modifier.fillMaxWidth().padding(32.dp))
                failed && res == null -> Problem(tr("Couldn't search. Check the connection and try again.")) { attempt++ }
                else -> res?.let { r -> MusicResultsList(app, r) }
            }
        }
    }
}

@Composable
private fun MusicResultsList(app: AppState, r: MusicResults) {
    if (r.artists.isEmpty() && r.albums.isEmpty() && r.songs.isEmpty()) { Empty(tr("Nothing found")); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (r.artists.isNotEmpty()) item { CardShelf(app, tr("Artists"), r.artists) }
        if (r.albums.isNotEmpty()) item { CardShelf(app, tr("Albums"), r.albums) }
        if (r.songs.isNotEmpty()) {
            item { SectionTitle(tr("Songs")) }
            // a song from a search: it, then songs like it
            itemsIndexed(r.songs, key = { _, s -> "s" + s.id }) { _, s -> SongRow(app, s) { app.playback.playRadio(s) } }
        }
    }
}

@Composable
private fun MusicHome(app: AppState, recent: List<Song>, opened: List<MusicCard>, searches: List<String>, search: (String) -> Unit) {
    val seed = recent.firstOrNull()
    var quick by remember { mutableStateOf(if (seed != null && seed.id == picksFor) picks else emptyList()) }
    LaunchedEffect(seed?.id) {
        if (seed == null || seed.id == picksFor) return@LaunchedEffect
        val r = runCatching { Music.radio(seed.id) }.getOrDefault(emptyList()).filter { it.id != seed.id }.take(20)
        if (r.isNotEmpty()) { picksFor = seed.id; picks = r; quick = r }
    }
    if (recent.isEmpty() && opened.isEmpty() && searches.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Sym.Music.outline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("Search for a song, an artist or an album"), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(tr("Songs play one after another, with the lyrics and songs like them. Only the sound is loaded, to save data."),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (searches.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(searches.take(12)) { t -> Chip(false, { search(t) }, t, Sym.History.outline) }
            }
        }
        if (recent.size > 1) item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.playback.playSongs(recent.take(100), 0, shuffled = true) }) {
                    Icon(Sym.Shuffle.fill, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Shuffle my songs"))
                }
                if (app.playback.music) OutlinedButton(onClick = { app.go(Screen.NowPlaying) }) { Text(tr("Now playing")) }
            }
        }
        if (quick.isNotEmpty()) {
            item { SectionTitle(tr("Quick picks")) }
            items(quick.take(8), key = { "q" + it.id }) { s -> SongRow(app, s) { app.playback.playSongs(quick, quick.indexOf(s), radio = true) } }
        }
        if (opened.isNotEmpty()) item { CardShelf(app, tr("Your albums and artists"), opened) }
        if (recent.isNotEmpty()) {
            item { SectionTitle(tr("Recently played")) }
            items(recent.take(30), key = { "r" + it.id }) { s -> SongRow(app, s) { app.playback.playSongs(recent.take(100), recent.indexOf(s)) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------- album, artist

private val albums = HashMap<String, AlbumPage>()
private val artists = HashMap<String, ArtistPage>()

@Composable
fun AlbumScreen(app: AppState, id: String, title: String) {
    var page by remember(id) { mutableStateOf(albums[id]) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(id, attempt) {
        if (page != null) return@LaunchedEffect
        failed = false
        val p = runCatching { Music.album(id) }.getOrNull()
        if (p == null) failed = true else { albums[id] = p; page = p }
    }
    LaunchedEffect(page) { page?.let { app.store.rememberCard(CardMeta(id, it.title, listOf(it.artist, it.subtitle).filter { s -> s.isNotEmpty() }.joinToString(" • "), it.art, "album")) } }
    val p = page
    Narrow {
        when {
            p == null && failed -> Problem(tr("Couldn't open this album.")) { attempt++ }
            p == null -> Busy(Modifier.fillMaxWidth().padding(32.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Img(p.art, Modifier.widthIn(max = 240.dp).fillMaxWidth(0.6f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                        Text(p.title.ifEmpty { title }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (p.artist.isNotEmpty()) TextButton(onClick = { if (p.artistId.isNotEmpty()) app.go(Screen.Artist(p.artistId, p.artist)) }) { Text(p.artist) }
                        Text(listOf(p.subtitle, p.info).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        PlayButtons(enabled = p.songs.isNotEmpty(), play = { app.playback.playSongs(p.songs, 0) }, shuffle = { app.playback.playSongs(p.songs, (p.songs.indices).random(), shuffled = true) })
                    }
                }
                itemsIndexed(p.songs, key = { i, s -> "$i" + s.id }) { i, s ->
                    SongRow(app, s, number = i + 1, playing = app.playback.song?.id == s.id) { app.playback.playSongs(p.songs, i) }
                }
                if (p.songs.isEmpty()) item { Empty(tr("This album has no songs that can be played here")) }
            }
        }
    }
}

@Composable
private fun PlayButtons(enabled: Boolean, play: () -> Unit, shuffle: () -> Unit, radio: (() -> Unit)? = null) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (radio == null) Button(onClick = play, enabled = enabled) { Icon(Sym.PlayArrow.fill, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Play")) }
        OutlinedButton(onClick = shuffle, enabled = enabled) { Icon(Sym.Shuffle.fill, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Shuffle")) }
        if (radio != null) OutlinedButton(onClick = radio) { Icon(Sym.Explore.outline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Radio")) }
    }
}

@Composable
fun ArtistScreen(app: AppState, id: String, name: String) {
    var page by remember(id) { mutableStateOf(artists[id]) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    // the artist page has no song list of its own: its shuffle mix stands in
    var songs by remember(id) { mutableStateOf(artists[id]?.songs ?: emptyList()) }
    var aboutOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, attempt) {
        if (page == null) {
            failed = false
            val p = runCatching { Music.artist(id) }.getOrNull()
            if (p == null) { failed = true; return@LaunchedEffect }
            page = p
        }
        val p = page ?: return@LaunchedEffect
        if (p.songs.isEmpty() && p.shuffleId != null) {
            val mix = runCatching { Music.mix(p.shuffleId) }.getOrDefault(emptyList())
            if (mix.isNotEmpty()) { songs = mix; artists[id] = p.copy(songs = mix) } else artists[id] = p
        } else { songs = p.songs; artists[id] = p }
    }
    LaunchedEffect(page) { page?.let { app.store.rememberCard(CardMeta(id, it.name, it.audience, it.art, "artist")) } }
    val scope = app.scope
    val p = page
    Narrow {
        when {
            p == null && failed -> Problem(tr("Couldn't open this artist.")) { attempt++ }
            p == null -> Busy(Modifier.fillMaxWidth().padding(32.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Img(p.art, Modifier.size(160.dp).clip(CircleShape))
                        Text(p.name.ifEmpty { name }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (p.audience.isNotEmpty()) Text(p.audience, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PlayButtons(
                            enabled = songs.isNotEmpty() || p.shuffleId != null,
                            play = {},
                            shuffle = {
                                if (songs.isNotEmpty()) app.playback.playSongs(songs, songs.indices.random(), shuffled = true)
                                else p.shuffleId?.let { sid -> scope.launch { val l = runCatching { Music.mix(sid) }.getOrDefault(emptyList()); app.playback.playSongs(l, 0, shuffled = true) } }
                            },
                            radio = p.radioId?.let { rid -> {
                                scope.launch {
                                    val l = runCatching { Music.mix(rid) }.getOrDefault(emptyList())
                                    if (l.isEmpty()) app.toast(tr("Couldn't start the radio")) else app.playback.playSongs(l, 0, radio = true)
                                }
                                Unit
                            } },
                        )
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionTitle(tr("Songs")) }
                    itemsIndexed(songs.take(10), key = { i, s -> "$i" + s.id }) { i, s ->
                        SongRow(app, s, playing = app.playback.song?.id == s.id) { app.playback.playSongs(songs, i) }
                    }
                }
                for ((t, cards) in p.shelves) if (cards.isNotEmpty()) item { CardShelf(app, t, cards) }
                if (p.about.isNotEmpty()) item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionTitle(tr("About"), Modifier.padding(start = 0.dp))
                        Text(p.about, Modifier.tvClickable { aboutOpen = !aboutOpen }, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (aboutOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- now playing

/** the lyrics of the songs heard this session (LRCLIB is asked once per song) */
private val lyricsCache = HashMap<String, Lyrics.Found?>()

/** What plays as music: the cover (or the video), the controls, the lyrics and what comes next. */
@Composable
fun NowPlayingScreen(app: AppState) {
    val pb = app.playback
    val s = pb.song
    if (s == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Empty(tr("Nothing is playing"))
            Button(onClick = { app.go(Screen.Music) }) { Text(tr("Music")) }
        }
        return
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) { SongPanel(app, s, big = true) }
            Column(Modifier.weight(1f).fillMaxSize()) {
                NowTabs(listOf(tr("Lyrics"), tr("Coming up")), (tab - 1).coerceAtLeast(0)) { tab = it + 1 }
                if (tab <= 1) LyricsPanel(app, s) else QueuePanel(app)
            }
        } else Column(Modifier.fillMaxSize()) {
            NowTabs(listOf(tr("Song"), tr("Lyrics"), tr("Coming up")), tab) { tab = it }
            when (tab) {
                0 -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { SongPanel(app, s, big = true) }
                else -> {
                    SongPanel(app, s, big = false)
                    Box(Modifier.weight(1f)) { if (tab == 1) LyricsPanel(app, s) else QueuePanel(app) }
                }
            }
        }
    }
}

@Composable
private fun NowTabs(names: List<String>, at: Int, pick: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(names) { i, n -> Chip(i == at, { pick(i) }, n) }
    }
}

/** the cover, the title, the seek bar and the buttons; small: one line above the lyrics or the queue */
@Composable
private fun SongPanel(app: AppState, s: Song, big: Boolean) {
    val pb = app.playback
    val st by app.player.state.collectAsState()
    val c = MaterialTheme.colorScheme
    val liked = app.store.lib.state.collectAsState().value.likes.any { it.id == s.id }
    fun like() {
        app.store.setInList("likes", s.video(), !liked)
        app.store.likeSong(s.meta(), !liked)
    }
    if (!big) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SongCover(s, Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = { pb.previous() }) { Icon(Sym.SkipPrevious.fill, tr("Previous")) }
            IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
            }
            IconButton(onClick = { pb.skipNext() }) { Icon(Sym.SkipNext.fill, tr("Next")) }
        }
        return
    }
    // the cover as big as fits with everything under it; on a short window the rest scrolls
    BoxWithConstraints(Modifier.widthIn(max = 520.dp)) {
    val side = minOf(maxWidth - 48.dp, maxHeight - 300.dp, 380.dp).coerceAtLeast(140.dp)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pb.withVideo) {
            Box(Modifier.widthIn(max = side * 16f / 9f).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black)
                .tvClickable(RoundedCornerShape(12.dp)) { app.go(Screen.Watch(s.id)) }) {
                if (st.videoId == s.id) app.player.View(Modifier.fillMaxSize())
            }
        } else SongCover(s, Modifier.size(side), round = 14)
        Text(s.title, Modifier.padding(top = 16.dp).fillMaxWidth(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(s.artist, Modifier.fillMaxWidth().tvClickable { if (s.artistId.startsWith("UC")) app.go(Screen.Artist(s.artistId, s.artist)) }, style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
        // seek bar: dragging moves the thumb; the song jumps when it is let go
        var drag by remember { mutableStateOf<Float?>(null) }
        val dur = st.durationMs.coerceAtLeast(1)
        Slider(
            value = drag ?: (st.positionMs.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { app.player.seekTo((it * dur).toLong()) }; drag = null },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(durationMs(drag?.let { (it * dur).toLong() } ?: st.positionMs), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (st.durationMs > 0) durationMs(st.durationMs) else "", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pb.toggleShuffle() }) { Icon(Sym.Shuffle.fill, tr("Shuffle"), tint = if (pb.shuffle) Teal else c.onSurfaceVariant) }
            IconButton(onClick = { pb.previous() }, Modifier.size(52.dp)) { Icon(Sym.SkipPrevious.fill, tr("Previous"), Modifier.size(34.dp)) }
            FilledIconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }, Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.onBackground, contentColor = c.background)) {
                if (st.buffering && !st.playing) Busy(Modifier.size(28.dp))
                else Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"), Modifier.size(38.dp))
            }
            IconButton(onClick = { pb.skipNext() }, Modifier.size(52.dp)) { Icon(Sym.SkipNext.fill, tr("Next"), Modifier.size(34.dp)) }
            IconButton(onClick = {
                pb.repeat = (pb.repeat + 1) % 3
                app.toast(tr(when (pb.repeat) { 1 -> "Repeat all"; 2 -> "Repeat this song"; else -> "Repeat off" }))
            }) {
                Icon(if (pb.repeat == 2) Sym.RepeatOne.fill else Sym.Repeat.fill, tr("Repeat"), tint = if (pb.repeat > 0) Teal else c.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { like() }) { Icon(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like")) }
            if (!pb.withVideo) IconButton(onClick = { pb.showVideo() }) { Icon(Sym.MusicVideo.outline, tr("Watch video")) }
            else IconButton(onClick = { app.go(Screen.Watch(s.id)) }) { Icon(Sym.Fullscreen.outline, tr("Open the video")) }
            if (s.albumId.isNotEmpty()) IconButton(onClick = { app.go(Screen.Album(s.albumId, s.album)) }) { Icon(Sym.Album.outline, tr("Go to album")) }
            if (s.artistId.startsWith("UC")) IconButton(onClick = { app.go(Screen.Artist(s.artistId, s.artist)) }) { Icon(Sym.Person.outline, tr("Go to artist")) }
            IconButton(onClick = { pb.playRadio(s) }) { Icon(Sym.Explore.outline, tr("Start radio")) }
        }
    }
    }
}

@Composable
private fun LyricsPanel(app: AppState, s: Song) {
    var found by remember(s.id) { mutableStateOf(lyricsCache[s.id]) }
    var done by remember(s.id) { mutableStateOf(lyricsCache.containsKey(s.id)) }
    LaunchedEffect(s.id) {
        if (done) return@LaunchedEffect
        val f = runCatching { Lyrics.find(s) }.getOrNull()
        lyricsCache[s.id] = f
        found = f; done = true
    }
    val f = found
    when {
        !done -> Busy(Modifier.fillMaxWidth().padding(32.dp))
        f == null || f.lines.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("No lyrics found for this song"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(tr("Lyrics come from LRCLIB"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val st by app.player.state.collectAsState()
            val list = rememberLazyListState()
            val cur = if (!f.synced) -1 else f.lines.indexOfLast { it.ms <= st.positionMs + 300 }
            LaunchedEffect(cur) { if (cur >= 0) list.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
            val c = MaterialTheme.colorScheme
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp)) {
                itemsIndexed(f.lines) { i, l ->
                    val now = i == cur
                    Text(
                        l.text.ifEmpty { "♪" },
                        Modifier.fillMaxWidth().then(if (f.synced) Modifier.tvClickable(RoundedCornerShape(8.dp)) { app.player.seekTo(l.ms) } else Modifier)
                            .padding(vertical = if (f.synced) 6.dp else 2.dp, horizontal = 4.dp),
                        style = if (f.synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (now) FontWeight.Bold else null,
                        color = if (!f.synced || now) c.onBackground else c.onSurfaceVariant.copy(alpha = if (i < cur) 0.55f else 0.8f),
                    )
                }
                item {
                    Text(tr("Lyrics come from LRCLIB"), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(app: AppState) {
    val pb = app.playback
    val list = rememberLazyListState(initialFirstVisibleItemIndex = pb.songAt.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(pb.songs, key = { i, s -> "$i" + s.id }) { i, s ->
            SongRow(app, s, playing = i == pb.songAt, onRemove = if (i > pb.songAt) ({ pb.removeSong(i) }) else null) { pb.playSongAt(i) }
        }
        item {
            Text(tr(if (pb.repeat == 1) "The queue starts again after the last song" else "Songs like these are added near the end"),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
