#!/usr/bin/env python3
"""TubeHaus server - the API behind the TubeHaus web app.

It asks YouTube itself (the built-in engine below, which answers in Invidious' API shapes, so
the web app and the apps' server mode read it as they read Invidious) and adds:
  * one shared library (subscriptions, history, resume points, playlists, likes)
    so every device sees the same state;
  * YouTube live streams as HLS, with segments pulled ahead of the player;
  * live captions transcribed on the fly with whisper.cpp;
  * machine-translated subtitles for videos that have none in your language;
  * "send to TV": a phone starts playback on another open TubeHaus screen;
  * high-quality offline save (separate video + audio merged with ffmpeg);
  * a cache in front of /api/v1/videos so clicks answer instantly.

Configuration comes from environment variables - see .env.example.
Python standard library only; ffmpeg, yt-dlp and whisper.cpp are optional.
"""
import base64, json, os, re, subprocess, threading, time, urllib.request, urllib.parse, urllib.error
import datetime, secrets
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def env(key, default=""):
    return os.environ.get(key, default)


BIND = (env("HT_BIND", "127.0.0.1"), int(env("HT_PORT", "3010")))
STATE = env("HT_STATE", "./state")                       # library, subtitle cache
PUBLIC = env("HT_PUBLIC_URL", "http://localhost:8080").rstrip("/")   # where browsers open TubeHaus
# YouTube refuses most cloud/VPS addresses ("Sign in to confirm you're not a bot").
# Sending YouTube traffic out through Cloudflare WARP (warp-cli in proxy mode,
# which listens on 127.0.0.1:40000 and speaks HTTP CONNECT) is what makes this work.
YT_PROXY = env("HT_YT_PROXY", "127.0.0.1:40000")        # "" = connect to YouTube directly
WARP_ROTATE = env("HT_WARP_ROTATE", "1") == "1"         # get a new WARP address when YouTube blocks us
YTDLP = env("HT_YTDLP", "yt-dlp")
LT = env("HT_LIBRETRANSLATE", "")                        # optional LibreTranslate URL (translation fallback)
SUB_LANG = env("HT_SUB_LANG", "en")                      # default target of translated subtitles (the app asks for its own language)
WHISPER_BIN = env("HT_WHISPER_BIN", "")                  # whisper.cpp "whisper-cli"; empty = no live captions
WHISPER_MODEL = env("HT_WHISPER_MODEL", "")              # e.g. ggml-base-q5_1.bin
WHISPER_LANG = env("HT_WHISPER_LANG", "auto")
# more live-caption engines to choose from in the player, as "name=/path/to/cli,...": each takes whisper-cli's
# arguments (-m -f -l ...) and prints its "[00:00:01.000 --> 00:00:03.000]  text" lines
LIVECC_ALT = env("HT_LIVECC_ALT", "")
YTDL_DIR = env("HT_DOWNLOAD_DIR", "/var/tmp/tubehaus-dl")
# Public instance: every visitor keeps their own library in their browser, and the server
# keeps none. Anything that would be shared between strangers (library, send to TV,
# server downloads) is switched off.
PUBLIC_MODE = env("HT_PUBLIC_MODE", "0") == "1"
# Load limits, so a crowd can't take the server (or your YouTube access) down. 0 = no limit.
def env_int(key, default):
    """An empty or unset variable means the default (docker compose passes unset ones as "")."""
    try:
        return int(env(key, "").strip() or default)
    except ValueError:
        return default


MAX_CONN = env_int("HT_MAX_CONNECTIONS", 256)            # requests handled at the same time
# the key for the usage page (/y/admin.html, public instances); empty = no such page
ADMIN_TOKEN = env("HT_ADMIN_TOKEN", "")
MAX_VIEWERS = env_int("HT_MAX_VIEWERS", 0)               # people watching at once (by IP, 3 min window)
RATE_PER_MIN = env_int("HT_RATE_PER_MIN", 120 if PUBLIC_MODE else 0)   # API requests per IP per minute
MAX_HEIGHT = env_int("HT_MAX_HEIGHT", 0)                 # highest quality offered to players, e.g. 1080
# The visitor's address comes from X-Forwarded-For only when the request came from this machine or a
# private network (a reverse proxy), and it's read from the end, past the proxies' own private
# addresses: the start of that header is whatever the visitor wrote. Proxies with public addresses in
# front (Cloudflare's orange cloud) can't be told from visitors, so say how many there are.
PUBLIC_PROXIES = max(0, env_int("HT_PUBLIC_PROXIES", 0))
MAX_BODY = env_int("HT_MAX_BODY_MB", 64) * 1024 * 1024     # largest request body (a big YouTube history import fits)
# Sign-in required: username + password only, no e-mail. The reverse proxy asks GET /api/p/auth/check
# before letting anyone in (deploy/Caddyfile.public, or TUBEHAUS_AUTH=accounts with docker compose).
# On a public instance libraries stay in each visitor's browser; on a household server every
# account has a library of its own on the server.
ACCOUNTS = env("HT_ACCOUNTS", "0") == "1" or env("TUBEHAUS_AUTH", "") == "accounts"
MAX_ACCOUNTS = env_int("HT_MAX_ACCOUNTS", 1000)
def _names(v, n):
    """a name, or one per language as {"ko": "홈튜브", "en": "HomeTube"} (the page picks the visitor's language)"""
    if isinstance(v, dict):
        d = {str(k).strip()[:8]: str(s).strip()[:n] for k, s in v.items() if str(s or "").strip()}
        return d or None
    return str(v or "").strip()[:n] or None


def _name(raw):
    raw = raw.strip()
    if raw.startswith("{"):
        try:
            return _names(json.loads(raw), 40)
        except ValueError:
            print("HT_NAME looks like JSON but is not, used as it is", flush=True)
    return raw[:40] or None


# The instance's own name in the page title and menu (empty = TubeHaus, in the visitor's language)
NAME = _name(env("HT_NAME", ""))


def _links(raw):
    """HT_LINKS: extra menu links, as JSON [{"name": "Home hub", "url": "https://..."}] (or /path on this site);
    a name may be one per language, {"ko": "우리집 허브", "en": "Home hub"}"""
    try:
        items = json.loads(raw) if raw.strip() else []
    except ValueError:
        print("HT_LINKS is not JSON, ignored", flush=True)
        return []
    out = []
    for x in items if isinstance(items, list) else []:
        if not isinstance(x, dict):
            continue
        name, url = _names(x.get("name"), 60), str(x.get("url") or "").strip()[:500]
        if name and re.match(r"(?i)^(https?://|/(?!/))", url):
            out.append({"name": name, "url": url})
    return out[:12]


LINKS = _links(env("HT_LINKS", ""))

lock = threading.Lock()
LIVE_CACHE = {}
# every request to YouTube itself goes through YT_PROXY when one is set
YT = urllib.request.build_opener(urllib.request.ProxyHandler(
    {"http": "http://" + YT_PROXY, "https": "http://" + YT_PROXY} if YT_PROXY else {}))
LIVE_ORIGIN = PUBLIC
LIVE_BASE = LIVE_ORIGIN + "/api/p/live/s/"   # + base64url(segment url) + ".ts"; proxies re-decode %-escapes

# ---------- /api/v1/videos cache ----------
# Invidious re-fetches any videos row older than ten minutes, and that refetch is
# a YouTube round trip: measured 0.9-2.1 s on a click, 0.003 s warm.
# The signed googlevideo URLs in the answer stay valid for six hours, so a recent
# copy is just as playable. Serve it straight back, refresh behind the user once
# it ages, and warm the recommendations the user is most likely to click next.
VID_FRESH = 900        # younger than this: hand it over, do nothing else
VID_STALE = 7200       # older than this: fetch synchronously (URLs would be near expiry)
VID_KEEP = 400         # entries
# recommended videos warmed after a cold click (off on public instances); 0 = never look anything
# up that nobody asked for, the least traffic to YouTube, at the cost of slower next clicks
VID_PREFETCH = max(0, env_int("HT_VID_PREFETCH", 4))
vid_cache = {}         # path -> (ts, code, body)
vid_lk = threading.Lock()
vid_busy = set()       # paths currently being refreshed
vid_wait = {}          # path -> Event: a cold fetch in flight; others wait for it instead of asking YouTube again


def vid_store(key, code, body):
    """Cache only plain, finished videos - live and premieres must stay fresh."""
    try:
        d = json.loads(body)
    except Exception:
        return None
    if code != 200 or not isinstance(d, dict) or not d.get("videoId"):
        return None
    if d.get("liveNow") or d.get("premiereTimestamp"):
        return d
    with vid_lk:
        vid_cache[key] = (time.time(), code, body)
        if len(vid_cache) > VID_KEEP:
            for k, _ in sorted(vid_cache.items(), key=lambda kv: kv[1][0])[:len(vid_cache) - VID_KEEP]:
                vid_cache.pop(k, None)
    return d


# YouTube's player response only has the upload *date*, so Invidious reports a video
# from this morning as published at 00:00 UTC ("17 hours ago" on the watch page while
# the feeds say "2 hours ago"). The channel's RSS has the exact time: use it for
# recent videos whose timestamp sits exactly on midnight.
PUB_RSS = {}           # cid -> (fetched at, {videoId: published})
PUB_RSS_TTL = 600


def exact_published(d):
    pub, cid, vid = d.get("published") or 0, d.get("authorId") or "", d.get("videoId")
    if not pub or pub % 86400 or time.time() - pub > 3 * 86400 or not re.fullmatch(r"UC[\w-]{22}", cid):
        return None
    for it in yt_feed["items"].get(cid) or []:       # the subscription feed may already know it
        if it.get("id") == vid and it.get("published"):
            return it["published"]
    if time.time() < RSS_DOWN["until"]:            # YouTube's RSS is down: keep the date as it is
        return None
    hit = PUB_RSS.get(cid)
    if not hit or time.time() - hit[0] > PUB_RSS_TTL:
        ns = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015"}
        found = {}
        try:
            root = ET.fromstring(get(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}", 5))
            for e in root.findall("a:entry", ns):
                try:
                    found[e.findtext("yt:videoId", "", ns)] = int(datetime.datetime.fromisoformat(
                        e.findtext("a:published", "", ns).replace("Z", "+00:00")).timestamp())
                except ValueError:
                    pass
        except Exception as ex:
            log("videos", "published RSS FAIL %s %s" % (cid, repr(ex)[:80]))
        hit = PUB_RSS[cid] = (time.time(), found)
        if len(PUB_RSS) > 300:
            for k in sorted(PUB_RSS, key=lambda k: PUB_RSS[k][0])[:100]:
                PUB_RSS.pop(k, None)
    exact = hit[1].get(vid)
    # within two days of the date YouTube gave (that date can be US Pacific, not UTC)
    return exact if exact and 0 <= exact - pub < 2 * 86400 else None


def vid_fetch_once(key):
    """Many viewers clicking the same uncached video at once: one YouTube round trip, shared."""
    with vid_lk:
        ev = vid_wait.get(key)
        mine = ev is None
        if mine:
            ev = vid_wait[key] = threading.Event()
    if not mine:
        ev.wait(60)
        with vid_lk:
            hit = vid_cache.get(key)
        if hit:
            return hit[1], hit[2]
        return vid_fetch(key)        # it failed or wasn't cacheable (live): ask ourselves
    try:
        return vid_fetch(key)
    finally:
        with vid_lk:
            vid_wait.pop(key, None)
        ev.set()


def vid_fetch(key):
    """key is the request path; returns (code, body) with hlsUrl patched in."""
    t0 = time.time()
    u = urllib.parse.urlsplit(key)
    code, d = eng_api(u.path, dict(urllib.parse.parse_qsl(u.query)))
    body = json.dumps(d, ensure_ascii=False).encode()
    if code >= 400:
        log("videos", "%d %s" % (code, key.split("?")[0][:60]), t0)
        return code, body
    try:
        d = json.loads(body)
    except Exception:
        d = None
    if isinstance(d, dict) and d.get("videoId"):
        if d.get("liveNow") and not d.get("hlsUrl"):
            d["hlsUrl"] = "%s/api/p/live/%s.m3u8" % (LIVE_ORIGIN, d["videoId"])
            body = json.dumps(d, ensure_ascii=False).encode()
            log("videos", "hlsUrl injected %s %d" % (d["videoId"], code), t0, every=0)
        exact = exact_published(d)
        if exact:
            d["published"] = exact
            body = json.dumps(d, ensure_ascii=False).encode()
        # the caption files come through /companion/api/v1/captions/ (the address the apps know)
        caps = [c for c in d.get("captions") or [] if (c.get("url") or "").startswith("/api/v1/captions/")]
        for c in caps:
            c["url"] = "/companion" + c["url"]
        if caps:
            body = json.dumps(d, ensure_ascii=False).encode()
    vid_store(key, code, body)
    return code, body


def vid_bg(key, prefetch_from=None):
    """Refresh one path (optional), then warm the videos it recommends."""
    if key:
        try:
            vid_fetch(key)
        except Exception as ex:
            log("videos", "refresh FAIL %s %s" % (key.split("/")[-1][:20], repr(ex)[:80]))
        finally:
            with vid_lk:
                vid_busy.discard(key)
    if not prefetch_from or PUBLIC_MODE:
        return
    try:
        ids = [v.get("videoId") for v in (prefetch_from.get("recommendedVideos") or [])]
        warmed = 0
        for vid in ids:
            if warmed >= VID_PREFETCH:
                break
            if not vid or not re.fullmatch(r"[\w-]{11}", vid):
                continue
            k = "/api/v1/videos/" + vid
            with vid_lk:
                hot = k in vid_cache
            if hot:
                continue
            time.sleep(1.0)      # a human clicking, not a crawler
            vid_fetch(k)
            warmed += 1
        if warmed:
            log("videos", "prefetched %d recommended" % warmed, every=300)
    except Exception as ex:
        log("videos", "prefetch FAIL %s" % repr(ex)[:80])

# ---------- HLS for ordinary videos (the iPad/iPhone app) ----------
# Apple's player plays HLS, not DASH, and YouTube's own HLS is only handed to its
# iOS client. The same MP4 streams the DASH manifest lists work as HLS too: each
# format's index (sidx box) gives its segments as byte ranges of the one file,
# fetched through this server's stream proxy like everything else.
HLS_KEEP = 300
hls_cache = {}         # (id, itag) -> (ts, playlist text)
hls_lk = threading.Lock()


def _video_json(vid):
    key = "/api/v1/videos/" + vid
    with vid_lk:
        hit = vid_cache.get(key)
    code, body = (hit[1], hit[2]) if hit and time.time() - hit[0] < VID_STALE else vid_fetch_once(key)
    if code != 200:
        raise ValueError("HTTP %d" % code)
    return json.loads(body)


def _companion_url(url):
    """a googlevideo.com stream address as this server's proxy path for it (/companion/videoplayback, the path
    Invidious' companion used, so the apps' server mode finds it where it always did)"""
    u = urllib.parse.urlsplit(url)
    return "/companion/videoplayback?%s&host=%s" % (u.query, u.netloc)


def _atrack(f):
    """an audio format's track: {"id", "lang", "kind" (original, dubbed, dubbed-auto, descriptive), "orig", "drc", "name"}.
    From audioTrack when YouTube gives it, else from the stream's xtags (acont=dubbed:lang=ko, drc=1 = stable volume)."""
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(f.get("url") or "").query)
    x = dict(p.split("=", 1) for p in (q.get("xtags") or [""])[0].split(":") if "=" in p)
    t = f.get("audioTrack") or {}
    lang = x.get("lang") or (t.get("id") or "").split(".")[0]
    kind = x.get("acont") or ("original" if t.get("isDefault") else "dubbed" if t else "")
    return {"id": t.get("id") or ("%s.%s" % (lang, kind) if lang else ""), "lang": lang, "kind": kind,
            "orig": kind == "original" or bool(t.get("isDefault")) or not lang, "drc": x.get("drc") == "1",
            "name": t.get("displayName") or ""}


def _hls_formats(d):
    """(video formats, [the best AAC audio per language, the original first]) Apple's player can take: H.264 and AAC in MP4"""
    fs = [f for f in d.get("adaptiveFormats") or [] if f.get("url") and f.get("init") and f.get("index")]
    video = [f for f in fs if (f.get("type") or "").startswith("video/mp4") and 'codecs="avc1' in f["type"]]
    best = {}
    for f in fs:
        if not (f.get("type") or "").startswith("audio/mp4"):
            continue
        t = _atrack(f)
        if t["drc"]:
            continue
        k = (t["lang"], t["kind"])
        if k not in best or int(f.get("bitrate") or 0) > int(best[k].get("bitrate") or 0):
            best[k] = f
    audio = sorted(best.values(), key=lambda f: not _atrack(f)["orig"])
    return video, audio


def _codecs(f):
    m = re.search(r'codecs="([^"]+)"', f.get("type") or "")
    return m.group(1) if m else ""


def hls_master(vid, h=0):
    """all qualities, or with h only the best one up to h high (so the player can't step down on its own)"""
    d = _video_json(vid)
    video, audios = _hls_formats(d)
    if not video or not audios:
        raise ValueError("no H.264/AAC streams")
    audio = audios[0]
    if h:
        def height(f):
            m = re.search(r"x(\d+)", f.get("size") or "")
            return int(m.group(1)) if m else 0
        fit = [f for f in video if height(f) <= h] or [min(video, key=height)]
        video = [max(fit, key=lambda f: (height(f), int(f.get("fps") or 0), int(f.get("bitrate") or 0)))]
    out = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"]
    # one audio per language, the original as the default; the device's own player lists them in its audio menu
    names = set()
    for a in audios:
        t = _atrack(a)
        if len(audios) == 1 or not t["lang"]:
            name, ref, lang = "Audio", a["itag"], ""
        else:
            name = t["name"] or t["lang"] + {"original": " (original)", "descriptive": " (audio description)"}.get(t["kind"], "")
            ref, lang = "%s-%s" % (a["itag"], t["lang"]), ',LANGUAGE="%s"' % t["lang"]
            if t["kind"] == "descriptive":
                ref += "-ad"
        if name in names:
            continue
        names.add(name)
        out.append('#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="%s"%s,DEFAULT=%s,AUTOSELECT=YES%s,URI="%s/%s.m3u8"' % (
            name.replace('"', "'"), lang, "YES" if a is audio else "NO",
            ',CHARACTERISTICS="public.accessibility.describes-video"' if t["kind"] == "descriptive" else "", vid, ref))
    for f in sorted(video, key=lambda f: int(f.get("bitrate") or 0)):
        bw = int(f.get("bitrate") or 0) + int(audio.get("bitrate") or 0)
        attrs = 'BANDWIDTH=%d,CODECS="%s,%s",AUDIO="a"' % (bw, _codecs(f), _codecs(audio))
        if f.get("size"):
            attrs += ",RESOLUTION=" + f["size"]
        if f.get("fps"):
            attrs += ",FRAME-RATE=%d" % int(f["fps"])
        out += ["#EXT-X-STREAM-INF:" + attrs, "%s/%s.m3u8" % (vid, f["itag"])]
    return "\n".join(out) + "\n"


def _sidx(b):
    """[(size, duration in seconds)] and the offset of the first segment after the box, from an MP4 sidx box"""
    i = b.find(b"sidx")
    if i < 4:
        raise ValueError("no sidx box")
    box_end = i - 4 + int.from_bytes(b[i - 4:i], "big")
    p = i + 4
    version = b[p]
    p += 4 + 4                                           # version+flags, reference_ID
    scale = int.from_bytes(b[p:p + 4], "big"); p += 4
    n = 8 if version else 4
    p += n                                               # earliest_presentation_time
    first = int.from_bytes(b[p:p + n], "big"); p += n
    p += 2
    count = int.from_bytes(b[p:p + 2], "big"); p += 2
    refs = []
    for _ in range(count):
        size = int.from_bytes(b[p:p + 4], "big") & 0x7FFFFFFF
        dur = int.from_bytes(b[p + 4:p + 8], "big")
        refs.append((size, dur / scale))
        p += 12
    return refs, box_end + first


def dash_mpd(vid):
    """the DASH list of qualities for the web player, with every SDR video format: H.264 and AV1 (MP4)
    and VP9 (WebM). (The companion's list, used before the built-in engine, had only the MP4 ones.) HDR (VP9.2, BT.2020) is left out where the same size exists
    in SDR (HDR looks washed out on SDR screens); where it is the only one, as 8K often is, it stays."""
    d = _video_json(vid)
    fs = [f for f in d.get("adaptiveFormats") or [] if f.get("url") and f.get("init") and f.get("index")]
    if not fs:
        raise ValueError("no streams")
    esc = lambda s: str(s).replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")
    sets = {}                                  # (kind, mime, track) -> [formats], in YouTube's order
    seen = set()
    hdr = lambda f: "vp9.2" in (f.get("type") or "") or "BT2020" in str(f.get("colorInfo") or "")
    sdr_h = {f.get("size") for f in fs if (f.get("type") or "").startswith("video/") and not hdr(f)}
    for f in fs:
        mime = (f.get("type") or "").split(";")[0].strip()
        kind = mime.split("/")[0]
        if kind not in ("audio", "video"):
            continue
        if kind == "video" and hdr(f) and f.get("size") in sdr_h:   # HDR stays only where it's the sole one (often 8K)
            continue
        tr = _atrack(f) if kind == "audio" else {}
        if tr.get("drc"):                      # YouTube's "stable volume" copy
            continue
        key = (str(f.get("itag")), tr.get("id") or "")
        if key in seen:                        # (when the copy isn't marked: the second one of an itag)
            continue
        seen.add(key)
        sets.setdefault((kind, mime, tr.get("id") or ""), []).append(f)
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.500S" profiles="urn:mpeg:dash:profile:isoff-main:2011" '
           'type="static" mediaPresentationDuration="PT%dS"><Period>' % int(d.get("lengthSeconds") or 0)]
    for n, ((kind, mime, tid), group) in enumerate(sorted(sets.items(), key=lambda x: x[0][0])):
        tr = _atrack(group[0]) if kind == "audio" else {}
        lang = ' lang="%s"' % esc(tr["lang"]) if tr.get("lang") else ""
        out.append('<AdaptationSet id="%d" mimeType="%s" contentType="%s" startWithSAP="1" subsegmentAlignment="true"%s>' % (n, mime, kind, lang))
        if kind == "audio":
            # the original is "main" (the player starts with it); dubs are "dub", audio description "description"
            role = "main" if tr["orig"] else "description" if tr["kind"] == "descriptive" else "dub"
            out.append('<Role schemeIdUri="urn:mpeg:dash:role:2011" value="%s"/>' % role)
            if tr.get("name"):
                out.append("<Label>%s</Label>" % esc(tr["name"]))
        for f in group:
            attrs = 'id="%s" bandwidth="%d" codecs="%s"' % (esc(f.get("itag")), int(f.get("bitrate") or 0), esc(_codecs(f)))
            if kind == "video":
                m = re.match(r"(\d+)x(\d+)", f.get("size") or "")
                if m:
                    attrs += ' width="%s" height="%s"' % m.groups()
                if f.get("fps"):
                    attrs += ' frameRate="%d"' % int(f["fps"])
            else:
                attrs += ' audioSamplingRate="%d"' % int(f.get("audioSampleRate") or 48000)
            out.append("<Representation %s>" % attrs)
            if kind == "audio":
                out.append('<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="%d"/>' % int(f.get("audioChannels") or 2))
            out.append("<BaseURL>%s</BaseURL>" % esc(_companion_url(f["url"])))
            out.append('<SegmentBase indexRange="%s"><Initialization range="%s"/></SegmentBase></Representation>' % (esc(f["index"]), esc(f["init"])))
        out.append("</AdaptationSet>")
    out.append("</Period></MPD>")
    return "\n".join(out) + "\n"


def hls_media(vid, itag):
    """one format's list; itag is "140", or "140-ko" / "140-en-ad" for one language of a dubbed audio format"""
    with hls_lk:
        hit = hls_cache.get((vid, itag))
    if hit and time.time() - hit[0] < VID_FRESH:
        return hit[1]
    d = _video_json(vid)
    num, _, lang = itag.partition("-")
    ad = lang.endswith("-ad")
    lang = lang[:-3] if ad else lang
    cands = [f for f in d.get("adaptiveFormats") or [] if str(f.get("itag")) == num and f.get("index") and f.get("url")]
    if cands and (cands[0].get("type") or "").startswith("audio/"):
        tr = lambda f: _atrack(f)
        cands = [f for f in cands if not tr(f)["drc"]] or cands
        if lang:
            cands = [f for f in cands if tr(f)["lang"] == lang and (tr(f)["kind"] == "descriptive") == ad] or cands
        else:
            cands.sort(key=lambda f: not tr(f)["orig"])
    f = cands[0] if cands else None
    if not f:
        raise ValueError("no such format")
    url = _companion_url(f["url"])
    i0, i1 = (int(x) for x in f["index"].split("-"))
    init_end = int(f["init"].split("-")[1])
    refs, pos = _sidx(gv_open(f["url"], "bytes=%d-%d" % (i0, i1), 30).read())
    pos += i0                                            # the box was read from the index range's start
    out = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:%d" % max(1, int(max(r[1] for r in refs) + 0.999)),
           "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS",
           '#EXT-X-MAP:URI="%s",BYTERANGE="%d@0"' % (url, init_end + 1)]
    for size, dur in refs:
        out += ["#EXTINF:%.3f," % dur, "#EXT-X-BYTERANGE:%d@%d" % (size, pos), url]
        pos += size
    out.append("#EXT-X-ENDLIST")
    text = "\n".join(out) + "\n"
    with hls_lk:
        hls_cache[(vid, itag)] = (time.time(), text)
        if len(hls_cache) > HLS_KEEP:
            for k in sorted(hls_cache, key=lambda k: hls_cache[k][0])[:len(hls_cache) - HLS_KEEP]:
                del hls_cache[k]
    return text


# ---------- journal logging ----------
# Failures here used to be silent, so a dead proxy or an expired live URL
# looked like "the video just doesn't play". One compact line per event, and
# rate-limited per tag because a retrying player can hammer the same path.
LOG_EVERY = 60
_log_at, _log_lk = {}, threading.Lock()


def log(tag, msg, t0=None, every=LOG_EVERY):
    """every=0 logs every time (rare events); otherwise at most once per `every` s per tag."""
    now = time.time()
    if every:
        with _log_lk:
            if now - _log_at.get(tag, 0) < every:
                return
            _log_at[tag] = now
            if len(_log_at) > 500:
                for k in [k for k, v in _log_at.items() if now - v > 3600]:
                    del _log_at[k]
    print("[%s] %s%s" % (tag, msg, "" if t0 is None else " %dms" % ((now - t0) * 1000)), flush=True)


# ---------- machine-translated subtitles ----------
SUBS = f"{STATE}/subs"
SUB_MAX = 1200                        # cues; longer videos get truncated, not dropped


def _inv(path, timeout=60):
    """an Invidious API path, answered by the built-in engine"""
    return eng_json(path, timeout)


def _vtt_cues(text):
    """[(timing line, [text lines])] in file order."""
    out, cur = [], None
    for line in text.replace("\r\n", "\n").split("\n"):
        if "-->" in line:
            cur = (line, [])
            out.append(cur)
        elif cur is not None:
            # only an empty line ends a cue: automatic captions start a line after a pause with " " (the empty top
            # line of two), and ending the cue there lost the words below it
            if line == "":
                cur = None
            elif line.strip():
                cur[1].append(line)
    return out


def _translate(lines, tl=SUB_LANG):
    """Google first - a small local model turns "All right" into nonsense - and fall
    back to LibreTranslate (if configured) when Google refuses. Google's dict-chrome endpoint
    takes repeated q= parameters and answers one string per line, in order.
    (translate.googleapis.com/gtx often answers 429 from cloud addresses; this
    one does not.)"""
    UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"}
    GOOGLE = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=" + tl

    def google(chunk):
        url = GOOGLE + "".join("&q=" + urllib.parse.quote(x) for x in chunk)
        if len(url) > 7000:
            raise ValueError("too long")
        r = json.loads(urllib.request.urlopen(
            urllib.request.Request(url, headers=UA), timeout=60).read())
        out = [x if isinstance(x, str) else (x[0] if x else "") for x in r]
        if len(out) != len(chunk):
            raise ValueError("line count %d != %d" % (len(out), len(chunk)))
        return out

    def libre(chunk):
        if not LT:
            return chunk
        body = json.dumps({"q": chunk, "source": "en", "target": tl,
                           "format": "text"}).encode()
        req = urllib.request.Request(LT + "/translate", data=body,
                                     headers={"Content-Type": "application/json"})
        t = json.loads(urllib.request.urlopen(req, timeout=120).read()).get("translatedText")
        return t if isinstance(t, list) and len(t) == len(chunk) else chunk

    done, engine = [], "google"
    for i in range(0, len(lines), 20):
        chunk = lines[i:i + 20]
        try:
            done += google(chunk)
        except Exception as ex:
            log("sub", "google FAIL %s (falling back)" % repr(ex)[:70])
            engine = "libretranslate"
            try:
                done += libre(chunk)
            except Exception as ex2:
                log("sub", "libre FAIL %s" % repr(ex2)[:70])
                done += chunk
        time.sleep(0.2)
    log("sub", "translated %d lines via %s" % (len(lines), engine), every=0)
    return done


# ---------- titles in the viewer's language ----------
# The web app can show video titles written in another script in the viewer's language (Settings ->
# Translate titles, off by default). The titles go to the translator the captions use, and the
# answers are kept, so a title is only ever asked once. Not on public instances.
TR_FILE = os.path.join(STATE, "titles-tr.json")      # {language: {title: its translation}}
TR_KEEP = 5000                                        # titles kept per language; the oldest go first
tr_lock = threading.Lock()
tr_mem = None
tr_saved = 0.0


def _translate_auto(lines, tl):
    """[(translation or None, answered)] for each line, whatever language it is in. None with
    answered=True: the line is in tl already (or came back unchanged). answered=False: the
    translator didn't answer, ask again another time."""
    UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"}
    out, i = [], 0
    while i < len(lines):
        url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + urllib.parse.quote(tl)
        chunk = []
        while i < len(lines) and len(chunk) < 20:
            q = "&q=" + urllib.parse.quote(lines[i])
            if chunk and len(url) + len(q) > 6000:
                break
            url += q
            chunk.append(lines[i])
            i += 1
        try:
            r = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read())
            if not isinstance(r, list) or len(r) != len(chunk):
                raise ValueError("an answer of another length")
            for src, x in zip(chunk, r):
                text, lang = (x[0], x[1] if len(x) > 1 else "") if isinstance(x, list) and x else (x if isinstance(x, str) else "", "")
                same = str(lang).lower().split("-")[0] == tl.lower().split("-")[0]
                out.append((None if same or not text or text.strip() == src.strip() else str(text), True))
        except Exception as ex:
            log("tr", "titles FAIL %s" % repr(ex)[:80])
            out += [(None, False)] * len(chunk)
        if i < len(lines):
            time.sleep(0.2)
    return out


def titles_tr(b):
    """{"tl": "ko", "q": [titles]} -> {"t": [the same titles in that language]}; a title that is
    in that language already, or that couldn't be translated just now, comes back as it is."""
    global tr_mem, tr_saved
    tl, q = str(b.get("tl") or ""), b.get("q")
    if not re.fullmatch(r"[a-z]{2,3}(-[A-Za-z]{2,4})?", tl) or not isinstance(q, list):
        return 400, {"error": "Bad request"}
    q = [str(x)[:300] for x in q[:60]]
    with tr_lock:
        if tr_mem is None:
            d = jload(TR_FILE, {})
            tr_mem = {k: v for k, v in d.items() if isinstance(v, dict)} if isinstance(d, dict) else {}
        have = tr_mem.setdefault(tl, {})
        need = list(dict.fromkeys(x for x in q if x.strip() and x not in have))
    if need:
        got = _translate_auto(need, tl)
        with tr_lock:
            have = tr_mem.setdefault(tl, {})
            for src, (text, answered) in zip(need, got):
                if answered:
                    have[src] = text or src
            while len(have) > TR_KEEP:
                have.pop(next(iter(have)))
            if time.time() - tr_saved > 30:
                tr_saved = time.time()
                try:
                    jsave(TR_FILE, tr_mem)
                except OSError as ex:
                    log("tr", "save FAIL %s" % repr(ex)[:80])
    with tr_lock:
        have = tr_mem.get(tl, {})
        return 200, {"t": [have.get(x, x) for x in q]}


def sub_vtt(vid, tl=SUB_LANG):
    """Translated VTT for one video id, cached on disk."""
    # both end up in a file name: only a video id and a language code may get through
    if not re.fullmatch(r"[\w-]{11}", vid) or not re.fullmatch(r"[a-z]{2}(-[A-Za-z]{2,4})?", tl):
        raise ValueError("bad video id or language")
    os.makedirs(SUBS, exist_ok=True)
    base = os.path.abspath(SUBS) + os.sep
    cache = os.path.normpath(os.path.join(base, "%s.%s.vtt" % (vid, tl)))
    if not cache.startswith(base):
        raise ValueError("bad cache path")
    if os.path.exists(cache):
        return open(cache, "rb").read()
    t0 = time.time()
    try:
        tracks = eng_caption_list(vid)
        # prefer English, else whatever the video has
        pick = next((c for c in tracks if (c.get("languageCode") or "").startswith("en")), None) \
            or (tracks[0] if tracks else None)
        if not pick:
            raise LookupError("This video has no captions to translate")
        raw = eng_caption(vid, pick["label"]).decode("utf-8", "replace")
        src = pick.get("languageCode", "?")
    except LookupError:
        raise
    except Exception:
        # refused (429): the English track straight from YouTube, as made by people, else the automatic one
        raw = None
        for lang, auto in (("en", False), ("en", True)):
            try:
                raw, src = cap_direct(vid, lang, auto).decode("utf-8", "replace"), lang
                break
            except Exception:
                pass
        if raw is None:
            raise ValueError("Couldn't get the captions")
    cues = _vtt_cues(raw)[:SUB_MAX]
    if not cues:
        raise ValueError("Couldn't read the captions")
    flat, idx = [], []
    for ci, (_, lines) in enumerate(cues):
        for li, ln in enumerate(lines):
            if ln.strip():
                idx.append((ci, li))
                flat.append(re.sub(r"<[^>]+>", "", ln).strip())
    ko = _translate(flat, tl)
    for (ci, li), text in zip(idx, ko):
        cues[ci][1][li] = text
    out = ["WEBVTT", ""]
    for timing, lines in cues:
        out += [timing] + lines + [""]
    data = "\n".join(out).encode("utf-8")
    tmp = cache + ".part"
    open(tmp, "wb").write(data)
    os.replace(tmp, cache)
    log("sub", "%s %s->%s %d cues" % (vid, src, tl, len(cues)), t0, every=0)
    return data


# ---------- captions through yt-dlp when YouTube refuses the engine's ----------
# YouTube answers caption requests from one address with 429 for hours at a time (the engine's and
# yt-dlp's over the proxy alike). Then yt-dlp fetches the same track over this server's own line (then
# over the proxy), and the file is kept for a week. Two at a time; one track per (video, language, kind).
cap_sem = threading.Semaphore(2)
CAP_KEEP = 7 * 86400
cap_fail = {}         # (video, language, kind) -> when it last failed: not tried again for 10 minutes


