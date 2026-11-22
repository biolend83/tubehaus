# How TubeHaus plays YouTube

*A tour of the engine, written like a blog post: what YouTube does to third-party players, and what TubeHaus does about it. [한국어](HOW-IT-WORKS.ko.md)*

---

## Contents

1. [The problem: YouTube is an app API, not a website](#1-the-problem-youtube-is-an-app-api-not-a-website)
2. [InnerTube, and why the client matters](#2-innertube-and-why-the-client-matters)
3. [The fallback chain](#3-the-fallback-chain)
4. [Videos made for kids: YouTube Kids and the TV client](#4-videos-made-for-kids-youtube-kids-and-the-tv-client)
5. [Locked links: signatures and the n parameter](#5-locked-links-signatures-and-the-n-parameter)
6. [The ad that holds the links back](#6-the-ad-that-holds-the-links-back)
7. [SABR: speaking youtube.com's own streaming protocol](#7-sabr-speaking-youtubecoms-own-streaming-protocol)
8. [PO tokens and BotGuard, without an account](#8-po-tokens-and-botguard-without-an-account)
9. [Turning SABR back into a file](#9-turning-sabr-back-into-a-file)
10. [SABR in the apps: a hidden browser page](#10-sabr-in-the-apps-a-hidden-browser-page)
11. [WARP: an address YouTube will talk to](#11-warp-an-address-youtube-will-talk-to)
12. [Making it fast](#12-making-it-fast)
13. [Captions: translated in seconds, written live](#13-captions-translated-in-seconds-written-live)
14. [The players](#14-the-players)
15. [One file from two streams, without ffmpeg](#15-one-file-from-two-streams-without-ffmpeg)
16. [Casting without Google's SDK](#16-casting-without-googles-sdk)
17. [Recommendations that never leave the device](#17-recommendations-that-never-leave-the-device)
18. [Knowing when it breaks](#18-knowing-when-it-breaks)

---

## 1. The problem: YouTube is an app API, not a website

When you open a video on youtube.com, the page doesn't contain the video. It contains a JavaScript player that asks a private API, **InnerTube**, for a *player response*: a JSON document with the title, the captions, the chapters and, most importantly, the **streaming data**, which is the list of formats (each resolution and codec of the picture, each language and bitrate of the sound) and how to get them.

Every official YouTube app (Android, iOS, TV, the Kids app, the VR app) asks the same API, saying who it is. YouTube answers each one differently. That's the whole game: a third-party player is only as good as the answers it can get, and YouTube keeps changing which clients get which answers.

TubeHaus never scrapes HTML for streams and never uses an account. It asks InnerTube the way the official apps do, and has several ways to ask.

## 2. InnerTube, and why the client matters

An InnerTube request is a POST to `www.youtube.com/youtubei/v1/player` (or `/search`, `/browse`, `/next`) with a `context.client` block: client name, version, device, OS, language and country. Examples:

| Client | What it gets today |
|---|---|
| `WEB` | Lists (search, channels, playlists, comments) work well. Streams come through SABR (below), with a PO token required |
| `VISIONOS` (Apple Vision Pro) | **Plain stream addresses, no PO token needed.** The fastest way to a playable video, and TubeHaus' first choice |
| `ANDROID_VR` (Quest) | Plain addresses too, a close second |
| `ANDROID_KIDS` | Videos *made for kids*, which the others refuse |
| `TVHTML5` (a smart TV) | Nearly everything, but the addresses are **locked** and held behind an ad |

TubeHaus keeps a small table of these clients (`clients.json` in the apps, `ENG_*` in the server) with exact versions and user agents. Every known client was tried against live YouTube in November 2026: most were dead or half-working, and VISIONOS was the only clean one. The table is the result.

A **visitor ID** goes with each request, like a browser's anonymous cookie. When YouTube walls one off ("Sign in to confirm you're not a bot"), TubeHaus takes a new one and asks again before trying anything heavier.

## 3. The fallback chain

No single client plays everything, so a video goes down a chain until one way works:

```
visionOS ──refused──► (new visitor ID) ──► YouTube Kids ──► SABR ──► TV client
  ~0.2 s                                     ~1 s           2-3 s     3-8 s
```

- A refusal that says *"not available"* goes to the next way at once.
- A refusal that says *"sign in"* means the visitor ID or the address is walled, so those are changed first.
- Age-restricted videos stop early, since none of these clients can pass an age check without an account.
- Live streams skip SABR, because they have their own HLS manifest.

On the server, `HT_SABR` reorders the chain (`first` puts SABR before the others, `only` uses it for everything but live and age-restricted videos). In the apps, the player's **Playback method** setting pins one way, which helps when a video only plays one way.

## 4. Videos made for kids: YouTube Kids and the TV client

A video marked *made for kids* (BabyBus, CoComelon…) comes back "This video is not available" from VISIONOS and ANDROID_VR. YouTube wants it played in the Kids app. So TubeHaus asks as the Kids app (`ANDROID_KIDS`), which usually just works.

When it doesn't, the TV client always does. The catch is that the TV client's links are locked (next section) and come with an ad.

## 5. Locked links: signatures and the n parameter

The TV client returns `signatureCipher` instead of `url`: a scrambled signature that must be unscrambled by a function hidden in YouTube's current player JavaScript (`base.js`). Every address also carries an `n` parameter that must be transformed by another hidden function. Without it, YouTube throttles the download to a crawl or refuses it.

Older projects found those functions with regular expressions. That stopped working on current players. TubeHaus uses the **EJS solver from yt-dlp** (Unlicense), which parses the player script properly and runs the functions:

- **Server:** `server/ejs/solve.js` under deno or node. The preprocessed player is cached on disk, so a new player version is parsed once.
- **Android:** the same solver in a hidden WebView (`WebSolver.kt`).
- **iPad:** embedded in the engine script and run in JavaScriptCore.

The *signature timestamp* of that player version goes into the TV request (`playbackContext.contentPlaybackContext.signatureTimestamp`), so YouTube scrambles the links for the player TubeHaus can unscramble.

## 6. The ad that holds the links back

The TV client's answer includes `adPlacements`, typically a start ad with a skip offset. Until roughly 80% of that offset has passed, googlevideo answers **403** to the video's links, because a real TV would be showing the ad meanwhile.

The first version simply waited out the offset (up to 8 s). Now TubeHaus asks for a single byte (`&range=0-0`) of both the picture and the sound every half second, and hands the links to the player the moment both answer. That's about 4 s on average instead of up to 8.

If the player still gets a 403 later (on a phone that moved from Wi-Fi to mobile data, say), the Android app asks for fresh links **once** and then shows the error. In 1.7.0 it asked forever, which looked like a video flicking between play and stop ([#101](https://github.com/biolend83/tubehaus/issues/101)).

## 7. SABR: speaking youtube.com's own streaming protocol

The clients above are side doors, and YouTube closes them one by one. The front door, the one youtube.com uses for almost every video, is **SABR** (*Server-side Adaptive Bit Rate*). YouTube can't close it without breaking its own website.

With SABR, the player gets no address per format. It gets **one** `serverAbrStreamingUrl` and has a conversation with it:

```
player ── POST (protobuf VideoPlaybackAbrRequest) ─────────────────────► googlevideo
          "I'm at 1:23.4, I have 40 s of 1080p + Opus buffered,
           my bandwidth is about 25 Mbit/s, here is my PO token"

player ◄── UMP stream ─────────────────────────────────────────────────── googlevideo
          MEDIA_HEADER (format, byte range in the file, time range)
          MEDIA (the bytes)  MEDIA_END
          NEXT_REQUEST_POLICY ("wait 2 s"), SABR_REDIRECT ("use this host"),
          FORMAT_INITIALIZATION_METADATA, STREAM_PROTECTION_STATUS, ...
```

**UMP** (*Universal Media Protocol*) is a simple framing: a varint part type, a varint size, then the payload, back to back. TubeHaus uses the protobuf definitions and UMP reader from [googlevideo](https://github.com/LuanRT/googlevideo) (MIT), and keeps the playback state the request needs (player time, buffered ranges, selected formats, the cookie-like `playbackCookie` YouTube sends back) the way a real player does.

The player response for SABR comes from the **watch page** itself (`ytInitialPlayerResponse`), because the WEB client's answer must match the current web player version. Its `serverAbrStreamingUrl` also carries an `n` parameter, solved with the same EJS solver.

## 8. PO tokens and BotGuard, without an account

SABR only keeps streaming to a player that proves it is a real browser, with a **PO token** (*proof of origin*). Without one, it stops after about a minute.

A PO token comes from **BotGuard**, Google's obfuscated anti-bot VM:

1. Load the BotGuard *challenge*. TubeHaus takes it from the YouTube home page's own bootstrap (`ytAtN(...)`), the same place youtube.com gets it.
2. Run the challenge's interpreter script and take a *snapshot*: BotGuard looks at its environment and produces an opaque blob.
3. Send that blob to YouTube's **GenerateIT** endpoint (through youtube.com's own `/api/jnn/v1`), which returns an *integrity token*.
4. With the integrity token, **mint** a PO token bound to the **video ID**.

The binding matters. Tokens bound to the visitor ID or to a session were accepted at first and then turned down after about a minute. Tokens bound to the video ID keep streaming to the end. YouTube reports its verdict in-band as `STREAM_PROTECTION_STATUS`, and TubeHaus watches it:

- On the **server**, BotGuard runs in deno with jsdom. Roughly half of the minters it creates turn out bad, so a first status of 1 (good) keeps the minter and 2 (bad) replaces it at once.
- In a **real browser**, the first status is 2 even for good tokens, so only three 3s in a row (*attestation failed*) count as bad.

No cookies, no Google account, no headless browser on the server.

## 9. Turning SABR back into a file

Players like ExoPlayer, mpv and AVPlayer expect a file they can read by byte range. SABR delivers *pieces*. So TubeHaus rebuilds the file:

- Every `MEDIA_HEADER` says where its bytes belong in YouTube's original file (`segmentByteRangeStart`) and which time range they cover.
- The bytes are written **at that offset** into a **sparse file**: only the pieces received take disk space, so a 2-hour 4K video costs nothing until it's watched.
- A sorted list of the ranges already on disk, plus the `(byte, ms)` points seen, is kept per format.
- A small HTTP server on `127.0.0.1` answers the player's `Range:` requests from that file.
- When a requested byte isn't there yet, it estimates the time of that byte from the known points and **starts a new SABR conversation at that time**. A seek to 70% starts at 70% instead of downloading everything before it (0.77 s in tests).
- It reads at most 24 MB past what the player asked for, then pauses, and the file is dropped after 20 idle minutes.

To the player it's an ordinary file. Seeking, quality switching, audio tracks and captions all work unchanged. The server serves the same files to the web app as `/companion/sabr?v=&itag=`, behind the same DASH manifest as every other video.

## 10. SABR in the apps: a hidden browser page

The apps can't run deno. They run SABR in a **hidden browser page whose origin is `https://www.youtube.com`**, so BotGuard sees what it expects:

| | Hidden page | Local file server |
|---|---|---|
| **Android** | a WebView, `loadDataWithBaseURL("https://www.youtube.com/tubehaus-sabr", …)` with a desktop Chrome user agent | Kotlin, `common/jvm/Sabr.kt` |
| **Desktop** | Edge or Chrome, headless, driven over the DevTools protocol, the page served at a youtube.com URL with `Fetch.fulfillRequest` | the same Kotlin |
| **iPad / iPhone** | a 1-point WKWebView, `loadHTMLString(…, baseURL: youtube.com)` | Kotlin/Native over POSIX sockets, serving **HLS** (AVPlayer only plays HLS) built from each format's index |

The page (`sabr-page.js`, about 180 KB, bundled with esbuild) does the BotGuard and SABR work and posts each piece (format, offset, bytes) to the native side, which keeps the sparse file and serves the player.

Things that cost a day each to find:

- The EJS solver must run in a **Web Worker**. Inside the page, the player script navigates the page away.
- BotGuard's interpreter must load through a `<script src>`, because `fetch` is refused by CORS.
- `window.yt.config_` must hold the home page's `ytcfg`, or every token is turned down.
- The SABR request body goes as `text/plain`, so no forbidden headers are needed.
- `--remote-debugging-port=0` (a random port) makes YouTube turn every token down. TubeHaus picks a free port itself.
- On Android, `InetAddress.getLoopbackAddress()` is `::1`, so the server binds `127.0.0.1` explicitly.
- Newer Edge hands off to another process at start, so the app waits for the DevTools port instead of watching the process.

The desktop keeps the hidden browser warm while YouTube Kids is being asked, so if SABR is needed it's ready. That turns 12.8 s into 5.8 s for a cold start and 1.6 s for the next video.

## 11. WARP: an address YouTube will talk to

Cloud servers' addresses are the first ones YouTube asks to "sign in to confirm you're not a bot". The server goes out through **Cloudflare WARP in proxy mode**. `warp-cli` listens on `127.0.0.1:40000`, only YouTube traffic goes through it, and the rest of the server's routing is untouched.

When YouTube flags an address (a "sign in" wall on InnerTube, or Google's **sorry** page on the watch page that SABR reads), the server rotates:

1. `warp-cli disconnect` then `connect`, and wait until traffic flows.
2. If the exit address didn't change (it often doesn't), delete the registration and **register again**, which gets a new address. Then put back proxy mode and the port.
3. Replay the request.

Requests arriving during a rotation **wait** on the rotation's lock and then retry, instead of failing or starting a second rotation. Rotations are rate-limited (a minimum gap, and a cap per 30 minutes) so a YouTube-wide outage doesn't churn registrations. A rotation takes about 2 s.

## 12. Making it fast

A video page used to take 1.3 s through Invidious. Now the video starts in **0.16 s**:

- **Fast answer first.** The player needs only the streams. The title, description and related videos come from a second call and fill in a moment later.
- **One request, not several.** Identical requests in flight share one call to YouTube (`api_busy` events), and translated captions do the same.
- **Stale while refreshing.** Answers are cached for a time per kind (search 15 min, channels 10 min, suggestions 1 h, …) and served up to 6 hours stale while a background refresh runs.
- **Prefetch.** Hovering a video, or a list of search results appearing, fetches the first few player answers. A click usually lands on something already fetched. With `HT_SABR=only` it prefetches just the next video, since each one costs a watch page.
- **Kept-open connections.** Stream pieces reuse pooled connections to googlevideo (a new TLS connection costs 0.17 s), and InnerTube calls are compressed.
- **Comments early.** A video's comments are fetched when it opens, so they're there when you scroll (0.002 s).

## 13. Captions: translated in seconds, written live

**Translated captions.** YouTube's own machine translation (`&tlang=`) often comes back empty or 429 for long videos. So the server splits the caption into chunks and translates **six at a time**. The player asks with `?part=1` and gets what's done so far: the original lines first, swapped for the translation as each chunk lands, with an `X-Sub-Done` header saying when it's complete. A 1-hour video went from 93 s to about 10 s, with the first lines in 0.3 s.

**Live captions.** YouTube has none for most live streams. The server pulls the stream's audio a few seconds ahead of the viewer and runs **whisper.cpp** (`ggml-base-q5_1`, one CPU core) on it, then serves the text as a WebVTT track in time with the picture.

## 14. The players

| Where | Player | Notes |
|---|---|---|
| Web app | [Shaka Player](https://github.com/shaka-project/shaka-player) over a DASH manifest the server writes | storyboards, chapters, the "most replayed" wave |
| Android, Android TV | ExoPlayer (Media3) | hardware codec first (AV1 → VP9 → H.264 by what the device decodes in hardware), 60 fps first, an 8K-sized buffer |
| Windows, macOS, Linux | **mpv** (libmpv), GPU decoding | the same DASH manifest, so quality switching is seamless |
| iPad, iPhone, Apple TV | AVPlayer | HLS built from each format's `sidx` index, up to 4K |

Nothing is ever transcoded. The device decodes what YouTube sends, in a format it can decode.

## 15. One file from two streams, without ffmpeg

Above 720p YouTube sends picture and sound separately. The desktop app's **Save as one file** joins them without ffmpeg (`Mux.kt`): it reads both containers' boxes (fragmented MP4) or elements (WebM/Matroska), writes one container with both tracks, and copies the samples as they are. Nothing is re-encoded, so it takes seconds.

## 16. Casting without Google's SDK

The apps cast to Chromecast and Google TV and AirPlay to Apple TV without Google's or Apple's SDKs:

- **Google Cast:** mDNS discovery, then the Cast v2 protocol (protobuf `CastMessage` frames over TLS on port 8009) to launch the Default Media Receiver and load the stream.
- **AirPlay:** discovery over mDNS and playback over AirPlay's HTTP API.

That code became its own library, [Castwire](https://github.com/biolend83/castwire).

## 17. Recommendations that never leave the device

The Home feed in the apps (and Shorts on the server) is learned locally. Each video you watch adds to a score per channel and per *word* of its title and keywords. The score is weighted by how much of the video you watched, whether you looped, liked, disliked, hid or skipped it, and the time of day. Every fourth Short is something new, so the feed doesn't close in on itself. The scores are a small file on the device or your server. Nothing is sent anywhere, and you can turn learning off or reset it.

## 18. Knowing when it breaks

YouTube changes things without notice, so every day a [GitHub Actions check](../.github/workflows/engine-check.yml) runs each engine call against live YouTube through a fresh WARP address. If one breaks, it opens an issue. It also runs a repair tool that probes the known clients and versions (from the app, yt-dlp and YouTube.js) and proposes an updated `clients.json`, so a fix usually ships before most people notice.

On your own server, `--doctor` runs the same checks one line each: WARP, YouTube, a search, a real video and a few bytes of its stream.

---

### Credits

TubeHaus stands on others' work: [yt-dlp](https://github.com/yt-dlp/yt-dlp) (the EJS solver), [googlevideo](https://github.com/LuanRT/googlevideo) and [BgUtils](https://github.com/LuanRT/BgUtils) (SABR, UMP and BotGuard), [YouTube.js](https://github.com/LuanRT/YouTube.js), [Invidious](https://invidious.io) and invidious-companion, [whisper.cpp](https://github.com/ggml-org/whisper.cpp), Shaka Player, ExoPlayer and mpv. Thank you.
