package app.tubehaus.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Dash
import app.tubehaus.engine.Format
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.CastDevice
import app.tubehaus.ui.CastLink
import app.tubehaus.ui.Caster
import app.tubehaus.ui.CastingView
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/** Google's Default Media Receiver: the player every Cast device has, no app of our own needed */
private const val RECEIVER_APP = "CC1AD845"

/**
 * Google Cast without Google's library (so no Play Services, and the same code on the desktop):
 * devices found with multicast DNS ([Mdns]), driven over the Cast v2 protocol ([CastChannel]),
 * playing streams this device hands on ([CastServer]).
 *
 * [awake] is told which device is being cast to (null: none any more), so Android can keep
 * running and keep the Wi-Fi up meanwhile; [multicast] brackets each search (Android must be
 * asked to let multicast packets in).
 */
class GoogleCast(
    private val scope: CoroutineScope,
    private val awake: (CastDevice?) -> Unit = {},
    private val multicast: (Boolean) -> Unit = {},
) : Caster {
    override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val scanning = MutableStateFlow(false)

    override fun scan() {
        if (scanning.value) return
        scanning.value = true
        scope.launch(Dispatchers.IO) {
            val found = LinkedHashMap<String, CastDevice>()
            runCatching { multicast(true) }
            try {
                Mdns.discover(3500) { d ->
                    synchronized(found) {
                        found[d.id] = d
                        devices.value = found.values.sortedBy { it.name.lowercase() }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { multicast(false) }
                devices.value = synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
                scanning.value = false
            }
        }
    }

    override suspend fun connect(d: CastDevice): CastLink = withContext(Dispatchers.IO) {
        val p = CastPlayer(d, scope)
        try { p.open() } catch (e: Exception) { p.disconnect(); throw e }
        p
    }

    override fun casting(d: CastDevice?) {
        if (d == null) CastServer.forget()
        runCatching { awake(d) }
    }
}

/** What plays on one Cast device: the app's player controls drive it like the one on this device. */
internal class CastPlayer(override val device: CastDevice, private val scope: CoroutineScope) : CastLink {
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = st
    override val lost = MutableStateFlow<String?>(null)

    private var ch: CastChannel? = null
    @Volatile private var transport: String? = null
    @Volatile private var session: String? = null
    @Volatile private var media: Int? = null
    @Volatile private var left = false
    private var stream: Stream? = null
    private var opts = PlayOptions()
    private var caption: Caption? = null
    private var translateTo: String? = null
    /** the subtitle track sent with the video (it can be shown and hidden without loading again) */
    private var sentCaption: Caption? = null
    private var sentTranslate: String? = null
    private var height = 0
    private var audioLang: String? = null
    private var loop = false
    private var loadJob: Job? = null
    private var poll: Job? = null
    /** while a LOAD is on its way, statuses about what played before are ignored */
    @Volatile private var loading = false

    suspend fun open() {
        val c = CastChannel(device.host, device.port)
        ch = c
        c.onMessage = ::onMessage
        c.onClosed = { why -> if (!left) lost.value = why }
        c.send(NS_CONNECTION, "receiver-0", json { put("type", "CONNECT") }, withId = false)
        val r = c.request(NS_RECEIVER, "receiver-0", json { put("type", "LAUNCH"); put("appId", RECEIVER_APP) }, 20_000)
        if (!takeApp(r)) {
            // some devices answer the launch before the player is up: ask again
            delay(1500)
            if (!takeApp(c.request(NS_RECEIVER, "receiver-0", json { put("type", "GET_STATUS") }))) throw IOException("the device didn't start its player")
        }
        c.send(NS_CONNECTION, transport!!, json { put("type", "CONNECT") }, withId = false)
        poll = scope.launch(Dispatchers.IO) {
            while (isActive && !left) {
                delay(1000)
                val m = media
                val t = transport
                if (m != null && t != null && !loading) ch?.send(NS_MEDIA, t, json { put("type", "GET_STATUS"); put("mediaSessionId", m) })
            }
        }
    }

    /** our player app in a receiver status: remember where to talk to it; false when it isn't running */
    private fun takeApp(r: JsonObject): Boolean {
        val s = r.obj("status") ?: return false
        s.obj("volume")?.let { v -> st.value = st.value.copy(volume = v.num("level")?.toFloat() ?: st.value.volume, muted = (v["muted"] as? JsonPrimitive)?.content == "true") }
        val apps = s["applications"] as? JsonArray ?: return false
        val app = apps.mapNotNull { it as? JsonObject }.firstOrNull { it.str("appId") == RECEIVER_APP } ?: return false
        transport = app.str("transportId") ?: return false
        session = app.str("sessionId")
        return true
    }

    private fun onMessage(ns: String, source: String, m: JsonObject) {
        when (ns) {
            NS_RECEIVER -> if (m.str("type") == "RECEIVER_STATUS") {
                val had = session
                if (!takeApp(m) && had != null && transport != null && !left) {
                    // the TV went back to its home screen, or another phone started something else
                    lost.value = "The device stopped playing"
                }
            }
            NS_MEDIA -> when (m.str("type")) {
                "MEDIA_STATUS" -> (m["status"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.let(::status) }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "ERROR" -> if (!loading) failed()
            }
            NS_CONNECTION -> if (m.str("type") == "CLOSE" && source == transport && !left) lost.value = "The device closed the connection"
        }
    }

    private fun failed() {
        st.value = st.value.copy(buffering = false, playing = false, error = tr("The TV couldn't play this video"))
    }

    /** a MEDIA_STATUS of what plays now */
    private fun status(m: JsonObject) {
        val id = m.num("mediaSessionId")?.toInt()
        if (loading) return
        if (id != null) media = id
        val ps = m.str("playerState") ?: return
        val idle = m.str("idleReason")
        if (ps == "IDLE" && idle == "FINISHED" && loop) {
            transport?.let { t -> media?.let { ms -> ch?.send(NS_MEDIA, t, json { put("type", "SEEK"); put("mediaSessionId", ms); put("currentTime", 0); put("resumeState", "PLAYBACK_START") }) } }
            return
        }
        if (ps == "IDLE" && idle == "ERROR") { failed(); return }
        val pos = m.num("currentTime")?.let { (it * 1000).toLong() }
        val dur = m.obj("media")?.num("duration")?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        val ended = ps == "IDLE" && idle == "FINISHED"
        val cur = st.value
        st.value = cur.copy(
            playing = ps == "PLAYING" || (ps == "BUFFERING" && cur.playing),
            buffering = ps == "BUFFERING",
            positionMs = if (ended) cur.durationMs.takeIf { it > 0 } ?: pos ?: cur.positionMs else pos ?: cur.positionMs,
            durationMs = dur ?: cur.durationMs,
            ended = ended,
            speed = m.num("playbackRate")?.toFloat() ?: cur.speed,
            error = null,
        )
    }

    // ------------------------------------------------------------------ VideoPlayer

    override fun load(s: Stream, o: PlayOptions) {
        val sameVideo = stream?.id == s.id
        stream = s
        opts = o
        loop = o.loop
        caption = o.captions
        translateTo = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        sentCaption = caption ?: o.standbyCaption
        sentTranslate = if (caption != null) translateTo else o.standbyTranslate
        if (!sameVideo) { audioLang = null; height = 0 }
        val heights = castable(s.video).map { it.height }.filter { it > 0 }.distinct().sortedDescending()
        val langs = s.audio.mapNotNull { f -> f.audioLang?.let { it to (f.audioTrackName ?: it) } }.distinctBy { it.first }
        st.value = PlayerState(
            videoId = s.id, buffering = true, durationMs = s.lengthSec * 1000L, speed = o.speed,
            captions = caption, captionsTranslated = translateTo.takeIf { caption != null }, heights = heights, height = height,
            audioTracks = langs, audioTrack = audioLang ?: s.audio.firstOrNull { it.audioDefault }?.audioLang,
            volume = st.value.volume, muted = st.value.muted, positionMs = o.startMs,
        )
        send(o.startMs, o.autoplay)
    }

    /** what the TV's player takes: H.264 (every Cast device has it), VP9 only for a video without it */
    private fun castable(v: List<Format>): List<Format> {
        val ok = v.filter { it.indexRange != null }
        val family = if (ok.any { it.codecFamily == "h264" }) "h264" else "vp9"
        return ok.filter { it.codecFamily == family }
    }

    /**
     * A saved copy the TV can't take (VP9 above 1080p, Opus sound: what a 4K download is) goes as
     * the same video from YouTube instead; the copy keeps playing on this device as before.
     */
    private suspend fun forTv(s: Stream): Stream {
        val saved = (s.video + s.audio).any { it.url.startsWith("file:") }
        if (!saved || s.live) return s
        val cap = if (height > 0) Int.MAX_VALUE else minOf(opts.maxHeight, 1080)
        val picture = castable(s.video).any { it.height <= cap }
        val sound = s.audio.any { it.indexRange != null && it.codecFamily == "aac" }
        if (picture && sound) return s
        val online = YouTube.stream(s.id)
        if (stream?.id != s.id) return online
        stream = online
        st.value = st.value.copy(
            heights = castable(online.video).map { it.height }.filter { it > 0 }.distinct().sortedDescending(),
            audioTracks = online.audio.mapNotNull { f -> f.audioLang?.let { it to (f.audioTrackName ?: it) } }.distinctBy { it.first },
            audioTrack = audioLang ?: online.audio.firstOrNull { it.audioDefault }?.audioLang,
        )
        return online
    }

    /** (re)loads the current video on the TV at [startMs] */
    private fun send(startMs: Long, autoplay: Boolean) {
        val s0 = stream ?: return
        val t = transport ?: return
        loadJob?.cancel()
        loading = true
        loadJob = scope.launch(Dispatchers.IO) {
            try {
                val s = forTv(s0)
                val base = CastServer.base(device.host)
                val info = mediaInfo(s, base)
                val showCaption = caption != null && sentCaption != null
                val r = ch!!.request(NS_MEDIA, t, json {
                    put("type", "LOAD")
                    session?.let { put("sessionId", it) }
                    put("media", info)
                    put("autoplay", autoplay)
                    if (!s.live) put("currentTime", startMs / 1000.0)
                    putJsonArray("activeTrackIds") { if (showCaption) add(1) }
                }, 30_000)
                loading = false
                when (r.str("type")) {
                    "MEDIA_STATUS" -> {
                        (r["status"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.let(::status) }
                        if (opts.speed != 1f) setSpeed(opts.speed)
                    }
                    else -> failed()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loading = false
                if (!left) failed()
            }
        }
    }

    private fun mediaInfo(s: Stream, base: String): JsonObject = buildJsonObject {
        if (s.live || s.video.isEmpty()) {
            put("contentId", CastServer.proxy(base, s.hls ?: throw IOException("no stream")))
            put("contentType", "application/x-mpegURL")
            put("streamType", "LIVE")
        } else {
            val video = castable(s.video).map { it.copy(url = CastServer.proxy(base, it.url)) }
                .filter { height <= 0 || it.height == height }
            val aac = s.audio.filter { it.indexRange != null && it.codecFamily == "aac" && !it.drc }.ifEmpty { s.audio.filter { it.indexRange != null && it.codecFamily == "aac" } }
            val lang = audioLang
            val audio = (if (lang != null) aac.filter { it.audioLang == lang } else emptyList())
                .ifEmpty { aac.filter { it.audioDefault } }.ifEmpty { aac }
                .map { it.copy(url = CastServer.proxy(base, it.url)) }
            val cap = if (height > 0) Int.MAX_VALUE else minOf(opts.maxHeight, 1080)
            val mpd = Dash.manifest(s.copy(video = video, audio = audio), listOf("h264", "vp9"), cap)
            put("contentId", CastServer.text(base, mpd, "application/dash+xml", "mpd"))
            put("contentType", "application/dash+xml")
            put("streamType", "BUFFERED")
            put("duration", s.lengthSec.toDouble())
        }
        putJsonObject("metadata") {
            put("metadataType", 0)
            put("title", opts.title.ifEmpty { s.title })
            put("subtitle", opts.artist.ifEmpty { s.author })
            if (opts.artwork.isNotEmpty()) putJsonArray("images") { addJsonObject { put("url", opts.artwork) } }
        }
        sentCaption?.let { c ->
            putJsonArray("tracks") {
                addJsonObject {
                    put("trackId", 1)
                    put("type", "TEXT")
                    put("subtype", "SUBTITLES")
                    put("trackContentId", CastServer.proxy(base, YouTube.captionUrl(c, sentTranslate)))
                    put("trackContentType", "text/vtt")
                    put("language", sentTranslate ?: c.lang)
                    put("name", c.name)
                }
            }
        }
    }

    private fun cmd(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val t = transport ?: return
        val m = media ?: return
        ch?.send(NS_MEDIA, t, json { put("type", type); put("mediaSessionId", m); extra() })
    }

    override fun play() {
        if (st.value.ended) { seekTo(0); cmd("PLAY") } else cmd("PLAY")
        st.value = st.value.copy(playing = true, ended = false)
    }

    override fun pause() {
        cmd("PAUSE")
        st.value = st.value.copy(playing = false)
    }

    override fun seekTo(ms: Long) {
        cmd("SEEK") { put("currentTime", ms / 1000.0) }
        st.value = st.value.copy(positionMs = ms, ended = false)
    }

    override fun setSpeed(x: Float) {
        cmd("SET_PLAYBACK_RATE") { put("playbackRate", x.toDouble()) }
        opts = opts.copy(speed = x)
        st.value = st.value.copy(speed = x)
    }

    override fun setHeight(h: Int) {
        if (h == height) return
        height = h
        st.value = st.value.copy(height = h)
        again()
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        caption = c
        this.translateTo = translateTo
        st.value = st.value.copy(captions = c, captionsTranslated = translateTo.takeIf { c != null })
        // off, or back on with the track that went with the video: just show or hide it
        if (c == null || (c == sentCaption && translateTo == sentTranslate)) {
            cmd("EDIT_TRACKS_INFO") { putJsonArray("activeTrackIds") { if (c != null) add(1) } }
            return
        }
        sentCaption = c
        sentTranslate = translateTo
        again()
    }

    override fun setAudioTrack(lang: String) {
        audioLang = lang
        st.value = st.value.copy(audioTrack = lang)
        again()
    }

    /** the same video loaded again where it is (another quality, subtitle track or language) */
    private fun again() {
        val s = st.value
        if (stream == null) return
        st.value = s.copy(buffering = true)
        send(s.positionMs, s.playing || s.buffering)
    }

    override fun setLoop(on: Boolean) { loop = on }

    override fun stop() {
        loadJob?.cancel()
        loading = false
        cmd("STOP")
        media = null
        stream = null
        st.value = PlayerState(volume = st.value.volume, muted = st.value.muted)
    }

    override val hasVolume: Boolean get() = true

    override fun setVolume(v: Float) {
        val x = v.coerceIn(0f, 1f)
        ch?.send(NS_RECEIVER, "receiver-0", json { put("type", "SET_VOLUME"); putJsonObject("volume") { put("level", x.toDouble()) } })
        st.value = st.value.copy(volume = x)
    }

    override fun setMuted(on: Boolean) {
        ch?.send(NS_RECEIVER, "receiver-0", json { put("type", "SET_VOLUME"); putJsonObject("volume") { put("muted", on) } })
        st.value = st.value.copy(muted = on)
    }

    override fun disconnect() {
        if (left) return
        left = true
        poll?.cancel()
        loadJob?.cancel()
        val c = ch ?: return
        // the TV leaves the video and goes back to its own screen
        session?.let { sid -> c.send(NS_RECEIVER, "receiver-0", json { put("type", "STOP"); put("sessionId", sid) }) }
        transport?.let { t -> c.send(NS_CONNECTION, t, json { put("type", "CLOSE") }, withId = false) }
        c.send(NS_CONNECTION, "receiver-0", json { put("type", "CLOSE") }, withId = false)
        c.close()
    }

    @Composable
    override fun View(modifier: Modifier) = CastingView(device, modifier)
}

private inline fun json(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(b)