def _cap_cache(vid, lang, auto):
    if not re.fullmatch(r"[\w-]{11}", vid) or not re.fullmatch(r"[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?", lang):
        raise ValueError("bad video id or language")
    os.makedirs(SUBS, exist_ok=True)
    base = os.path.abspath(SUBS) + os.sep
    cache = os.path.normpath(os.path.join(base, "cap.%s.%s.%s.vtt" % (vid, lang, "a" if auto else "m")))
    if not cache.startswith(base):
        raise ValueError("bad cache path")
    return cache


def cap_direct(vid, lang, auto=False):
    """One caption track as WebVTT, fetched by yt-dlp."""
    import glob
    import tempfile
    cache = _cap_cache(vid, lang, auto)
    fresh = lambda: os.path.exists(cache) and time.time() - os.path.getmtime(cache) < CAP_KEEP
    if fresh():
        return open(cache, "rb").read()
    key = (vid, lang, auto)
    if time.time() - cap_fail.get(key, 0) < 600:
        raise ValueError("Couldn't get the captions")
    t0, err = time.time(), "?"
    with cap_sem:
        if fresh():      # fetched by another request while this one waited
            return open(cache, "rb").read()
        tries = [None] + (["http://" + YT_PROXY] if YT_PROXY else [])
        rotated = False
        while tries:
            proxy = tries.pop(0)
            with tempfile.TemporaryDirectory() as tmp:
                cmd = [YTDLP] + (["--proxy", proxy] if proxy else []) + [
                    "--skip-download", "--no-playlist", "--no-warnings", "--sub-format", "vtt", "--sub-langs", lang,
                    "--write-auto-subs" if auto else "--write-subs", "-o", os.path.join(tmp, "c"),
                    "https://www.youtube.com/watch?v=" + vid]
                try:
                    out = subprocess.run(cmd, capture_output=True, text=True, timeout=90,
                                         env={**os.environ, "PATH": os.environ.get("PATH", "") + ":/usr/local/bin"})
                except (subprocess.TimeoutExpired, OSError) as ex:
                    err = repr(ex)[:120]
                    continue
                got = glob.glob(os.path.join(tmp, "c*.vtt"))
                if got and os.path.getsize(got[0]) > 10:
                    data = open(got[0], "rb").read()
                    part = cache + ".part"
                    open(part, "wb").write(data)
                    os.replace(part, cache)
                    log("cap", "%s %s%s via %s" % (vid, lang, " auto" if auto else "", "proxy" if proxy else "direct"), t0, every=0)
                    return data
                err = ((out.stderr or "").strip().splitlines() or ["no captions in that language"])[-1][:120]
                # refused over the proxy: once, a new WARP address (rate-limited by warp_rotate) and again
                if proxy and "429" in err and not rotated:
                    rotated = True
                    if warp_rotate("captions 429", t0):
                        tries.append(proxy)
    cap_fail[key] = time.time()
    if len(cap_fail) > 2000:
        cap_fail.clear()
    log("cap", "%s %s FAIL %s" % (vid, lang, err), t0, every=0)
    raise ValueError("Couldn't get the captions")


def jload(p, d):
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return d


def jsave(p, v):
    os.makedirs(os.path.dirname(p) or ".", exist_ok=True)     # an account's own folder, the first time
    # titles are any language: always UTF-8, whatever the system locale says
    t = p + ".tmp"
    with open(t, "w", encoding="utf-8") as f:
        json.dump(v, f, ensure_ascii=False)
    os.replace(t, p)


def get(url, t=15):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=t).read()


def resolve_master(vid):
    # mweb + a BotGuard PO token (bgutil container) gives HLS URLs that keep
    # working; android_vr needs no token but its segments 403 after ~20 s.
    t0 = time.time()
    # (install the bgutil-ytdlp-pot-provider plugin next to yt-dlp for the PO token)
    out = subprocess.run([YTDLP] + (["--proxy", "http://" + YT_PROXY] if YT_PROXY else []) + ["--extractor-args",
                          "youtube:player_client=mweb", "-j", "https://www.youtube.com/watch?v=" + vid],
                         capture_output=True, text=True, timeout=150,
                         env={**os.environ, "PATH": os.environ.get("PATH", "") + ":/usr/local/bin"})
    try:
        info = json.loads(out.stdout)
    except Exception:
        err = (out.stderr.strip().splitlines() or ["?"])[-1][:200]
        log("live", f"resolve {vid} FAIL yt-dlp {err[:120]}", t0, every=0)
        raise RuntimeError("Couldn't get the live stream: " + err)
    variants = {}
    for f in info.get("formats", []):
        if "m3u8" not in (f.get("protocol") or "") or f.get("vcodec") in (None, "none") or not f.get("height"):
            continue
        inf = (f'#EXT-X-STREAM-INF:BANDWIDTH={int((f.get("tbr") or 1000) * 1000)},'
               f'RESOLUTION={f.get("width")}x{f["height"]},FRAME-RATE={f.get("fps") or 30},'
               f'CODECS="{f.get("vcodec")},{f.get("acodec") or "mp4a.40.2"}"')
        variants[str(f["format_id"])] = (inf, f["url"])
    if not variants:
        log("live", f"resolve {vid} FAIL no-hls-variants", t0, every=0)
        raise RuntimeError("Not a live stream, or the stream has ended")
    log("live", f"resolve {vid} ok variants={len(variants)}", t0, every=0)
    return {"at": time.time(), "variants": variants, "used": time.time()}


LIVE_WINDOW = 40          # seconds of live history offered to the player


def live_window(text):
    """YouTube's mweb live playlist lists only the newest 3 one-second segments, so the
    player can never sit more than ~3 s behind the edge and one slow segment stalls it.
    Older segments stay downloadable by changing /sq/N/ in the URL, so rebuild the
    playlist with LIVE_WINDOW segments ending at the newest one."""
    segs = [l for l in text.splitlines() if l.startswith("https://")]
    m = re.search(r"/sq/(\d+)/", segs[-1]) if segs else None
    if not m:
        return "\n".join(LIVE_BASE + base64.urlsafe_b64encode(l.encode()).decode().rstrip("=") + ".ts"
                         if l.startswith("https://") else l for l in text.splitlines()) + "\n"
    durs = re.findall(r"#EXTINF:([\d.]+)", text)
    dur = durs[-1] if durs else "1.0"
    last, tmpl = int(m.group(1)), segs[-1]
    first = max(0, last - LIVE_WINDOW + 1)
    out = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % max(1, round(float(dur))),
           "#EXT-X-MEDIA-SEQUENCE:%d" % first]
    for sq in range(first, last + 1):
        u = tmpl.replace("/sq/%d/" % last, "/sq/%d/" % sq, 1)
        out += ["#EXTINF:%s," % dur, LIVE_BASE + base64.urlsafe_b64encode(u.encode()).decode().rstrip("=") + ".ts"]
    return "\n".join(out) + "\n"


def live_segment_retry(u, code):
    """Re-fetch segment sq of (vid, itag) through the current playlist URL template."""
    m = re.search(r"/id/([\w-]{11})[^/]*/itag/(\d+)/.*?/sq/(\d+)/", u)
    if not m:
        return None
    vid, itag, sq = m.group(1), m.group(2), int(m.group(3))
    for attempt in range(3):
        try:
            e = live_entry(vid, force=(code == 403 and attempt == 0))
            text = YT.open(e["variants"][itag][1], timeout=15).read().decode()
            segs = [l for l in text.splitlines() if l.startswith("https://")]
            last = int(re.search(r"/sq/(\d+)/", segs[-1]).group(1))
            if sq > last:                      # not produced yet: wait for it
                time.sleep(min(3, sq - last))
                continue
            u2 = segs[-1].replace("/sq/%d/" % last, "/sq/%d/" % sq, 1)
            r = YT.open(urllib.request.Request(u2), timeout=30)
            log("live", f"segment {code} recovered {vid}/{itag}/sq{sq} try{attempt + 1}", every=0)
            return r
        except Exception as ex:
            code = getattr(ex, "code", code)
            time.sleep(1)
    log("live", f"segment {code} NOT recovered {vid}/{itag}/sq{sq}", every=0)
    return None


def live_entry(vid, force=False):
    # URLs are re-resolved every 10 min while watched, and at once on a 403.
    e = LIVE_CACHE.get(vid)
    if force or not e or time.time() - e["at"] > 1800:
        e2 = resolve_master(vid)
        e2["used"] = time.time()
        LIVE_CACHE[vid] = e = e2
        if not any(t.name == "live-" + vid for t in threading.enumerate()):
            threading.Thread(target=live_refresher, args=(vid,), name="live-" + vid, daemon=True).start()
    e["used"] = time.time()
    return e


def live_refresher(vid):
    while True:
        time.sleep(600)
        e = LIVE_CACHE.get(vid)
        if not e or time.time() - e["used"] > 120:
            LIVE_CACHE.pop(vid, None)
            return
        try:
            n = resolve_master(vid)
            n["used"] = e["used"]
            LIVE_CACHE[vid] = n
        except Exception as ex:
            log("live", f"re-resolve {vid} FAIL {repr(ex)[:120]}", every=0)


# ---------- live pull-ahead: segments are fetched before the player asks ----------
# Measured: a 1-second live segment took 1.5 s through a proxy when every fetch
# opened a new TCP + CONNECT + TLS session, 0.45 s on a kept-alive one. So:
#  - GV_POOL keeps HTTPS connections to googlevideo open (through YT_PROXY if set);
#  - a LivePull per watched (video, itag) polls the playlist every second and pulls each
#    new segment into memory with a few parallel workers. The player sits ~20 s behind
#    the edge (the player's liveSegmentsDelay), so slow or failed segments are retried
#    here long before it needs them, and it is served from RAM at LAN speed.
import http.client
import queue

PULL_KEEP = 60        # segments kept per variant (~1 min, ~40 MB at 1080p)
PULL_WORKERS = 4
PULL_IDLE = 30        # stop pulling a variant this long after the player last asked


class GVPool:
    """Keep-alive HTTPS connections to googlevideo hosts, through YT_PROXY when set."""

    def __init__(self, per_host=8):
        self.idle, self.lock, self.per_host = {}, threading.Lock(), per_host

    def get(self, url, timeout=20, hops=3):
        sp = urllib.parse.urlsplit(url)
        host, path = sp.hostname, sp.path + ("?" + sp.query if sp.query else "")
        for attempt in range(2):
            with self.lock:
                lst = self.idle.get(host)
                c = lst.pop() if lst else None
            fresh = c is None
            if fresh:
                if YT_PROXY:
                    ph, pp = YT_PROXY.rsplit(":", 1)
                    c = http.client.HTTPSConnection(ph, int(pp), timeout=timeout)
                    c.set_tunnel(host, 443)
                else:
                    c = http.client.HTTPSConnection(host, 443, timeout=timeout)
            elif c.sock:
                c.sock.settimeout(timeout)
            try:
                c.request("GET", path)
                r = c.getresponse()
                body = r.read()
            except Exception:
                c.close()
                if fresh or attempt:
                    raise
                continue                      # the pooled connection had gone stale
            if r.will_close:
                c.close()
            else:
                with self.lock:
                    lst = self.idle.setdefault(host, [])
                    if len(lst) < self.per_host:
                        lst.append(c)
                    else:
                        c.close()
            if r.status in (301, 302, 303, 307, 308) and hops and r.getheader("Location"):
                return self.get(urllib.parse.urljoin(url, r.getheader("Location")), timeout, hops - 1)
            return r.status, r.getheader("Content-Type"), body


GV_POOL = GVPool()
LIVE_PULL, LIVE_PULL_LOCK = {}, threading.Lock()


def _sq(url):
    m = re.search(r"/sq/(\d+)/", url)
    return int(m.group(1)) if m else None


def ts_first_pts(ts):
    """First video PTS (90 kHz) in an MPEG-TS segment, or None."""
    for i in range(0, min(len(ts), 188 * 4000) - 187, 188):
        pk = ts[i:i + 188]
        if pk[0] != 0x47 or not (pk[1] & 0x40):
            continue
        afc, off = (pk[3] >> 4) & 3, 4
        if afc == 2:
            continue
        if afc == 3:
            off += 1 + pk[4]
        pes = pk[off:]
        if len(pes) < 14 or pes[:3] != b"\x00\x00\x01" or not 0xE0 <= pes[3] <= 0xEF or not pes[7] & 0x80:
            continue
        b = pes[9:14]
        return ((b[0] >> 1) & 7) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1
    return None


class LivePull:
    def __init__(self, vid, itag):
        self.vid, self.itag = vid, itag
        self.cond = threading.Condition()
        self.segs = {}            # sq -> (content type, bytes)
        self.inflight = set()     # queued or being fetched
        self.fetching = set()     # being fetched right now
        self.urgent = set()       # re-queued at the front because the player is waiting
        self.tmpl, self.last, self.queued = None, -1, -1
        self.text, self.text_at = None, 0.0
        self.used = time.time()
        self.q = queue.PriorityQueue()
        # per segment: exact duration, discontinuity before it, discontinuity sequence -
        # from YouTube's own playlist while the segment is listed there, else worked
        # out from the video timestamps of the segments we pulled
        self.meta, self.pts = {}, {}
        self.stats = {"pulled": 0, "hit": 0, "miss": 0, "retry": 0, "fail": 0}
        threading.Thread(target=self.watch, name=f"pull-{vid}-{itag}", daemon=True).start()
        for i in range(PULL_WORKERS):
            threading.Thread(target=self.worker, name=f"pullw-{vid}-{itag}-{i}", daemon=True).start()

    # -- the playlist edge
    def watch(self):
        fails = 0
        while time.time() - self.used < PULL_IDLE:
            t0 = time.time()
            try:
                e = live_entry(self.vid)
                st, _, body = GV_POOL.get(e["variants"][self.itag][1], timeout=15)
                if st == 403:
                    live_entry(self.vid, force=True)
                    raise RuntimeError("playlist 403")
                if st != 200:
                    raise RuntimeError(f"playlist {st}")
                text = body.decode()
                segs = [l for l in text.splitlines() if l.startswith("https://")]
                last = _sq(segs[-1]) if segs else None
                if last is None:
                    raise RuntimeError("no segments")
                mm = re.search(r"#EXT-X-DISCONTINUITY-SEQUENCE:(\d+)", text)
                d, dur, disc = int(mm.group(1)) if mm else 0, None, False
                found = {}
                for line in text.splitlines():
                    if line == "#EXT-X-DISCONTINUITY":
                        disc, d = True, d + 1
                    elif line.startswith("#EXTINF:"):
                        dur = float(line[8:].split(",")[0])
                    elif line.startswith("https://"):
                        k = _sq(line)
                        if k is not None and dur:
                            found[k] = {"dur": dur, "disc": disc, "dseq": d}
                        dur, disc = None, False
                with self.cond:
                    self.meta.update(found)
                    self.text, self.text_at, self.tmpl, self.last = text, time.time(), segs[-1], last
                    start = max(self.queued + 1, last - 25)       # first poll: backfill what the player will want
                    for sq in range(start, last + 1):
                        if sq not in self.segs and sq not in self.inflight:
                            self.inflight.add(sq)
                            self.q.put((1, sq))
                    self.queued = max(self.queued, last)
                    for old in [k for k in self.segs if k < last - PULL_KEEP]:
                        del self.segs[old]
                    for dct in (self.meta, self.pts):
                        for old in [k for k in dct if k < last - PULL_KEEP - 5]:
                            del dct[old]
                    self.cond.notify_all()
                fails = 0
            except Exception as ex:
                fails += 1
                log("live", f"pull {self.vid}/{self.itag} playlist FAIL {repr(ex)[:100]}", every=30)
                if fails >= 3:
                    try:
                        live_entry(self.vid, force=True)
                    except Exception:
                        pass
                    fails = 0
            time.sleep(max(0.2, 1.0 - (time.time() - t0)))
        with LIVE_PULL_LOCK:
            if LIVE_PULL.get((self.vid, self.itag)) is self:
                del LIVE_PULL[(self.vid, self.itag)]
        for _ in range(PULL_WORKERS):
            self.q.put((9, -1))
        log("live", f"pull {self.vid}/{self.itag} stopped {self.stats}", every=0)

    # -- segment fetchers
    def worker(self):
        while True:
            _, sq = self.q.get()
            if sq < 0:
                return
            with self.cond:
                if sq in self.segs or sq in self.fetching:
                    continue                                # duplicate of an urgent re-queue
                self.fetching.add(sq)
            got = None
            for attempt in range(5):
                with self.cond:
                    tmpl, last = self.tmpl, self.last
                if sq < last - PULL_KEEP:
                    break                                   # fell out of the window
                if sq > last:
                    time.sleep(0.5)                          # not produced yet
                    continue
                try:
                    st, ctype, body = GV_POOL.get(tmpl.replace("/sq/%d/" % last, "/sq/%d/" % sq, 1), timeout=20)
                    if st == 200 and body:
                        got = (ctype or "video/mp2t", body)
                        break
                    if st == 403:
                        live_entry(self.vid, force=True)
                except Exception:
                    pass
                self.stats["retry"] += 1
                time.sleep(0.3 * (attempt + 1))
            with self.cond:
                self.inflight.discard(sq)
                self.fetching.discard(sq)
                self.urgent.discard(sq)
                if got:
                    self.segs[sq] = got
                    self.stats["pulled"] += 1
                    pts = ts_first_pts(got[1])
                    if pts is not None:
                        self.pts[sq] = pts
                else:
                    self.stats["fail"] += 1
                self.cond.notify_all()

    # -- what the HTTP handler calls
    def playlist(self):
        self.used = time.time()
        with self.cond:
            if self.text is None:
                self.cond.wait(8)
            return self.text if self.text and time.time() - self.text_at < 3 else None

    def window(self):
        """Our LIVE_WINDOW-segment playlist with YouTube's real durations and
        discontinuities, so the player's timeline matches the video and it never
        jumps a 'gap' that is really a timestamp reset."""
        with self.cond:
            if self.tmpl is None or not self.meta:
                return None
            last, tmpl = self.last, self.tmpl
            first = max(0, last - LIVE_WINDOW + 1, last - PULL_KEEP)
            known = [m["dur"] for m in self.meta.values()]
            typical = max(set(known), key=known.count)
            durs, discs = {}, {}
            for sq in range(first, last + 1):
                m = self.meta.get(sq)
                if m:
                    durs[sq], discs[sq] = m["dur"], m["disc"]
                    continue
                a, b, prev = self.pts.get(sq), self.pts.get(sq + 1), self.pts.get(sq - 1)
                dt = ((b - a) % (1 << 33)) / 90000 if a is not None and b is not None else None
                durs[sq] = dt if dt and 0.3 * typical < dt < 1.7 * typical else typical
                jump = ((a - prev) % (1 << 33)) / 90000 if a is not None and prev is not None else None
                discs[sq] = bool(jump is not None and abs(jump - durs.get(sq - 1, typical)) > 1.0)
            # discontinuity sequence of the first segment, counted back from a known one
            anchor = next((sq for sq in range(first, last + 1) if sq in self.meta), None)
            dseq = 0
            if anchor is not None:
                dseq = self.meta[anchor]["dseq"] - sum(1 for sq in range(first + 1, anchor + 1) if discs[sq])
            out = ["#EXTM3U", "#EXT-X-VERSION:3",
                   "#EXT-X-TARGETDURATION:%d" % max(1, round(max(durs.values()) + 0.4)),
                   "#EXT-X-MEDIA-SEQUENCE:%d" % first, "#EXT-X-DISCONTINUITY-SEQUENCE:%d" % max(0, dseq)]
            for sq in range(first, last + 1):
                if discs[sq] and sq != first:
                    out.append("#EXT-X-DISCONTINUITY")
                u = tmpl.replace("/sq/%d/" % last, "/sq/%d/" % sq, 1)
                out += ["#EXTINF:%.6f," % durs[sq], LIVE_BASE + base64.urlsafe_b64encode(u.encode()).decode().rstrip("=") + ".ts"]
            return "\n".join(out) + "\n"

    def segment(self, sq, timeout=15):
        self.used = time.time()
        deadline = time.time() + timeout
        with self.cond:
            while True:
                if sq in self.segs:
                    self.stats["hit"] += 1
                    return self.segs[sq]
                if self.last >= 0 and (sq < self.last - PULL_KEEP or sq > self.last + 5):
                    break
                if 0 <= sq <= self.last and sq not in self.fetching and sq not in self.urgent:
                    self.inflight.add(sq)
                    self.urgent.add(sq)
                    self.q.put((0, sq))                     # the player is waiting: jump the queue
                left = deadline - time.time()
                if left <= 0:
                    break
                self.cond.wait(left)
        self.stats["miss"] += 1
        return None


def live_pull(vid, itag, create=True):
    with LIVE_PULL_LOCK:
        p = LIVE_PULL.get((vid, itag))
        if p is None and create:
            p = LIVE_PULL[(vid, itag)] = LivePull(vid, itag)
            log("live", f"pull {vid}/{itag} start", every=0)
        return p


# ---------- self-healing egress: new WARP address when YouTube starts refusing ----------
# Sooner or later every uncached video fails with "Sign in to confirm you're not a bot /
# This helps protect our community" - YouTube has flagged the WARP exit address.
# A warp-cli disconnect/connect hands out a different one and playback comes straight
# back, so do that automatically and replay the request.
ROTATE_GAP = 45            # seconds between two rotations
ROTATE_BURST = (6, 1800)   # at most 6 rotations per 30 min, then pause and log
rot_lock, rot_hist = threading.Lock(), []


def warp_ip():
    try:
        t = YT.open("https://www.cloudflare.com/cdn-cgi/trace", timeout=8).read().decode()
        return re.search(r"^ip=(.+)$", t, re.M).group(1)
    except Exception:
        return "?"


def yt_blocked(d=None, body=b""):
    """True when YouTube refused us as a bot rather than refusing the video."""
    if d is not None:
        ps = d.get("playabilityStatus") or {}
        text = json.dumps(ps, ensure_ascii=False).lower()
        return ps.get("status") == "LOGIN_REQUIRED" and ("bot" in text or "protect our community" in text or not ps.get("reason")) \
            or "protect our community" in text
    return b"protect our community" in body or b"confirm you\xe2\x80\x99re not a bot" in body or b"not a bot" in body


def warp_rotate(why, started):
    """Get a new WARP exit address. Requests that were already waiting when another
    thread rotated just retry instead of rotating again. Returns True if the caller
    should retry."""
    if not (WARP_ROTATE and YT_PROXY):
        return False
    with rot_lock:
        now = time.time()
        if rot_hist and rot_hist[-1] > started:
            return True                              # someone rotated while we waited
        if rot_hist and now - rot_hist[-1] < ROTATE_GAP:
            return False
        recent = [t for t in rot_hist if now - t < ROTATE_BURST[1]]
        if len(recent) >= ROTATE_BURST[0]:
            log("warp", f"rotate skipped: {len(recent)} in 30 min ({why})", every=600)
            return False
        old = warp_ip()
        subprocess.run(["warp-cli", "--accept-tos", "disconnect"], capture_output=True, timeout=20)
        time.sleep(1)
        subprocess.run(["warp-cli", "--accept-tos", "connect"], capture_output=True, timeout=20)
        # wait until traffic flows again (in a container the status line can stay at
        # "Validating proxy configuration" while the proxy already works)
        new = "?"
        for _ in range(40):
            time.sleep(1)
            new = warp_ip()
            if new != "?":
                break
        rot_hist.append(time.time())
        del rot_hist[:-20]
        log("warp", f"rotated {old} -> {new} ({why}) in {time.time() - now:.1f}s", every=0)
        return True


# ---------- library: subscriptions / history / resume points / playlists ----------
# Without accounts every device shares this one file (put TubeHaus behind a login). With
# accounts on a household server (HT_ACCOUNTS=1, not public), everyone who signs in has a library
# of their own in STATE/users/<name>/: subscriptions, history, playlists, Shorts and the TVs
# they can cast to. The subscription feed is gathered from YouTube RSS every 15 min.
YTLIB = f"{STATE}/ytlib.json"
yt_lock = threading.Lock()
yt_feed = {"items": {}, "updated": 0, "running": False}   # cid -> [entries]
_req = threading.local()          # .user: the account signed in on the request this thread answers
_ME = object()                    # "whoever this request belongs to"


def _uid():
    """The account whose library this request uses; None = the one shared library."""
    return getattr(_req, "user", None) if ACCOUNTS and not PUBLIC_MODE else None


def _udir(u):
    """STATE/users/<name>, only for a valid account name and only ever inside STATE/users."""
    if not (u and re.fullmatch(r"[a-z0-9_]{3,24}", u)):
        return None
    base = os.path.abspath(os.path.join(STATE, "users"))
    d = os.path.normpath(os.path.join(base, u))
    return d if d.startswith(base + os.sep) else None


def _ytlib_file(u=_ME):
    d = _udir(_uid() if u is _ME else u)
    return f"{d}/ytlib.json" if d else YTLIB


def _shorts_file(u=_ME):
    d = _udir(_uid() if u is _ME else u)
    return f"{d}/shorts.json" if d else SHORTS


def _lib_owners():
    """Every library on this server: None (the shared one) and each account's."""
    owners = [None]
    if ACCOUNTS and not PUBLIC_MODE:
        try:
            owners += sorted(n for n in os.listdir(f"{STATE}/users") if _udir(n))
        except OSError:
            pass
    return owners


def _yt_load(u=_ME):
    d = jload(_ytlib_file(u), {})
    for k, v in (("subs", {}), ("hist", []), ("pos", {}), ("wl", []), ("likes", []), ("dislikes", []), ("playlists", {})):
        d.setdefault(k, v)
    return d


def _yt_meta(v):
    v = v or {}
    out = {"id": str(v.get("id", ""))[:11]}
    for k in ("title", "author", "authorId"):
        out[k] = str(v.get(k) or "")[:300]
    for k in ("len", "published", "views"):
        try:
            out[k] = int(v.get(k) or 0)
        except Exception:
            out[k] = 0
    if v.get("live"):
        out["live"] = True
    return out if re.fullmatch(r"[\w-]{11}", out["id"]) else None


def _pl_summary(pl):
    items = pl.get("items", [])
    return {"title": pl.get("title", ""), "count": len(items), "first": items[0]["id"] if items else None,
            "updated": pl.get("updated", 0), "ids": [x["id"] for x in items]}


def ytlib_get():
    with yt_lock:
        d = _yt_load()
    return {"subs": d["subs"], "wl": d["wl"], "likes": d["likes"], "dislikes": d["dislikes"],
            "playlists": {k: _pl_summary(v) for k, v in d["playlists"].items()}}


def ytlib_post(path, b):
    now = int(time.time())
    with yt_lock:
        d = _yt_load()
        res = {"ok": True}
        if path.endswith("/yt/pos"):
            vid = str(b.get("id", ""))
            if not re.fullmatch(r"[\w-]{11}", vid):
                return 400, {"error": "bad id"}
            t, ln = int(b.get("t") or 0), int(b.get("len") or 0)
            d["pos"][vid] = [t, ln, now]
            if len(d["pos"]) > 5000:
                for k in sorted(d["pos"], key=lambda k: d["pos"][k][2])[:len(d["pos"]) - 5000]:
                    del d["pos"][k]
            meta = _yt_meta(b.get("meta")) if b.get("meta") else None
            old = next((h for h in d["hist"] if h["id"] == vid), None)
            if meta or old:
                h = dict(old or {}, **(meta or {}))
                h["at"], h["id"] = now, vid
                d["hist"] = [h] + [x for x in d["hist"] if x["id"] != vid]
                d["hist"] = d["hist"][:3000]
        elif path.endswith("/yt/sub"):
            cid = str(b.get("id", ""))
            if not re.fullmatch(r"UC[\w-]{22}", cid):
                return 400, {"error": "bad channel"}
            if b.get("on"):
                d["subs"][cid] = {"name": str(b.get("name") or cid)[:200], "thumb": str(b.get("thumb") or "")[:500], "at": now}
                threading.Thread(target=yt_feed_one, args=(cid,), daemon=True).start()
            else:
                d["subs"].pop(cid, None)
                yt_feed["items"].pop(cid, None)
        elif path.endswith("/yt/subs/import"):
            added = 0
            for x in (b.get("list") or [])[:2000]:
                cid = str(x.get("id", ""))
                if re.fullmatch(r"UC[\w-]{22}", cid) and cid not in d["subs"]:
                    d["subs"][cid] = {"name": str(x.get("name") or cid)[:200], "thumb": str(x.get("thumb") or "")[:500], "at": now}
                    added += 1
            res["added"] = added
            if added:
                threading.Thread(target=yt_feed_refresh, daemon=True).start()
        elif path.endswith("/yt/list"):
            lst, on, meta = str(b.get("list", "")), bool(b.get("on")), _yt_meta(b.get("video"))
            if not meta:
                return 400, {"error": "bad video"}
            if lst in ("wl", "likes"):
                d[lst] = [x for x in d[lst] if x["id"] != meta["id"]]
                if on:
                    d[lst].insert(0, meta)
                d[lst] = d[lst][:5000]
            elif lst == "dislikes":
                d["dislikes"] = [x for x in d["dislikes"] if x != meta["id"]] + ([meta["id"]] if on else [])
            elif lst in d["playlists"]:
                pl = d["playlists"][lst]
                pl["items"] = [x for x in pl["items"] if x["id"] != meta["id"]]
                if on:
                    pl["items"].append(meta)
                pl["updated"] = now
                res["playlist"] = _pl_summary(pl)
            else:
                return 404, {"error": "Playlist not found"}
        elif path.endswith("/yt/pl/new"):
            title = str(b.get("title") or "").strip()[:150]
            if not title:
                return 400, {"error": "Please enter a name"}
            # the apps make the ID themselves (so a playlist made offline keeps it); the web lets the server choose
            pid = str(b.get("id") or "")
            if not re.fullmatch(r"HP[0-9a-f]{16}", pid) or pid in d["playlists"]:
                pid = "HP" + secrets.token_hex(8)
            meta = _yt_meta(b.get("video")) if b.get("video") else None
            d["playlists"][pid] = {"title": title, "items": [meta] if meta else [], "updated": now}
            res.update(id=pid, playlist=_pl_summary(d["playlists"][pid]))
        elif path.endswith("/yt/pl/del"):
            d["playlists"].pop(str(b.get("id")), None)
        elif path.endswith("/yt/pl/move"):          # the apps reorder a playlist one video at a time
            pl = d["playlists"].get(str(b.get("id")))
            if not pl:
                return 404, {"error": "Playlist not found"}
            try:
                i, j = int(b.get("from")), int(b.get("to"))
            except (TypeError, ValueError):
                return 400, {"error": "bad position"}
            if not (0 <= i < len(pl["items"]) and 0 <= j < len(pl["items"])):
                return 400, {"error": "bad position"}
            pl["items"].insert(j, pl["items"].pop(i))
            pl["updated"] = now
            res["playlist"] = _pl_summary(pl)
        elif path.endswith("/yt/pl/rename"):
            pl = d["playlists"].get(str(b.get("id")))
            if not pl:
                return 404, {"error": "Playlist not found"}
            pl["title"] = str(b.get("title") or pl["title"]).strip()[:150]
            pl["updated"] = now
            res["playlist"] = _pl_summary(pl)
        elif path.endswith("/yt/hist/del"):
            if b.get("all"):
                d["hist"], d["pos"] = [], {}
            else:
                vid = str(b.get("id", ""))
                d["hist"] = [x for x in d["hist"] if x["id"] != vid]
                d["pos"].pop(vid, None)
        else:
            return 404, {"error": "not found"}
        jsave(_ytlib_file(), d)
    return 200, res


def ytlib_query(path, qs):
    with yt_lock:
        d = _yt_load()
    if path.endswith("/yt/pos"):
        ids = [i for i in qs.get("ids", "").split(",") if re.fullmatch(r"[\w-]{11}", i)][:200]
        return {i: d["pos"][i][:2] for i in ids if i in d["pos"]}
    if path.endswith("/yt/hist"):
        n, o, q = min(int(qs.get("n") or 40), 200), int(qs.get("o") or 0), (qs.get("q") or "").strip().lower()
        h = [x for x in d["hist"] if not q or q in (x.get("title", "") + " " + x.get("author", "")).lower()]
        return {"items": h[o:o + n], "total": len(h)}
    if path.endswith("/yt/pl"):
        pl = d["playlists"].get(qs.get("id", ""))
        return pl or {"error": "Playlist not found"}
    if path.endswith("/yt/feed"):
        subs = d["subs"]
        if time.time() - yt_feed["updated"] > 900 and not yt_feed["running"]:
            threading.Thread(target=yt_feed_refresh, daemon=True).start()
        items = [e for cid, es in list(yt_feed["items"].items()) if cid in subs for e in es]
        items.sort(key=lambda e: e["published"], reverse=True)
        return {"items": items[:400], "updated": yt_feed["updated"],
                "updating": yt_feed["running"] or bool(subs and not yt_feed["items"])}
    return {"error": "not found"}


# ---------- import / export: your data is never stuck here ----------
# Export: a full TubeHaus backup (re-importable), Invidious' export format, the CSV Google
# Takeout uses for subscriptions, or OPML for any feed reader.
# Import: any of those, plus NewPipe and FreeTube exports and Google Takeout's watch-history.json.
UCID = re.compile(r"UC[\w-]{22}")
VID = re.compile(r"[\w-]{11}")


def ytlib_export(fmt, d=None):
    given = d is not None
    if d is None:
        with yt_lock:
            d = _yt_load()
    else:   # public mode: the visitor's own library, sent from their browser
        d = {k: d.get(k) or ({} if k in ("subs", "pos", "playlists") else []) for k in
             ("subs", "hist", "pos", "wl", "likes", "dislikes", "playlists")}
    day = time.strftime("%Y-%m-%d")
    if fmt == "invidious":
        body = {"subscriptions": list(d["subs"]), "watch_history": [h["id"] for h in d["hist"]], "preferences": {},
                "playlists": [{"title": p.get("title", ""), "description": "", "privacy": "Private",
                               "videos": [x["id"] for x in p.get("items", [])]} for p in d["playlists"].values()]}
        return f"invidious-export-{day}.json", "application/json", json.dumps(body, ensure_ascii=False, indent=1).encode()
    if fmt == "csv":
        import csv
        import io
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(["Channel Id", "Channel Url", "Channel Title"])
        for cid, s in d["subs"].items():
            w.writerow([cid, f"http://www.youtube.com/channel/{cid}", s.get("name", "")])
        return f"subscriptions-{day}.csv", "text/csv; charset=utf-8", buf.getvalue().encode()
    if fmt == "opml":
        from xml.sax.saxutils import quoteattr
        rows = "".join(f'\n      <outline text={quoteattr(s.get("name") or cid)} title={quoteattr(s.get("name") or cid)} type="rss" '
                       f'xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id={cid}"/>' for cid, s in d["subs"].items())
        xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<opml version="1.1">\n  <head><title>TubeHaus subscriptions</title></head>\n'
               '  <body>\n    <outline text="YouTube" title="YouTube">' + rows + '\n    </outline>\n  </body>\n</opml>\n')
        return f"subscriptions-{day}.opml", "text/x-opml; charset=utf-8", xml.encode()
    body = {"format": "tubehaus-backup", "version": 1, "exported": int(time.time()), **d,
            "shorts": {} if given else jload(_shorts_file(), {})}
    return f"tubehaus-backup-{day}.json", "application/json", json.dumps(body, ensure_ascii=False).encode()


def _yt_parse_ts(s):
    try:
        return int(datetime.datetime.fromisoformat(str(s).replace("Z", "+00:00")).timestamp())
    except Exception:
        return 0


_TAKEOUT_SUFFIXES = ("-videos", "-video", "-동영상", "-vidéos", "-vídeos", "-videó", "-動画", "-视频", "-影片", "-видео", "-filmy")
_TAKEOUT_LIKED = {"liked videos", "좋아요 표시한 동영상", "vidéos j'aime", "videos que me gustan", "mag ich-videos", "高く評価した動画"}
_TAKEOUT_LATER = {"watch later", "나중에 볼 동영상", "à regarder plus tard", "ver más tarde", "später ansehen", "後で見る"}


def _freetube_meta(v, at_ms):
    """A FreeTube playlist item or history entry; FreeTube keeps times in milliseconds."""
    def sec(t):
        try:
            t = int(t or 0)
        except Exception:
            return 0
        return t // 1000 if t > 100_000_000_000 else t
    if not isinstance(v, dict):
        return None
    m = _yt_meta({"id": v.get("videoId"), "title": v.get("title"), "author": v.get("author"),
                  "authorId": v.get("authorId") if UCID.fullmatch(str(v.get("authorId") or "")) else "",
                  "len": v.get("lengthSeconds"), "published": sec(v.get("published")), "views": v.get("viewCount")})
    if m:
        m["at"] = sec(at_ms)
    return m


def _takeout_html(text):
    """Takeout's watch-history.html: one cell per video, newest first (its dates are in the
    account's language, so only the order is kept)."""
    import html
    now, out = int(time.time()), []
    for cell in re.findall(r'<div class="content-cell[^"]*body-1">(.*?)</div>', text, re.S):
        v = re.search(r'href="https://(?:www|music)\.youtube\.com/watch\?v=([\w-]{11})[^"]*">(.*?)</a>', cell, re.S)
        if not v:
            continue
        ch = re.search(r'href="https://www\.youtube\.com/channel/(UC[\w-]{22})">(.*?)</a>', cell, re.S)
        m = _yt_meta({"id": v.group(1), "title": html.unescape(v.group(2)).strip(),
                      "author": html.unescape(ch.group(2)).strip() if ch else "", "authorId": ch.group(1) if ch else ""})
        if m:
            m["at"] = now - len(out)
            out.append(m)
        if len(out) >= 20000:
            break
    return out


def _takeout_playlist(text, name):
    """One Takeout playlist CSV (video ID, time added). The title is the older files' Title
    column, else the file name without Takeout's "-videos"."""
    import csv
    import io
    rows = list(csv.reader(io.StringIO(text)))
    items = []
    for r in rows:
        if len(r) > 1 and VID.fullmatch(r[0].strip()) and re.match(r"\d{4}-\d{2}-\d{2}", r[1].strip()):
            m = _yt_meta({"id": r[0].strip()})
            when = r[1].strip()
            m["at"] = _yt_parse_ts(when) or _yt_parse_ts(when.replace(" UTC", "+00:00").replace(" ", "T", 1))
            items.append(m)
    if not items:
        return None
    title = ""
    head = [c.strip().lower() for c in rows[0]] if rows else []
    if "title" in head and len(rows) > 1 and len(rows[1]) > head.index("title"):
        title = rows[1][head.index("title")].strip()
    if not title:
        base = re.sub(r"\.[^.]*$", "", re.split(r"[\\/]", str(name or ""))[-1])
        title = next((base[:-len(s)] for s in _TAKEOUT_SUFFIXES if base.lower().endswith(s)), base)
    return title.strip()[:150], items


def ytlib_parse_import(text, name=""):
    """Turn an export file from TubeHaus, Invidious, NewPipe, FreeTube (profiles, playlists,
    history), Google Takeout (CSV subscriptions, watch-history.json or .html, playlist CSVs) or
    any OPML into one shape. [name], the file's name, only names a Takeout playlist. Pure, so it
    can be tested."""
    out = {"format": None, "subs": {}, "hist": [], "playlists": [], "wl": [], "likes": [], "dislikes": [],
           "pos": {}, "shorts": None}
    text = text.lstrip("﻿").strip()

    def sub(cid, name="", thumb=""):
        if cid and UCID.fullmatch(cid) and cid not in out["subs"]:
            out["subs"][cid] = {"name": str(name or cid)[:200], "thumb": str(thumb or "")[:500]}

    def meta(v):
        return _yt_meta(v) if isinstance(v, dict) else (_yt_meta({"id": v}) if isinstance(v, str) else None)

    data = None
    try:
        data = json.loads(text)
    except Exception:
        pass
    # FreeTube's .db exports: one JSON object per line, each a profile (its subscriptions), a
    # playlist or a watched video; playlists exported before 0.20 are one JSON array
    rows = None
    if data is None and text.startswith("{"):
        rows = []
        for line in text.splitlines():
            try:
                rows.append(json.loads(line))
            except Exception:
                pass
    elif isinstance(data, dict) and ("playlistName" in data or "timeWatched" in data):
        rows = [data]
    elif isinstance(data, list) and data and isinstance(data[0], dict) and "playlistName" in data[0]:
        rows = data
    if rows:
        for r in rows:
            if not isinstance(r, dict) or r.get("$deleted"):
                continue
            for s in r.get("subscriptions") or []:
                if isinstance(s, dict):
                    sub(s.get("id"), s.get("name"), s.get("thumbnail"))
            if isinstance(r.get("playlistName"), str):
                items = [m for m in (_freetube_meta(v, (v or {}).get("timeAdded") if isinstance(v, dict) else 0)
                                     for v in r.get("videos") or []) if m]
                out["playlists"].append({"title": r["playlistName"][:150], "items": items})
            elif "timeWatched" in r:
                m = _freetube_meta(r, r.get("timeWatched"))
                if m:
                    out["hist"].append(m)
        if out["subs"] or out["playlists"] or out["hist"]:
            out["format"] = "freetube"
            seen = set()
            out["hist"] = [h for h in sorted(out["hist"], key=lambda h: -h["at"])
                           if not (h["id"] in seen or seen.add(h["id"]))][:20000]
            return out
    if isinstance(data, dict) and data.get("format") == "tubehaus-backup":
        out["format"] = "tubehaus"
        for cid, s in (data.get("subs") or {}).items():
            sub(cid, (s or {}).get("name"), (s or {}).get("thumb"))
        for x in data.get("hist") or []:
            h = meta(x)
            if h:
                if isinstance(x, dict) and str(x.get("at", "")).isdigit():
                    h["at"] = int(x["at"])
                out["hist"].append(h)
        out["wl"] = [m for m in (meta(x) for x in data.get("wl") or []) if m]
        out["likes"] = [m for m in (meta(x) for x in data.get("likes") or []) if m]
        out["dislikes"] = [x for x in data.get("dislikes") or [] if isinstance(x, str) and VID.fullmatch(x)]
        for pid, p in (data.get("playlists") or {}).items():
            out["playlists"].append({"id": pid, "title": str(p.get("title", ""))[:150],
                                     "items": [m for m in (meta(x) for x in p.get("items") or []) if m]})
        out["pos"] = {k: v for k, v in (data.get("pos") or {}).items()
                      if VID.fullmatch(k) and isinstance(v, list) and len(v) == 3}
        out["shorts"] = data.get("shorts") if isinstance(data.get("shorts"), dict) else None
        return out
    if isinstance(data, dict) and isinstance(data.get("subscriptions"), list):
        subs = data["subscriptions"]
        if subs and isinstance(subs[0], dict) and "url" in subs[0]:
            out["format"] = "newpipe"
            for s in subs:
                m = UCID.search(str(s.get("url", "")))
                if m and str(s.get("service_id", 0)) == "0":
                    sub(m.group(0), s.get("name"))
        elif subs and isinstance(subs[0], dict):
            out["format"] = "freetube"
            for s in subs:
                sub(s.get("id"), s.get("name"), s.get("thumbnail"))
        else:
            out["format"] = "invidious"
            for cid in subs:
                sub(str(cid))
        hist = [x for x in data.get("watch_history") or [] if isinstance(x, str) and VID.fullmatch(x)]
        now = int(time.time())
        out["hist"] = [dict(_yt_meta({"id": v}), at=now - i) for i, v in enumerate(hist[:3000])]
        for p in data.get("playlists") or []:
            if isinstance(p, dict):
                ids = [v for v in p.get("videos") or [] if isinstance(v, str) and VID.fullmatch(v)]
                out["playlists"].append({"title": str(p.get("title") or "Imported")[:150],
                                         "items": [_yt_meta({"id": v}) for v in ids]})
        return out
    if isinstance(data, list) and data and isinstance(data[0], dict):
        if "titleUrl" in data[0] or "header" in data[0]:
            # Google Takeout watch-history.json
            out["format"] = "takeout-history"
            for x in data[:20000]:
                m = re.search(r"[?&]v=([\w-]{11})", str(x.get("titleUrl", "")))
                if not m:
                    continue
                ch = (x.get("subtitles") or [{}])[0]
                cm = UCID.search(str(ch.get("url", "")))
                title = re.sub(r"^Watched ", "", str(x.get("title", "")))
                h = _yt_meta({"id": m.group(1), "title": title, "author": ch.get("name", ""),
                              "authorId": cm.group(0) if cm else ""})
                if h:
                    h["at"] = _yt_parse_ts(x.get("time"))
                    out["hist"].append(h)
            return out
        out["format"] = "invidious-subscriptions"
        for s in data:
            sub(s.get("authorId") or s.get("id"), s.get("author") or s.get("name"))
        return out
    if data is None and "content-cell" in text and "youtube.com/watch?v=" in text:
        hist = _takeout_html(text)
        if hist:
            out["format"] = "takeout-history"
            out["hist"] = hist
            return out
    if data is None and not UCID.search(text):
        pl = _takeout_playlist(text, name)
        if pl:
            out["format"] = "takeout-playlist"
            title, items = pl
            if title.lower() in _TAKEOUT_LIKED:
                out["likes"] = items
            elif title.lower() in _TAKEOUT_LATER:
                out["wl"] = items
            else:
                out["playlists"].append({"title": title, "items": items})
            return out
    if "<opml" in text[:500].lower() or "<outline" in text:
        out["format"] = "opml"
        for tag in re.findall(r"<outline\b[^>]*>", text):
            m = UCID.search(tag)
            name = re.search(r"""\b(?:title|text)=(?:"([^"]*)"|'([^']*)')""", tag)
            if m:
                import html
                sub(m.group(0), html.unescape(name.group(1) or name.group(2) or "") if name else "")
        return out
    if UCID.search(text):
        import csv
        import io
        out["format"] = "csv"
        for row in csv.reader(io.StringIO(text)):
            cid = next((c.strip() for c in row if UCID.fullmatch(c.strip())), None)
            if not cid:
                m = UCID.search(",".join(row))
                cid = m.group(0) if m else None
            name = row[2].strip() if len(row) > 2 and not UCID.fullmatch(row[2].strip()) else ""
            sub(cid, name)
        return out
    return out


def ytlib_import(b):
    text = str(b.get("text") or "")
    if len(text) > 40_000_000:
        return 413, {"error": "This file is too big"}
    p = ytlib_parse_import(text, str(b.get("name") or ""))
    if not p["format"] or not any((p["subs"], p["hist"], p["playlists"], p["wl"], p["likes"])):
        return 400, {"error": "Couldn't find anything to import in this file"}
    if PUBLIC_MODE or b.get("dry"):
        return 200, dict(p, dry=True)       # the browser merges it into its own library
    now = int(time.time())
    n = {"format": p["format"], "subs": 0, "hist": 0, "playlists": 0, "videos": 0}
    with yt_lock:
        d = _yt_load()
        for cid, s in p["subs"].items():
            if cid not in d["subs"]:
                d["subs"][cid] = dict(s, at=now)
                n["subs"] += 1
        have = {h["id"] for h in d["hist"]}
        new_hist = [dict(h, at=h.get("at") or now) for h in p["hist"] if h["id"] not in have]
        if new_hist:
            d["hist"] = sorted(d["hist"] + list({h["id"]: h for h in new_hist}.values()),
                               key=lambda h: -(h.get("at") or 0))[:3000]
            n["hist"] = len(new_hist)
        for lst in ("wl", "likes"):
            ids = {x["id"] for x in d[lst]}
            d[lst] = (d[lst] + [m for m in p[lst] if m["id"] not in ids])[:5000]
        d["dislikes"] = list(dict.fromkeys(d["dislikes"] + p["dislikes"]))
        for k, v in p["pos"].items():
            if k not in d["pos"] or d["pos"][k][2] < v[2]:
                d["pos"][k] = v
        titles = {(pl.get("title"), tuple(x["id"] for x in pl.get("items", []))) for pl in d["playlists"].values()}
        for pl in p["playlists"]:
            if (pl["title"], tuple(x["id"] for x in pl["items"])) in titles or not pl["items"]:
                continue
            pid = pl.get("id") if re.fullmatch(r"HP[0-9a-f]{16}", str(pl.get("id"))) and pl.get("id") not in d["playlists"] \
                else "HP" + secrets.token_hex(8)
            d["playlists"][pid] = {"title": pl["title"] or "Imported", "items": pl["items"][:5000], "updated": now}
            n["playlists"] += 1
        jsave(_ytlib_file(), d)
    if p["shorts"] and not jload(_shorts_file(), {}).get("ch"):
        with sh_lock:
            jsave(_shorts_file(), p["shorts"])
    untitled = list({x["id"] for x in p["hist"] + p["wl"] + p["likes"] + [i for pl in p["playlists"] for i in pl["items"]]
                     if not x.get("title")})
    n["videos"] = len(untitled)
    threading.Thread(target=_yt_import_fill, args=(bool(n["subs"]), untitled[:500], _uid()), daemon=True).start()
    log("import", f"{p['format']}: {n}", every=0)
    return 200, n


def _yt_import_fill(new_subs, ids, owner=None):
    """After an import: fetch the new channels' feeds, names and pictures, and the titles of
    videos that came as bare IDs (Invidious exports only list video IDs)."""
    if new_subs:
        yt_feed_refresh()
    got = {}
    for vid in ids:
        try:
            v = json.loads(_inv(f"/api/v1/videos/{vid}?fields=title,author,authorId,lengthSeconds,viewCount,published", 20))
            m = _yt_meta({"id": vid, "title": v.get("title"), "author": v.get("author"), "authorId": v.get("authorId"),
                          "len": v.get("lengthSeconds"), "views": v.get("viewCount"), "published": v.get("published")})
            if m:
                got[vid] = m
        except Exception:
            pass
    if not got:
        return
    with yt_lock:
        d = _yt_load(owner)
        for lst in [d["hist"], d["wl"], d["likes"]] + [pl["items"] for pl in d["playlists"].values()]:
            for i, x in enumerate(lst):
                if x["id"] in got and not x.get("title"):
                    lst[i] = dict(x, **{k: v for k, v in got[x["id"]].items() if v})
        jsave(_ytlib_file(owner), d)
    log("import", f"filled {len(got)}/{len(ids)} video titles", every=0)


# ---------- Shorts recommendations, learned on this server only ----------
# No Google account and no cloud: every Short you watch reports how long you watched it,
# whether you looped, liked, disliked or swiped away. That moves a score per channel and
# per keyword (the video's tags, hashtags and genre). The next Shorts are picked from a few
# candidate pools and ranked by those scores, with some room for new things.
SHORTS = f"{STATE}/shorts.json"
sh_lock = threading.Lock()
sh_cand = {}                 # pool key -> (time, [items])
SH_DECAY = 0.97              # per day: old tastes fade
SH_CAP = 10.0                # an affinity never goes beyond +-10
SH_SEEN_DAYS = 30            # a Short you've seen doesn't come back for this long
SH_MAX_LEN = 180             # what counts as a Short among search results (seconds)


def _sh_load():
    d = jload(_shorts_file(), {})
    for k, v in (("ch", {}), ("kw", {}), ("seen", {}), ("names", {}), ("decayed", time.time())):
        d.setdefault(k, v)
    return d


def _sh_tokens(title="", keywords=(), genre=""):
    """Words that describe a Short: its tags, the #hashtags in the title, its genre."""
    out = []
    for k in list(keywords or [])[:15] + re.findall(r"#([^\s#]{2,40})", title or ""):
        k = str(k).strip().lower().lstrip("#")
        if 2 <= len(k) <= 40 and k not in ("shorts", "short", "youtubeshorts", "viral", "fyp", "trending"):
            out.append(k)
    if genre:
        out.append("genre:" + str(genre).lower())
    return list(dict.fromkeys(out))[:20]


def _sh_engagement(watched, length, liked=False, disliked=False):
    """How much one viewing says you liked it: swiped away < 0 < watched < looped < liked."""
    length = max(1.0, float(length or 0))
    watched = max(0.0, float(watched or 0))
    ratio = watched / length
    if watched < 2 or ratio < 0.2:
        e = -1.0
    elif ratio < 0.8:
        e = 0.3
    else:
        e = 1.0 + 0.5 * min(3, int(ratio) - 1 if ratio >= 2 else 0)
    if liked:
        e += 2.0
    if disliked:
        e -= 3.0
    return e


def _sh_decay(d, now):
    days = (now - d.get("decayed", now)) / 86400
    if days >= 0.25:
        f = SH_DECAY ** days
        for table in ("ch", "kw"):
            d[table] = {k: round(v * f, 4) for k, v in d[table].items() if abs(v * f) >= 0.05}
        d["decayed"] = now


def shorts_signal(b):
    vid = str(b.get("id", ""))
    if not re.fullmatch(r"[\w-]{11}", vid):
        return 400, {"error": "bad id"}
    cid = str(b.get("authorId") or "")
    e = _sh_engagement(b.get("watched"), b.get("len"), bool(b.get("liked")), bool(b.get("disliked")))
    toks = _sh_tokens(str(b.get("title") or ""), b.get("keywords") if isinstance(b.get("keywords"), list) else [],
                      str(b.get("genre") or ""))
    now = time.time()
    with sh_lock:
        d = _sh_load()
        _sh_decay(d, now)
        if re.fullmatch(r"UC[\w-]{22}", cid):
            d["ch"][cid] = max(-SH_CAP, min(SH_CAP, d["ch"].get(cid, 0) + e))
            d["names"][cid] = str(b.get("author") or "")[:100]
        for t in toks:
            d["kw"][t] = max(-SH_CAP, min(SH_CAP, d["kw"].get(t, 0) + e * 0.5))
        d["seen"][vid] = int(now)
        if len(d["seen"]) > 3000:
            for k in sorted(d["seen"], key=d["seen"].get)[:len(d["seen"]) - 3000]:
                del d["seen"][k]
        for table, keep in (("ch", 300), ("kw", 500)):
            if len(d[table]) > keep:
                d[table] = dict(sorted(d[table].items(), key=lambda kv: -abs(kv[1]))[:keep])
        d["names"] = {k: v for k, v in d["names"].items() if k in d["ch"]}
        jsave(_shorts_file(), d)
    return 200, {"ok": True, "score": e}


def shorts_reset():
    with sh_lock:
        jsave(_shorts_file(), {})
    sh_cand.clear()
    return 200, {"ok": True}


def _sh_item(v, src, tags=()):
    vid = v.get("videoId") or v.get("id")
    if not vid or not re.fullmatch(r"[\w-]{11}", vid):
        return None
    ln = int(v.get("lengthSeconds") or v.get("len") or 0)
    if src not in ("sub", "chan") and not (0 < ln <= SH_MAX_LEN):
        return None
    return {"id": vid, "title": v.get("title", ""), "author": v.get("author", ""), "authorId": v.get("authorId", ""),
            "views": v.get("viewCount", v.get("views")), "published": v.get("published") or 0, "len": ln, "src": src,
            "tags": list(tags)}


def _sh_pool(key, ttl, fetch):
    c = sh_cand.get(key)
    if c and time.time() - c[0] < ttl:
        return c[1]
    try:
        items = [x for x in fetch() if x]
    except Exception as ex:
        log("shorts", f"pool {key[:40]} FAIL {repr(ex)[:80]}")
        items = c[1] if c else []
    sh_cand[key] = (time.time(), items)
    if len(sh_cand) > 200:
        for k in sorted(sh_cand, key=lambda k: sh_cand[k][0])[:50]:
            sh_cand.pop(k, None)
    return items


def _sh_search(q, region, src, tag=None):
    path = "/api/v1/search?" + urllib.parse.urlencode({"q": q, "type": "video", "duration": "short", "region": region})
    return [_sh_item(v, src, [tag] if tag else ()) for v in json.loads(_inv(path, 30)) if isinstance(v, dict)]


def _sh_channel(cid):
    """A channel's latest Shorts from its public RSS feed (no Invidious round trip)."""
    yt_feed_one(cid)
    return [_sh_item(x, "chan") for x in yt_feed["items"].get(cid, []) if x.get("short")]


def shorts_candidates(d, region):
    """Candidate pools: subscriptions, channels you like, words you like, what's popular."""
    pools = []
    with yt_lock:
        subs = list(_yt_load()["subs"])
    pools.append([_sh_item(x, "sub") for cid in subs for x in yt_feed["items"].get(cid, []) if x.get("short")])
    liked_ch = [c for c, s in sorted(d["ch"].items(), key=lambda kv: -kv[1]) if s > 0.5 and c not in subs][:6]
    for cid in liked_ch:
        pools.append(_sh_pool("ch:" + cid, 3600, lambda cid=cid: _sh_channel(cid)))
    liked_kw = [k for k, s in sorted(d["kw"].items(), key=lambda kv: -kv[1]) if s > 0.5 and not k.startswith("genre:")][:5]
    for kw in liked_kw:
        pools.append(_sh_pool(f"kw:{region}:{kw}", 1800, lambda kw=kw: _sh_search(f"{kw} #shorts", region, "kw", kw)))
    pools.append(_sh_pool(f"pop:{region}", 1800, lambda: _sh_search("#shorts", region, "pop")))
    return pools


def shorts_rank(d, items, n, skip=(), now=None, rnd=None):
    """Score, de-duplicate and diversify. Pure function so it can be tested."""
    import math
    import random
    rnd = rnd or random.Random()
    now = now or time.time()
    skip = set(skip)
    seen = {k for k, t in d["seen"].items() if now - t < SH_SEEN_DAYS * 86400}
    src_bonus = {"sub": 0.6, "chan": 0.4, "kw": 0.2, "pop": 0.0}
    best = {}
    for v in items:
        if not v or v["id"] in skip or v["id"] in seen:
            continue
        toks = _sh_tokens(v.get("title", ""), v.get("tags") or ())
        kw = sum(d["kw"].get(t, 0) for t in toks) / math.sqrt(len(toks)) if toks else 0
        s = (d["ch"].get(v.get("authorId", ""), 0) + 0.6 * kw + src_bonus.get(v.get("src"), 0)
             + 0.1 * math.log10(1 + (v.get("views") or 0)) / 7
             + (0.2 if v.get("published") and now - v["published"] < 3 * 86400 else 0)
             + rnd.gauss(0, 0.15))
        if v["id"] not in best or best[v["id"]][0] < s:
            best[v["id"]] = (s, v)
    ranked = sorted(best.values(), key=lambda sv: -sv[0])
    out, per_ch = [], {}
    explore = [v for s, v in ranked if v.get("src") == "pop"]
    rnd.shuffle(explore)
    for s, v in ranked:
        if len(out) >= n:
            break
        if len(out) % 4 == 3 and explore:          # every 4th slot: something new
            e = explore.pop()
            if e["id"] not in {x["id"] for x in out} and per_ch.get(e["authorId"], 0) < 2:
                out.append(dict(e, why="new"))
                per_ch[e["authorId"]] = per_ch.get(e["authorId"], 0) + 1
                if len(out) >= n:
                    break
        if v["id"] in {x["id"] for x in out} or per_ch.get(v["authorId"], 0) >= 2:
            continue
        if d["ch"].get(v["authorId"], 0) <= -3:      # channels you keep swiping away
            continue
        out.append(dict(v, why=v.get("src")))
        per_ch[v["authorId"]] = per_ch.get(v["authorId"], 0) + 1
    return out


def shorts_next(qs):
    try:
        n = max(1, min(30, int(qs.get("n") or 10)))
    except ValueError:
        n = 10
    region = qs.get("region", "US")
    region = region if re.fullmatch(r"[A-Z]{2}", region) else "US"
    skip = [x for x in qs.get("skip", "").split(",") if re.fullmatch(r"[\w-]{11}", x)][:300]
    if PUBLIC_MODE:
        d = {"ch": {}, "kw": {}, "seen": {}, "names": {}}
    else:
        with sh_lock:
            d = _sh_load()
    items = [v for pool in shorts_candidates(d, region) for v in pool]
    return {"items": shorts_rank(d, items, n, skip),
            "learned": {"channels": len([1 for s in d["ch"].values() if s > 0]), "keywords": len(d["kw"])}}


# ---------- YouTube's topic pages: Music, Live, News, Sports, Learning, Gaming, Fashion & beauty ----------
# The pages YouTube's own app opens from its top tabs and from "Explore". Each is a channel of YouTube's
# whose first tab is a few titled shelves ("Live now", "This week's best", ...). Read straight from
# YouTube's browse API (through YT_PROXY like every other YouTube request), then the same shapes as
# Invidious' API, so the web page draws them with its usual cards. What they hold depends on the
# region: some are empty in some countries (News in Korea), and the web page falls back to a search.
TOPICS = {
    "music": "UC-9-kyTW8ZkZNDHQJ6FgpwQ", "live": "UC4R8DWoMoI7CAwX8_LjQHig", "news": "UCYfdidRxbB8Qhf0Nx7ioOYw",
    "sports": "UCEgdi0XIXXZ-qJOFPf4JSKw", "learning": "UCtFRv9O2AHqOZjjynzrv-xg", "gaming": "UCOpNcN46UbXVtpKMrmU4Abg",
    "fashion": "UCrpQ4p1Ql_hG8rKXIKM1MOQ",
}
TOPIC_TTL = 1800          # YouTube refreshes these pages a few times a day
topic_cache = {}          # (key, region, hl) -> (time, answer)
topic_lock = threading.Lock()
WEB_CLIENT = {"clientName": "WEB", "clientVersion": "2.20251101.00.00"}
BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"


def yt_browse(body, region="US", hl="en", timeout=15):
    data = json.dumps({"context": {"client": dict(WEB_CLIENT, hl=hl, gl=region)}, **body}).encode()
    req = urllib.request.Request("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", data=data,
                                 headers={"Content-Type": "application/json", "User-Agent": BROWSER_UA})
    return json.loads(YT.open(req, timeout=timeout).read())


def _dig(o, *path):
    for k in path:
        if isinstance(o, dict):
            o = o.get(k)
        elif isinstance(o, list) and isinstance(k, int) and -len(o) <= k < len(o):
            o = o[k]
        else:
            return None
    return o


def _txt(o):
    """YouTube's text in any of its three forms: simpleText, runs, or a view model's content."""
    if isinstance(o, str):
        return o
    if not isinstance(o, dict):
        return ""
    if isinstance(o.get("simpleText"), str):
        return o["simpleText"]
    if isinstance(o.get("runs"), list):
        return "".join(r.get("text", "") for r in o["runs"] if isinstance(r, dict))
    if isinstance(o.get("content"), str):
        return o["content"]
    return ""


def _all(o, key, skip=()):
    """every value stored under key below o, in document order"""
    out = []

    def walk(e):
        if isinstance(e, list):
            for x in e:
                walk(x)
        elif isinstance(e, dict):
            for k, v in e.items():
                if k == key:
                    out.append(v)
                elif k not in skip:
                    walk(v)
    walk(o)
    return out


def _secs(t):
    """'1:02:03' -> 3723; anything else -> 0"""
    if not isinstance(t, str) or not re.fullmatch(r"\d+(:\d{1,2}){1,2}", t.strip()):
        return 0
    n = 0
    for part in t.strip().split(":"):
        n = n * 60 + int(part)
    return n


def _thumbs(o):
    """a list of {url, width, height} from YouTube's thumbnails or image sources, smallest first"""
    src = _dig(o, "thumbnails") or _dig(o, "sources") or []
    out = []
    for s in src if isinstance(src, list) else []:
        u = s.get("url") if isinstance(s, dict) else None
        if isinstance(u, str) and u:
            out.append({"url": ("https:" + u) if u.startswith("//") else u, "width": s.get("width") or 0, "height": s.get("height") or 0})
    return sorted(out, key=lambda x: x["width"])


def _byline(o):
    """(channel name, UC id) from a byline text with a browse link"""
    name = _txt(o)
    ids = [i for i in _all(o, "browseId") if isinstance(i, str) and re.fullmatch(r"UC[\w-]{22}", i)]
    return name, ids[0] if ids else ""


def _video(vid, title, author="", author_id="", length=0, views_text="", published_text="", live=False, upcoming=False,
           short=False, avatar=None):
    return {"type": "video", "videoId": vid, "title": title, "author": author, "authorId": author_id,
            "lengthSeconds": length, "viewCountText": views_text, "publishedText": published_text,
            "liveNow": live, "isUpcoming": upcoming, "short": short, "authorThumbnails": avatar or []}


def topic_item(kind, v):
    """One item of a browse answer as Invidious would give it, or None (ads, channels, oddities)."""
    if kind in ("videoRenderer", "gridVideoRenderer", "compactVideoRenderer"):
        vid = v.get("videoId")
        if not isinstance(vid, str) or not re.fullmatch(r"[\w-]{11}", vid):
            return None
        author, cid = _byline(v.get("longBylineText") or v.get("ownerText") or v.get("shortBylineText") or {})
        badges = [b for b in _all(v.get("badges") or [], "style") if isinstance(b, str)]
        overlays = [b for b in _all(v.get("thumbnailOverlays") or [], "style") if isinstance(b, str)]
        if any("MEMBERS" in b for b in badges):
            return None                     # members only: it won't play without their account
        live = "BADGE_STYLE_TYPE_LIVE_NOW" in badges or "LIVE" in overlays
        upcoming = "UPCOMING" in overlays or bool(v.get("upcomingEventData"))
        length = _secs(_txt(v.get("lengthText"))) or next(
            (_secs(_txt(_dig(o, "thumbnailOverlayTimeStatusRenderer", "text"))) for o in v.get("thumbnailOverlays") or []
             if isinstance(o, dict) and "thumbnailOverlayTimeStatusRenderer" in o), 0)
        return _video(vid, _txt(v.get("title")), author, cid, length,
                      _txt(v.get("viewCountText")) or _txt(v.get("shortViewCountText")), _txt(v.get("publishedTimeText")),
                      live, upcoming, "SHORTS" in overlays or bool(_dig(v, "navigationEndpoint", "reelWatchEndpoint")),
                      _thumbs(_dig(v, "channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail")
                              or v.get("channelThumbnail") or {}))
    if kind == "lockupViewModel":
        cid_ = v.get("contentId")
        if not isinstance(cid_, str) or _dig(v, "metadata", "feedAdMetadataViewModel"):
            return None
        meta = _dig(v, "metadata", "lockupMetadataViewModel") or {}
        title = _txt(meta.get("title"))
        rows = []
        for row in _dig(meta, "metadata", "contentMetadataViewModel", "metadataRows") or []:
            parts = [_txt(p.get("text")) for p in (row.get("metadataParts") or []) if isinstance(p, dict)]
            parts = [p for p in parts if p]
            if parts:
                rows.append(parts)
        # the channel is the metadata part that links to one ("재생목록" / "Playlist" next to it does not)
        owner = next((_txt(p.get("text")) for row in _dig(meta, "metadata", "contentMetadataViewModel", "metadataRows") or []
                      for p in row.get("metadataParts") or [] if isinstance(p, dict)
                      and any(isinstance(i, str) and i.startswith("UC") for i in _all(p.get("text") or {}, "browseId"))), "")
        badges = _all(v.get("contentImage") or {}, "thumbnailBadgeViewModel")
        badge = next((b.get("text") for b in badges if isinstance(b, dict) and isinstance(b.get("text"), str)), "")
        live = any("LIVE" in str(b.get("badgeStyle", "")) for b in badges if isinstance(b, dict))
        ids = [i for i in _all(meta, "browseId", skip=("menuButton",)) if isinstance(i, str) and re.fullmatch(r"UC[\w-]{22}", i)]
        ctype = v.get("contentType") or ""
        if ctype == "LOCKUP_CONTENT_TYPE_VIDEO" or (not ctype and re.fullmatch(r"[\w-]{11}", cid_)):
            author = rows[0][0] if len(rows) > 1 else ""
            stats = rows[-1] if rows else []
            return _video(cid_, title, author, ids[0] if ids else "", _secs(badge), stats[0] if stats else "",
                          stats[1] if len(stats) > 1 else "", live, False, False, _thumbs(_dig(meta, "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image") or {}))
        if ctype in ("LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST") and re.fullmatch(r"(PL|OLAK|RDCLAK)[\w-]+", cid_):
            # a playlist (an album, a chart): it opens on the playlist page. Mixes ("stations") are left
            # out: they are made for one listener on YouTube and don't open as a playlist
            th = _thumbs(_dig(v, "contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel", "image") or {})
            count = re.sub(r"\D", "", badge or "")
            return {"type": "playlist", "playlistId": cid_, "title": title, "author": owner,
                    "authorId": ids[0] if ids else "", "videoCount": int(count) if count else None,
                    "playlistThumbnail": th[-1]["url"] if th else ""}
        return None
    if kind == "shortsLockupViewModel":
        vid = _dig(v, "onTap", "innertubeCommand", "reelWatchEndpoint", "videoId")
        if not isinstance(vid, str):
            ent = v.get("entityId") or ""
            vid = ent.rsplit("-", 1)[-1] if isinstance(ent, str) else ""
        if not re.fullmatch(r"[\w-]{11}", vid or ""):
            return None
        return _video(vid, _txt(_dig(v, "overlayMetadata", "primaryText")), views_text=_txt(_dig(v, "overlayMetadata", "secondaryText")), short=True)
    if kind == "reelItemRenderer":
        vid = v.get("videoId")
        if not isinstance(vid, str) or not re.fullmatch(r"[\w-]{11}", vid):
            return None
        return _video(vid, _txt(v.get("headline")), views_text=_txt(v.get("viewCountText")), short=True)
    return None


ITEM_KINDS = ("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "lockupViewModel", "shortsLockupViewModel", "reelItemRenderer")


def topic_items(root):
    """every item below root, in order, once each"""
    out, seen = [], set()

    def walk(e):
        if isinstance(e, list):
            for x in e:
                walk(x)
        elif isinstance(e, dict):
            for k, v in e.items():
                if k in ITEM_KINDS and isinstance(v, dict):
                    try:
                        it = topic_item(k, v)
                    except Exception:
                        it = None             # one odd item must never sink the whole page
                    key = it and (it.get("videoId") or it.get("playlistId"))
                    if key and key not in seen:
                        seen.add(key)
                        out.append(it)
                elif k not in ("continuationItemRenderer", "menu", "menuRenderer"):
                    walk(v)
    walk(root)
    return out


def topic_shelves(d):
    """[{title, items}] from a topic page's first tab, in YouTube's order; a page without shelves is one untitled shelf"""
    tabs = _dig(d, "contents", "twoColumnBrowseResultsRenderer", "tabs") or []
    sel = next((t for t in tabs if _dig(t, "tabRenderer", "selected")), tabs[0] if tabs else None)
    content = _dig(sel, "tabRenderer", "content") or {}
    shelves = []
    for kind in ("richShelfRenderer", "shelfRenderer"):
        for sh in _all(content, kind):
            items = topic_items(sh.get("contents") or sh.get("content") or [])
            if items:
                shelves.append({"title": _txt(sh.get("title")), "items": items})
        if shelves:
            break
    if not shelves:
        items = topic_items(content)
        if items:
            shelves.append({"title": "", "items": items})
    return shelves


def topic_page(qs):
    key = (qs.get("key") or "").lower()
    if key not in TOPICS:
        return {"error": "unknown topic"}
    region = (qs.get("region") or "US").upper()
    region = region if re.fullmatch(r"[A-Z]{2}", region) else "US"
    hl = qs.get("hl") or "en"
    hl = hl if re.fullmatch(r"[a-z]{2}(-[A-Za-z]{2,4})?", hl) else "en"
    ck = (key, region, hl)
    with topic_lock:
        hit = topic_cache.get(ck)
    if hit and time.time() - hit[0] < TOPIC_TTL:
        return hit[1]
    try:
        shelves = topic_shelves(yt_browse({"browseId": TOPICS[key]}, region, hl))
    except Exception as ex:
        log("topic", f"{key} {region} FAIL {repr(ex)[:100]}")
        if hit:
            return hit[1]                     # an old copy beats an error
        return {"error": "YouTube didn't answer"}
    out = {"key": key, "region": region, "shelves": shelves}
    with topic_lock:
        topic_cache[ck] = (time.time(), out)
        if len(topic_cache) > 200:            # many regions x languages: keep the newest
            for k in sorted(topic_cache, key=lambda k: topic_cache[k][0])[:50]:
                topic_cache.pop(k, None)
    return out


# ---------- Music: albums and artists from YouTube Music, songs from YouTube, lyrics from LRCLIB ----------
# The lookups the apps make (engine/Music.kt), for the web page's Music tab. No account and no Premium:
# where YouTube keeps Music's songs and charts for Premium (Korea among other countries, November 2026),
# its albums and artists still answer, and so do YouTube's own radio mixes of a song ("RDAMVM" + id),
# which are mostly the songs' audio tracks. So: albums and artists from music.youtube.com, songs from
# YouTube's search, the radio from YouTube's watch page. Everything goes through YT_PROXY.
MUSIC_CLIENT = {"clientName": "WEB_REMIX", "clientVersion": "1.20261104.00.00"}
M_ARTISTS = "EgWKAQIgAWoQEBAQBRAJEAoQBBAVEA4QEQ=="
M_ALBUMS = "EgWKAQIYAWoQEBAQBRAJEAoQBBAVEA4QEQ=="
YT_ONLY_VIDEOS = "EgIQAQ=="
MUSIC_TTL = 6 * 3600
music_cache = {}          # (kind, args) -> (time, answer)
music_lock = threading.Lock()
LYRICS_UA = "TubeHaus (https://github.com/biolend83/tubehaus)"
# pictures a music page may show; they come through here so the viewer's browser only talks to this server
MUSIC_IMG_HOSTS = ("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com", "i.ytimg.com")


def yt_call(endpoint, body, music=False, hl="en", region="US", timeout=15):
    client = dict(MUSIC_CLIENT if music else WEB_CLIENT, hl=hl, gl=region)
    host = "music.youtube.com" if music else "www.youtube.com"
    headers = {"Content-Type": "application/json", "User-Agent": BROWSER_UA}
    if music:
        headers.update({"Origin": "https://music.youtube.com", "Referer": "https://music.youtube.com/"})
    data = json.dumps({"context": {"client": client}, **body}).encode()
    req = urllib.request.Request(f"https://{host}/youtubei/v1/{endpoint}?prettyPrint=false", data=data, headers=headers)
    return json.loads(YT.open(req, timeout=timeout).read())


def _first(o, key):
    a = _all(o, key)
    return a[0] if a else None


# ---------- the built-in YouTube engine (in place of Invidious and invidious-companion) ----------
# TubeHaus used to stand on Invidious (lists, video pages) and its companion (streams). This
# section asks YouTube itself, the way the apps do (common/src/app/tubehaus/engine in the app
# branch), and answers with Invidious' JSON, so the web page, the apps' server mode and the
# rest of this file keep working unchanged:
#   * lists (search, channels, playlists, comments, related videos) from the WEB client;
#   * streams from VISIONOS (plain URLs to the end, no PO token), ANDROID_VR as a fallback;
#   * the exact upload time, category and like count from WEB's own player answer (which has
#     no streams any more, but still the page's details);
#   * every stream, caption and picture through this server (and WARP): a stream only plays
#     from the address it was given to, and the viewer's browser never talks to Google.
# Every request carries a visitor identity, as a browser's cookie would; YouTube's bot wall is
# tied to it, so when every player client is walled the identity is replaced once (at most twice
# in six hours), then the WARP address.
import gzip, math, random
from concurrent.futures import ThreadPoolExecutor

ENG_HL = env("HT_HL", "")                   # YouTube's language for texts ("3 days ago"); "" = the viewer's browser
ENG_REGION = env("HT_REGION", "US")         # the default region of searches and topic pages
ENG_POOL = ThreadPoolExecutor(16, thread_name_prefix="eng")
ENG_VISITOR = f"{STATE}/visitor.json"
VIS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
# (context.client, X-YouTube-Client-Name, User-Agent), in the order they are tried. When YouTube
# stops answering one, its version here is the first thing to update (the app's clients.json has
# the same list and an automatic repair, android-app .github/workflows/engine-check.yml).
ENG_PLAYERS = [
    ({"clientName": "VISIONOS", "clientVersion": "1.02", "deviceMake": "Apple", "deviceModel": "RealityDevice17,1",
      "osName": "visionOS", "osVersion": "26.5.23O471"}, 101, VIS_UA),
    ({"clientName": "ANDROID_VR", "clientVersion": "1.65.10", "deviceMake": "Oculus", "deviceModel": "Quest 3",
      "androidSdkVersion": 32, "osName": "Android", "osVersion": "12L"}, 28, VR_UA),
]
ENG_WEB = dict(WEB_CLIENT, clientVersion="2.20260708.00.00")
_vis = {"v": None}
_vis_lk = threading.Lock()
_vis_rerolls = []
VID_RE = re.compile(r"[\w-]{11}")
UC_RE = re.compile(r"UC[\w-]{22}")


class EngError(Exception):
    """YouTube refused or failed: code is the HTTP status to answer with"""
    def __init__(self, msg, code=500):
        super().__init__(msg)
        self.code = code


def _eng_fetch_visitor():
    try:
        d = it_call("visitor_id", {}, visitor=False)
        v = (d.get("responseContext") or {}).get("visitorData")
        if v:
            jsave(ENG_VISITOR, {"visitorData": v, "at": int(time.time())})
        return v
    except Exception as ex:
        log("engine", "visitor FAIL %s" % repr(ex)[:100])
        return None


def eng_visitor():
    """the visitor identity in use: the saved one while it is under a week old, else a new one"""
    v = _vis["v"]
    if v:
        return v
    with _vis_lk:
        if _vis["v"]:
            return _vis["v"]
        saved = jload(ENG_VISITOR, {})
        v = saved.get("visitorData") if 0 <= time.time() - (saved.get("at") or 0) < 7 * 86400 else None
        _vis["v"] = v or _eng_fetch_visitor()
        return _vis["v"]


def eng_reroll():
    """a new visitor identity for a walled one; at most twice in six hours, so a walled network can't loop"""
    with _vis_lk:
        now = time.time()
        _vis_rerolls[:] = [t for t in _vis_rerolls if now - t < 6 * 3600]
        if len(_vis_rerolls) >= 2:
            return False
        v = _eng_fetch_visitor()
        if not v:
            return False
        _vis_rerolls.append(now)
        _vis["v"] = v
        log("engine", "new visitor identity", every=0)
        return True


_it_idle = queue.LifoQueue()      # open connections to www.youtube.com, reused: a new one costs a TLS handshake through WARP


def _it_conn(timeout):
    try:
        c = _it_idle.get_nowait()
        c.timeout = timeout
        if c.sock:
            c.sock.settimeout(timeout)
        return c, True
    except queue.Empty:
        pass
    if YT_PROXY:
        host, _, port = YT_PROXY.rpartition(":")
        c = http.client.HTTPSConnection(host, int(port), timeout=timeout)
        c.set_tunnel("www.youtube.com", 443)
    else:
        c = http.client.HTTPSConnection("www.youtube.com", 443, timeout=timeout)
    return c, False


def _it_post(path, data, headers, timeout):
    """POST to www.youtube.com on a kept-open connection, the answer gzip'd (about a tenth of the bytes);
    returns (status, body)"""
    for attempt in range(2):
        c, reused = _it_conn(timeout)
        try:
            c.request("POST", path, body=data, headers=dict(headers, **{"Accept-Encoding": "gzip"}))
            r = c.getresponse()
            body, status, enc = r.read(), r.status, (r.getheader("Content-Encoding") or "").lower()
        except (http.client.HTTPException, OSError):
            c.close()
            if reused and attempt == 0:      # YouTube closed it while it sat idle: once more on a new one
                continue
            raise
        if r.will_close or _it_idle.qsize() >= 8:
            c.close()
        else:
            _it_idle.put(c)
        return status, gzip.decompress(body) if enc == "gzip" else body
    raise OSError("no connection")


def it_call(endpoint, body, client=None, cid=1, ua=BROWSER_UA, hl="en", gl="US", timeout=15, visitor=True):
    """one InnerTube request (YouTube's own JSON API) through YT_PROXY; 429/503 wait 1-2 s, then 3-6 s"""
    client = client or ENG_WEB
    v = eng_visitor() if visitor else None
    ctx = dict(client, hl=hl or "en", gl=gl or "US", userAgent=ua)
    if v:
        ctx["visitorData"] = v
    headers = {"Content-Type": "application/json", "User-Agent": ua, "X-YouTube-Client-Name": str(cid),
               "X-YouTube-Client-Version": client["clientVersion"], "Origin": "https://www.youtube.com",
               "Referer": "https://www.youtube.com/", "Accept-Language": hl or "en"}
    if v:
        headers["X-Goog-Visitor-Id"] = v
    data = json.dumps({"context": {"client": ctx}, **{k: x for k, x in body.items() if x is not None}}).encode()
    path = "/youtubei/v1/%s?prettyPrint=false" % endpoint
    for attempt in range(3):
        try:
            status, raw = _it_post(path, data, headers, timeout)
        except (http.client.HTTPException, OSError) as e:
            if attempt < 1:
                continue
            raise EngError("No connection to YouTube (%s)" % repr(e)[:80], 502)
        if status in (429, 503) and attempt < 2:
            time.sleep(random.uniform(1, 2) if attempt == 0 else random.uniform(3, 6))
            continue
        if status in (400, 404):
            raise EngError("YouTube didn't find that", 404)
        if status >= 300:
            raise EngError("YouTube answered %d" % status, 502)
        try:
            return json.loads(raw)
        except ValueError:
            raise EngError("YouTube sent something that isn't JSON", 502)
    raise EngError("YouTube is busy", 502)


# ---- numbers and times in YouTube's texts, in the languages it writes them in ----
_MULT = {"k": 1e3, "m": 1e6, "b": 1e9, "천": 1e3, "만": 1e4, "억": 1e8, "千": 1e3, "万": 1e4, "萬": 1e4, "亿": 1e8, "億": 1e8,
         "mil": 1e3, "mio": 1e6, "mln": 1e6, "mrd": 1e9, "tys": 1e3, "тыс": 1e3, "млн": 1e6, "млрд": 1e9, "lakh": 1e5, "crore": 1e7,
         "jt": 1e6, "rb": 1e3, "bin": 1e3, "mn": 1e6, "md": 1e9}
_NUM = re.compile(r"(\d+(?:[.,  ]\d+)*)\s*(lakh|crore|mil|mio|mln|mrd|tys|тыс|млн|млрд|jt|rb|bin|mn|md|[kmb천만억千万萬亿億])?(?![a-z])", re.I)


def eng_count(text):
    """'1.2M views' / '조회수 79만회' / '1,234 views' / '12万回視聴' -> a number; None when there is none"""
    if not isinstance(text, str) or not text.strip():
        return None
    m = _NUM.search(text.replace(" ", " "))
    if not m:
        return 0 if re.search(r"(?i)\bno\b|없음", text) else None
    num, suf = m.group(1), (m.group(2) or "").lower()
    if suf:
        num = num.replace(",", ".") if re.fullmatch(r"\d+,\d{1,2}", num) else num.replace(",", "")
        num = re.sub(r"[  ]", "", num)
        try:
            return int(float(num) * _MULT.get(suf, 1))
        except ValueError:
            return None
    return int(re.sub(r"\D", "", num) or 0)


_AGO_UNITS = [(r"second|sec|초|秒|segundo|seconde|sekunde|секунд|detik|saniye", 1),
              (r"minute|min|분|分|minuto|минут|menit|dakika", 60),
              (r"hour|시간|時間|小时|小時|hora|heure|stunde|час|jam|saat", 3600),
              (r"day|일|日|天|día|dia|jour|tag|день|дня|дней|hari|gün", 86400),
              (r"week|주|週|周|semana|semaine|woche|недел|minggu|hafta", 7 * 86400),
              (r"month|개월|달|か月|ヶ月|个月|個月|mes|mois|monat|месяц|bulan|ay", 30 * 86400),
              (r"year|년|年|año|ano|an|jahr|год|лет|tahun|yıl", 365 * 86400)]


def eng_ago(text, now=None):
    """'3 days ago' / '3일 전' / 'Streamed 2 weeks ago' -> the epoch second it means; 0 when it can't be read"""
    if not isinstance(text, str):
        return 0
    m = re.search(r"(\d+)\s*([^\d\s,.]+)", text)
    if not m:
        return 0
    n, word = int(m.group(1)), m.group(2).lower()
    for pat, sec in _AGO_UNITS:
        if re.match(pat, word):
            return int((now or time.time()) - n * sec)
    return 0


def _https(u):
    return ("https:" + u) if isinstance(u, str) and u.startswith("//") else (u or "")


def _vthumbs(vid):
    """the picture list Invidious gives for a video (through this server's /vi/)"""
    q = [("maxres", "maxres", 1280, 720), ("maxresdefault", "maxresdefault", 1280, 720), ("sddefault", "sddefault", 640, 480),
         ("high", "hqdefault", 480, 360), ("medium", "mqdefault", 320, 180), ("default", "default", 120, 90),
         ("start", "1", 120, 90), ("middle", "2", 120, 90), ("end", "3", 120, 90)]
    return [{"quality": a, "url": "%s/vi/%s/%s.jpg" % (PUBLIC, vid, b), "width": w, "height": h} for a, b, w, h in q]


def _imgs(o):
    """the sizes of the first picture anywhere below o (YouTube nests them deeper on newer pages)"""
    th = _thumbs(o) if isinstance(o, dict) else []
    if th:
        return th
    for k in ("sources", "thumbnails"):
        for arr in _all(o or {}, k):
            th = _thumbs({k: arr})
            if th:
                return th
    return []


def _athumbs(src):
    """a channel picture in Invidious' six sizes; src is a URL or YouTube's thumbnails object"""
    if not isinstance(src, str):
        th = _imgs(src or {})
        src = th[-1]["url"] if th else ""
    src = _https(src)
    if not src:
        return []
    if not re.search(r"=s\d+", src):
        return [{"url": src, "width": 176, "height": 176}]
    return [{"url": re.sub(r"=s\d+", "=s%d" % s, src), "width": s, "height": s} for s in (32, 48, 76, 100, 176, 512)]


def eng_vitem(v, author="", author_id=""):
    """an item from topic_item() (or alike) as a full Invidious video entry"""
    vid = v.get("videoId")
    views_text = v.get("viewCountText") or ""
    pub_text = v.get("publishedText") or ""
    views = v.get("viewCount")
    if views is None:
        views = eng_count(views_text) if views_text else None
    return {"type": "video", "title": v.get("title") or "", "videoId": vid, "author": v.get("author") or author,
            "authorId": v.get("authorId") or author_id, "authorUrl": "/channel/" + (v.get("authorId") or author_id),
            "authorVerified": bool(v.get("authorVerified")), "authorThumbnails": v.get("authorThumbnails") or [],
            "videoThumbnails": _vthumbs(vid), "description": v.get("description") or "", "descriptionHtml": "",
            "viewCount": views if views is not None else 0, "viewCountText": views_text,
            "published": v.get("published") or eng_ago(pub_text), "publishedText": pub_text,
            "lengthSeconds": v.get("lengthSeconds") or 0, "liveNow": bool(v.get("liveNow")), "premium": False,
            "isUpcoming": bool(v.get("isUpcoming")), "premiereTimestamp": 0, "isNew": False, "is4k": False, "is8k": False,
            "isVr180": False, "isVr360": False, "is3d": False, "hasCaptions": False,
            **({"short": True} if v.get("short") else {})}


def _plvideo(v):
    """playlistVideoRenderer / playlistPanelVideoRenderer"""
    vid = v.get("videoId")
    if not isinstance(vid, str) or not VID_RE.fullmatch(vid):
        return None
    author, cid = _byline(v.get("shortBylineText") or v.get("longBylineText") or {})
    length = int(v.get("lengthSeconds") or 0) or _secs(_txt(v.get("lengthText")))
    idx = re.sub(r"\D", "", _txt(v.get("index")) or "")
    info = _txt(v.get("videoInfo"))
    return {"videoId": vid, "title": _txt(v.get("title")), "author": author, "authorId": cid, "lengthSeconds": length,
            "index": int(idx) - 1 if idx else None, "viewCountText": info.split("•")[0].strip() if info else "",
            "publishedText": info.split("•")[1].strip() if "•" in info else ""}


def eng_items(root, author="", author_id=""):
    """every video, channel and playlist below root, in Invidious' shapes, each once"""
    out, seen = [], set()

    def add(key, it):
        if key and key not in seen:
            seen.add(key)
            out.append(it)

    def walk(e):
        if isinstance(e, list):
            for x in e:
                walk(x)
            return
        if not isinstance(e, dict):
            return
        for k, v in e.items():
            if not isinstance(v, dict) or k in ("continuationItemRenderer", "menu", "menuRenderer"):
                if k not in ("continuationItemRenderer", "menu", "menuRenderer"):
                    walk(v)
                continue
            try:
                if k in ITEM_KINDS:
                    it = topic_item(k, v)
                    if it and it.get("type") == "video" and k == "lockupViewModel":
                        it = _lockup_fix(it, v)
                    if it and it.get("type") == "video":
                        add(it["videoId"], eng_vitem(it, author, author_id))
                    elif it and it.get("type") == "playlist":
                        add(it["playlistId"], eng_plitem(it))
                elif k in ("playlistVideoRenderer", "playlistPanelVideoRenderer"):
                    it = _plvideo(v)
                    if it:
                        add(it["videoId"], dict(eng_vitem(it, author, author_id), index=it["index"]))
                elif k == "channelRenderer":
                    ch = eng_chitem(v)
                    if ch:
                        add(ch["authorId"], ch)
                elif k in ("playlistRenderer", "gridPlaylistRenderer"):
                    pid = v.get("playlistId")
                    if isinstance(pid, str):
                        name, cid = _byline(v.get("shortBylineText") or v.get("longBylineText") or {})
                        th = _thumbs((v.get("thumbnails") or [{}])[0] if isinstance(v.get("thumbnails"), list) else v.get("thumbnail") or {})
                        count = eng_count(_txt(v.get("videoCountText")) or str(v.get("videoCount") or ""))
                        add(pid, eng_plitem({"playlistId": pid, "title": _txt(v.get("title")), "author": name, "authorId": cid,
                                             "videoCount": count, "playlistThumbnail": th[-1]["url"] if th else ""}))
                else:
                    walk(v)
            except Exception:
                pass                # one odd item must never sink the whole list
    walk(root)
    return out


def _part_texts(meta):
    """[(text, the channel it links to or "")] of every metadata part below meta, in order"""
    out = []
    for row in _dig(meta, "metadata", "contentMetadataViewModel", "metadataRows") or []:
        for p in row.get("metadataParts") or [] if isinstance(row, dict) else []:
            if not isinstance(p, dict):
                continue
            t = _txt(p.get("text")) or _txt(_first(p, "text"))
            ids = [i for i in _all(p, "browseId") if isinstance(i, str) and UC_RE.fullmatch(i)]
            if t:
                out.append((t, ids[0] if ids else ""))
    return out


def _lockup_fix(it, v):
    """a video lockup's channel, views and age by what each text is, not where it is (rows differ by page)"""
    parts = _part_texts(_dig(v, "metadata", "lockupMetadataViewModel") or {})
    if not parts:
        return it
    owner = next(((t, c) for t, c in parts if c), None)
    ago = next((t for t, c in parts if not c and eng_ago(t)), "")
    views = next((t for t, c in parts if not c and t != ago and re.search(r"\d", t) and eng_count(t) is not None), "")
    if owner:
        it["author"], it["authorId"] = owner
    elif len(parts) >= 3 and not it.get("author"):
        it["author"] = parts[0][0]
    it["viewCountText"], it["publishedText"] = views, ago
    return it


def eng_plitem(p):
    return {"type": "playlist", "title": p.get("title") or "", "playlistId": p["playlistId"],
            "playlistThumbnail": _https(p.get("playlistThumbnail") or ""), "author": p.get("author") or "",
            "authorId": p.get("authorId") or "", "authorUrl": "/channel/" + (p.get("authorId") or ""),
            "authorVerified": False, "videoCount": p.get("videoCount") or 0, "videos": []}


def eng_chitem(v):
    cid = v.get("channelId")
    if not isinstance(cid, str):
        return None
    # since 2024 "subscriberCountText" holds the @handle and "videoCountText" the subscribers
    a, b = _txt(v.get("subscriberCountText")), _txt(v.get("videoCountText"))
    handle = next((x for x in (a, b) if x.startswith("@")), "")
    subs = next((x for x in (a, b) if x and not x.startswith("@")), "")
    return {"type": "channel", "author": _txt(v.get("title")), "authorId": cid, "authorUrl": "/channel/" + cid,
            "authorVerified": "VERIFIED" in json.dumps(v.get("ownerBadges") or []), "authorThumbnails": _athumbs(v.get("thumbnail")),
            "autoGenerated": False, "subCount": eng_count(subs) or 0, "videoCount": 0, "channelHandle": handle,
            "description": _txt(v.get("descriptionSnippet")), "descriptionHtml": html_escape(_txt(v.get("descriptionSnippet")))}


def html_escape(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def eng_cont(root):
    """the last "load more" token below root (the one that continues this list)"""
    toks = []
    for kind in ("continuationItemRenderer", "continuationItemViewModel"):
        for r in _all(root, kind):
            t = _all(r, "token")
            if t and isinstance(t[0], str):
                toks.append(t[0])
    return toks[-1] if toks else None


def eng_text_html(desc):
    """an attributedDescription (text + where its links are) as Invidious' descriptionHtml"""
    if not isinstance(desc, dict) or not isinstance(desc.get("content"), str):
        return None
    text, runs = desc["content"], sorted((r for r in desc.get("commandRuns") or [] if isinstance(r, dict)),
                                         key=lambda r: r.get("startIndex", 0))
    out, pos = [], 0
    # YouTube counts in UTF-16 units
    u16 = text.encode("utf-16-le")
    sub = lambda a, b: u16[a * 2:b * 2].decode("utf-16-le", "replace")
    for r in runs:
        a, n = int(r.get("startIndex") or 0), int(r.get("length") or 0)
        if a < pos or n <= 0:
            continue
        out.append(html_escape(sub(pos, a)))
        label = sub(a, a + n)
        cmd = _dig(r, "onTap", "innertubeCommand") or {}
        href = None
        u = _dig(cmd, "urlEndpoint", "url")
        if isinstance(u, str):
            q = re.search(r"[?&]q=([^&]+)", u)
            href = urllib.parse.unquote(q.group(1)) if q and "youtube.com/redirect" in u else u
        elif _dig(cmd, "watchEndpoint", "videoId"):
            w = cmd["watchEndpoint"]
            href = "/watch?v=%s%s" % (w["videoId"], "&t=%ds" % w["startTimeSeconds"] if w.get("startTimeSeconds") else "")
        elif _dig(cmd, "browseEndpoint", "browseId"):
            bid = cmd["browseEndpoint"]["browseId"]
            href = "/channel/" + bid if bid.startswith("UC") else _dig(cmd, "commandMetadata", "webCommandMetadata", "url")
        out.append('<a href="%s">%s</a>' % (html_escape(href), html_escape(label)) if href else html_escape(label))
        pos = a + n
    out.append(html_escape(sub(pos, len(u16) // 2)))
    return "".join(out)


# ---- a video: streams, details, related videos ----
def eng_player(vid):
    """the first player answer with streams: each client in turn; all walled -> a new identity, then a new WARP address"""
    t0, first = time.time(), None
    for attempt in range(3):
        walled = True
        for client, cid, ua in ENG_PLAYERS:
            try:
                d = it_call("player", {"videoId": vid, "contentCheckOk": True, "racyCheckOk": True}, client, cid, ua, timeout=20)
            except EngError as e:
                if e.code == 404:
                    raise
                first = first or {"playabilityStatus": {"status": "ERROR", "reason": str(e)}}
                walled = False
                continue
            ps, sd = d.get("playabilityStatus") or {}, d.get("streamingData") or {}
            if ps.get("status") == "OK" and (any(f.get("url") for f in sd.get("adaptiveFormats") or []) or sd.get("hlsManifestUrl")):
                return d, client["clientName"]
            if not yt_blocked(d):
                walled = False
                if first is None or yt_blocked(first) or (first.get("playabilityStatus") or {}).get("status") == "OK":
                    first = d
            elif first is None:
                first = d
        if not walled:
            break
        if attempt == 0 and eng_reroll():
            continue
        if attempt < 2 and warp_rotate("player LOGIN_REQUIRED", t0):
            continue
        break
    return first or {}, None


def _fmt(f):
    """one streamingData format as Invidious lists it"""
    mime = f.get("mimeType") or ""
    m = re.match(r"(\w+)/(\w+)", mime)
    kind, cont = (m.group(1), m.group(2)) if m else ("", "")
    codecs = (re.search(r'codecs="([^"]+)"', mime) or [None, ""])[1]
    enc = {"avc1": "h264", "av01": "av1", "vp9": "vp9", "vp09": "vp9", "mp4a": "aac", "opus": "opus", "ac-3": "ac3", "ec-3": "eac3"}
    out = {"url": f["url"], "itag": str(f.get("itag")), "type": mime, "bitrate": str(f.get("bitrate") or 0),
           "clen": str(f.get("contentLength") or ""), "lmt": str(f.get("lastModified") or ""),
           "projectionType": f.get("projectionType") or "RECTANGULAR",
           "container": {"mp4": "m4a" if kind == "audio" else "mp4"}.get(cont, cont),
           "encoding": next((v for k, v in enc.items() if codecs.startswith(k)), codecs.split(".")[0])}
    if f.get("initRange") and f.get("indexRange"):
        out["init"] = "%s-%s" % (f["initRange"]["start"], f["initRange"]["end"])
        out["index"] = "%s-%s" % (f["indexRange"]["start"], f["indexRange"]["end"])
    if kind == "video":
        out.update(fps=f.get("fps") or 0, size="%sx%s" % (f.get("width"), f.get("height")),
                   resolution=f.get("qualityLabel") or "", qualityLabel=f.get("qualityLabel") or "")
        if f.get("colorInfo"):
            out["colorInfo"] = f["colorInfo"]
    else:
        out.update(audioQuality=f.get("audioQuality") or "", audioSampleRate=int(f.get("audioSampleRate") or 0),
                   audioChannels=int(f.get("audioChannels") or 2))
        t = f.get("audioTrack")
        if isinstance(t, dict):
            out["audioTrack"] = {"id": t.get("id") or "", "displayName": t.get("displayName") or "",
                                 "isDefault": bool(t.get("audioIsDefault"))}
    if f.get("quality") and kind == "video" and "audioQuality" in f:     # a muxed one (formatStreams)
        out["quality"] = f["quality"]
    return out


def _storyboards(spec, vid):
    """the seek-bar previews, from the player's storyboard spec"""
    parts = (spec or "").split("|")
    out = []
    for i, p in enumerate(parts[1:]):
        f = p.split("#")
        if len(f) < 8:
            continue
        try:
            w, h, count, cols, rows, interval = (int(x) for x in f[:6])
        except ValueError:
            continue
        url = parts[0].replace("$L", str(i)).replace("$N", f[6])
        url += ("&" if "?" in url else "?") + "sigh=" + urllib.parse.quote(f[7], safe="")
        out.append({"url": "/api/v1/storyboards/%s?width=%d&height=%d" % (vid, w, h), "templateUrl": url, "width": w, "height": h,
                    "count": count, "interval": interval, "storyboardWidth": cols, "storyboardHeight": rows,
                    "storyboardCount": max(1, math.ceil(count / max(1, cols * rows)))})
    return out


eng_caps = {}            # video -> (time, [caption tracks with YouTube's address])
eng_caps_lk = threading.Lock()


def eng_video(vid, hl="", gl=""):
    """/api/v1/videos/<id>: the streams (a player client), the details (WEB's player) and the watch page (next), at once"""
    hl, gl = hl or ENG_HL or "en", gl or ENG_REGION
    t0 = time.time()
    fp = ENG_POOL.submit(eng_player, vid)
    fm = ENG_POOL.submit(it_call, "player", {"videoId": vid, "contentCheckOk": True, "racyCheckOk": True}, None, 1, BROWSER_UA, hl, gl)
    fn = ENG_POOL.submit(it_call, "next", {"videoId": vid}, None, 1, BROWSER_UA, hl, gl)
    d, client = fp.result()
    try:
        meta = (fm.result().get("microformat") or {}).get("playerMicroformatRenderer") or {}
    except Exception:
        meta = {}
    try:
        nx = fn.result()
    except Exception:
        nx = {}
    ps = d.get("playabilityStatus") or {}
    if not client:
        reason = ps.get("reason") or _txt(_first(ps.get("errorScreen") or {}, "reason")) or "This video is unavailable"
        log("engine", "video %s %s: %s" % (vid, ps.get("status"), reason[:80]), t0, every=0)
        raise EngError(reason, 403 if ps.get("status") == "LOGIN_REQUIRED" else 404 if ps.get("status") in ("ERROR", "UNPLAYABLE") else 500)
    vd, sd = d.get("videoDetails") or {}, d.get("streamingData") or {}
    watch = _dig(nx, "contents", "twoColumnWatchNextResults") or {}
    primary = _first(watch, "videoPrimaryInfoRenderer") or {}
    secondary = _first(watch, "videoSecondaryInfoRenderer") or {}
    owner = _first(watch, "videoOwnerRenderer") or {}
    # the exact upload time (WEB's details), else the date the watch page writes
    published = 0
    for k in ("publishDate", "uploadDate"):
        try:
            published = int(datetime.datetime.fromisoformat(str(meta.get(k)).replace("Z", "+00:00")).timestamp())
            break
        except Exception:
            pass
    rel_text = _txt(primary.get("relativeDateText")) or _txt(primary.get("dateText"))
    published = published or eng_ago(rel_text)
    likes = eng_count(str(meta.get("likeCount") or "")) if meta.get("likeCount") else None
    if likes is None:
        lb = _first(watch, "likeButtonViewModel") or {}
        likes = eng_count(_txt(_first(lb, "accessibilityText")) or _txt(_first(lb, "title")) or "") or 0
    subs_text = _txt(owner.get("subscriberCountText"))
    caps = []
    for t in _dig(d, "captions", "playerCaptionsTracklistRenderer", "captionTracks") or []:
        if not isinstance(t, dict) or not t.get("baseUrl"):
            continue
        lang = t.get("languageCode") or ""
        label = _txt(t.get("name")) or lang
        if t.get("kind") == "asr" and "auto-generated" not in label:
            label += " (auto-generated)"
        caps.append({"label": label, "language_code": lang, "kind": t.get("kind") or "", "baseUrl": t["baseUrl"]})
    with eng_caps_lk:
        eng_caps[vid] = (time.time(), caps)
        if len(eng_caps) > 1000:
            for k in sorted(eng_caps, key=lambda k: eng_caps[k][0])[:200]:
                eng_caps.pop(k, None)
    adaptive = [_fmt(f) for f in sd.get("adaptiveFormats") or [] if f.get("url") and not f.get("isDrc")]
    muxed = [_fmt(f) for f in sd.get("formats") or [] if f.get("url")]
    rel = _dig(watch, "secondaryResults", "secondaryResults") or {}
    desc = vd.get("shortDescription") or _txt(meta.get("description")) or ""
    author_id = vd.get("channelId") or ""
    out = {
        "type": "video", "title": vd.get("title") or _txt(primary.get("title")), "videoId": vid, "aiMade": False,
        "videoThumbnails": _vthumbs(vid), "storyboards": _storyboards(_dig(d, "storyboards", "playerStoryboardSpecRenderer", "spec"), vid),
        "description": desc, "descriptionHtml": eng_text_html(secondary.get("attributedDescription")) or html_escape(desc),
        "published": published, "publishedText": rel_text,
        "keywords": vd.get("keywords") or [], "viewCount": int(vd.get("viewCount") or 0), "likeCount": likes, "dislikeCount": 0,
        "paid": False, "premium": False, "isFamilyFriendly": bool(meta.get("isFamilySafe", True)),
        "allowedRegions": meta.get("availableCountries") or [], "genre": meta.get("category") or "", "genreUrl": None,
        "author": vd.get("author") or _txt(owner.get("title")), "authorId": author_id, "authorUrl": "/channel/" + author_id,
        "authorVerified": "VERIFIED" in json.dumps(owner.get("badges") or []), "authorThumbnails": _athumbs(owner.get("thumbnail")),
        "subCountText": re.sub(r"(?i)\s*(subscribers?|구독자|명)\s*", "", subs_text).strip(),
        "lengthSeconds": int(vd.get("lengthSeconds") or 0), "allowRatings": bool(vd.get("allowRatings", True)), "rating": 0,
        "isListed": not meta.get("isUnlisted"), "liveNow": bool(vd.get("isLive")), "isPostLiveDvr": bool(vd.get("isPostLiveDvr")),
        "isUpcoming": bool(vd.get("isUpcoming")),
        "dashUrl": "%s/api/manifest/dash/id/%s" % (PUBLIC, vid),
        "adaptiveFormats": adaptive, "formatStreams": muxed,
        "captions": [{"label": c["label"], "language_code": c["language_code"],
                      "url": "/api/v1/captions/%s?label=%s" % (vid, urllib.parse.quote_plus(c["label"]))} for c in caps],
        "recommendedVideos": [v for v in eng_items(rel) if v.get("type") == "video"][:20],
        "client": client,
    }
    start = _dig(meta, "liveBroadcastDetails", "startTimestamp")
    if vd.get("isUpcoming") and start:
        try:
            out["premiereTimestamp"] = int(datetime.datetime.fromisoformat(start.replace("Z", "+00:00")).timestamp())
        except ValueError:
            pass
    log("engine", "video %s via %s, %d streams" % (vid, client, len(adaptive)), t0, every=300)
    return out


def eng_caption(vid, label="", lang="", tlang=""):
    """one caption track as WebVTT (label as /api/v1/videos lists it, or a language), optionally machine-translated by YouTube"""
    with eng_caps_lk:
        hit = eng_caps.get(vid)
    if not hit or time.time() - hit[0] > 3 * 3600:
        eng_video(vid)
        with eng_caps_lk:
            hit = eng_caps.get(vid)
    tracks = hit[1] if hit else []
    t = next((c for c in tracks if label and c["label"] == label), None) or \
        next((c for c in tracks if lang and c["language_code"] == lang and c["kind"] != "asr"), None) or \
        next((c for c in tracks if lang and c["language_code"].split("-")[0] == lang.split("-")[0]), None)
    if not t:
        raise EngError("No such caption track", 404)
    u = t["baseUrl"] + "&fmt=vtt" + ("&tlang=" + urllib.parse.quote(tlang) if tlang else "")
    req = urllib.request.Request(u, headers={"User-Agent": BROWSER_UA})
    try:
        return YT.open(req, timeout=30).read()
    except urllib.error.HTTPError as e:
        raise EngError("YouTube answered %d for the captions" % e.code, 502)


# ---- search ----
_FEATURES = {"hd": 4, "subtitles": 5, "creative_commons": 6, "3d": 7, "live": 8, "purchased": 9, "4k": 14, "360": 15,
             "location": 23, "hdr": 25, "vr180": 26}


def _search_params(qs):
    """YouTube's search filters (a small protobuf message: sort = 1, filters = 2 {date 1, type 2, duration 3, features})"""
    sort = {"rating": 1, "upload_date": 2, "date": 2, "views": 3, "view_count": 3}.get(qs.get("sort") or qs.get("sort_by") or "", 0)
    f = []

    def field(n, v):              # a varint field: tag (n << 3, as a varint), then the value (< 128 here)
        if v:
            tag = n << 3
            f.extend([tag] if tag < 0x80 else [tag & 0x7F | 0x80, tag >> 7])
            f.append(v)
    field(1, {"hour": 1, "today": 2, "week": 3, "month": 4, "year": 5}.get(qs.get("date") or "", 0))
    field(2, {"video": 1, "channel": 2, "playlist": 3, "movie": 4}.get(qs.get("type") or "", 0))
    field(3, {"short": 1, "long": 2, "medium": 3}.get(qs.get("duration") or "", 0))
    for name in (qs.get("features") or "").split(","):
        if name.strip() in _FEATURES:
            field(_FEATURES[name.strip()], 1)
    out = []
    if sort:
        out += [0x08, sort]
    if f:
        out += [0x12, len(f)] + f
    return base64.b64encode(bytes(out)).decode() if out else None


eng_pages = {}           # (kind, key) -> [token of page 2, token of page 3, ...]
eng_pages_lk = threading.Lock()


def _paged(kind, key, page, first, more, max_page=20):
    """page N of a list YouTube only gives one page at a time: walks the "load more" tokens, kept for the next ask"""
    page = max(1, min(max_page, page))
    if page == 1:
        items, tok = first()
        with eng_pages_lk:
            eng_pages[(kind, key)] = [tok]
            if len(eng_pages) > 2000:
                eng_pages.clear()
        return items
    with eng_pages_lk:
        toks = list(eng_pages.get((kind, key)) or [])
    if not toks:
        _, tok = first()
        toks = [tok]
    while len(toks) < page - 1 and toks[-1]:
        _, tok = more(toks[-1])
        toks.append(tok)
    tok = toks[page - 2] if len(toks) >= page - 1 else None
    if not tok:
        return []
    items, nxt = more(tok)
    toks = toks[:page - 1] + [nxt]
    with eng_pages_lk:
        eng_pages[(kind, key)] = toks
    return items


def eng_search(qs, hl, gl):
    q = (qs.get("q") or "").strip()
    if not q:
        return []
    params = _search_params(qs)

    def first():
        d = it_call("search", {"query": q, "params": params}, hl=hl, gl=gl)
        root = d.get("contents") or {}
        return eng_items(root), eng_cont(root)

    def more(tok):
        d = it_call("search", {"continuation": tok}, hl=hl, gl=gl)
        root = d.get("onResponseReceivedCommands") or {}
        return eng_items(root), eng_cont(root)
    try:
        page = int(qs.get("page") or 1)
    except ValueError:
        page = 1
    items = _paged("search", (q, params, hl, gl), page, first, more)
    want = qs.get("type") or "all"
    if want in ("video", "channel", "playlist"):
        items = [i for i in items if i.get("type") == want]
    return items


def eng_suggest(qs, hl, gl):
    q = qs.get("q") or ""
    if not q.strip():
        return {"query": q, "suggestions": []}
    # client=youtube, as YouTube's own search box asks (client=firefox has answered 400 since November 2026);
    # the answer is a script call: window.google.ac.h(["q", [["suggestion", 0, [...]], ...], {...}])
    url = "https://suggestqueries-clients6.youtube.com/complete/search?" + urllib.parse.urlencode(
        {"client": "youtube", "ds": "yt", "gs_ri": "youtube", "hl": qs.get("hl") or hl or "en", "gl": qs.get("gl") or gl or "US", "q": q})
    try:
        r = YT.open(urllib.request.Request(url, headers={"User-Agent": BROWSER_UA}), timeout=8)
        t = r.read().decode(r.headers.get_content_charset() or "utf-8", "replace")     # EUC-KR for hl=ko, for one
        d = json.loads(t[t.index("(") + 1:t.rindex(")")] if "(" in t[:40] else t)
        out = [x[0] if isinstance(x, list) and x else x for x in d[1]]
        return {"query": q, "suggestions": [x for x in out if isinstance(x, str)][:15]}
    except Exception as ex:
        log("engine", "suggest FAIL %s" % repr(ex)[:80])
        return {"query": q, "suggestions": []}


# ---- channels ----
CH_TABS = {"videos": "EgZ2aWRlb3PyBgQKAjoA", "shorts": "EgZzaG9ydHPyBgUKA5oBAA%3D%3D", "streams": "EgdzdHJlYW1z8gYECgJ6AA%3D%3D",
           "playlists": "EglwbGF5bGlzdHPyBgQKAkIA", "search": "EgZzZWFyY2jyBgQKAloA"}
eng_ch_cache = {}        # (ucid, hl) -> (time, browse answer of the first tab)
eng_ch_lk = threading.Lock()


def _ch_browse(ucid, hl, gl, params=None, query=None):
    if not UC_RE.fullmatch(ucid or ""):
        r = eng_resolve("https://www.youtube.com/" + (ucid if ucid.startswith("@") else "channel/" + ucid), hl, gl)
        ucid = r.get("ucid") or ucid
    key = (ucid, hl, params, query)
    with eng_ch_lk:
        hit = eng_ch_cache.get(key)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    d = it_call("browse", {"browseId": ucid, "params": urllib.parse.unquote(params) if params else None, "query": query}, hl=hl, gl=gl)
    if (d.get("alerts") and not d.get("contents")) or not d.get("contents"):
        msg = _txt(_first(d.get("alerts") or {}, "text")) or "This channel does not exist"
        raise EngError(msg, 404)
    with eng_ch_lk:
        eng_ch_cache[key] = (time.time(), d)
        if len(eng_ch_cache) > 300:
            for k in sorted(eng_ch_cache, key=lambda k: eng_ch_cache[k][0])[:100]:
                eng_ch_cache.pop(k, None)
    return d


def _sel_tab(d):
    tabs = _dig(d, "contents", "twoColumnBrowseResultsRenderer", "tabs") or []
    t = next((t for t in tabs if _dig(t, "tabRenderer", "selected") or _dig(t, "expandableTabRenderer", "selected")),
             tabs[0] if tabs else {})
    return _dig(t, "tabRenderer", "content") or _dig(t, "expandableTabRenderer", "content") or {}


def eng_channel(ucid, hl, gl):
    d = _ch_browse(ucid, hl, gl)
    meta = _dig(d, "metadata", "channelMetadataRenderer") or {}
    head = _first(d.get("header") or {}, "pageHeaderViewModel") or {}
    old = _dig(d, "header", "c4TabbedHeaderRenderer") or {}
    ucid = meta.get("externalId") or ucid
    rows = [_txt(p.get("text")) for r in _dig(head, "metadata", "contentMetadataViewModel", "metadataRows") or []
            for p in r.get("metadataParts") or [] if isinstance(p, dict)]
    handle = next((r for r in rows if r.startswith("@")), "")
    stats = [r for r in rows if not r.startswith("@") and re.search(r"\d", r)]
    tabs = []
    for t in _dig(d, "contents", "twoColumnBrowseResultsRenderer", "tabs") or []:
        u = _dig(t, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") or ""
        name = u.rsplit("/", 1)[-1].lower() if u else ""
        if name:
            tabs.append({"posts": "community", "featured": "home"}.get(name, name))
    banner = _imgs(_dig(head, "banner") or old.get("banner") or {})
    avatar = _dig(head, "image") or old.get("avatar") or meta.get("avatar") or {}
    home = _sel_tab(d)
    latest = [v for v in eng_items(home, meta.get("title") or "", ucid) if v.get("type") == "video"]
    related = [i for i in eng_items(_all(home, "channelRenderer") and home or {}) if i.get("type") == "channel"]
    title = meta.get("title") or _txt(_dig(head, "title")) or _txt(old.get("title"))
    return {"author": title, "authorId": ucid, "authorUrl": "https://www.youtube.com/channel/" + ucid,
            "authorBanners": [{"url": b["url"], "width": b["width"], "height": b["height"]} for b in banner],
            "authorThumbnails": _athumbs(avatar), "subCount": eng_count(stats[0]) if stats else 0, "totalViews": 0, "joined": 0,
            "autoGenerated": False, "ageGated": False, "isFamilyFriendly": bool(meta.get("isFamilySafe", True)),
            "description": meta.get("description") or "", "descriptionHtml": html_escape(meta.get("description") or ""),
            "allowedRegions": meta.get("availableCountryCodes") or [], "tabs": tabs, "tags": meta.get("keywords", "").split() if isinstance(meta.get("keywords"), str) else [],
            "authorVerified": "CHECK_CIRCLE" in json.dumps(head.get("title") or {}) or "VERIFIED" in json.dumps(old.get("badges") or []),
            "pronouns": None, "channelHandle": handle, "videoCountText": stats[1] if len(stats) > 1 else "",
            "latestVideos": latest[:30], "relatedChannels": related[:12]}


def _chip_token(root, n):
    """the "Latest / Popular / Oldest" chips above a channel's videos: the token of the n-th"""
    chips = _all(root, "chipViewModel") or _all(root, "chipCloudChipRenderer")
    toks = [(_all(c, "token") or [None])[0] for c in chips]
    toks = [t for t in toks if isinstance(t, str)]
    return toks[n] if len(toks) > n else None


def eng_channel_tab(ucid, tab, qs, hl, gl):
    """/channels/<id>/videos|shorts|streams|playlists ({"videos"|"playlists": [...], "continuation"})"""
    cont = qs.get("continuation")
    key = "playlists" if tab == "playlists" else "videos"
    name = ""
    if cont:
        d = it_call("browse", {"continuation": cont}, hl=hl, gl=gl)
        root = d.get("onResponseReceivedActions") or d.get("onResponseReceivedEndpoints") or {}
    else:
        d = _ch_browse(ucid, hl, gl, CH_TABS[tab])
        name = (_dig(d, "metadata", "channelMetadataRenderer", "title") or "")
        root = _sel_tab(d)
        sort = (qs.get("sort_by") or qs.get("sort") or "newest").lower()
        n = {"popular": 1, "oldest": 2}.get(sort)
        if n and tab != "playlists":
            tok = _chip_token(root, n)
            if tok:
                d = it_call("browse", {"continuation": tok}, hl=hl, gl=gl)
                root = d.get("onResponseReceivedActions") or d.get("onResponseReceivedEndpoints") or {}
    if UC_RE.fullmatch(ucid or ""):
        cid = ucid
    else:
        cid = _dig(d, "metadata", "channelMetadataRenderer", "externalId") or ""
    items = eng_items(root, name, cid)
    if tab == "playlists":
        items = [dict(i, author=name or i.get("author"), authorId=cid or i.get("authorId")) for i in items if i.get("type") == "playlist"]
    else:
        items = [dict(i, short=True) if tab == "shorts" else i for i in items if i.get("type") == "video"]
        if tab == "streams":
            for i in items:
                i["isUpcoming"] = i.get("isUpcoming") or ("scheduled" in (i.get("publishedText") or "").lower())
    return {key: items, "continuation": eng_cont(root)}


def eng_channel_search(ucid, qs, hl, gl):
    q = (qs.get("q") or qs.get("query") or "").strip()
    if not q:
        return []
    cid = ucid if UC_RE.fullmatch(ucid) else ""

    def first():
        d = _ch_browse(ucid, hl, gl, CH_TABS["search"], q)
        root = _sel_tab(d)
        return eng_items(root, _dig(d, "metadata", "channelMetadataRenderer", "title") or "", cid), eng_cont(root)

    def more(tok):
        d = it_call("browse", {"continuation": tok}, hl=hl, gl=gl)
        root = d.get("onResponseReceivedActions") or d.get("onResponseReceivedEndpoints") or {}
        return eng_items(root, "", cid), eng_cont(root)
    try:
        page = int(qs.get("page") or 1)
    except ValueError:
        page = 1
    return _paged("chsearch", (ucid, q, hl), page, first, more)


def _post(p, cid):
    """a backstagePostRenderer as Invidious' community entry"""
    pid = p.get("postId")
    if not pid:
        return None
    runs = _dig(p, "contentText", "runs") or []
    text = _txt(p.get("contentText"))
    parts = []
    for r in runs if isinstance(runs, list) else []:
        t = html_escape(r.get("text") or "")
        u = _dig(r, "navigationEndpoint", "urlEndpoint", "url") or _dig(r, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url")
        if isinstance(u, str):
            q = re.search(r"[?&]q=([^&]+)", u)
            u = urllib.parse.unquote(q.group(1)) if q and "youtube.com/redirect" in u else u
            parts.append('<a href="%s">%s</a>' % (html_escape(u), t))
        else:
            parts.append(t)
    att = p.get("backstageAttachment") or {}
    attach = None
    img = att.get("backstageImageRenderer")
    if img:
        attach = {"type": "image", "imageThumbnails": _imgs(img.get("image") or {})}
    elif att.get("postMultiImageRenderer"):
        ims = [_imgs(_dig(i, "backstageImageRenderer", "image") or {}) for i in att["postMultiImageRenderer"].get("images") or []]
        attach = {"type": "multiImage", "images": ims, "imageThumbnails": ims[0] if ims else []}
    elif att.get("pollRenderer"):
        pr = att["pollRenderer"]
        attach = {"type": "poll", "totalVotes": eng_count(_txt(pr.get("totalVotes"))) or 0,
                  "choices": [{"text": _txt(c.get("text"))} for c in pr.get("choices") or []]}
    elif att:
        vids = [v for v in eng_items(att) if v.get("type") == "video"]
        if vids:
            attach = dict(vids[0], type="video")
    author, _ = _byline(p.get("authorText") or {})
    return {"author": author, "authorThumbnails": _athumbs(p.get("authorThumbnail")), "authorId": cid, "authorUrl": "/channel/" + cid,
            "isEdited": False, "content": text, "contentHtml": "".join(parts).replace("\n", "<br>"),
            "published": eng_ago(_txt(p.get("publishedTimeText"))), "publishedText": _txt(p.get("publishedTimeText")),
            "likeCount": eng_count(_txt(p.get("voteCount"))) or 0,
            "replyCount": eng_count(_txt(_first(_first(p.get("actionButtons") or {}, "replyButton") or {}, "text"))) or 0,
            "commentId": pid, "authorIsChannelOwner": True, **({"attachment": attach} if attach else {})}


def eng_community(ucid, qs, hl, gl):
    cont = qs.get("continuation")
    if cont:
        d = it_call("browse", {"continuation": cont}, hl=hl, gl=gl)
        root = d.get("onResponseReceivedActions") or d.get("onResponseReceivedEndpoints") or {}
        cid = ucid
    else:
        d = _ch_browse(ucid, hl, gl)
        cid = _dig(d, "metadata", "channelMetadataRenderer", "externalId") or ucid
        params = None
        for t in _dig(d, "contents", "twoColumnBrowseResultsRenderer", "tabs") or []:
            u = _dig(t, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") or ""
            if u.endswith("/posts") or u.endswith("/community"):
                params = _dig(t, "tabRenderer", "endpoint", "browseEndpoint", "params")
        if not params:
            return {"authorId": cid, "comments": []}
        root = _sel_tab(_ch_browse(cid, hl, gl, params))
    seen, posts = set(), []
    for p in _all(root, "backstagePostRenderer"):
        e = _post(p, cid) if isinstance(p, dict) else None
        if e and e["commentId"] not in seen:
            seen.add(e["commentId"])
            posts.append(e)
    return {"authorId": cid, "comments": posts, "continuation": eng_cont(root)}


# ---- playlists and mixes ----
def eng_playlist(plid, qs, hl, gl):
    bid = plid if plid.startswith("VL") else "VL" + plid
    plid = bid[2:]
    holder = {}

    def first():
        d = it_call("browse", {"browseId": bid}, hl=hl, gl=gl)
        if not d.get("contents"):
            raise EngError(_txt(_first(d.get("alerts") or {}, "text")) or "This playlist does not exist", 404)
        holder["d"] = d
        root = d.get("contents") or {}
        return eng_items(root), eng_cont(root)

    def more(tok):
        d = it_call("browse", {"continuation": tok}, hl=hl, gl=gl)
        root = d.get("onResponseReceivedActions") or d.get("onResponseReceivedEndpoints") or {}
        return eng_items(root), eng_cont(root)
    try:
        page = int(qs.get("page") or 1)
    except ValueError:
        page = 1
    videos = [v for v in _paged("playlist", (plid, hl), page, first, more, 50) if v.get("type") == "video"]
    d = holder.get("d")
    if d is None:                         # a later page: the header from page 1 again (cached by YouTube's side cheaply)
        d = it_call("browse", {"browseId": bid}, hl=hl, gl=gl)
    head = _first(d.get("header") or {}, "pageHeaderViewModel") or {}
    old = _first(d.get("header") or {}, "playlistHeaderRenderer") or {}
    meta = _dig(d, "metadata", "playlistMetadataRenderer") or {}
    parts = _part_texts(head)
    flat = [t for t, c in parts]
    owner = next(((t, c) for t, c in parts if c), ("", ""))
    author, cid = _byline(old.get("ownerText") or {}) if old else ("", "")
    author = author or re.sub(r"^(by|제작자:?)\s+", "", owner[0])
    cid = cid or owner[1]
    count = next((eng_count(x) for x in flat if re.search(r"(?i)video|동영상|개", x)), None) or eng_count(_txt(old.get("numVideosText"))) or len(videos)
    views = next((eng_count(x) for x in flat if re.search(r"(?i)view|조회", x)), None) or 0
    first_vid = videos[0]["videoId"] if videos else ""
    for i, v in enumerate(videos):
        if v.get("index") is None:
            v["index"] = (page - 1) * 100 + i
    return {"type": "playlist", "title": _txt(head.get("title")) or meta.get("title") or _txt(old.get("title")), "playlistId": plid,
            "playlistThumbnail": "https://i.ytimg.com/vi/%s/hqdefault.jpg" % first_vid if first_vid else "",
            "author": author, "authorId": cid, "authorUrl": "/channel/" + cid if cid else "", "subtitle": None,
            "authorThumbnails": _athumbs(_first(head, "avatarViewModel") or {}), "description": meta.get("description") or "",
            "descriptionHtml": html_escape(meta.get("description") or ""), "videoCount": count, "viewCount": views,
            "updated": 0, "isListed": True, "videos": videos}


def eng_mix(mid, hl, gl):
    vid = mid[2:] if len(mid) == 13 and VID_RE.fullmatch(mid[2:]) else mid[-11:]
    body = {"playlistId": mid}
    if VID_RE.fullmatch(vid):
        body["videoId"] = vid
    d = it_call("next", body, hl=hl, gl=gl)
    pl = _dig(d, "contents", "twoColumnWatchNextResults", "playlist", "playlist") or {}
    videos = [v for v in eng_items(pl.get("contents") or []) if v.get("type") == "video"]
    if not videos:
        raise EngError("This mix is empty", 404)
    return {"title": _txt(pl.get("title")), "mixId": mid, "videos": videos}


# ---- comments ----
def _comment_page(d):
    pay = {}
    for m in _dig(d, "frameworkUpdates", "entityBatchUpdate", "mutations") or []:
        p = _dig(m, "payload", "commentEntityPayload")
        cid = _dig(p, "properties", "commentId")
        if cid:
            pay[cid] = p
    items = []
    for e in d.get("onResponseReceivedEndpoints") or []:
        items += _dig(e, "reloadContinuationItemsCommand", "continuationItems") or _dig(e, "appendContinuationItemsAction", "continuationItems") or []
    out = []
    for it in items:
        th = it.get("commentThreadRenderer") if isinstance(it, dict) else None
        vm = _dig(th, "commentViewModel", "commentViewModel") if th else (it.get("commentViewModel") if isinstance(it, dict) else None)
        cid = _dig(vm, "commentId")
        p = pay.get(cid)
        if not p:
            continue
        author = _dig(p, "author") or {}
        tb = _dig(p, "toolbar") or {}
        pub_text = _dig(p, "properties", "publishedTime") or ""
        content = _dig(p, "properties", "content", "content") or ""
        replies_tok = (_all(th.get("replies") or {}, "token") or [None])[0] if th else None
        nrep = eng_count(tb.get("replyCount") or "") or 0
        c = {"authorId": author.get("channelId") or "", "authorUrl": "/channel/" + (author.get("channelId") or ""),
             "author": author.get("displayName") or "", "verified": bool(author.get("isVerified")),
             "authorThumbnail": _https(author.get("avatarThumbnailUrl")), "authorThumbnails": _athumbs(author.get("avatarThumbnailUrl")),
             "authorIsChannelOwner": bool(author.get("isCreator")), "isSponsor": bool(author.get("sponsorBadgeUrl")),
             "likeCount": eng_count(tb.get("likeCountNotliked") or "") or 0, "isPinned": bool(vm.get("pinnedText")),
             "commentId": cid, "content": content, "contentHtml": html_escape(content).replace("\n", "<br>"),
             "isEdited": "(edited)" in pub_text or "수정됨" in pub_text, "published": eng_ago(pub_text),
             "publishedText": re.sub(r"\s*\((edited|수정됨)\)", "", pub_text)}
        if tb.get("creatorThumbnailUrl") or tb.get("heartActiveTooltip"):
            c["creatorHeart"] = {"creatorThumbnail": _https(tb.get("creatorThumbnailUrl")), "creatorName": ""}
        if replies_tok and nrep:
            c["replies"] = {"replyCount": nrep, "continuation": replies_tok}
        out.append(c)
    nxt = None
    if items and isinstance(items[-1], dict) and items[-1].get("continuationItemRenderer"):
        nxt = (_all(items[-1]["continuationItemRenderer"], "token") or [None])[0]
    return out, nxt


def _pb_varint(n):
    n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b, n = n & 0x7F, n >> 7
        out.append(b | 0x80 if n else b)
        if not n:
            return bytes(out)


def _pb(fields):
    """a protobuf message from [(field, int | str | bytes | [nested fields])]"""
    out = b""
    for num, v in fields:
        if isinstance(v, int):
            out += _pb_varint(num << 3) + _pb_varint(v)
            continue
        v = _pb(v) if isinstance(v, list) else v.encode() if isinstance(v, str) else v
        out += _pb_varint(num << 3 | 2) + _pb_varint(len(v)) + v
    return out


def _comments_token(vid, newest=False):
    """the token of a video's first comments page, as YouTube's own page builds it, so no "next" call is needed
    to find it (Invidious does the same)"""
    msg = _pb([(2, [(2, vid), (25, 0), (28, 1), (36, [(5, -1), (8, 0)]), (40, [(1, 4), (3, "https://www.youtube.com"), (4, "")])]),
               (3, 6), (6, [(1, ""), (4, [(4, vid), (6, 1 if newest else 0)]), (5, 20)])])
    return base64.urlsafe_b64encode(msg).decode()


def eng_comments(vid, qs, hl, gl):
    cont = qs.get("continuation")
    count = None
    if not cont:
        newest = (qs.get("sort_by") or "").lower() in ("new", "newest")
        try:
            d = it_call("next", {"continuation": _comments_token(vid, newest)}, hl=hl, gl=gl)
        except EngError:
            d = {}
        comments, nxt = _comment_page(d)
        if comments:
            out = {"videoId": vid, "comments": comments}
            head = next((e for e in d.get("onResponseReceivedEndpoints") or []), {})
            c = eng_count(_txt(_first(head, "countText")))
            if c is not None:
                out["commentCount"] = c
            if nxt:
                out["continuation"] = nxt
            return out
        # no comments that way (turned off, or YouTube changed the token): find the token on the watch page
        n = it_call("next", {"videoId": vid}, hl=hl, gl=gl)
        panel = next((_dig(p, "engagementPanelSectionListRenderer") for p in n.get("engagementPanels") or []
                      if _dig(p, "engagementPanelSectionListRenderer", "panelIdentifier") == "engagement-panel-comments-section"), None)
        cont = (_all(_dig(panel, "content") or {}, "token") or [None])[0] if panel else None
        if not cont:
            sec = next((s for s in _all(_dig(n, "contents", "twoColumnWatchNextResults") or {}, "itemSectionRenderer")
                        if isinstance(s, dict) and s.get("sectionIdentifier") == "comment-item-section"), None)
            cont = (_all(sec or {}, "token") or [None])[0]
        if not cont:
            raise EngError("Comments are turned off", 404)
        d = it_call("next", {"continuation": cont}, hl=hl, gl=gl)
        head = next((e for e in d.get("onResponseReceivedEndpoints") or []), {})
        count = eng_count(_txt(_first(head, "countText")))
        if (qs.get("sort_by") or "").lower() == "new":
            sub = _first(head, "sortFilterSubMenuRenderer") or {}
            items = sub.get("subMenuItems") or []
            tok = (_all(items[1], "token") or [None])[0] if len(items) > 1 else None
            if tok:
                d = it_call("next", {"continuation": tok}, hl=hl, gl=gl)
    else:
        d = it_call("next", {"continuation": cont}, hl=hl, gl=gl)
    comments, nxt = _comment_page(d)
    out = {"videoId": vid, "comments": comments}
    if count is not None:
        out["commentCount"] = count
    if nxt:
        out["continuation"] = nxt
    return out


eng_trend = {}           # (topics, hl, gl) -> (time, videos)


# ---- trending (YouTube took its Trending page down in 2025): the topic pages' shelves instead ----
def eng_trending(qs, hl, gl):
    kind = (qs.get("type") or "").lower()
    keys = {"music": ["music"], "gaming": ["gaming"], "news": ["news"], "movies": ["live"], "sports": ["sports"]}.get(kind) \
        or ["music", "gaming", "sports", "news", "learning"]
    ck = (tuple(keys), hl, gl)
    with eng_pages_lk:
        hit = eng_trend.get(ck)
    if hit and time.time() - hit[0] < 1800:
        return hit[1]

    def one(k):
        r = topic_page({"key": k, "region": gl, "hl": hl})
        got = [eng_vitem(i) for s in r.get("shelves") or [] for i in s.get("items") or [] if i.get("type") == "video"]
        if not got:              # no such topic page in this region (gaming in Korea): this week's most watched instead
            try:
                got = eng_search({"q": {"learning": "lecture", "live": "movie trailer"}.get(k, k), "type": "video",
                                  "date": "week", "sort": "views"}, hl, gl)
            except EngError:
                got = []
        return got
    lists = list(ENG_POOL.map(one, keys))
    out, seen = [], set()
    for row in zip(*[l + [None] * (max(map(len, lists)) - len(l)) for l in lists]) if lists else []:
        for v in row:
            if v and v["videoId"] not in seen and not v.get("short"):
                seen.add(v["videoId"])
                out.append(v)
    out = out[:60]
    if out:
        with eng_pages_lk:
            eng_trend[ck] = (time.time(), out)
            if len(eng_trend) > 100:
                eng_trend.clear()
    return out


def eng_resolve(url, hl="en", gl="US"):
    m = re.search(r"(?:v=|youtu\.be/|shorts/|live/|embed/)([\w-]{11})", url)
    if m:
        return {"videoId": m.group(1), "pageType": "WEB_PAGE_TYPE_WATCH"}
    m = re.search(r"channel/(UC[\w-]{22})", url)
    if m:
        return {"ucid": m.group(1), "browseId": m.group(1), "params": None, "pageType": "WEB_PAGE_TYPE_CHANNEL"}
    d = it_call("navigation/resolve_url", {"url": url}, hl=hl, gl=gl)
    ep = d.get("endpoint") or {}
    b = ep.get("browseEndpoint") or {}
    if b.get("browseId"):
        bid = b["browseId"]
        page = _dig(ep, "commandMetadata", "webCommandMetadata", "webPageType") or ""
        return {"ucid": bid if bid.startswith("UC") else None, "browseId": bid, "params": b.get("params"), "pageType": page}
    w = ep.get("watchEndpoint") or {}
    if w.get("videoId"):
        return {"videoId": w["videoId"], "pageType": "WEB_PAGE_TYPE_WATCH"}
    raise EngError("Couldn't find that page", 404)


def _fields(obj, fields):
    """Invidious' ?fields=a,b: only those keys (of the object, or of each one in a list)"""
    keep = [f.strip() for f in (fields or "").split(",") if f.strip()]
    if not keep:
        return obj
    pick = lambda o: {k: o[k] for k in keep if k in o} if isinstance(o, dict) else o
    return [pick(o) for o in obj] if isinstance(obj, list) else pick(obj)


def eng_api(path, qs, hl="", gl=""):
    """an Invidious API path (/api/v1/...) answered by the engine: (HTTP status, JSON object).
    YouTube's language is ?hl=, else HT_HL, else English (as Invidious: the numbers in its texts read surely)"""
    hl = hl or qs.get("hl") or ENG_HL or "en"
    hl = hl if re.fullmatch(r"[a-zA-Z]{2,3}(-[A-Za-z0-9]{2,4})?", hl) else "en"
    gl = (qs.get("region") or gl or ENG_REGION).upper()
    gl = gl if re.fullmatch(r"[A-Z]{2}", gl) else "US"
    p = path.split("?")[0].rstrip("/")
    try:
        m = re.fullmatch(r"/api/v1/videos/([\w-]{11})", p)
        if m:
            v = eng_video(m.group(1), hl, gl)
            if (qs.get("local") or "").lower() in ("true", "1"):
                # as Invidious' ?local=true: the streams through this server (other Invidious clients ask for it)
                v = dict(v, **{k: [dict(x, url=_companion_url(x["url"])) if str(x.get("url", "")).startswith("http") else x
                                   for x in v.get(k) or []] for k in ("adaptiveFormats", "formatStreams")})
            return 200, _fields(v, qs.get("fields"))
        if p == "/api/v1/search":
            return 200, eng_search(qs, hl, gl)
        if p == "/api/v1/search/suggestions":
            return 200, eng_suggest(qs, hl, gl)
        if p in ("/api/v1/trending", "/api/v1/popular"):
            return 200, eng_trending(qs, hl, gl)
        m = re.fullmatch(r"/api/v1/channels/([\w@.-]+?)(?:/(videos|shorts|streams|playlists|community|posts|search|latest))?", p)
        if m:
            ucid, tab = m.group(1), m.group(2)
            if tab in ("videos", "shorts", "streams", "playlists", "latest"):
                return 200, _fields(eng_channel_tab(ucid, "videos" if tab == "latest" else tab, qs, hl, gl), qs.get("fields"))
            if tab in ("community", "posts"):
                return 200, eng_community(ucid, qs, hl, gl)
            if tab == "search":
                return 200, eng_channel_search(ucid, qs, hl, gl)
            return 200, _fields(eng_channel(ucid, hl, gl), qs.get("fields"))
        m = re.fullmatch(r"/api/v1/playlists/([\w-]+)", p)
        if m:
            return 200, _fields(eng_playlist(m.group(1), qs, hl, gl), qs.get("fields"))
        m = re.fullmatch(r"/api/v1/mixes/([\w-]+)", p)
        if m:
            return 200, eng_mix(m.group(1), hl, gl)
        m = re.fullmatch(r"/api/v1/comments/([\w-]{11})", p)
        if m:
            return 200, eng_comments(m.group(1), qs, hl, gl)
        if p == "/api/v1/resolveurl":
            return 200, eng_resolve(qs.get("url") or "", hl, gl)
        if p == "/api/v1/stats":
            return 200, {"version": "2.0", "software": {"name": "tubehaus", "version": "builtin", "branch": "main"},
                         "openRegistrations": False, "playback": {}, "usage": {"users": {"total": 0}}, "metadata": {"updatedAt": int(time.time())}}
        if p.startswith("/api/v1/auth/"):
            return 404, {"error": "This server has no Invidious accounts"}
        return 404, {"error": "Not found"}
    except EngError as e:
        return e.code, {"error": str(e)}
    except Exception as e:
        log("engine", "%s FAIL %s" % (p[:60], repr(e)[:160]), every=0)
        return 500, {"error": "Something went wrong asking YouTube"}


def gv_url(path):
    """/companion/videoplayback?<query>&host=<YouTube's host> (the stream addresses the web player and the apps use) ->
    YouTube's address of that stream, or None when it isn't one"""
    u = urllib.parse.urlsplit(path)
    q = urllib.parse.parse_qsl(u.query, keep_blank_values=True)
    host = next((v for k, v in q if k == "host"), "")
    if not re.fullmatch(r"[\w-]+(\.[\w-]+)*\.googlevideo\.com", host or ""):
        return None
    rest = urllib.parse.urlencode([(k, v) for k, v in q if k != "host"])
    return "https://%s/videoplayback?%s" % (host, rest)


def gv_open(url, rng=None, timeout=60):
    """one stream (a googlevideo.com address or our /companion/ path for it) through YT_PROXY, the address it was given to"""
    if url.startswith("/"):
        url = gv_url(url) or ""
    if not url.startswith("https://"):
        raise ValueError("not a stream address")
    h = {"User-Agent": VIS_UA, "Origin": "https://www.youtube.com", "Referer": "https://www.youtube.com/"}
    if rng:
        h["Range"] = rng
    return YT.open(urllib.request.Request(url, headers=h), timeout=timeout)


def eng_format_url(vid, itag):
    """YouTube's address of one format of a video (for downloads)"""
    d = _video_json(vid)
    for f in (d.get("adaptiveFormats") or []) + (d.get("formatStreams") or []):
        if str(f.get("itag")) == str(itag) and f.get("url"):
            return f["url"]
    raise ValueError("no such format")




def eng_json(path, timeout=None):
    """an Invidious API path as the JSON text Invidious would have sent (for the parts of this file that used to ask it)"""
    u = urllib.parse.urlsplit(path)
    code, obj = eng_api(u.path, dict(urllib.parse.parse_qsl(u.query)))
    if code != 200:
        raise EngError((obj or {}).get("error") or "HTTP %d" % code, code)
    return json.dumps(obj, ensure_ascii=False).encode()


def eng_caption_list(vid):
    """the caption tracks of a video: [{"label", "languageCode", "kind"}] (the original language's first)"""
    with eng_caps_lk:
        hit = eng_caps.get(vid)
    if not hit or time.time() - hit[0] > 3 * 3600:
        _video_json(vid)
        with eng_caps_lk:
            hit = eng_caps.get(vid)
    return [{"label": c["label"], "languageCode": c["language_code"], "kind": c["kind"],
             "url": "/companion/api/v1/captions/%s?label=%s" % (vid, urllib.parse.quote_plus(c["label"]))} for c in (hit[1] if hit else [])]


def eng_img(path):
    """/vi/<id>/<name>.jpg, /vi_webp/..., /ggpht/..., /sb/<i, i9...>/... -> (status, content type, bytes, from YouTube's picture servers"""
    m = re.fullmatch(r"/(vi|vi_webp)/([\w-]{11})/(\w+)\.(jpg|webp)", path.split("?")[0])
    if m:
        name = {"maxres": "maxresdefault"}.get(m.group(3), m.group(3))
        url = "https://i.ytimg.com/%s/%s/%s.%s" % (m.group(1), m.group(2), name, m.group(4))
    elif path.startswith("/ggpht/"):
        url = "https://yt3.ggpht.com/" + path[len("/ggpht/"):]
    else:
        m = re.fullmatch(r"/sb/(i\d?)/(.+)", path.split("?")[0])
        if not m:
            return 404, "text/plain", b"not found"
        url = "https://%s.ytimg.com/sb/%s" % (m.group(1), path.split("/", 3)[3])
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": BROWSER_UA}), timeout=20)
        return 200, r.headers.get("Content-Type") or "image/jpeg", r.read()
    except urllib.error.HTTPError as e:
        return e.code, "text/plain", b""
    except Exception:
        return 502, "text/plain", b""


def _best_img(o):
    """the largest picture anywhere below o"""
    best, bw = "", -1
    for arr in _all(o, "thumbnails"):
        for t in arr if isinstance(arr, list) else []:
            if isinstance(t, dict) and isinstance(t.get("url"), str) and (t.get("width") or 0) >= bw:
                best, bw = t["url"], t.get("width") or 0
    return ("https:" + best) if best.startswith("//") else best


def _square(u):
    """the pictures come small ("=w120-h120-..."); ask for a size that stays sharp on a large cover"""
    return re.sub(r"=w\d+-h\d+", "=w544-h544", u or "")


def _m_kind(b):
    t = _dig(b, "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType") or ""
    return {"MUSIC_PAGE_TYPE_ARTIST": "artist", "MUSIC_PAGE_TYPE_USER_CHANNEL": "artist", "MUSIC_PAGE_TYPE_ALBUM": "album",
            "MUSIC_PAGE_TYPE_AUDIOBOOK": "album", "MUSIC_PAGE_TYPE_PLAYLIST": "playlist"}.get(t, "other")


def _m_song(vid, title, artist="", artist_id="", length=0, art="", album="", album_id=""):
    return {"id": vid, "title": title, "artist": artist, "artistId": artist_id, "len": length, "art": art,
            "album": album, "albumId": album_id}


def _m_row(r):
    """a song in a list (an album's tracks, an artist's top songs)"""
    vid = _dig(r, "playlistItemData", "videoId") or _dig(_first(r, "watchEndpoint") or {}, "videoId")
    if not isinstance(vid, str) or not re.fullmatch(r"[\w-]{11}", vid):
        return None
    cols = [_dig(c, "musicResponsiveListItemFlexColumnRenderer", "text") for c in (r.get("flexColumns") or [])]
    title = _txt(cols[0]) if cols else ""
    if not title:
        return None
    by = (_dig(cols[1], "runs") if len(cols) > 1 else None) or []
    runs = [x for x in by if isinstance(x, dict) and str(_dig(x, "navigationEndpoint", "browseEndpoint", "browseId") or "").startswith("UC")]
    # "IU • Palette • 3:38" on an artist's top songs; just the artists on an album's (or nothing)
    artist = ", ".join(x.get("text", "") for x in runs)
    if not artist and by and isinstance(by[0], dict):
        t = by[0].get("text", "")
        artist = t if t.strip() and ":" not in t else ""
    fixed = [_txt(_dig(c, "musicResponsiveListItemFixedColumnRenderer", "text")) for c in (r.get("fixedColumns") or [])]
    length = next((_secs(x) for x in fixed if _secs(x)), 0)
    art = _square(_best_img(r.get("thumbnail") or {}))
    return _m_song(vid, title, artist, _dig(runs[0], "navigationEndpoint", "browseEndpoint", "browseId") if runs else "",
                   length, art if "googleusercontent" in art else "")


def _m_cards(d):
    """search results: each row an artist, an album, a playlist... (what its page type says)"""
    out = []
    for r in _all(d, "musicResponsiveListItemRenderer"):
        b = _dig(r, "navigationEndpoint", "browseEndpoint")
        bid = _dig(b, "browseId")
        if not isinstance(bid, str):
            continue
        cols = [_txt(_dig(c, "musicResponsiveListItemFlexColumnRenderer", "text")) for c in (r.get("flexColumns") or [])]
        out.append({"id": bid, "title": cols[0] if cols else "", "sub": cols[1] if len(cols) > 1 else "",
                    "art": _square(_best_img(r.get("thumbnail") or {})), "kind": _m_kind(b)})
    return out


def _m_two_row(t):
    b = _dig(t, "navigationEndpoint", "browseEndpoint")
    bid = _dig(b, "browseId")
    if not isinstance(bid, str):
        return None
    return {"id": bid, "title": _txt(t.get("title")), "sub": _txt(t.get("subtitle")),
            "art": _square(_best_img(t.get("thumbnailRenderer") or {})), "kind": _m_kind(b)}


def music_songs_for(q, hl, region):
    """YouTube's videos for q that look like songs: not live, not Shorts, a song's length"""
    d = yt_call("search", {"query": q, "params": YT_ONLY_VIDEOS}, hl=hl, region=region)
    out = []
    for v in topic_items(_dig(d, "contents") or d):
        if v.get("type") != "video" or v.get("liveNow") or v.get("isUpcoming") or v.get("short"):
            continue
        n = v.get("lengthSeconds") or 0
        if n and not 60 <= n <= 900:
            continue
        out.append(_m_song(v["videoId"], v.get("title") or "", v.get("author") or "", v.get("authorId") or "", n))
    return out


def music_search(q, hl, region):
    from concurrent.futures import ThreadPoolExecutor

    def safe(f):
        try:
            return f()
        except Exception as ex:
            log("music", f"search part FAIL {repr(ex)[:100]}")
            return []
    with ThreadPoolExecutor(3) as ex:
        a = ex.submit(safe, lambda: _m_cards(yt_call("search", {"query": q, "params": M_ARTISTS}, music=True, hl=hl, region=region)))
        b = ex.submit(safe, lambda: _m_cards(yt_call("search", {"query": q, "params": M_ALBUMS}, music=True, hl=hl, region=region)))
        s = ex.submit(safe, lambda: music_songs_for(q, hl, region))
        artists, albums, songs = a.result(), b.result(), s.result()
    return {"artists": [x for x in artists if x["kind"] == "artist"][:6], "albums": [x for x in albums if x["kind"] == "album"][:12],
            "songs": songs}


def music_album(bid, hl, region):
    d = yt_call("browse", {"browseId": bid}, music=True, hl=hl, region=region)
    h = _first(d, "musicResponsiveHeaderRenderer") or _first(d, "musicDetailHeaderRenderer") or {}
    strap = _dig(h, "straplineTextOne", "runs") or []
    artist = "".join(x.get("text", "") for x in strap if isinstance(x, dict))
    artist_id = next((i for i in (_dig(x, "navigationEndpoint", "browseEndpoint", "browseId") for x in strap) if isinstance(i, str)), "")
    art = _square(_best_img(h.get("thumbnail") or {}))
    title = _txt(h.get("title"))
    songs = []
    for r in _all(d, "musicResponsiveListItemRenderer"):
        s = _m_row(r)
        if s:
            s.update(artist=s["artist"] or artist, artistId=s["artistId"] or artist_id, album=title, albumId=bid, art=art)
            songs.append(s)
    return {"title": title, "sub": _txt(h.get("subtitle")), "info": _txt(h.get("secondSubtitle")), "artist": artist,
            "artistId": artist_id, "art": art, "songs": songs}


def music_artist(bid, hl, region):
    d = yt_call("browse", {"browseId": bid}, music=True, hl=hl, region=region)
    hd = d.get("header") or {}
    h = _first(hd, "musicImmersiveHeaderRenderer") or _first(hd, "musicVisualHeaderRenderer") or {}
    name = _txt(h.get("title"))
    shelves = []
    for s in _all(d, "musicCarouselShelfRenderer"):
        t = _txt(_dig(s, "header", "musicCarouselShelfBasicHeaderRenderer", "title"))
        items = [x for x in (_m_two_row(c.get("musicTwoRowItemRenderer")) for c in (s.get("contents") or [])
                             if isinstance(c, dict) and isinstance(c.get("musicTwoRowItemRenderer"), dict)) if x]
        if t and items:
            shelves.append({"title": t, "items": items})
    songs = []
    for sh in _all(d, "musicShelfRenderer"):
        for r in _all(sh, "musicResponsiveListItemRenderer"):
            s = _m_row(r)
            if s:
                s["artist"] = s["artist"] or name
                songs.append(s)
    if not songs and name:
        # no "Top songs" without Premium in some countries (Korea): YouTube's songs for the name instead
        try:
            songs = music_songs_for(name, hl, region)[:12]
        except Exception:
            songs = []
    about = _txt(_dig(_first(d, "musicDescriptionShelfRenderer") or {}, "description")) or _txt(h.get("description"))
    return {"name": name, "audience": _txt(h.get("monthlyListenerCount")) or _txt(_dig(h, "subscriptionButton", "subscribeButtonRenderer", "subscriberCountText")),
            "art": _best_img(h.get("thumbnail") or {}), "about": about,
            "shuffleId": _first(h.get("playButton") or {}, "playlistId"), "radioId": _first(h.get("startRadioButton") or {}, "playlistId"),
            "songs": songs, "shelves": shelves}


def music_mix(list_id, vid, hl, region):
    """a YouTube mix or playlist (a song's radio, an artist's radio or shuffle) as songs"""
    body = {"playlistId": list_id}
    if vid:
        body["videoId"] = vid
    d = yt_call("next", body, hl=hl, region=region)
    out = []
    for p in _all(_dig(d, "contents", "twoColumnWatchNextResults", "playlist", "playlist", "contents") or [], "playlistPanelVideoRenderer"):
        v = p.get("videoId")
        if not isinstance(v, str) or not re.fullmatch(r"[\w-]{11}", v) or not p.get("lengthText"):
            continue                          # no length: a live stream
        name, cid = _byline(p.get("shortBylineText") or p.get("longBylineText"))
        out.append(_m_song(v, _txt(p.get("title")), name, cid, _secs(_txt(p.get("lengthText")))))
    return out


# lyrics: what to search LRCLIB for. YouTube's titles carry extras ("[MV] IU(아이유) _ Blueming(블루밍)",
# "Palette (feat. G-DRAGON)") and channel names ("이지금 [IU Official]", "IU - Topic"); the lyrics are
# under the plain title and artist. The same rules as the apps' (engine/Lyrics.kt).
def _ly_clean(s):
    return re.sub(r"\s+", " ", re.sub(r"[\"'“”‘’|]", " ", s)).strip()


def _song_parts(title, channel):
    """([titles], [artists]) a video's title and channel name stand for"""
    t = re.sub(r"[\[【].*?[\]】]", " ", title)
    artist_t = None
    m = re.match(r"^(.*?)(?:^|\s)['‘\"“](.+?)['’\"”](?=\s|$)(.*)$", t.strip())
    if m:
        if m.group(1).strip():
            artist_t = m.group(1).strip()
        t = m.group(2) + " " + m.group(3)
    if artist_t is None:
        m = re.match(r"^(.+?)\s+[-_–]\s+(.+)$", t.strip())
        if m:
            artist_t, t = m.group(1).strip(), m.group(2)
    t = re.sub(r"(?i)\b(official|music video|m/?v|lyrics?( video)?|audio|visualizer|performance video|live clip)\b", " ", t)
    main = _ly_clean(re.sub(r"\(.*?\)+|（.*?）", " ", t))
    inner = next((x for x in (_ly_clean(g) for g in re.findall(r"\(([^()]+)\)", t))
                  if x and not re.match(r"(?i)^(feat|ft|prod|with|from|ost)\b", x)), None)
    ch = re.sub(r"(?i)vevo|official|\bchannel\b", " ", re.sub(r" - Topic$", "", channel))
    ch_main = _ly_clean(re.sub(r"[\[(（].*?[\])）]", " ", ch))
    m = re.search(r"[\[(（]([^\])）]+)[\])）]", ch)
    ch_inner = _ly_clean(m.group(1)) if m else None
    artists = []
    for a in (_ly_clean(re.sub(r"\(.*?\)", " ", artist_t)) if artist_t else None, ch_inner, ch_main):
        if a and a not in artists:
            artists.append(a)
    titles = []
    for x in (main or None, inner):
        if x and x not in titles:
            titles.append(x)
    return titles, artists


def lyrics_queries(title, channel):
    titles, artists = _song_parts(title, channel)
    out = []
    for a in artists[:2]:
        for x in titles:
            out.append(f"{x} {a}")
    if titles:
        out.append(titles[0])
    return list(dict.fromkeys(out))


def lyrics_lrc(text):
    """[[ms, words]] from "[01:02.34] words" lines (a line may carry several times)"""
    out = []
    for line in text.splitlines():
        stamps = list(re.finditer(r"\[(\d+):(\d+(?:\.\d+)?)\]", line))
        if not stamps:
            continue
        words = line[stamps[-1].end():].strip()
        for s in stamps:
            out.append([int(s.group(1)) * 60000 + int(float(s.group(2)) * 1000), words])
    return sorted(out, key=lambda x: x[0])


def _ly_key(lines):
    """the same timed words, however they were spaced"""
    return tuple((ms // 100, re.sub(r"\W", "", w.lower())) for ms, w in lines[:10])


def music_lyrics(title, artist, length):
    """{synced, lines, len}: at most three LRCLIB searches a song, made only when the lyrics are opened.
    len is the length of the recording the times were made for, so the page can tell a music video
    (an intro, an ending) from the song itself"""
    for q in lyrics_queries(title, artist)[:3]:
        req = urllib.request.Request("https://lrclib.net/api/search?q=" + urllib.parse.quote(q), headers={"User-Agent": LYRICS_UA})
        with urllib.request.urlopen(req, timeout=12) as r:
            found = json.loads(r.read(3_000_000))
        found = [x for x in found if isinstance(x, dict) and not x.get("instrumental")] if isinstance(found, list) else []
        named = lambda x: len(_ly_clean(x.get("trackName") or "")) >= 3 and _ly_clean(x.get("trackName") or "").lower() in q.lower()
        # LRCLIB keeps copies: the song's timed words uploaded again under a music video's length (or
        # any other). The same words under many lengths belong to the length most of them give
        groups = {}
        for x in found:
            lines = lyrics_lrc((x.get("syncedLyrics") or "").strip())
            if not any(w for _, w in lines) or not isinstance(x.get("duration"), (int, float)):
                continue
            g = groups.setdefault(_ly_key(lines), {"lines": lines, "lens": [], "named": False})
            g["lens"].append(round(x["duration"]))
            g["named"] = g["named"] or named(x)
        for g in groups.values():
            ls = sorted(g["lens"])
            g["len"] = max(set(ls), key=lambda d: (ls.count(d), -abs(d - ls[len(ls) // 2])))
        pick = None
        if groups:
            # first the timing made for this very recording, then words whose copies only say so
            near = [g for g in groups.values() if length and abs(g["len"] - length) <= 2]
            copy = [g for g in groups.values() if length and any(abs(d - length) <= 5 for d in g["lens"])]
            # a music video a little longer than the song (up to 90 s): the song's timing, the page nudges it
            mv = [g for g in groups.values() if g["named"] and length and 0 < length - g["len"] <= 90]
            pool = near or copy or mv or ([g for g in groups.values()] if not length else [])
            if pool:
                pick = max(pool, key=lambda g: (len(g["lens"]), g["named"]))
        if pick:
            return {"synced": True, "lines": pick["lines"], "len": pick["len"]}
        plain = next((x for x in found if (x.get("plainLyrics") or "").strip()
                      and (named(x) or (length and isinstance(x.get("duration"), (int, float)) and abs(x["duration"] - length) <= 5))), None)
        if plain:
            lines = [[0, l.strip()] for l in plain["plainLyrics"].splitlines()]
            if any(l[1] for l in lines):
                return {"synced": False, "lines": lines}
    return {"synced": False, "lines": []}


# A music video's sound is the song with more around it (an intro, a scene, an ending), so lyrics timed
# for the song run early on it. YouTube Music plays such a video as the song's own recording: the same
# title on an album or single of the same artist. Found the same way here: one album search, at most
# three albums opened (kept like any album). Versions are left alone: a live stage, a cover, a remix
# is what the listener chose.
SONG_KEEP = re.compile(r"(?i)live|cover|remix|acoustic|\bver\b|ver\.|version|perform|stage|practice|dance|sped|slowed|\binst|karaoke|"
                       r"piano|concert|fancam|teaser|relay|mashup|nightcore|medley|full album|playlist|커버|라이브|무대|직캠|교차|리믹스|연습")


def _norm_t(s):
    return re.sub(r"\W", "", re.sub(r"\(.*?\)|\[.*?\]|（.*?）", " ", s or "").lower())


def _album_cached(bid, hl, region):
    key = ("album", bid, hl, region)
    with music_lock:
        hit = music_cache.get(key)
    if hit and time.time() - hit[0] < MUSIC_TTL:
        return hit[1]
    out = music_album(bid, hl, region)
    with music_lock:
        music_cache[key] = (time.time(), out)
    return out


def music_song_of(title, channel, length, hl, region):
    """{song}: the recording on YouTube Music a music video is of, or {} (not found, or a version)"""
    if SONG_KEEP.search(title):
        return {}
    titles, artists = _song_parts(title, channel)
    want = {_norm_t(t) for t in titles} - {""}
    # the names in brackets too: "IU(아이유)", "RESCENE (리센느)" (YouTube Music shows either, by language)
    extra = re.findall(r"[(（]([^()（）]+)[)）]", title + " " + channel)
    arts = [a for a in dict.fromkeys(_norm_t(x) for x in artists + extra) if a and not re.match(r"(?i)^(feat|ft|prod|official|mv|part)", a)]
    if not want or not arts:
        return {}

    def same_artist(x):
        x = _norm_t(x)
        return bool(x) and any(x == a or (min(len(x), len(a)) >= 4 and (x in a or a in x)) for a in arts)
    d = yt_call("search", {"query": f"{titles[0]} {artists[0]}", "params": M_ALBUMS}, music=True, hl=hl, region=region)
    opened = 0
    for a in [x for x in _m_cards(d) if x["kind"] == "album"][:8]:
        parts = [p.strip() for p in (a.get("sub") or "").split("•")]
        # "EP • RESCENE • 2024": the artists are between the kind and the year
        if not any(same_artist(p) for p in parts[1:-1] or parts):
            continue
        al = _album_cached(a["id"], hl, region)
        opened += 1
        for s in al.get("songs") or []:
            # the song can't be longer than its video (a few seconds of rounding aside)
            if s.get("len") and _norm_t(s["title"]) in want and (not length or s["len"] <= length + 3):
                return {"song": s}
        if opened >= 3:
            break
    return {}


def music_api(p, qs):
    """(status, answer) for /api/p/yt/music/<what>"""
    what = p.rsplit("/", 1)[-1]
    hl = qs.get("hl") or "en"
    hl = hl if re.fullmatch(r"[a-z]{2}(-[A-Za-z]{2,4})?", hl) else "en"
    region = (qs.get("region") or "US").upper()
    region = region if re.fullmatch(r"[A-Z]{2}", region) else "US"
    if what == "search":
        q = (qs.get("q") or "").strip()[:200]
        if not q:
            return 400, {"error": "no query"}
        key, fn = ("search", q, hl, region), lambda: music_search(q, hl, region)
    elif what in ("album", "artist"):
        bid = qs.get("id") or ""
        if not re.fullmatch(r"(MPRE|OLAK|UC|VL)[\w-]{8,60}", bid):
            return 400, {"error": "bad id"}
        key, fn = (what, bid, hl, region), (lambda: music_album(bid, hl, region)) if what == "album" else (lambda: music_artist(bid, hl, region))
    elif what == "mix":
        lid, vid = qs.get("list") or "", qs.get("v") or ""
        if not re.fullmatch(r"[\w-]{10,80}", lid) or (vid and not re.fullmatch(r"[\w-]{11}", vid)):
            return 400, {"error": "bad list"}
        key, fn = ("mix", lid, vid, hl, region), lambda: {"songs": music_mix(lid, vid, hl, region)}
    elif what == "lyrics":
        vid, title, artist = qs.get("v") or "", (qs.get("title") or "")[:300], (qs.get("artist") or "")[:200]
        try:
            length = max(0, int(qs.get("len") or 0))
        except ValueError:
            length = 0
        if not re.fullmatch(r"[\w-]{11}", vid) or not title:
            return 400, {"error": "bad song"}
        key, fn = ("lyrics", vid), lambda: music_lyrics(title, artist, length)
    elif what == "song":
        vid, title, artist = qs.get("v") or "", (qs.get("title") or "")[:300], (qs.get("artist") or "")[:200]
        try:
            length = max(0, int(qs.get("len") or 0))
        except ValueError:
            length = 0
        if not re.fullmatch(r"[\w-]{11}", vid) or not title:
            return 400, {"error": "bad song"}
        key, fn = ("song", vid, hl, region), lambda: music_song_of(title, artist, length, hl, region)
    else:
        return 404, {"error": "unknown"}
    with music_lock:
        hit = music_cache.get(key)
    if hit and time.time() - hit[0] < MUSIC_TTL:
        return 200, hit[1]
    try:
        out = fn()
    except Exception as ex:
        log("music", f"{what} FAIL {repr(ex)[:120]}")
        return (200, hit[1]) if hit else (502, {"error": "YouTube didn't answer"})
    with music_lock:
        music_cache[key] = (time.time(), out)
        if len(music_cache) > 2000:
            for k in sorted(music_cache, key=lambda k: music_cache[k][0])[:400]:
                music_cache.pop(k, None)
    return 200, out


def music_img(qs):
    """(status, content type, bytes): an album cover or artist picture, fetched for the browser"""
    u = qs.get("u") or ""
    pu = urllib.parse.urlparse(u)
    if pu.scheme != "https" or pu.hostname not in MUSIC_IMG_HOSTS:
        return 400, "text/plain", b"bad picture"
    try:
        with YT.open(urllib.request.Request(u, headers={"User-Agent": BROWSER_UA}), timeout=15) as r:
            ctype = r.headers.get("Content-Type") or ""
            body = r.read(3_000_000)
            if not ctype.startswith("image/") or not body:
                return 404, "text/plain", b"no picture"
            return 200, ctype, body
    except Exception as ex:
        log("music", f"img FAIL {repr(ex)[:100]}")
        return 404, "text/plain", b"no picture"


# ---------- "Most replayed": the heat line YouTube draws over its progress bar ----------
# YouTube's watch page carries, for videos with enough views, a hundred points of how much each part of
# the video is replayed, and where the peak is. Read from its /next answer (through YT_PROXY), kept
# 6 hours; the web player draws it above the progress bar.
HEAT_TTL, HEAT_MISS_TTL = 6 * 3600, 600
heat_cache = {}           # video id -> (time, answer)
heat_lock = threading.Lock()


def yt_next(video_id, timeout=12):
    data = json.dumps({"context": {"client": dict(WEB_CLIENT, hl="en", gl="US")}, "videoId": video_id}).encode()
    req = urllib.request.Request("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", data=data,
                                 headers={"Content-Type": "application/json", "User-Agent": BROWSER_UA})
    return json.loads(YT.open(req, timeout=timeout).read())


def heat_from_next(d):
    """{heat: [0..1 per equal slice], span: seconds the slices cover, peak: seconds of "Most replayed" or None}"""
    for m in _dig(d, "frameworkUpdates", "entityBatchUpdate", "mutations") or []:
        ml = _dig(m, "payload", "macroMarkersListEntity", "markersList")
        if not isinstance(ml, dict) or ml.get("markerType") != "MARKER_TYPE_HEATMAP":
            continue
        pts, end = [], 0
        for mk in (ml.get("markers") or [])[:400]:
            if not isinstance(mk, dict):
                continue
            try:
                pts.append(max(0.0, min(1.0, round(float(mk.get("intensityScoreNormalized") or 0), 3))))
                end = max(end, int(mk.get("startMillis") or 0) + int(mk.get("durationMillis") or 0))
            except (TypeError, ValueError):
                pts.append(0.0)
        dec = _dig(ml, "markersDecoration", "timedMarkerDecorations", 0, "decorationTimeMillis")
        peak = round(int(dec) / 1000, 1) if isinstance(dec, (int, str)) and str(dec).isdigit() else None
        if len(pts) >= 10 and end > 0:
            return {"heat": pts, "span": round(end / 1000, 1), "peak": peak}
    return {"heat": [], "span": 0, "peak": None}


def heat_page(qs):
    vid = qs.get("v") or ""
    if not re.fullmatch(r"[\w-]{11}", vid):
        return {"error": "bad video id"}
    with heat_lock:
        hit = heat_cache.get(vid)
    if hit and time.time() - hit[0] < (HEAT_TTL if hit[1]["heat"] else HEAT_MISS_TTL):
        return hit[1]
    try:
        out = heat_from_next(yt_next(vid))
    except Exception as ex:
        log("heat", f"{vid} FAIL {repr(ex)[:100]}")
        return hit[1] if hit else {"heat": [], "span": 0, "peak": None}
    with heat_lock:
        heat_cache[vid] = (time.time(), out)
        if len(heat_cache) > 3000:
            for k in sorted(heat_cache, key=lambda k: heat_cache[k][0])[:500]:
                heat_cache.pop(k, None)
    return out


# ---------- DeArrow thumbnails ----------
# DeArrow (dearrow.ajay.app, by the SponsorBlock people) replaces clickbait titles and thumbnails with
# ones its users chose. The browser asks for titles itself, sending only 4 characters of the video ID's
# hash, like SponsorBlock; the thumbnails are frames that DeArrow's server cuts, and those come through
# here so DeArrow sees this server instead of each viewer.
DEARROW_THUMB = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"


def dearrow_thumb(qs):
    """(status, content type, bytes)"""
    vid, t = qs.get("v") or "", qs.get("t") or ""
    if not re.fullmatch(r"[\w-]{11}", vid) or not re.fullmatch(r"\d{1,6}(\.\d{1,6})?", t):
        return 400, "text/plain", b"bad request"
    url = DEARROW_THUMB + "?" + urllib.parse.urlencode({"videoID": vid, "time": t})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TubeHaus"}), timeout=15) as r:
            ctype = r.headers.get("Content-Type") or ""
            body = r.read(2_000_000)
            if r.status != 200 or not ctype.startswith("image/") or not body:
                return 404, "text/plain", b"no thumbnail"
            return 200, ctype, body
    except Exception as ex:
        log("dearrow", f"{vid} FAIL {repr(ex)[:100]}")
        return 404, "text/plain", b"no thumbnail"


RSS_DOWN = {"until": 0, "fails": 0}   # YouTube takes its RSS feeds down now and then (404 for every channel)
RSS_DOWN_AFTER = 8        # failures in a row before that's assumed; one 404 is often just a deleted channel


def yt_feed_channel_page(cid):
    """A channel's latest uploads from its Videos tab (the engine), for when YouTube's RSS is down."""
    try:
        d = json.loads(_inv(f"/api/v1/channels/{cid}/videos", 20))
    except Exception as ex:
        log("ytfeed", f"{cid} channel page FAIL {repr(ex)[:100]}")
        return None
    old = {e["id"]: e for e in yt_feed["items"].get(cid) or []}
    now = time.time()
    out = []
    for v in (d.get("videos") or [])[:15]:
        if not v.get("videoId"):
            continue
        pub, views = int(v.get("published") or 0), v.get("viewCount")
        # Invidious can't read the date and views of some videos in YouTube's newer layout and
        # says "0 seconds ago, 0 views" for them, which put months-old videos on top as new (#33).
        # Keep what was known; a video never seen before gets no date (unless the channel was
        # already known, so it really is new) rather than "just now"
        if now - pub < 600 and not views:
            o = old.get(v["videoId"])
            pub, views = (o["published"], o.get("views")) if o else (int(now) if old else 0, None)
        out.append({"id": v["videoId"], "title": v.get("title") or "", "author": v.get("author") or "", "authorId": cid,
                    "published": pub, "views": views, "short": False})
    return out


def yt_feed_one(cid):
    ns = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015",
          "m": "http://search.yahoo.com/mrss/"}
    root = None
    if time.time() > RSS_DOWN["until"]:
        try:
            root = ET.fromstring(get(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}", 15))
        except Exception as ex:
            log("ytfeed", f"{cid} FAIL {repr(ex)[:100]}")
            # many 404s or 5xx in a row are YouTube's feed service, not the channels: skip RSS for an
            # hour. One alone is usually a deleted channel, and with a big subscription list that used
            # to send every channel to Invidious all the time (#33)
            if isinstance(ex, urllib.error.HTTPError) and (ex.code == 404 or ex.code >= 500):
                RSS_DOWN["fails"] += 1
                if RSS_DOWN["fails"] >= RSS_DOWN_AFTER:
                    RSS_DOWN.update(until=time.time() + 3600, fails=0)
        else:
            RSS_DOWN["fails"] = 0
    if root is None:
        got = yt_feed_channel_page(cid)
        if got is not None:
            yt_feed["items"][cid] = got
        return
    out = []
    for e in root.findall("a:entry", ns)[:15]:
        vid = e.findtext("yt:videoId", "", ns)
        if not vid:
            continue
        link = e.find("a:link", ns)
        href = link.get("href", "") if link is not None else ""
        try:
            pub = int(datetime.datetime.fromisoformat(e.findtext("a:published", "", ns).replace("Z", "+00:00")).timestamp())
        except Exception:
            pub = 0
        st = e.find("m:group/m:community/m:statistics", ns)
        out.append({"id": vid, "title": e.findtext("a:title", "", ns), "author": e.findtext("a:author/a:name", "", ns),
                    "authorId": cid, "published": pub, "views": int(st.get("views", 0)) if st is not None else None,
                    "short": "/shorts/" in href})
    yt_feed["items"][cid] = out


def yt_fill_thumbs():
    """Imported subscriptions often come without a channel picture, and some import formats
    without a name either; fill both in from the channel's page."""
    with yt_lock:
        missing = list(dict.fromkeys(c for u in _lib_owners() for c, v in _yt_load(u)["subs"].items()
                                     if not v.get("thumb") or v.get("name") in ("", c)))
    got = {}
    for cid in missing[:200]:
        try:
            d = json.loads(_inv(f"/api/v1/channels/{cid}?fields=author,authorThumbnails", 20))
            th = sorted(d.get("authorThumbnails") or [], key=lambda x: x.get("width", 0))
            th = next((x for x in th if x.get("width", 0) >= 88), th[-1] if th else None)
            got[cid] = (str(d.get("author") or "")[:200],
                        re.sub(r"^https?://(yt3\.googleusercontent\.com|yt3\.ggpht\.com)/", "/ggpht/",
                               re.sub(r"^https?://[^/]+(?=/ggpht/)", "", th["url"])) if th else "")
        except Exception as ex:
            log("ytfeed", f"thumb {cid} FAIL {repr(ex)[:80]}")
    if got:
        with yt_lock:
            for owner in _lib_owners():
                d, hit = _yt_load(owner), False
                for cid, (name, u) in got.items():
                    if cid in d["subs"]:
                        hit = True
                        if u and not d["subs"][cid].get("thumb"):
                            d["subs"][cid]["thumb"] = u
                        if name and d["subs"][cid].get("name") in ("", cid):
                            d["subs"][cid]["name"] = name
                if hit:
                    jsave(_ytlib_file(owner), d)


def yt_feed_refresh():
    if yt_feed["running"]:
        return
    yt_feed["running"] = True
    t0 = time.time()
    try:
        with yt_lock:            # everyone's channels: one fetch each, however many people follow it
            subs = list(dict.fromkeys(c for u in _lib_owners() for c in _yt_load(u)["subs"]))
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(6) as ex:
            list(ex.map(yt_feed_one, subs))
        yt_feed["updated"] = time.time()
        log("ytfeed", f"refreshed {len(subs)} channels", t0, every=0)
        yt_fill_thumbs()
    finally:
        yt_feed["running"] = False


def yt_feed_loop():
    while True:
        try:
            yt_feed_refresh()
        except Exception as e:
            log("ytfeed", f"loop FAIL {repr(e)[:160]}")
        time.sleep(900)


# ---------- high-quality offline save: YouTube serves 720p+ as separate video and
# audio, so fetch both (through YT_PROXY) and merge them with ffmpeg ----------
ytdl_jobs = {}
ytdl_lock = threading.Lock()


def _ytdl_part(vid, itag, path, prog, url=None):
    """one stream into path: by default the itag YouTube serves first (the original audio); url = one exact stream
    (another audio track)"""
    if not url:
        url = eng_format_url(vid, itag)
    m = re.search(r"clen=(\d+)", url)
    if not m:
        raise IOError(f"itag {itag}: size unknown")
    size = int(m.group(1))
    prog[itag] = [0, size]
    with open(path, "wb") as f:
        f.truncate(size)
    step = 8_000_000

    def get(start):
        end = min(start + step, size) - 1
        for attempt in range(5):
            try:
                data = gv_open(f"{url}&range={start}-{end}", timeout=90).read()
                if len(data) != end - start + 1:
                    raise IOError("short read")
                with open(path, "r+b") as f:
                    f.seek(start)
                    f.write(data)
                with ytdl_lock:
                    prog[itag][0] += len(data)
                return
            except Exception:
                if attempt == 4:
                    raise
                time.sleep(1 + attempt * 2)
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(4) as ex:
        list(ex.map(get, range(0, size, step)))


# ISO 639-2 codes for the language tags in a saved file (MP4 wants three letters)
_ISO3 = {"ar": "ara", "bn": "ben", "de": "deu", "en": "eng", "es": "spa", "fr": "fra", "hi": "hin", "id": "ind", "it": "ita",
         "ja": "jpn", "ko": "kor", "nl": "nld", "pl": "pol", "pt": "por", "ru": "rus", "th": "tha", "tr": "tur", "uk": "ukr",
         "vi": "vie", "zh": "zho"}


def _iso3(lang):
    return _ISO3.get((lang or "").split("-")[0].lower(), "")


def _ytdl_track_url(vid, itag, track):
    """YouTube's address of one audio stream: this itag in this audio track (dub, description, ...)"""
    for f in _video_json(vid).get("adaptiveFormats") or []:
        t = _atrack(f) if str(f.get("itag")) == str(itag) and f.get("url") else None
        if t and t["id"] == track and not t["drc"]:
            return f["url"]
    raise IOError("That audio track isn't there any more")


def _clean_vtt(raw):
    """a caption file as plain WebVTT for a saved video: no styling or word timings, and automatic captions'
    rolling lines (each cue repeats the one before) and 10 ms in-between cues kept once"""
    import html

    def sec(t):
        return sum(float(x) * 60 ** i for i, x in enumerate(reversed(t.strip().replace(",", ".").split(":"))))
    out, last = ["WEBVTT", ""], ""
    for timing, lines in _vtt_cues(raw):
        a, b = timing.split("-->", 1)
        a, b = a.strip(), (b.split() or [""])[0]
        try:
            if sec(b) - sec(a) < 0.05:
                continue
        except ValueError:
            continue
        txt = [t for t in (re.sub(r"[\u200b-\u200f\ufeff]", "", html.unescape(re.sub(r"<[^>]*>", "", ln))).strip() for ln in lines) if t]
        while txt and txt[0] == last:
            txt.pop(0)
        if not txt:
            continue
        last = txt[-1]
        out += ["%s --> %s" % (a, b)] + txt + [""]
    return "\n".join(out)


def _ytdl_sub(vid, label, path):
    """the caption track with this label (as /api/v1/videos lists it) into path"""
    raw = eng_caption(vid, label).decode("utf-8", "replace")
    vtt = _clean_vtt(raw)
    if "-->" not in vtt:
        raise IOError("no captions")
    with open(path, "w", encoding="utf-8") as f:
        f.write(vtt)


def _ytdl_run(jid, vid, vitag, aitag, ext, name, track="", sub="", sub_lang="", track_lang=""):
    """vitag "" = the sound only; track = an audio track other than the original; sub = a caption track's label to put in"""
    j = ytdl_jobs[jid]
    d = f"{YTDL_DIR}/{jid}"
    os.makedirs(d, exist_ok=True)
    t0 = time.time()
    try:
        from concurrent.futures import ThreadPoolExecutor
        vp, ap, sp = f"{d}/v.part", f"{d}/a.part", f"{d}/s.vtt"
        aurl = _ytdl_track_url(vid, aitag, track) if track else None
        with ThreadPoolExecutor(3) as ex:
            jobs = [ex.submit(_ytdl_part, vid, aitag, ap, j["prog"], aurl)]
            if vitag:
                jobs.append(ex.submit(_ytdl_part, vid, vitag, vp, j["prog"]))
            sj = ex.submit(_ytdl_sub, vid, sub, sp) if sub and vitag else None
            for f in jobs:
                f.result()
            if sj:
                try:
                    sj.result()
                except Exception as e:                  # the video still gets saved, without them
                    sub = ""
                    j["warn"] = "The captions couldn't be added, the video was saved without them"
                    log("ytdl", f"{vid} captions {repr(e)[:100]}", every=0)
        j["status"] = "merge"
        out = f"{d}/out.{ext}"
        if vitag:
            cmd = ["ffmpeg", "-v", "error", "-y", "-i", vp, "-i", ap] + (["-i", sp] if sub else []) + \
                  ["-map", "0:v:0", "-map", "1:a:0"] + (["-map", "2:s:0"] if sub else []) + ["-c:v", "copy", "-c:a", "copy"]
            if sub:
                cmd += ["-c:s", "mov_text" if ext == "mp4" else "webvtt", "-metadata:s:s:0", "title=" + sub]
                if _iso3(sub_lang):
                    cmd += ["-metadata:s:s:0", "language=" + _iso3(sub_lang)]
        else:
            cmd = ["ffmpeg", "-v", "error", "-y", "-i", ap, "-map", "0:a:0", "-c", "copy"]
        if _iso3(track_lang):
            cmd += ["-metadata:s:a:0", "language=" + _iso3(track_lang)]
        if ext in ("mp4", "m4a"):
            cmd += ["-movflags", "+faststart"]
        r = subprocess.run(cmd + [out], capture_output=True, text=True, timeout=3600)
        if r.returncode != 0:
            raise IOError("Merging failed: " + r.stderr.strip()[-200:])
        for x in (vp, ap, sp):
            if os.path.exists(x):
                os.remove(x)
        j.update(status="done", path=out, size=os.path.getsize(out), done_at=time.time())
        log("ytdl", f"{vid} {vitag}+{aitag} done {j['size']}B", t0, every=0)
    except Exception as ex:
        j.update(status="error", err=str(ex)[:200], done_at=time.time())
        log("ytdl", f"{vid} {vitag}+{aitag} FAIL {repr(ex)[:160]}", t0, every=0)
        subprocess.run(["rm", "-rf", d])


def ytdl_start(b):
    """{id, v (video itag, "" = the sound only), a (audio itag), ext, name, at (audio track id, "" = the original),
    sub (a caption track's label to put in the video), sl (its language), al (the audio track's language)}"""
    vid, vitag, aitag = str(b.get("id", "")), str(b.get("v") or ""), str(b.get("a", ""))
    ext = str(b.get("ext") or "")
    ext = ext if ext in (("webm", "m4a") if not vitag else ("webm",)) else "mp4" if vitag else "m4a"
    track, sub = str(b.get("at") or ""), str(b.get("sub") or "")[:100]
    sl, al = str(b.get("sl") or ""), str(b.get("al") or "")
    if not re.fullmatch(r"[\w-]{11}", vid) or not (vitag.isdigit() or not vitag) or not aitag.isdigit() \
            or not re.fullmatch(r"[\w.-]{0,40}", track) or re.search(r"[\x00-\x1f]", sub) \
            or not re.fullmatch(r"[A-Za-z]{0,3}(-[A-Za-z0-9]{1,8})*", sl) or not re.fullmatch(r"[A-Za-z]{0,3}(-[A-Za-z0-9]{1,8})*", al):
        return 400, {"error": "Bad request"}
    name = re.sub(r'[\\/:*?"<>|\r\n]+', " ", str(b.get("name") or vid)).strip()[:120] or vid
    ytdl_gc()
    with ytdl_lock:
        busy = [x for x in ytdl_jobs.values() if x["status"] in ("download", "merge")]
        same = next((k for k, x in ytdl_jobs.items() if x["key"] == (vid, vitag, aitag, track, sub) and x["status"] != "error"), None)
        if same:
            return 200, {"job": same}
        if len(busy) >= 2:
            return 429, {"error": "Two other saves are in progress. Try again when they finish"}
        os.makedirs(YTDL_DIR, exist_ok=True)
        st = os.statvfs(YTDL_DIR)
        if st.f_bavail * st.f_frsize < 10e9:
            return 507, {"error": "Not enough disk space on the server"}
        jid = secrets.token_hex(8)
        ytdl_jobs[jid] = {"key": (vid, vitag, aitag, track, sub), "status": "download", "prog": {}, "name": f"{name}.{ext}", "t": time.time()}
    threading.Thread(target=_ytdl_run, args=(jid, vid, vitag, aitag, ext, name, track, sub, sl, al), name="ytdl-" + jid, daemon=True).start()
    return 200, {"job": jid}


def ytdl_status(jid):
    j = ytdl_jobs.get(jid)
    if not j:
        return {"status": "error", "err": "No such job (the server may have restarted)"}
    got = sum(x[0] for x in j["prog"].values())
    tot = sum(x[1] for x in j["prog"].values()) or 1
    pct = 100 if j["status"] == "done" else round(got / tot * 95, 1) if j["status"] == "download" else 97
    return {"status": j["status"], "pct": pct, "name": j["name"], "size": j.get("size") or tot, "err": j.get("err"), "warn": j.get("warn")}


def ytdl_gc():
    # Delete files two hours after they finished. A restart empties the job list, so
    # judge by each folder's last change instead (folders in use keep changing).
    try:
        names = os.listdir(YTDL_DIR)
    except FileNotFoundError:
        return
    for k in names:
        d = f"{YTDL_DIR}/{k}"
        j = ytdl_jobs.get(k)
        if j and j["status"] in ("download", "merge"):
            continue
        try:
            newest = max([os.path.getmtime(d)] + [os.path.getmtime(f"{d}/{f}") for f in os.listdir(d)])
        except OSError:
            continue
        last = max(newest, (j or {}).get("done_at") or 0)
        if time.time() - last > 7200:
            subprocess.run(["rm", "-rf", d])
            ytdl_jobs.pop(k, None)


def ytdl_gc_loop():
    while True:
        try:
            ytdl_gc()
        except Exception as e:
            log("ytdl", f"gc FAIL {repr(e)[:120]}")
        time.sleep(1800)


# ---------- "send to TV": open screens long-poll for commands, a phone posts them ----------
cast_cond = threading.Condition()
cast_devs = {}     # device id -> {"name", "tv", "seen"}
cast_q = []        # [{"seq", "to", "cmd", "from", "at"}]
cast_state = {"seq": 0}
CAST_TYPES = ("play", "toggle", "seek", "stop")


def _cast_id(v):
    v = str(v or "")
    return v if re.fullmatch(r"[\w-]{8,64}", v) else None


def cast_devices():
    now = time.time()
    with cast_cond:
        return {"devices": [{"id": k, "name": d["name"], "tv": d["tv"]}
                            for k, d in cast_devs.items() if now - d["seen"] < 75 and d.get("u") == _uid()]}   # only your own screens


def cast_send(b):
    to = _cast_id(b.get("to"))
    cmd = b.get("cmd") if isinstance(b.get("cmd"), dict) else {}
    typ = cmd.get("type")
    if not to or typ not in CAST_TYPES:
        return 400, {"error": "Bad request"}
    clean = {"type": typ}
    if typ == "play":
        if not re.fullmatch(r"[\w-]{11}", str(cmd.get("id", ""))):
            return 400, {"error": "Invalid video ID"}
        clean["id"] = cmd["id"]
        clean["t"] = max(0, int(cmd.get("t") or 0))
        if cmd.get("list") and re.fullmatch(r"[\w-]{2,64}", str(cmd["list"])):
            clean["list"] = cmd["list"]
    if typ == "seek":
        clean["d"] = max(-3600, min(3600, int(cmd.get("d") or 0)))
    with cast_cond:
        if to not in cast_devs or time.time() - cast_devs[to]["seen"] > 75 or cast_devs[to].get("u") != _uid():
            return 404, {"error": "That device is off"}
        cast_state["seq"] += 1
        cast_q.append({"seq": cast_state["seq"], "to": to, "cmd": clean,
                       "from": str(b.get("from") or "")[:40], "at": time.time()})
        del cast_q[:-50]
        cast_cond.notify_all()
    log("cast", f"{typ} -> {cast_devs[to]['name']}", every=0)
    return 200, {"ok": True}


def cast_wait(qs):
    """Long poll (up to 25 s) from a receiving screen; while it waits it is listed as online."""
    did = _cast_id(qs.get("id"))
    if not did:
        return {"error": "bad id"}
    name = str(qs.get("name") or "Device")[:40]
    tv = qs.get("tv") == "1"
    try:
        since = int(qs.get("since", "-1"))
    except ValueError:
        since = -1
    deadline = time.time() + 25
    with cast_cond:
        if did in cast_devs and cast_devs[did].get("u") != _uid() and time.time() - cast_devs[did]["seen"] < 75:   # someone else's screen, still on
            return {"error": "bad id"}
        cast_devs[did] = {"name": name, "tv": tv, "seen": time.time(), "u": _uid()}
        if since < 0:   # first connection: do not replay old commands
            return {"seq": cast_state["seq"], "cmds": []}
        while True:
            mine = [c for c in cast_q if c["seq"] > since and c["to"] == did and time.time() - c["at"] < 60]
            left = deadline - time.time()
            if mine or left <= 0:
                cast_devs[did]["seen"] = time.time()
                return {"seq": cast_state["seq"], "cmds": [{"cmd": c["cmd"], "from": c["from"]} for c in mine]}
            cast_cond.wait(left)
            cast_devs[did]["seen"] = time.time()


# ---------- watch together: a room that keeps everyone's player on the same video and moment ----------
# One person opens a room and gets a six-letter code (and a link); others join with it. Whoever plays,
# pauses, seeks or picks another video moves everyone. A short chat sits beside it. Rooms live in memory
# only and close 30 minutes after the last member left; nothing about them is written anywhere.
party_cond = threading.Condition()
parties = {}       # code -> {"seq", "state": {"v", "t", "playing", "at", "by"}, "members": {mid: {"name", "seen"}}, "chat": [...]}
PARTY_CODE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PARTY_REACTS = ("❤️", "😂", "😮", "👏", "🔥")


def _party_gc():
    now = time.time()
    for code in [c for c, r in parties.items() if not any(now - m["seen"] < 1800 for m in r["members"].values())]:
        del parties[code]


def _party_name(v):
    return re.sub(r"[\x00-\x1f<>]", "", str(v or "")).strip()[:30] or "Guest"


def _party_state(b, old=None):
    v = str(b.get("v") or (old or {}).get("v") or "")
    if not re.fullmatch(r"[\w-]{11}", v):
        return None
    try:
        t = max(0.0, min(float(b.get("t") or 0), 86400.0))
    except (TypeError, ValueError):
        t = 0.0
    return {"v": v, "t": t, "playing": bool(b.get("playing")), "at": time.time()}


def _party_view(r, mid):
    now = time.time()
    return {"seq": r["seq"], "state": r["state"], "me": mid, "now": now,
            "members": [m["name"] for k, m in r["members"].items() if now - m["seen"] < 60],
            "chat": r["chat"][-50:],
            "reacts": [x for x in r.get("reacts", []) if now - x["at"] < 10]}


def party_create(b):
    st = _party_state(b)
    if not st:
        return 400, {"error": "Invalid video ID"}
    with party_cond:
        _party_gc()
        if len(parties) >= 200:
            return 503, {"error": "Too many rooms are open. Please try again later"}
        code = "".join(secrets.choice(PARTY_CODE) for _ in range(6))
        while code in parties:
            code = "".join(secrets.choice(PARTY_CODE) for _ in range(6))
        mid = secrets.token_hex(8)
        st["by"] = _party_name(b.get("name"))
        parties[code] = {"seq": 1, "state": st, "members": {mid: {"name": st["by"], "seen": time.time()}}, "chat": []}
        log("party", f"open {code}", every=0)
        return 200, {"code": code, **_party_view(parties[code], mid)}


def party_join(b):
    code = str(b.get("code") or "").upper().strip()
    with party_cond:
        r = parties.get(code)
        if not r:
            return 404, {"error": "That room is closed"}
        if len(r["members"]) >= 20 and b.get("me") not in r["members"]:
            return 403, {"error": "That room is full"}
        mid = b.get("me") if b.get("me") in r["members"] else secrets.token_hex(8)
        r["members"][mid] = {"name": _party_name(b.get("name")), "seen": time.time()}
        r["seq"] += 1
        party_cond.notify_all()
        return 200, {"code": code, **_party_view(r, mid)}


def party_update(b):
    """A member played, paused, seeked, picked a video, said something or left."""
    code = str(b.get("code") or "").upper().strip()
    mid = str(b.get("me") or "")
    with party_cond:
        r = parties.get(code)
        if not r or mid not in r["members"]:
            return 404, {"error": "That room is closed"}
        m = r["members"][mid]
        m["seen"] = time.time()
        if b.get("leave"):
            del r["members"][mid]
        elif b.get("say"):
            text = re.sub(r"[\x00-\x1f]", " ", str(b["say"])).strip()[:300]
            if text:
                r["chat"].append({"name": m["name"], "text": text, "at": time.time()})
                del r["chat"][:-50]
        elif b.get("react") in PARTY_REACTS:
            r.setdefault("reacts", []).append({"e": b["react"], "from": mid, "at": time.time()})
            del r["reacts"][:-20]
        else:
            st = _party_state(b, r["state"])
            if not st:
                return 400, {"error": "Invalid video ID"}
            st["by"] = m["name"]
            st["from"] = mid
            r["state"] = st
        r["seq"] += 1
        party_cond.notify_all()
        return 200, {"ok": True, "seq": r["seq"]}


def party_wait(qs):
    """Long poll (up to 25 s): what changed in the room after `since`; while waiting the member counts as here."""
    code = str(qs.get("code") or "").upper().strip()
    mid = str(qs.get("me") or "")
    try:
        since = int(qs.get("since", "0"))
    except ValueError:
        since = 0
    deadline = time.time() + 25
    with party_cond:
        while True:
            r = parties.get(code)
            if not r or mid not in r["members"]:
                return 404, {"error": "That room is closed"}
            r["members"][mid]["seen"] = time.time()
            left = deadline - time.time()
            if r["seq"] > since or left <= 0:
                return 200, _party_view(r, mid)
            party_cond.wait(left)


# ---------- live captions: YouTube gives none for live streams, so transcribe them ----------
# whisper.cpp + a small ggml model (base-q5_1 keeps up with real time on 2 ARM cores).
# Audio of the lightest variant is cut into ~10 s chunks (two segments); the text is
# stored per segment number (sq), relative to that segment's start. The player knows
# which sq it is playing and asks /api/p/live/<id>/cc.json for that range.
# A captioner stops 2 minutes after nobody asks for it.
LIVE_CC, LIVE_CC_LOCK = {}, threading.Lock()


def _livecc_engines():
    out = {}
    if WHISPER_BIN and WHISPER_MODEL:
        out["whisper"] = WHISPER_BIN
    for item in LIVECC_ALT.split(","):
        name, _, path = item.strip().partition("=")
        if re.fullmatch(r"[a-z0-9_-]{1,20}", name.strip()) and path.strip():
            out[name.strip()] = path.strip()
    return out


LIVECC_ENGINES = _livecc_engines()     # name -> program; the first is the default
CC_TAG = re.compile(r"[\[(（【♪*].*[\])）】♪*]")   # sound tags like [Music] (applause)
# phrases whisper invents over music or silence (mostly Korean broadcast sign-offs)
CC_JUNK = re.compile(r"^(감사합니다|시청해 ?주셔서 감사합니다|구독과 좋아요|자막 제공|"
                     r"Thanks? (you )?for watching|Subscribe|[.\-…♪\s]*)[.!]?$", re.I)


def _cc_time(t):
    h, m, rest = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(rest)


class LiveCaptioner:
    def __init__(self, vid, engine="whisper"):
        self.vid = vid
        self.engine = engine
        self.bin = LIVECC_ENGINES.get(engine) or WHISPER_BIN
        self.cues = {}          # sq -> [[start, end, text]] (seconds from the segment start)
        self.used = time.time()
        self.status = "starting"
        self.lag = None
        self.want = None        # the segment the viewer is watching: transcribe from there
        threading.Thread(target=self.run, name="cc-" + vid, daemon=True).start()

    def _itag(self):
        e = live_entry(self.vid)

        def bw(item):
            m = re.search(r"BANDWIDTH=(\d+)", item[1][0])
            return int(m.group(1)) if m else 1 << 40
        return min(e["variants"].items(), key=bw)[0]

    def run(self):
        # Transcription runs at about real-time speed, so starting where the viewer is
        # would always be one step late. Start 3 segments (~15 s) ahead of the viewer
        # and jump ahead again whenever it falls behind.
        itag, nxt = None, None
        fail_at, fails = None, 0
        while time.time() - self.used < 120:
            try:
                if itag is None:
                    itag = self._itag()
                pl = live_pull(self.vid, itag)
                pl.used = time.time()                      # keep the segments coming meanwhile
                want = self.want
                with pl.cond:
                    last = pl.last
                    if want is None:
                        want = last - 20
                    if nxt is None or nxt < want or nxt > want + 30:
                        nxt = min(want + 3, last - 1)
                    chunk = [k for k in (nxt, nxt + 1) if k in pl.segs]
                    data = {k: pl.segs[k][1] for k in chunk}
                    meta = {k: pl.meta.get(k, {}).get("dur") for k in chunk}
                if not chunk or chunk[0] != nxt:
                    if nxt < last - 3:
                        nxt += 1                            # a segment we never got: skip it
                    else:
                        time.sleep(1)
                    continue
                if len(chunk) < 2 and nxt + 1 >= last - 3:
                    time.sleep(1)                           # next segment still coming: wait to make 10 s
                    continue
                try:
                    self.transcribe(chunk, [data[k] for k in chunk], [meta.get(k) or 5.0 for k in chunk])
                    fails = 0
                except Exception as ex:
                    fails = fails + 1 if fail_at == nxt else 1
                    fail_at = nxt
                    if fails < 3:
                        raise                               # retried below, same chunk
                    log("livecc", f"{self.vid} sq {chunk[0]}-{chunk[-1]} skipped after {fails} failures: {repr(ex)[:120]}", every=0)
                    fails = 0
                nxt = chunk[-1] + 1
                self.lag = last - chunk[-1]
                self.status = "ok"
                for old in [k for k in self.cues if k < last - 80]:
                    del self.cues[old]
            except Exception as ex:
                self.status = "error"
                log("livecc", f"{self.vid} FAIL {repr(ex)[:160]}", every=30)
                time.sleep(3)
        with LIVE_CC_LOCK:
            if LIVE_CC.get((self.vid, self.engine)) is self:
                del LIVE_CC[(self.vid, self.engine)]
        log("livecc", f"{self.vid} {self.engine} stopped", every=0)

    def transcribe(self, sqs, blobs, durs):
        t0 = time.time()
        d = f"/var/tmp/tubehaus-cc-{self.vid}-{self.engine}"
        os.makedirs(d, exist_ok=True)
        wav = f"{d}/chunk.wav"
        r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "mpegts", "-i", "pipe:0", "-vn",
                            "-ar", "16000", "-ac", "1", wav], input=b"".join(blobs), capture_output=True, timeout=30)
        if r.returncode != 0:
            raise RuntimeError("ffmpeg " + r.stderr.decode()[-120:])
        # one thread and a 768-frame audio window (~15 s instead of 30 s): same speed
        # on a 10 s chunk, never more than one core, and no 100 % CPU bursts
        r = subprocess.run(["nice", "-n", "10", self.bin, "-m", WHISPER_MODEL, "-f", wav, "-l", WHISPER_LANG,
                            "-t", "1", "-ac", "768", "-np", "-sns"], capture_output=True, timeout=60)
        if r.returncode != 0:     # e.g. a bad model path or a crash: no text is not "silence"
            raise RuntimeError("%s exit %d: %s" % (self.engine, r.returncode, r.stderr.decode("utf-8", "replace").strip()[-160:]))
        starts, acc = [], 0.0
        for dd in durs:
            starts.append(acc)
            acc += dd
        n = 0
        for line in r.stdout.decode("utf-8", "replace").splitlines():
            m = re.match(r"\[(\d+:\d+:[\d.]+) --> (\d+:\d+:[\d.]+)\]\s*(.*)$", line.strip())
            if not m:
                continue
            a, b, text = _cc_time(m.group(1)), _cc_time(m.group(2)), m.group(3).strip()
            text = re.sub(r"^[-–]\s*", "", text).strip()
            if not text or CC_JUNK.match(text) or CC_TAG.fullmatch(text):
                continue
            i = max(i for i, st in enumerate(starts) if st <= a + 0.01)   # the segment the line starts in
            self.cues.setdefault(sqs[i], []).append([round(a - starts[i], 2), round(b - starts[i], 2), text[:200]])
            n += 1
        log("livecc", f"{self.vid} {self.engine} sq {sqs[0]}-{sqs[-1]} {n} cues", t0, every=60)

    def get(self, lo, hi):
        self.used = time.time()
        self.want = lo
        return {str(k): v for k, v in self.cues.items() if lo <= k <= hi}


def live_cc(vid, engine=None):
    engine = engine if engine in LIVECC_ENGINES else next(iter(LIVECC_ENGINES), "whisper")
    with LIVE_CC_LOCK:
        c = LIVE_CC.get((vid, engine))
        if c is None:
            c = LIVE_CC[(vid, engine)] = LiveCaptioner(vid, engine)
        return c


# ---------- self-check: `python3 tubehaus_server.py --doctor` / GET /api/p/health ----------
def doctor():
    """Check every piece TubeHaus depends on and print one line each. Returns the
    number of failed required checks (the exit code), so it can run in scripts."""
    import shutil
    fails = 0

    def line(ok, name, detail, required=True):
        nonlocal fails
        mark = "ok  " if ok else ("FAIL" if required else "warn")
        if not ok and required:
            fails += 1
        print(f"[{mark}] {name:<22} {detail}")

    def get(url, opener=None, timeout=10):
        return (opener or urllib.request.build_opener()).open(url, timeout=timeout).read()

    # 1. WARP (or no proxy)
    if YT_PROXY:
        try:
            t = get("https://www.cloudflare.com/cdn-cgi/trace", YT).decode()
            warp = re.search(r"^warp=(\w+)$", t, re.M)
            ip = re.search(r"^ip=(.+)$", t, re.M)
            line(warp and warp.group(1) in ("on", "plus"), "WARP proxy",
                 f"{YT_PROXY} -> warp={warp.group(1) if warp else '?'} ip={ip.group(1) if ip else '?'}")
        except Exception as ex:
            line(False, "WARP proxy", f"{YT_PROXY} unreachable ({type(ex).__name__}: {str(ex)[:80]})")
    else:
        line(True, "WARP proxy", "disabled (HT_YT_PROXY empty): YouTube is reached directly", required=False)

    # 2. YouTube itself, through the same path
    try:
        body = get("https://www.youtube.com/generate_204", YT)
        line(True, "YouTube reachable", "via WARP" if YT_PROXY else "direct")
    except urllib.error.HTTPError as ex:
        line(ex.code == 204, "YouTube reachable", f"HTTP {ex.code}")
    except Exception as ex:
        line(False, "YouTube reachable", f"{type(ex).__name__}: {str(ex)[:80]}")

    # 3. the built-in engine: a search, then a real video with its streams
    try:
        found = eng_search({"q": "me at the zoo", "type": "video"}, "en", "US")
        line(bool(found), "Search", f"{len(found)} results" if found else "no results")
    except Exception as ex:
        line(False, "Search", f"{type(ex).__name__}: {str(ex)[:80]}")
    try:
        d = eng_video("jNQXAC9IVRw")
        n = len(d.get("adaptiveFormats") or [])
        line(n > 0, "Video lookup", f"'{d.get('title', '?')}' -> {n} streams via {d.get('client')}" if n else "no streams")
        if n:
            a = next(f for f in d["adaptiveFormats"] if f["type"].startswith("audio/"))
            got = len(gv_open(a["url"], "bytes=0-9999", 20).read())
            line(got == 10000, "Stream download", f"{got} of 10000 bytes")
    except Exception as ex:
        line(False, "Video lookup", f"{type(ex).__name__}: {str(ex)[:80]}")

    # 5. optional tools
    yt = shutil.which(YTDLP) or (YTDLP if os.path.exists(YTDLP) else "")
    line(bool(yt), "yt-dlp (live streams)", yt or f"not found ({YTDLP})", required=False)
    ff = shutil.which("ffmpeg")
    line(bool(ff), "ffmpeg (HD downloads)", ff or "not found", required=False)
    wok = bool(WHISPER_BIN and WHISPER_MODEL and os.path.exists(WHISPER_BIN) and os.path.exists(WHISPER_MODEL))
    line(wok, "whisper (live captions)", f"{WHISPER_BIN} + {os.path.basename(WHISPER_MODEL)}" if wok else
         "not configured (HT_WHISPER_BIN / HT_WHISPER_MODEL)", required=False)

    # 6. state directory
    try:
        os.makedirs(STATE, exist_ok=True)
        probe = os.path.join(STATE, ".doctor")
        open(probe, "w").close()
        os.remove(probe)
        line(True, "State directory", os.path.abspath(STATE))
    except Exception as ex:
        line(False, "State directory", f"{STATE} not writable ({ex})")

    print("\nAll required checks passed." if not fails else f"\n{fails} required check(s) failed.")
    return fails


# ---------- accounts: sign-up required on a public instance ----------
# Users live in STATE/users.json as PBKDF2-SHA256 hashes (600k rounds, random salt). A session
# is an HMAC-signed cookie (user, expiry, password generation); the key never leaves the server.
import hashlib
import hmac

USERS = f"{STATE}/users.json"
SESSION_KEY = f"{STATE}/session.key"
SESSION_DAYS = 30
COOKIE = "th_session"
auth_lk = threading.Lock()
auth_tries = {}      # key -> [timestamps] for sign-up / login rate limits
USERNAME = re.compile(r"[a-z0-9_]{3,24}")


def _session_key():
    with auth_lk:
        try:
            with open(SESSION_KEY, "rb") as f:
                k = f.read()
            if len(k) >= 32:
                return k
        except OSError:
            pass
        k = secrets.token_bytes(32)
        os.makedirs(STATE, exist_ok=True)
        fd = os.open(SESSION_KEY, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(k)
        return k


def _pw_hash(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600_000).hex()


def _too_many(key, limit, window):
    now = time.time()
    with auth_lk:
        hits = [t for t in auth_tries.get(key, []) if now - t < window]
        if len(auth_tries) > 50000:
            # forget only the old entries: clearing everything would let a flood of made-up
            # names wipe the counts of someone who is guessing passwords right now
            for k in [k for k, v in auth_tries.items() if not v or now - v[-1] > 3600]:
                del auth_tries[k]
            if len(auth_tries) > 50000:
                auth_tries.clear()
        if len(hits) >= limit:
            auth_tries[key] = hits
            return True
        hits.append(now)
        auth_tries[key] = hits
        return False


def session_make(user, gen):
    exp = int(time.time()) + SESSION_DAYS * 86400
    msg = f"{user}.{exp}.{gen}"
    sig = hmac.new(_session_key(), msg.encode(), hashlib.sha256).hexdigest()
    return f"{msg}.{sig}"


def session_user(token):
    """The user name a session cookie belongs to, or None (bad signature, expired, user gone)."""
    try:
        user, exp, gen, sig = str(token).split(".")
    except ValueError:
        return None
    msg = f"{user}.{exp}.{gen}"
    good = hmac.new(_session_key(), msg.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(good, sig) or not exp.isdigit() or int(exp) < time.time():
        return None
    u = jload(USERS, {}).get(user)
    return user if u and str(u.get("gen", 0)) == gen else None


def auth_signup(b, ip):
    user = str(b.get("username", "")).strip().lower()
    pw = str(b.get("password", ""))
    if b.get("website"):                      # honeypot field real people never fill in
        return 400, {"error": "Could not create the account"}
    if not USERNAME.fullmatch(user):
        return 400, {"error": "Use 3-24 letters, numbers or _ for the user name"}
    if len(pw) < 8 or len(pw) > 200:
        return 400, {"error": "The password needs at least 8 characters"}
    if pw.lower() == user or pw in ("password", "12345678", "123456789", "qwertyui", "11111111"):
        return 400, {"error": "Please choose a less guessable password"}
    if _too_many("signup:" + ip, 3, 3600):
        return 429, {"error": "Too many new accounts from your network. Please try again later"}
    salt = secrets.token_bytes(16)
    h = _pw_hash(pw, salt)
    with auth_lk:
        users = jload(USERS, {})
        if user in users:
            return 409, {"error": "That user name is taken"}
        if len(users) >= MAX_ACCOUNTS:
            return 403, {"error": "This server is not taking new accounts right now"}
        first = not users
        users[user] = {"salt": salt.hex(), "hash": h, "gen": 1, "created": int(time.time())}
        jsave(USERS, users)
        os.chmod(USERS, 0o600)
    if first and not PUBLIC_MODE:
        # switching a server that was used without accounts: its library becomes the first account's
        for src, dst in ((YTLIB, _ytlib_file(user)), (SHORTS, _shorts_file(user))):
            if os.path.exists(src) and not os.path.exists(dst):
                with yt_lock:
                    jsave(dst, jload(src, {}))
        log("auth", "first account: took over the shared library", every=0)
    log("auth", "new account", every=0)
    stat("signups")
    return 200, {"ok": True, "user": user, "cookie": session_make(user, 1)}


def auth_password(b, user, ip):
    """A new password for the signed-in account; every other device is signed out."""
    if not user:
        return 401, {"error": "Please sign in"}
    old, new = str(b.get("old", "")), str(b.get("new", ""))
    if _too_many("pw:" + user, 5, 600) or _too_many("pwip:" + ip, 10, 600):
        return 429, {"error": "Too many attempts. Please wait a few minutes"}
    if len(new) < 8 or len(new) > 200:
        return 400, {"error": "The password needs at least 8 characters"}
    if new.lower() == user or new in ("password", "12345678", "123456789", "qwertyui", "11111111"):
        return 400, {"error": "Please choose a less guessable password"}
    u = jload(USERS, {}).get(user)
    if not u or not hmac.compare_digest(_pw_hash(old, bytes.fromhex(u["salt"])), u["hash"]):
        return 403, {"error": "The current password is wrong"}
    salt = secrets.token_bytes(16)
    h = _pw_hash(new, salt)
    with auth_lk:
        users = jload(USERS, {})
        if user not in users:
            return 401, {"error": "Please sign in"}
        gen = int(users[user].get("gen", 1)) + 1
        users[user].update(salt=salt.hex(), hash=h, gen=gen)
        jsave(USERS, users)
        os.chmod(USERS, 0o600)
    log("auth", "password changed", every=0)
    return 200, {"ok": True}


def auth_login(b, ip):
    user = str(b.get("username", "")).strip().lower()
    pw = str(b.get("password", ""))
    if _too_many("login:" + ip, 10, 600) or _too_many("user:" + user, 10, 600):
        return 429, {"error": "Too many attempts. Please wait a few minutes"}
    u = jload(USERS, {}).get(user) if USERNAME.fullmatch(user) else None
    # same work whether or not the user exists, so timing doesn't reveal names
    salt = bytes.fromhex(u["salt"]) if u else b"\0" * 16
    ok = hmac.compare_digest(_pw_hash(pw, salt), u["hash"]) if u else (_pw_hash(pw, salt) and False)
    if not ok:
        return 401, {"error": "Wrong user name or password"}
    stat("logins")
    return 200, {"ok": True, "user": user, "cookie": session_make(user, u.get("gen", 1))}


# ---------- usage counts (public instances only) ----------
# How many people visit and watch, per hour, so the operator can see how the instance is used.
# Only numbers are kept: no addresses, accounts or videos. People are told apart within an hour
# by a hash with a key that exists only in memory, so the counts can't be tied to anyone later.
STATS = f"{STATE}/stats.json"
STATS_DAYS = 60
stats_lk = threading.Lock()
stats = {}                                    # "2026-10-24T11" (UTC) -> {"visitors": 3, "plays": 5, ...}
stats_seen = {"hour": "", "visitors": set(), "watchers": set()}
STATS_KEY = secrets.token_bytes(16)


def stat(kind, ip=""):
    """Count one event: visitors and watchers once per person an hour; plays, signups and logins each time."""
    if not PUBLIC_MODE:
        return
    hour = time.strftime("%Y-%m-%dT%H", time.gmtime())
    with stats_lk:
        if stats_seen["hour"] != hour:
            stats_seen.update(hour=hour, visitors=set(), watchers=set())
        if kind in ("visitors", "watchers"):
            h = hmac.new(STATS_KEY, ip.encode(), "sha256").digest()[:8]
            if h in stats_seen[kind]:
                return
            stats_seen[kind].add(h)
        row = stats.setdefault(hour, {})
        row[kind] = row.get(kind, 0) + 1


def stats_loop():
    """Keeps the counts on disk every five minutes, the last STATS_DAYS days of them."""
    if not PUBLIC_MODE:
        return
    with stats_lk:
        for h, row in jload(STATS, {}).items():
            if isinstance(row, dict):
                stats.setdefault(h, {}).update({k: v for k, v in row.items() if k not in stats.get(h, {})})
    while True:
        time.sleep(300)
        cut = time.strftime("%Y-%m-%dT%H", time.gmtime(time.time() - STATS_DAYS * 86400))
        with stats_lk:
            for h in [h for h in stats if h < cut]:
                del stats[h]
            snap = {h: dict(r) for h, r in sorted(stats.items())}
        try:
            jsave(STATS, snap)
        except Exception as ex:
            log("stats", "save FAIL %s" % repr(ex)[:80])


admin_fails = {}                              # ip -> times of wrong admin keys (last 10 minutes)


def admin_stats():
    """Everything the usage page shows: live numbers, the hourly counts and sign-ups per day."""
    now = time.time()
    with stats_lk:
        hours = {h: dict(r) for h, r in stats.items()}
    with load_lk:
        watching = len([1 for t in viewers.values() if now - t < 180])
    users = jload(USERS, {}) if ACCOUNTS else {}
    by_day = {}
    for u in users.values():
        c = u.get("created") if isinstance(u, dict) else None
        if c:
            d = time.strftime("%Y-%m-%d", time.gmtime(c))
            by_day[d] = by_day.get(d, 0) + 1
    return {"now": int(now), "watching": watching, "max_viewers": MAX_VIEWERS or None,
            "accounts": len(users), "signups_by_day": by_day, "hours": hours}


def health():
    now = time.time()
    return {"ok": True, "warp_proxy": bool(YT_PROXY), "warp_rotate": WARP_ROTATE,
            "warp_rotations_30min": len([t for t in rot_hist if now - t < 1800]),
            "live_captions": bool(LIVECC_ENGINES), "live_caption_engines": list(LIVECC_ENGINES), "public": PUBLIC_MODE,
            "max_height": MAX_HEIGHT or None, "viewers": len([1 for t in list(viewers.values()) if now - t < 180]),
            "max_viewers": MAX_VIEWERS or None, "accounts": ACCOUNTS, "name": NAME or None, "links": LINKS}


viewers, rate, load_lk = {}, {}, threading.Lock()   # ip -> last seen / (tokens, time)


def viewer_admit(ip):
    """False when the server already has MAX_VIEWERS people watching and this is someone new."""
    if not MAX_VIEWERS:
        return True
    now = time.time()
    with load_lk:
        for k in [k for k, t in viewers.items() if now - t > 180]:
            del viewers[k]
        if ip not in viewers and len(viewers) >= MAX_VIEWERS:
            stat("turned_away")
            return False
        viewers[ip] = now
    stat("watchers", ip)
    return True


def rate_ok(ip):
    """Token bucket per IP: RATE_PER_MIN requests a minute, bursts up to the same number."""
    if not RATE_PER_MIN:
        return True
    now = time.time()
    with load_lk:
        tokens, last = rate.get(ip, (RATE_PER_MIN, now))
        tokens = min(RATE_PER_MIN, tokens + (now - last) * RATE_PER_MIN / 60)
        if len(rate) > 20000:
            # a full bucket after a quiet minute is the same as no entry, so only those go
            for k in [k for k, (_, t) in rate.items() if now - t > 60]:
                del rate[k]
            if len(rate) > 20000:
                rate.clear()
        if tokens < 1:
            rate[ip] = (tokens, now)
            return False
        rate[ip] = (tokens - 1, now)
        return True


def public_blocked(method, path):
    """On a public instance only the stateless parts of /api/p/yt/ stay open."""
    if not PUBLIC_MODE:
        return False
    if "/api/p/yt/" not in path and not path.endswith("/latest_version"):
        return False
    allowed = {("GET", "/yt/feed"), ("GET", "/yt/shorts/next"), ("GET", "/yt/topic"), ("GET", "/yt/heat"), ("GET", "/yt/dathumb"), ("POST", "/yt/import"), ("POST", "/yt/export")}
    return not any(method == m and path.endswith(suffix) for m, suffix in allowed)


yt_feed_at = {}              # cid -> last RSS fetch (public mode: feeds for any visitor's channels)
feed_busy = set()


def feed_for(chs):
    """Public mode: the visitor sends their channel list, the server only fetches the feeds."""
    from concurrent.futures import ThreadPoolExecutor
    chs = list(dict.fromkeys(c for c in chs if UCID.fullmatch(c)))[:300]
    now = time.time()
    with load_lk:      # a channel another visitor is already fetching isn't fetched twice
        stale = [c for c in chs if now - yt_feed_at.get(c, 0) > 900 and c not in feed_busy][:60]
        feed_busy.update(stale)
    if stale:
        try:
            with ThreadPoolExecutor(6) as ex:
                list(ex.map(yt_feed_one, stale))
            for c in stale:
                yt_feed_at[c] = time.time()
        finally:
            with load_lk:
                feed_busy.difference_update(stale)
    if len(yt_feed_at) > 5000:
        for c in sorted(yt_feed_at, key=yt_feed_at.get)[:1000]:
            yt_feed_at.pop(c, None)
            yt_feed["items"].pop(c, None)
    items = sorted((e for c in chs for e in yt_feed["items"].get(c, [])), key=lambda e: -e["published"])[:400]
    return {"items": items, "updated": now, "updating": any(c not in yt_feed_at for c in chs)}


# The personal parts (library, history, downloads, casting, sign-in, usage) answer only pages of
# TubeHaus' own address: another web site open in the same browser can neither read nor change
# them. The playback parts below stay open to every origin, like Invidious' API, because players
# on other origins (Invidious' own page for live streams) load them.
OPEN_PARTS = ("/api/p/health", "/api/p/live/", "/api/p/hls/", "/api/p/dash/", "/api/p/sub/", "/api/p/plog",
              "/api/p/yt/dathumb", "/api/p/yt/music/img", "/api/p/auth/check")


def personal(path):
    p = path.split("?")[0]
    return "/api/p/" in p and not any(o in p for o in OPEN_PARTS)


def _private_peer(ip):
    import ipaddress
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    a = getattr(a, "ipv4_mapped", None) or a
    return not a.is_global          # this machine, a home or Docker network, Tailscale's 100.64/10, ...


class H(BaseHTTPRequestHandler):
    # Invidious re-dialled TCP for every companion call under HTTP/1.0; 1.1 keeps
    # the socket. Every reply below either sends a real Content-Length or sets
    # close_connection, so the framing stays honest. Bodies are unchanged.
    protocol_version = "HTTP/1.1"
    # a connection that says nothing for this long is dropped, so idle or deliberately slow
    # clients can't sit on the request slots (kept-alive sockets wait for their next request the same way)
    timeout = 30

    def handle_one_request(self):
        # Handler exceptions used to escape into a socketserver traceback nobody reads.
        t0 = time.time()
        try:
            return BaseHTTPRequestHandler.handle_one_request(self)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as ex:
            self.close_connection = True
            log("http", "%s %s unhandled %s: %s" % (getattr(self, "command", "?"),
                str(getattr(self, "path", "?"))[:60], type(ex).__name__, str(ex)[:120]), t0, every=0)

    def send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if not personal(self.path):
            self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_OPTIONS(self):
        self.send_response(204)
        if personal(self.path):                # no permission for other web sites: the browser stops there
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")
        self.send_header("Content-Length", "0")
        self.end_headers()


    def qs(self):
        return {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}

    def client_ip(self):
        # a proxy adds the address it saw to the end of X-Forwarded-For; what comes before that is
        # whatever the visitor wrote, so it's read from the end, and only from a proxy we can trust
        peer = self.client_address[0]
        hops = [h.strip() for h in (self.headers.get("X-Forwarded-For") or "").split(",") if h.strip()]
        if not hops or not _private_peer(peer):
            return peer
        skip = PUBLIC_PROXIES
        for h in reversed(hops):
            if _private_peer(h):           # another proxy of ours (or a visitor on the home network)
                continue
            if skip:
                skip -= 1
                continue
            return h
        return hops[0]                     # all private: someone at home, as the first proxy saw them

    def needs_sign_in(self, p):
        """True, with a 401 sent, for a household library call from someone not signed in."""
        if not (ACCOUNTS and not PUBLIC_MODE) or _req.user or not personal(p) or "/api/p/auth/" in p or "/api/p/admin/" in p:
            return False
        self.send(401, {"error": "Please sign in"})
        return True

    def cross_site(self, p):
        """True, with a 403 sent, when another web site's page asks for a personal part.
        Browsers say where a request comes from (Sec-Fetch-Site, Origin) and pages can't change that."""
        if not personal(p):
            return False
        site = (self.headers.get("Sec-Fetch-Site") or "").lower()
        origin = self.headers.get("Origin")
        bad = site == "cross-site"
        if not bad and origin is not None and site != "same-origin":
            host = (self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or "").split(",")[0].strip().lower()
            bad = urllib.parse.urlsplit(origin).netloc.lower() != host
        if bad:
            log("http", "refused a request from another site to %s (%s)" % (p[:40], (origin or site)[:60]), every=60)
            self.close_connection = True
            self.send(403, {"error": "Not allowed from another web site"})
        return bad

    def over_limit(self, p):
        """429 when one visitor sends too much, 503 when the server is full of viewers."""
        if p.startswith("/companion") or p.endswith("/api/p/health") or re.match(r"/(vi|vi_webp|ggpht|sb)/", p):
            return False
        ip = self.client_ip()
        stat("visitors", ip)
        if not rate_ok(ip):
            self.send(429, {"error": "Too many requests. Please slow down a little"})
            return True
        if ("/api/v1/videos/" in p or "/live/" in p) and not viewer_admit(ip):
            self.send(503, {"error": "This server is busy right now. Please try again in a few minutes"})
            return True
        return False

    def cookie_user(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE:
                return session_user(v)
        return None

    def send_cookie(self, code, obj, value, max_age):
        # session tokens are user.expiry.generation.hex-signature; anything else is never sent
        value = value if re.fullmatch(r"[a-z0-9_]{3,24}\.\d+\.\d+\.[0-9a-f]{64}", value or "") else ""
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Set-Cookie", f"{COOKIE}={value}; Path=/; Max-Age={max_age}; HttpOnly; Secure; SameSite=Lax")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        _req.user = self.cookie_user() if ACCOUNTS else None
        p = self.path.split("?")[0].rstrip("/")
        if p.endswith("/api/p/auth/check"):         # asked by the reverse proxy for every request
            if not ACCOUNTS or self.cookie_user():
                stat("visitors", self.client_ip())
                self.send_response(204)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            return self.send(401, {"error": "Please sign in"})
        if self.cross_site(p) or self.needs_sign_in(p):
            return
        if p.endswith("/api/p/auth/me"):
            return self.send(200, {"accounts": ACCOUNTS, "user": self.cookie_user() if ACCOUNTS else None})
        if self.over_limit(p):
            return
        if p.endswith("/api/p/admin/stats"):
            # the key, not the account, opens it; after 10 wrong keys in 10 minutes nothing is checked
            if not (PUBLIC_MODE and ADMIN_TOKEN):
                return self.send(404, {"error": "not found"})
            ip, now = self.client_ip(), time.time()
            with auth_lk:
                fails = admin_fails[ip] = [t for t in admin_fails.get(ip, []) if now - t < 600]
            if len(fails) >= 10:
                return self.send(429, {"error": "Too many wrong keys. Please wait a few minutes"})
            if not hmac.compare_digest(self.headers.get("X-Admin-Token", "").encode(), ADMIN_TOKEN.encode()):
                with auth_lk:
                    admin_fails.setdefault(ip, []).append(now)
                return self.send(403, {"error": "Wrong key"})
            return self.send(200, admin_stats())
        # (a /latest_version without a title is the music player's audio, a redirect to the stream: open)
        if public_blocked("GET", p) and not (p.endswith("/latest_version") and not self.qs().get("title")):
            return self.send(403, {"error": "Not available on public instances"})
        if PUBLIC_MODE and p.endswith("/yt/feed"):
            return self.send(200, feed_for(self.qs().get("ch", "").split(",")))
        if p.endswith("/yt/party/wait"):
            return self.send(*party_wait(self.qs()))
        if "/yt/cast/" in p:
            if p.endswith("/cast/devices"):
                return self.send(200, cast_devices())
            if p.endswith("/cast/wait"):
                d = cast_wait(self.qs())
                return self.send(400 if d.get("error") else 200, d)
        if p.endswith("/yt/dl") or p.endswith("/yt/dl/file"):
            jid = self.qs().get("job", "")
            if p.endswith("/yt/dl"):
                return self.send(200, ytdl_status(jid))
            j = ytdl_jobs.get(jid)
            if not j or j["status"] != "done":
                return self.send(404, {"error": "Not ready yet"})
            self.send_response(200)
            self.send_header("Content-Type", "video/webm" if j["path"].endswith(".webm") else "audio/mp4" if j["path"].endswith(".m4a") else "video/mp4")
            self.send_header("Content-Length", str(j["size"]))
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(j["name"]))
            self.end_headers()
            try:
                with open(j["path"], "rb") as f:
                    while chunk := f.read(1 << 20):
                        self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True
            j["done_at"] = time.time()
            return
        if p.endswith("/yt/export"):
            name, ctype, data = ytlib_export(self.qs().get("format", ""))
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if p.endswith("/yt/shorts/next"):
            return self.send(200, shorts_next(self.qs()))
        if p.endswith("/yt/heat"):
            d = heat_page(self.qs())
            return self.send(400 if d.get("error") else 200, d)
        if p.endswith("/yt/dathumb"):
            code, ctype, body = dearrow_thumb(self.qs())
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "public, max-age=604800" if code == 200 else "public, max-age=600")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if "/yt/music/" in p and "/api/p/" in self.path:
            if p.endswith("/yt/music/img"):
                code, ctype, body = music_img(self.qs())
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Cache-Control", "public, max-age=604800" if code == 200 else "public, max-age=600")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            return self.send(*music_api(p, self.qs()))
        if p.endswith("/yt/topic"):
            d = topic_page(self.qs())
            return self.send(404 if d.get("error") == "unknown topic" else 502 if d.get("error") else 200, d)
        if "/yt/" in p and "/api/p/" in self.path:
            if p.endswith("/yt/lib"):
                return self.send(200, ytlib_get())
            d = ytlib_query(p, self.qs())
            return self.send(404 if d.get("error") else 200, d)
        if "/hls/" in p:
            return self.hls(p)
        if "/dash/" in p:          # /api/p/dash/<id>.mpd, or Invidious' /api/manifest/dash/id/<id>
            return self.dash(p if p.endswith(".mpd") else "/dash/%s.mpd" % p[-11:])
        if "/live/" in p:
            if p.endswith("/cc.json") and not LIVECC_ENGINES:
                return self.send(200, {"status": "off", "lag": None, "cues": {}})
            return self.live(p)
        if p.startswith("/cap/") or "/api/p/cap/" in self.path.split("?")[0]:
            vid = p.rsplit("/", 1)[-1].replace(".vtt", "")
            q = self.qs()
            try:
                data = cap_direct(vid, q.get("lang") or "en", q.get("auto") == "1")
            except Exception as ex:
                return self.send(502, {"error": str(ex)})
            self.send_response(200)
            self.send_header("Content-Type", "text/vtt; charset=utf-8")
            self.send_header("Cache-Control", "private, max-age=86400")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if "/sub/" in p:
            vid = p.rsplit("/", 1)[-1].replace(".vtt", "")
            if not re.fullmatch(r"[\w-]{11}", vid):
                return self.send(400, {"error": "Invalid video ID"})
            try:
                tl = self.qs().get("tl") or SUB_LANG     # the viewer's interface language
                if not re.fullmatch(r"[a-z]{2}(-[A-Za-z]{2,4})?", tl):
                    return self.send(400, {"error": "bad language"})
                data = sub_vtt(vid, tl)
            except Exception as ex:
                log("sub", "%s FAIL %s" % (vid, repr(ex)[:100]))
                return self.send(502, {"error": str(ex)})
            self.send_response(200)
            self.send_header("Content-Type", "text/vtt; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if p.endswith("/api/p/health"):
            return self.send(200, health())
        if p.endswith("/plog"):
            # the player is the only place that sees a playback failure; without this
            # beacon a video that dies mid-playback leaves no trace on the server
            log("player", " ".join("%s=%s" % (re.sub(r"[\x00-\x1f\x7f]", " ", k[:30]), re.sub(r"[\x00-\x1f\x7f]", " ", v[:120]))
                                     for k, v in sorted(self.qs().items())[:20]) or "?", every=0)   # no line breaks: one beacon, one log line
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if re.fullmatch(r"(/companion)?/api/v1/captions/[\w-]{11}", p):
            return self.captions_clean()
        if p in ("/companion/videoplayback", "/videoplayback"):
            return self.stream()
        if "/api/v1/videos/" in p:
            return self.videos_api()
        if p.endswith("/latest_version"):
            return self.download()
        if p.startswith("/api/v1/"):
            return self.send(*eng_api(p, self.qs()))
        if re.match(r"/(vi|vi_webp|ggpht|sb)/", p):
            return self.image()
        self.send(404, {"error": "not found"})

    def do_POST(self):
        _req.user = self.cookie_user() if ACCOUNTS else None
        p = self.path.split("?")[0].rstrip("/")
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        if n < 0 or n > MAX_BODY:        # never wait for, or keep, more than that in memory
            self.close_connection = True
            return self.send(413, {"error": "That is too much to send at once"})
        if self.cross_site(p) or self.needs_sign_in(p):
            self.close_connection = True
            return
        body_bytes = self.rfile.read(n)
        try:
            body = json.loads(body_bytes or b"{}")
        except Exception:
            body = {}
        body = body if isinstance(body, dict) else {}
        if ACCOUNTS and p.endswith("/api/p/auth/logout"):
            return self.send_cookie(200, {"ok": True}, "", 0)
        if ACCOUNTS and p.endswith("/api/p/auth/password"):
            code, d = auth_password(body, _req.user, self.client_ip())
            if code != 200:
                return self.send(code, d)
            gen = int(jload(USERS, {}).get(_req.user, {}).get("gen", 1))     # the one the change just made
            return self.send_cookie(200, d, session_make(_req.user, gen), SESSION_DAYS * 86400)
        if ACCOUNTS and (p.endswith("/api/p/auth/signup") or p.endswith("/api/p/auth/login")):
            fn = auth_signup if p.endswith("/signup") else auth_login
            code, d = fn(body, self.client_ip())
            if code != 200:
                return self.send(code, d)
            cookie = d.pop("cookie")
            return self.send_cookie(200, d, cookie, SESSION_DAYS * 86400)
        if self.over_limit(p):
            return
        if p.endswith("/api/p/stat/play"):           # the player started a video (public instances count it)
            stat("plays")
            return self.send(200, {"ok": True})
        if public_blocked("POST", p):
            return self.send(403, {"error": "Not available on public instances"})
        if p.endswith("/yt/export"):
            name, ctype, data = ytlib_export(str(body.get("format", "")), body.get("lib") if isinstance(body.get("lib"), dict) else {})
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if p.endswith("/yt/cast/send"):
            return self.send(*cast_send(body))
        if p.endswith("/yt/party/create"):
            return self.send(*party_create(body))
        if p.endswith("/yt/party/join"):
            return self.send(*party_join(body))
        if p.endswith("/yt/party/update"):
            return self.send(*party_update(body))
        if p.endswith("/yt/dl"):
            return self.send(*ytdl_start(body))
        if p.endswith("/yt/import"):
            return self.send(*ytlib_import(body))
        if p.endswith("/yt/shorts/sig"):
            return self.send(*shorts_signal(body))
        if p.endswith("/yt/shorts/reset"):
            return self.send(*shorts_reset())
        if p.endswith("/yt/tr"):                     # titles in the viewer's language (Settings -> Translate titles)
            return self.send(*titles_tr(body))
        if "/yt/" in p and "/api/p/" in self.path:
            return self.send(*ytlib_post(p, body))
        self.send(404, {"error": "not found"})


    def _pipe_resume(self, req, resp, code, hdrs, t0):
        """Copy an upstream body to the client, resuming with Range when the
        connection is cut short. Nothing is buffered, so length does not matter."""
        want = hdrs.get("Content-Length")
        want = int(want) if want and want.isdigit() else None
        self.send_response(code)
        for h in ("Content-Type", "Content-Range", "Accept-Ranges", "Content-Disposition"):
            if hdrs.get(h):
                self.send_header(h, hdrs[h])
        if want is not None:
            self.send_header("Content-Length", str(want))
        else:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        sent, resumes = 0, 0
        while True:
            try:
                chunk = resp.read(1 << 20)
            except Exception as ex:
                log("stream", "cut %s at %dB (%s)" % (
                    self.path.split("?")[0][:40], sent, repr(ex)[:50]), t0)
                chunk = b""
            if chunk:
                try:
                    self.wfile.write(chunk)
                except Exception:
                    return          # client went away mid-download
                sent += len(chunk)
                continue
            if want is None or sent >= want or resumes >= 8:
                break
            resumes += 1
            r2 = urllib.request.Request(req.full_url, method="GET")
            for k, v in req.header_items():
                if k.lower() != "range":
                    r2.add_header(k, v)
            r2.add_header("Range", "bytes=%d-" % sent)
            try:
                resp = YT.open(r2, timeout=120)
            except Exception as ex:
                log("stream", "resume FAIL %dB %s" % (sent, repr(ex)[:60]), t0)
                break
        if resumes:
            log("stream", "%dB with %d resume(s) %s" % (
                sent, resumes, self.path.split("?")[0][:40]), t0, every=0)
        return

    def captions_clean(self):
        # /companion/api/v1/captions/<id>: the list of tracks (JSON), or with ?label= (or ?lang=, and ?tlang= for
        # YouTube's machine translation) one track as plain WebVTT: YouTube's automatic captions have a timestamp
        # before every word, which Apple's player (the iPad/iPhone app) shows as text
        vid, q = self.path.split("?")[0].rstrip("/")[-11:], self.qs()
        try:
            if not q.get("label") and not q.get("lang"):
                return self.send(200, {"captions": eng_caption_list(vid)})
            raw = eng_caption(vid, q.get("label", ""), q.get("lang", ""), q.get("tlang", ""))
        except EngError as e:
            return self.send(e.code, {"error": str(e)})
        except Exception as ex:
            log("captions", "FAIL %s" % repr(ex)[:80], every=60)
            return self.send(502, {"error": "Couldn't get the captions"})
        body = _clean_vtt(raw.decode("utf-8", "replace")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/vtt; charset=utf-8")
        self.send_header("Cache-Control", "private, max-age=3600")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def stream(self):
        # /companion/videoplayback?<YouTube's query>&host=<its server>: a video or audio stream, through YT_PROXY
        # (a stream only plays from the address it was given to). Ranges pass through; a cut connection resumes.
        url = gv_url(self.path)
        if not url:
            return self.send(400, {"error": "Not a stream address"})
        h = {"User-Agent": VIS_UA, "Origin": "https://www.youtube.com", "Referer": "https://www.youtube.com/"}
        if self.headers.get("Range"):
            h["Range"] = self.headers["Range"]
        req = urllib.request.Request(url, headers=h)
        t0 = time.time()
        try:
            r = YT.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            log("stream", "%d %s" % (e.code, url.split("?")[0][:60]), t0)
            return self.send(e.code, {"error": "YouTube answered %d for the stream" % e.code})
        except Exception as ex:
            log("stream", "FAIL %s" % repr(ex)[:80], t0)
            return self.send(502, {"error": "No connection to YouTube"})
        return self._pipe_resume(req, r, r.status, r.headers, t0)

    def image(self):
        # /vi/<id>/<name>.jpg, /ggpht/..., /sb/i/...: pictures from YouTube's servers, so the viewer's browser talks only to this one
        code, ctype, body = eng_img(self.path)
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "public, max-age=86400" if code == 200 else "public, max-age=300")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def videos_api(self):
        # The engine's answer, with hlsUrl pointing at our own live proxy for live streams,
        # from the cache above when we can, because otherwise every click waits on YouTube.
        t0 = time.time()
        key = self.path
        with vid_lk:
            hit = vid_cache.get(key)
        if hit and time.time() - hit[0] < VID_STALE:
            age = time.time() - hit[0]
            code, body = hit[1], hit[2]
            if age > VID_FRESH:
                with vid_lk:
                    stale = key not in vid_busy
                    if stale:
                        vid_busy.add(key)
                if stale:
                    threading.Thread(target=vid_bg, args=(key,), name="vid-" + key[-11:], daemon=True).start()
            log("videos", "cache %ds %s" % (age, key.split("/")[-1][:20]), t0, every=300)
        else:
            code, body = vid_fetch_once(key)
            d = None
            try:
                if code == 200:
                    d = json.loads(body)
            except Exception as ex:
                log("videos", "parse FAIL %d %s" % (code, repr(ex)[:100]), t0)
            if VID_PREFETCH and isinstance(d, dict) and d.get("recommendedVideos"):
                threading.Thread(target=vid_bg, args=(None, d), name="vid-warm", daemon=True).start()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self):
        if "/live/" in self.path:
            self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def do_HEAD(self):
        # Shaka guesses the manifest type from a HEAD's Content-Type
        p = self.path.split("?")[0]
        self.send_response(200)
        self.send_header("Connection", "close")   # nothing to frame a HEAD reply with
        if "/live/" in p:
            self.send_header("Content-Type", "video/mp2t" if "/live/s/" in p else "application/vnd.apple.mpegurl")
        self.end_headers()

    def download(self):
        # Browsers fetch a download in one un-ranged request, which YouTube
        # throttles to a crawl for adaptive formats; pull it in 10 MB ranges instead.
        qs = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}
        vid, itag = qs.get("id", ""), qs.get("itag", "")
        if not re.fullmatch(r"[\w-]{11}", vid) or not itag.isdigit():
            return self.send(400, {"error": "bad id/itag"})
        t0 = time.time()
        try:
            url = eng_format_url(vid, itag)
            if not qs.get("title"):
                self.send_response(302)
                self.send_header("Location", _companion_url(url))
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            m = re.search(r"clen=(\d+)", url)
            size = int(m.group(1)) if m else None
        except Exception as ex:
            log("download", f"latest_version {vid}/{itag} FAIL 502 {repr(ex)[:120]}", t0, every=0)
            return self.send(502, {"error": f"Couldn't get the video URL: {ex}"})
        log("download", f"latest_version {vid}/{itag} ok clen={size}", t0, every=0)
        name = qs.get("title") or f"{vid}.{itag}"
        if size is None:
            # muxed formats (e.g. itag 18) carry no clen but are ratebypass: pass the single stream through
            try:
                up = gv_open(url, timeout=60)
            except Exception as ex:
                log("download", f"open {vid}/{itag} FAIL 502 {repr(ex)[:120]}", t0, every=0)
                return self.send(502, {"error": f"Download failed: {ex}"})
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            if up.headers.get("Content-Length"):
                self.send_header("Content-Length", up.headers["Content-Length"])
            else:
                self.send_header("Connection", "close")   # unknown length frames by close
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(name))
            self.end_headers()
            try:
                while chunk := up.read(1 << 16):
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True
            except Exception as ex:
                self.close_connection = True
                log("download", f"passthrough {vid}/{itag} FAIL {repr(ex)[:120]}", t0, every=0)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(name))
        self.end_headers()
        pos, step = 0, 10_000_000
        try:
            while pos < size:
                end = min(pos + step, size) - 1
                for attempt in range(3):
                    try:
                        chunk = gv_open(f"{url}&range={pos}-{end}", timeout=60).read()
                        break
                    except (BrokenPipeError, ConnectionResetError):
                        raise
                    except Exception:
                        if attempt == 2:
                            raise
                self.wfile.write(chunk)
                pos = end + 1
            log("download", f"sent {vid}/{itag} {size}B", t0, every=0)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as ex:
            self.close_connection = True
            log("download", f"stream {vid}/{itag} FAIL at {pos}/{size} {repr(ex)[:120]}", t0, every=0)
        if pos < size:
            self.close_connection = True   # short body: the socket must not be reused

    def dash(self, p):
        # /api/p/dash/<id>.mpd: every quality for the web player (dash_mpd)
        m = re.search(r"/dash/([\w-]{11})\.mpd$", p)
        if not m:
            return self.send(404, {"error": "not found"})
        t0 = time.time()
        try:
            text = dash_mpd(m.group(1))
        except Exception as ex:
            log("dash", "%s FAIL %s" % (m.group(1), repr(ex)[:120]), t0)
            return self.send(502, {"error": "Couldn't make the stream list: %s" % ex})
        b = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/dash+xml")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def hls(self, p):
        # /api/p/hls/<id>.m3u8 (the list of qualities), /api/p/hls/<id>/<itag>.m3u8 (one of them)
        m = re.search(r"/hls/([\w-]{11})(?:/(\d+(?:-[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*)?))?\.m3u8$", p)
        if not m:
            return self.send(404, {"error": "not found"})
        t0 = time.time()
        try:
            try:
                h = max(0, min(4320, int(self.qs().get("h") or 0)))
            except ValueError:
                h = 0
            text = hls_media(m.group(1), m.group(2)) if m.group(2) else hls_master(m.group(1), h)
        except Exception as ex:
            log("hls", "%s FAIL %s" % (p.rsplit("/hls/", 1)[-1][:24], repr(ex)[:120]), t0)
            return self.send(502, {"error": "Couldn't make the stream list: %s" % ex})
        b = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def live(self, p):
        qs = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        t0 = time.time()
        try:
            if "/live/s/" in p:                        # media segment
                b = p.rsplit("/", 1)[1].removesuffix(".ts")
                u = base64.urlsafe_b64decode(b + "=" * (-len(b) % 4)).decode()
                if not re.match(r"^https://[\w.-]+\.googlevideo\.com/videoplayback/", u):
                    return self.send(400, {"error": "bad url"})
                mk = re.search(r"/id/([\w-]{11})[^/]*/itag/(\d+)/.*?/sq/(\d+)/", u)
                pl = live_pull(mk.group(1), mk.group(2), create=False) if mk else None
                got = pl.segment(int(mk.group(3))) if pl else None
                if got:
                    self.send_response(200)
                    self.send_header("Content-Type", got[0])
                    self.send_header("Content-Length", str(len(got[1])))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(got[1])
                    return
                try:
                    r = YT.open(urllib.request.Request(u), timeout=30)
                except urllib.error.HTTPError as he:
                    m2 = re.search(r"/id/([\w-]{11})", u)
                    if he.code == 403 and m2 and m2.group(1) in LIVE_CACHE:
                        LIVE_CACHE[m2.group(1)]["at"] = 0
                        log("live", f"segment 403 {m2.group(1)} -> forcing re-resolve", t0, every=0)
                    else:
                        log("live", f"segment {he.code}", t0)
                    # rebuild the same segment from a fresh playlist instead of failing the player
                    r = live_segment_retry(u, he.code)
                    if r is None:
                        raise
                self.send_response(200)
                self.send_header("Content-Type", r.headers.get("Content-Type") or "video/mp2t")
                if r.headers.get("Content-Length"):
                    self.send_header("Content-Length", r.headers["Content-Length"])
                else:
                    self.send_header("Connection", "close")   # unknown length frames by close
                self.end_headers()
                while True:
                    chunk = r.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                return
            mc = re.search(r"/live/([\w-]{11})/cc\.json$", p)
            if mc:                                     # live captions
                q = {k: v[0] for k, v in qs.items()}
                try:
                    lo, hi = int(q.get("from", "0")), int(q.get("to", "0"))
                except ValueError:
                    return self.send(400, {"error": "bad range"})
                c = live_cc(mc.group(1), q.get("engine"))
                return self.send(200, {"status": c.status, "lag": c.lag, "cues": c.get(lo, min(hi, lo + 40))})
            m = re.search(r"/live/([\w-]{11})(?:\.m3u8)?(?:/(\d+)(?:\.m3u8)?)?$", p)
            if not m:
                return self.send(400, {"error": "video id"})
            vid, itag = m.group(1), m.group(2)
            e = live_entry(vid)
            if itag is None:                           # master playlist
                lines = ["#EXTM3U", "#EXT-X-INDEPENDENT-SEGMENTS"]
                for t, (inf, _) in sorted(e["variants"].items(), key=lambda kv: int(kv[0])):
                    inf = re.sub(r',SUBTITLES="[^"]*"', "", inf)
                    lines += [inf, f"{LIVE_ORIGIN}/api/p/live/{vid}/{t}.m3u8"]
                body = ("\n".join(lines) + "\n").encode()
            else:                                      # media playlist
                pl = live_pull(vid, itag) if itag in e["variants"] else None
                text = pl.playlist() if pl else None
                win = pl.window() if text else None
                if win:
                    body = win.encode()
                elif text:
                    body = live_window(text).encode()
                else:
                  try:
                    text = YT.open(e["variants"][itag][1], timeout=20).read().decode()
                  except Exception as ex:
                    log("live", f"playlist {vid}/{itag} stale ({repr(ex)[:80]}) -> re-resolve", t0, every=0)
                    e = live_entry(vid, force=True)
                    text = YT.open(e["variants"][itag][1], timeout=20).read().decode()
                  body = live_window(text).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as ex:
            log("live", f"502 {p[:60]} {type(ex).__name__}: {str(ex)[:120]}", t0, every=0)
            self.close_connection = True   # headers may already be out
            try:
                self.send(502, {"error": str(ex)[:300]})
            except Exception:
                pass

    def log_message(self, *a):
        pass


class Server(ThreadingHTTPServer):
    """At most MAX_CONN requests at once; beyond that answer 503 right away instead of piling up threads."""
    daemon_threads = True
    slots = threading.BoundedSemaphore(MAX_CONN) if MAX_CONN else None
    BUSY = b'{"error": "This server is busy right now. Try again soon"}'

    def process_request(self, request, client_address):
        if self.slots and not self.slots.acquire(timeout=0.2):   # short: never stall the accept loop
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n"
                                b"Retry-After: 10\r\nConnection: close\r\nContent-Length: %d\r\n\r\n" % len(self.BUSY) + self.BUSY)
            except OSError:
                pass
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            if self.slots:
                self.slots.release()


if __name__ == "__main__":
    import sys
    if "--doctor" in sys.argv:
        sys.exit(1 if doctor() else 0)
    os.makedirs(STATE, exist_ok=True)
    threading.Thread(target=yt_feed_loop, daemon=True).start()
    threading.Thread(target=ytdl_gc_loop, daemon=True).start()
    threading.Thread(target=stats_loop, daemon=True).start()
    log("start", "TubeHaus server on %s:%d, built-in YouTube engine%s" % (BIND[0], BIND[1], ", through " + YT_PROXY if YT_PROXY else ""), every=0)
    Server(BIND, H).serve_forever()
