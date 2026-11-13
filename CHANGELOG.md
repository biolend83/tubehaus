# Changelog

## Unreleased (1.6.0)

TubeHaus' server now talks to YouTube itself. Invidious, invidious-companion and PostgreSQL are no longer part of it: three fewer containers to run and keep up to date, and an install that is just the TubeHaus server, Caddy and (for live streams) the PO token provider.

### Changed
- **A built-in YouTube engine** in the server, in place of Invidious and its companion. It asks YouTube the way the TubeHaus apps do (lists from the web client, streams from the visionOS client, the exact upload time from the web player) and answers in Invidious' API format, so the web app, the apps' server mode and other tools that speak that API keep working. Video streams, captions and pictures come through the server as before
- **Updating:** after `git pull`, `docker compose up -d --remove-orphans` stops the old Invidious, companion and PostgreSQL containers; their volumes and the `COMPANION_KEY`, `INVIDIOUS_HMAC_KEY`, `POSTGRES_PASSWORD`, `TUBEHAUS_DOMAIN`, `TUBEHAUS_HTTPS` and `TUBEHAUS_EXTERNAL_PORT` settings can go (see [Updating](docs/UPGRADING.md)). The store packages (Umbrel, CasaOS, Unraid) are two containers now
- New settings: `HT_REGION` (the default region of searches and trending) and `HT_HL` (YouTube's language for its texts)
- `--doctor` checks a search, a video lookup and a few bytes of its stream instead of Invidious

### Removed
- "Import subscriptions from your Invidious account" on the subscriptions page (there is no Invidious account any more; Invidious export files still import)

## v1.5.1 — 2026-11-13

### Added
- **Importing from FreeTube and Google Takeout**: FreeTube's playlists and history files (.db) and Takeout's watch-history.html and playlist CSVs (one per playlist; Liked videos and Watch later go to your likes and Watch later) now import, and you can pick several files at once
- **Samsung (Tizen) and LG (webOS) TV apps** (experimental, [`tv-apps/`](tv-apps/)): they remember your server's address and open it; the TV mode now handles these TVs' Back and play / pause / fast-forward / rewind keys, and Back on the first screen closes the app

### Fixed
- **Shorts on Home showing grey placeholders**: when YouTube has no tall thumbnail for a Short it sends an empty image instead of an error; that now falls back to the normal thumbnail too
- **The "what's new" card on Home** introduced 1.0 in 1.5.0; it now shows 1.5 (new design, watch together, video at a glance), also for people who closed the old one

## v1.5.0 — 2026-11-12

TubeHaus 1.5: a new look, watch together, and the gist of a video before you watch it. Captions keep working when the caption service is rate-limited, and TubeHaus is ready for the self-hosting app stores. The apps go to 1.5.0 too (the same look, a new Music home, nearby devices, kids mode; see their own release).

### Added
- **The TubeHaus 1.5 design** (Settings → Design, the new default; Glass themes keep the classic one): calmer colours, and a Home that greets you and puts **Continue watching** (videos you stopped part-way) at the top. "Classic" brings back the old look
- **Watch together** (the people button on the watch page): start a room and share its link or code. Everyone in the room stays on the same moment (play, pause, seek and the next video follow the room), with a chat and reactions that float over the video. Not on public instances
- **Video at a glance** (Settings → "Video at a glance", on by default): under a video, the words it keeps coming back to, its key moments and a search for anything said in it, made in your browser from the captions. Videos without chapters get chapters on the seek bar
- **Store images:** `ghcr.io/biolend83/tubehaus-web` (the web app with its router) next to `ghcr.io/biolend83/tubehaus`, both for amd64 and arm64, and [`deploy/docker-compose.home.yml`](deploy/docker-compose.home.yml), a stack for a home server with one port and no extra permissions. `HT_WARP=0` runs the server without Cloudflare WARP (`HT_PROXY` for your own proxy). Packages for Umbrel, CasaOS and Unraid are in [`packaging/`](packaging/)

### Fixed
- **Captions when YouTube rate-limits the caption service** ("429"): the server fetches the caption file itself (directly, then through WARP with a fresh address) and keeps it for a week, for the player, the transcript and translated captions

## v1.0.6 — 2026-11-11

Titles in your language, switches to hide the Music tab and the logo, and room for a server's own additions (caption tracks, more live-caption engines). The apps go to 1.0.6 too (crash fixes, an AirPlay button that matches the others, AppImage and Flatpak; see their own release).

### Added
- **Titles in your language** (Settings → Look → Translate titles, off by default): video titles written in another script are shown in the interface language, on cards, the watch page and the mini player; the original stays in the tooltip. The titles go from your server to the translator the captions already use (Google) and the answers are kept on the server, so each title is asked once. Not on public instances. For a language in Latin letters, titles in other scripts (Korean, Japanese, Russian, ...) are translated; a title in another Latin-script language can't be told apart and stays as it is
- **Hide the Music tab** and **Hide the logo** in Settings → Look, for people who want less on the screen
- Live captions from more than one engine: `HT_LIVECC_ALT="name=/path/to/cli"` adds engines that take whisper-cli's arguments and print its lines, and the player lists one live-caption track per engine (`live_caption_engines` in `/api/p/health`, `?engine=` on `cc.json`)
- A server's own web add-on: if `web/y/local.js` exists, the web app loads it (it is in .gitignore, so updates leave it alone). It can add caption tracks to the player (`window.tubehaus.captionSources`), for example captions the server makes itself for videos without any

## v1.0.5 — 2026-11-11

Captions through the server are clean and complete again, and the installed web app gets back and forward buttons. The apps go to 1.0.5 today too (a video queue and new switches; see their own release).

### Fixed
- **Captions through the server** ([#53](https://github.com/biolend83/tubehaus/pull/53), by [Neatly9509](https://github.com/Neatly9509)): in the iPhone/iPad app playing through a server, automatic captions showed YouTube's word timings as text (`Now<00:03:09.217><c> is</c>`). Caption files now go through the server, which takes the timings out and shows each rolling line once; the web player gets the same clean captions
- **Lines after a pause were lost or a second late** in automatic captions (274 of 1091 lines on a one-hour video), and in translated captions too: a line holding only a space no longer ends the cue

### Added
- **Back and forward** at the top of the side menu when TubeHaus is installed as an app (where there is no browser bar); dimmed when there is nowhere to go

### Changed
- **Your own Caddy or nginx:** add the `/companion/api/v1/captions/` block from [`deploy/Caddyfile.example`](deploy/Caddyfile.example) or [`deploy/nginx.conf.example`](deploy/nginx.conf.example) to get the caption fix in the apps. Docker Compose setups get it with the usual `git pull` and `docker compose up -d`
- The daily engine check takes a new Cloudflare WARP address and runs again before it reports "Sign in to confirm you're not a bot", which is about the address, not the engine

## v1.0.4 — 2026-11-10

A library for each person in the household, password change, two glass themes and a prefetch setting, from the forks. The apps stay at 1.0.3 (nothing in them changed).

From Vildor Pierre's fork ([micronlogivdev/tubehaus](https://github.com/micronlogivdev/tubehaus)), written into this code:

### Added
- **A library for each person:** with `TUBEHAUS_AUTH=accounts` (or `HT_ACCOUNTS=1` on a server that isn't public), everyone signs up with a name and password and gets their own subscriptions, history, playlists, resume points and Shorts recommendations, and sees only their own TVs to cast to. The first account keeps the library that was there before. `TUBEHAUS_MAX_ACCOUNTS` closes sign-ups once the household has its accounts. The server itself turns away library calls from anyone not signed in
- **Change password** in Settings (with accounts): every other device is signed out
- **Glass themes:** "Glass" and "Light glass" in Settings → Theme, frosted panels over a soft blurred backdrop. Not on TVs; lighter for people who asked their system for less transparency or motion
- `HT_VID_PREFETCH` (`TUBEHAUS_PREFETCH`): how many of the videos you're likely to click next are looked up ahead (4, as before; 0 sends YouTube only what you ask for)

### Changed
- The theme choice in Settings wraps under its title when it doesn't fit on one line

### Security
- Account folders are normalised and checked to stay inside `STATE/users`, and a new session after a password change is made apart from the passwords (GitHub code scanning alerts on the unreleased per-person libraries; none was reachable)

## v1.0.3 — 2026-11-09

Security fixes for personal servers, a guide to opening TubeHaus only to your household, and a daily check of the apps' YouTube engine. The apps 1.0.3 work with screen readers.

### Security
- Other web sites can no longer read or change a personal server's library: requests for the library, downloads, casting and sign-in that a browser marks as coming from another site are refused, and those parts no longer give other sites CORS permission. Streams, captions and thumbnails stay open to every origin
- `X-Forwarded-For` is only believed from a proxy on this machine or a private network, and read from the end, so a visitor can't get around the rate and sign-in limits by writing another address in it (`HT_PUBLIC_PROXIES` for public-address proxies in front)
- Request bodies over `HT_MAX_BODY_MB` (64 MB) are refused before they're read; a negative `Content-Length` no longer holds a connection open
- A flood of made-up user names or addresses can no longer reset the sign-in and rate counts of everyone else
- Player beacons can't write extra lines into the server log

### Added
- [Opening TubeHaus to your household](docs/SELF-HOSTING.md): home network only, Tailscale or WireGuard, or the internet with a login
- [SECURITY.md](SECURITY.md) now says what the server protects against and what it doesn't
- A daily engine check runs every call of the apps' YouTube engine against live YouTube and opens an issue when one breaks

## v1.0.2 — 2026-11-08

Choose the audio track and captions when you save a video, transcripts in any language and as files, and more search filters. The apps 1.0.2 have the same.

### Added
- Save offline: choose the audio track (original, a dubbed language or audio description) for the video or for the sound alone, and put a caption track into the saved video as a subtitle stream (MP4 or WebM, language tagged). If the captions can't be fetched, the video is saved without them and you're told

From Vildor Pierre's fork ([micronlogivdev/tubehaus](https://github.com/micronlogivdev/tubehaus)), written into this code:

### Added
- Transcript: pick any caption language in the panel, and download the transcript as text, text with timestamps, SRT or WebVTT. Automatic captions no longer repeat each line
- Search filters: "Fewest views" and "Oldest first", and a view count range (minimum, maximum). They sort and filter the results loaded so far, with a button to load more; the filter panel stays open after a pick

### Fixed
- The web player's video is made in the page itself: one made in a template could play sound with a black picture on Apple WebKit
- Shorts cards without a vertical thumbnail are zoomed past the 4:3 thumbnail's black bars

## v1.0.1 — 2026-11-08

Every quality up to 8K in the web player, audio tracks with the original first, a buffer slider, and your instance's own name.

### Added
- `GET /api/p/dash/<id>.mpd`: the server's own DASH list of qualities for the web player, with VP9 (WebM) as well as H.264 and AV1, and 8K (YouTube often has it only in HDR, which is kept where there is no SDR copy)
- Settings → Preferred quality: 8K (4320p)
- Settings → Buffer ahead: a slider from Auto to 5 minutes. The player buffers that far ahead, still within the browser's memory limit at high quality
- `HT_NAME` and `HT_LINKS` (`TUBEHAUS_NAME`, `TUBEHAUS_LINKS` in `.env`): your instance's own name in the page title and menu, and extra side-menu links (a home page, an app to download). Any of the names can be one per language, `{"ko": "홈튜브", "en": "HomeTube"}`, and visitors see theirs (else English)
- `GET /api/p/hls/<id>.m3u8?h=1080`: only the best quality up to that height
- Audio tracks (dubs and audio description) in the web player's menu, named in your language with the original marked; the language you pick is remembered for the next videos that have it. iPhone and iPad's own player lists them too

### Changed
- iPad plays with the device's own player too, like iPhone (Shaka on iPadOS Chrome showed a black picture), and on both the quality stays at the preferred one instead of the player stepping down on its own. The setting is now "Device's own player"
- The 홈튜브 TV app (user agent `HomeTubeTV`) is treated as a TV
- The web player uses that list: videos whose 1440p and 4K exist only as VP9 played at most 1080p, because the companion's list has only the MP4 formats. The companion's list is still used if the server's fails
- TVs play at the best quality by default too, instead of 720p (a cap in Settings or data saver still limits it)

### Fixed
- Videos with dubbed audio could play a dub (often YouTube's automatic English one) instead of the original: the companion doesn't say which language each audio is, so it is now read from the stream itself
- Transcript: `&amp;` and the like showed as written instead of `&`; cue text is now read by the browser's own HTML parser instead of a pattern (also what CodeQL asked for)

## v1.0.0 — 2026-11-07

The biggest update so far: a Music tab, a new look with motion throughout, and a much better phone layout.

### Added
- **Music** (`/feed/music`): a YouTube Music–style home (quick picks, listen again, liked songs, recently opened albums and artists, YouTube's music shelves), search with a top result, songs, albums and artists, album and artist pages, a player bar at the bottom and a full player with the queue, shuffle, repeat, radio ("similar songs" keep playing) and **synced lyrics** from [LRCLIB](https://lrclib.net) (tap a line to jump there). Songs play as audio only, keep playing while you browse, and show on the lock screen (Media Session). Liked songs and history stay in the browser
- `GET /api/p/yt/music/{search,album,artist,mix,lyrics,song,img}` on the server: YouTube Music's own answers through `YT_PROXY`, kept 6 hours; lyrics from LRCLIB (at most 3 lookups a song); `img` passes pictures only from YouTube's own picture hosts. Stateless, so open on public instances too
- **Motion**: pages rise in, cards arrive one after another, pictures fade in once loaded, menus and dialogs grow from where they open, buttons give under the finger, like and subscribe pop, loading placeholders shimmer. TVs get none of it, and neither does a device set to reduce motion
- Home: a "TubeHaus 1.0" card that says what's new, until it's closed
- Lyrics in time from the first second: a music video played in Music plays as the song's own recording (its album track on YouTube Music, the one the lyrics are timed for), as YouTube Music itself does. Live stages, covers, remixes and other versions stay as chosen. `GET /api/p/yt/music/song?v=…&title=…&artist=…&len=…` finds it (one album search, at most three albums opened); the browser remembers the answer
- Lyrics timing: nudge the lyrics half a second earlier or later, or tap the line being sung to line them up; kept per song in the browser. A music video longer than the song says so above the lyrics

### Changed
- New look: the Pretendard font (bundled, no outside requests), new icons, rounder cards and buttons
- Settings: grouped cards with a list of sections (a strip on phones), side-by-side choices for the theme, TV mode and caption size, new switches, and tiles for your data
- Phones: one video per row, like the YouTube app (two narrow columns left three-word titles), and the bottom menu shows again (a later style rule had hidden it)

### Fixed
- Channel pictures that never loaded (some newer picture addresses are refused by Invidious' `/ggpht/` relay): they now come straight from YouTube's picture host through the server, then from the channel, and only then fall back to an icon. The same for community post pictures and channel banners
- Commenters' pictures: newer Invidious sends one `authorThumbnail` address instead of the `authorThumbnails` list, so every comment showed the empty icon
- The watch page showed "- subscribers" and no channel picture for some channels (Invidious loses both for them); they now come from the channel
- "- Topic" (auto-generated artist) channels get their picture from YouTube Music, since Invidious can't open them
- Playlist results in search, playlist cards and the trending tabs no longer push the page sideways on phones; like and dislike counts are no longer cut off
- A failed request no longer leaves an "Uncaught (in promise)" error in the console
- An album or artist that doesn't exist says so instead of showing an empty page
- Synced lyrics: LRCLIB keeps copies of the same lyrics under wrong song lengths; the copies are now grouped and the length most of them agree on wins, so the right timing is picked
- Auto-generated captions showed word timings like `<00:01:01.809>` as text, in the player and in the transcript

## v0.7.19 — 2026-11-05

### Added
- Home: the Live, Music, News, Gaming, Sports, Learning and Fashion & beauty chips show YouTube's own topic pages, shelf by shelf in YouTube's order ("Trending", "This week's best", "Live now", ...), with charts and albums as playlists. A long shelf shows two rows and "N more". Learning and Fashion & beauty are new chips. Where YouTube has nothing for the region (News in some countries) or can't be reached, the chip falls back to the search it used before
- `GET /api/p/yt/topic?key=music|live|news|sports|learning|gaming|fashion&region=KR&hl=ko`: a topic page as titled shelves of Invidious-shaped videos and playlists, read from YouTube's browse API through `YT_PROXY` and kept 30 minutes. Open on public instances too (stateless)
- **DeArrow**: clickbait titles and thumbnails are replaced, as the cards appear, with the ones [DeArrow](https://dearrow.ajay.app)'s users chose (the original title stays as the tooltip). The browser asks with 4 characters of the video ID's hash, like SponsorBlock; the thumbnails come through the server (`GET /api/p/yt/dathumb?v=&t=`) so DeArrow doesn't see the viewer. On by default; Settings has a switch for titles and one for thumbnails
- **"Most replayed"**: the player draws YouTube's heat wave over the progress bar while the pointer is on it, and the tooltip says "Most replayed" at the peak. `GET /api/p/yt/heat?v=` reads it from YouTube's watch page answer through `YT_PROXY`, kept 6 hours. A switch in Settings
- SponsorBlock **highlights**: where people marked a video's highlight, the progress bar shows a pink mark and, in the first 20 seconds, the skip button offers "Jump to the highlight"

### Changed
- Every video card shows its channel's picture. Many YouTube lists come without one, and only subscriptions had it saved, so cards showed an empty circle; the browser now asks the server for that channel's picture (a few channels at a time, `/api/v1/channels/<id>?fields=authorThumbnails`) and keeps it
- Chips are flat with rounded corners, as on YouTube today; playlist cards no longer leave a gap where a channel picture would be
- README: app download links to 0.5.16

## v0.7.18 — 2026-11-02

### Added
- The server stack can also run with [Flisk](https://flisk.sh) (`flisk up`), as an option: the same six containers as `docker-compose.yml`, one `service.json` each under `services/tubehaus/`, reading the same `.env` (#40, #42, thanks @odykyi). docker compose stays the main way; see [docs/FLISK.md](docs/FLISK.md)

### Changed
- README: the Flisk setup in *Quick start*, app download links to 0.5.13, a project page and launch badges

## v0.7.17 — 2026-11-01

### Added
- iPhone: videos play with Safari's own player, through the server's HLS (`/api/p/hls`), so they no longer start on a black picture, and Picture-in-Picture over other apps and AirPlay work (#41, thanks @Neatly9509). A video without HLS falls back to the usual player. Settings has a toggle (iPhone only, on by default). Other devices, iPad included, are unchanged

### Fixed
- iPhone home-screen app: the top bar, the side menu and the full-screen player clear the status bar (#41)

## v0.7.16 — 2026-10-30

### Added
- For the apps (0.5.8), which now play videos through the connected server: `GET /api/p/hls/<id>.m3u8` lists a video's MP4 streams as HLS (byte ranges from the file's index, segments through the companion), because Apple's player on the iPad/iPhone plays only HLS

### Fixed
- Captions in the web player, the apps and translated subtitles (`/api/p/sub`) came back empty: Invidious' own caption requests go to YouTube from the server's address. They now come from the companion, through WARP like the videos

## v0.7.15 — 2026-10-28

### Added
- For the apps' new server sync: a playlist made in an app keeps its ID on the server, and `POST /api/p/yt/pl/move` reorders a playlist

### Changed
- Wording: no more "ad-free" in the interface (the SponsorBlock settings are now called "SponsorBlock and dislikes", the home footer says "No tracking · on your own server") or in the README, and the WARP section describes what it does instead of "doesn't get blocked"
- README: a contact for rights holders in the disclaimer

## v0.7.14 — 2026-10-27

### Fixed
- Subscription feed: old videos showed up on top as "just now, 0 views" (#33, thanks @DarekRobert). One deleted channel answering 404 made the server think YouTube's RSS was down and fetch every channel from Invidious for an hour, and Invidious reports some videos as "0 seconds ago, 0 views". Now RSS counts as down only after 8 failures in a row, and such Invidious entries keep the date and views known before (or get no date, instead of "just now")

## v0.7.13 — 2026-10-27

### Added
- Settings: a Disclaimer section at the bottom, in all 13 languages. TubeHaus is independent and not affiliated with YouTube or Google, hosts no videos and comes with no warranty; downloaded videos are for your own viewing, and sharing or re-uploading them is your own responsibility

### Changed
- README: the disclaimer is at the top. The maintainer's public instance is closed and no longer listed

## v0.7.12 — 2026-10-26

### Fixed
- The player's volume slider was only a few pixels tall to click on. It now takes clicks and drags anywhere on a 32px-tall strip, with a bigger handle that grows under the pointer (#32, thanks @JamesMowery)

### Changed
- README: the server version and the apps compared side by side (what each installs, who talks to YouTube, where your library lives), and the app links point to 0.5.0, which adds Android TV / Google TV

## v0.7.11 — 2026-10-25

### Changed
- Spanish: clearer wording for Calm mode, rate limits, account privacy and user names (#31, thanks @Aaqibhafeezkhan)

### Fixed
- docker compose always published Caddy on ports 80 and 443, so `TUBEHAUS_SITE=:8080` didn't work and running behind your own nginx clashed with it. The host ports now come from `TUBEHAUS_HTTP_BIND` / `TUBEHAUS_HTTPS_BIND` in `.env` (default 80/443), and the README shows the LAN and own-proxy setups

### Added
- A usage page for public instances, `/y/admin.html`: people watching now, accounts, today's totals, visitors and plays per hour for the last 48 hours, sign-ups per day and a daily table. It refreshes every 5 seconds while open. It needs a key, `TUBEHAUS_ADMIN_TOKEN` in `.env`; without one the page is off. After 10 wrong keys in 10 minutes nothing is checked

## v0.7.10 — 2026-10-24

### Added
- Public instances keep anonymous usage counts per hour in `state/stats.json`: visitors, people who opened a video, videos played, people turned away when full, sign-ups and log-ins. Only numbers are stored, no addresses, accounts or videos. People are told apart within an hour by a keyed hash whose key lives only in memory. The last 60 days are kept. Private servers count nothing

## v0.7.9 — 2026-10-24

### Fixed
- A video whose upload time wasn't a plain number (on the page or in its related videos) stopped the watch page with "Invalid time value": the exact-date tooltip from v0.7.6 now skips times it can't read instead of failing

## v0.7.8 — 2026-10-24

### Fixed
- Pressing Enter right after typing a search left the suggestion list open over the results: a suggestion request that was still on its way reopened it. Late answers are now ignored once the search box is left

## v0.7.7 — 2026-10-24

### Changed
- TubeHaus has its own icon: a house with a play button, replacing the YouTube-like red play button, as the favicon, the app icon and the header logo (#30, thanks @Aaqibhafeezkhan)

## v0.7.6 — 2026-10-24

### Added
- Hovering a relative time ("3 days ago") shows the exact date and time (#27, thanks @bh-official)
- Settings → Playback → *Remember playback speed* keeps your speed between visits (#29, thanks @Aaqibhafeezkhan)
- An nginx reverse proxy example, `deploy/nginx.conf.example` (#28, thanks @Aaqibhafeezkhan)
- Tests for the exact upload time from RSS (#26, thanks @bh-official)

### Fixed
- When YouTube's RSS feeds stop answering (they return 404 from some addresses for hours at a time), the subscription feed now gets each channel's latest uploads from Invidious instead of coming up empty, and RSS is skipped for an hour

## v0.7.5 — 2026-10-24

### Fixed
- Under load the WARP relay for the companion (socat) could hit the container's process limit and exit, after which every video failed until a restart. It is now capped, restarted if it exits, and `init: true` reaps finished helper processes; the public compose allows 512 processes
- The watch page showed recent videos as posted at midnight UTC (e.g. "17 hours ago" for a video from 2 hours ago): YouTube only gives Invidious the upload date, so the exact time now comes from the channel's RSS feed (#20)

## v0.7.4 — 2026-10-23

### Changed
- English is the default language until one is chosen (it used to follow the browser); the first-visit picker
  preselects English

## v0.7.3 — 2026-10-23

### Changed
- The sign-up / sign-in page asks for the language first on a first visit (like the app), and has a language selector at the bottom; the choice carries over to the app

## v0.7.2 — 2026-10-23

### Fixed
- `Caddyfile.public`: the sign-up / sign-in page is never cached, so an update shows at once

## v0.7.1 — 2026-10-23

### Fixed
- The sign-up / sign-in page loaded its texts from the wrong path when served at /signup and /login

## v0.7.0 — 2026-10-23

### Added
- **Sign-up wall for public instances** (`HT_ACCOUNTS=1`): user name + password, no e-mail. PBKDF2-SHA256 (600k rounds)
  hashes, HMAC-signed HttpOnly/Secure session cookies, sign-ups limited to 3 per hour per network, log-ins to 10 per
  10 minutes per network and per user, a honeypot field, constant-work login. The reverse proxy asks
  `GET /api/p/auth/check`; sign-up/sign-in page `web/y/auth.html`, sign-out in Settings
- **`deploy/docker-compose.public.yml`**: the hardened public setup - Anubis proof-of-work bot check in front
  (`deploy/anubis-policy.yaml`), the sign-up wall, Invidious accounts/admin off, companion limited to the player's reads,
  load limits, CPU/memory/process caps, no access log
- Strings in 13 languages

## v0.6.1 — 2026-10-23

### Fixed
- docker compose: the TubeHaus container gets `NET_ADMIN` (inside its own network namespace only); without it WARP
  never finishes connecting and every YouTube request fails
- docker compose: invidious-companion gets `NETWORKING_FETCH_RETRY_INITIAL_DEBOUNCE` and
  `NETWORKING_FETCH_RETRY_DEBOUNCE_MULTIPLIER`; without them its PO-token job never succeeds

### Added
- A public instance to try TubeHaus without installing it: https://try.168-107-27-85.sslip.io

## v0.6.0 — 2026-10-23

### Added
- **Public instance mode** (`TUBEHAUS_PUBLIC=1` / `HT_PUBLIC_MODE=1`): each visitor's library lives in their own
  browser, the server stores nothing about visitors and only fetches feeds, parses imports and converts exports;
  send to TV and server downloads are off. A note in Settings reminds visitors to export backups
- **Load limits**: max viewers at once (`HT_MAX_VIEWERS`), max quality (`HT_MAX_HEIGHT`), requests per visitor per
  minute (`HT_RATE_PER_MIN`, 120 on public instances) and concurrent requests (`HT_MAX_CONNECTIONS`, 256)
- Many people opening the same uncached video, or the same channel feeds, at once cause a single YouTube request;
  idle connections are dropped after 30 s; public instances don't pre-load recommended videos
- Bengali interface, the 13th language (thanks @rahul05ranjan, #16)

### Changed
- Hindi: a native speaker's review of strings that read unnaturally (thanks @rahul05ranjan, #17)

## v0.5.0 — 2026-10-23

### Added
- **Calm mode** (Settings → Playback): the home screen shows only new videos from your subscriptions; Shorts,
  trending, the explore menu and recommended videos are hidden everywhere; nothing plays after a video ends (playlists
  still continue); comments stay folded behind a button. Shorts links open as regular videos
- README: a Thanks section for everyone who helped

## v0.4.0 — 2026-10-23

### Added
- **Import from file** (Settings → My data, and the subscriptions page): Google Takeout subscriptions CSV and
  `watch-history.json`, Invidious export, NewPipe, FreeTube, OPML, and TubeHaus backups. Channel names and pictures,
  and the titles of videos that come as bare IDs, are filled in afterwards. Importing the same file twice adds nothing (#15)
- **Export**: a full TubeHaus backup (re-importable), Invidious format, Takeout-style CSV, or OPML (#14)
- `GET /api/p/yt/export?format=tubehaus|invidious|csv|opml`, `POST /api/p/yt/import`

## v0.3.0 — 2026-10-22

### Added
- **Shorts recommendations learned on your own server.** Watch time, loops, likes, dislikes and swipes move a score
  per channel and per topic; the next Shorts come from subscriptions, liked channels, liked topics and popular Shorts,
  with every fourth slot for something new and at most two per channel. Stored in `shorts.json` next to the library;
  reset under History. New endpoints: `GET /api/p/yt/shorts/next`, `POST /api/p/yt/shorts/sig`, `POST /api/p/yt/shorts/reset`

### Fixed
- Trending (gaming) and the popular Shorts lists sent the literal text `${REGION}` instead of your region

## v0.2.1 — 2026-10-22

### Added
- Turkish interface (thanks @SakarPaisen, #8)
- Privacy headers in the Caddy configs (no referrer to other sites, no FLoC/Topics, no framing) and a robots.txt that keeps search engines out
- CI starts the server and checks it answers; the image workflow smoke-tests the published image
- Pull requests are labeled automatically by the files they touch; CodeRabbit review settings

## v0.2.0 — 2026-10-22

### Added
- `--doctor` self-check: `docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor` tests WARP,
  YouTube, Invidious, a real video lookup and the optional tools
- `GET /api/p/health` and a Docker healthcheck for the TubeHaus container
- Optional built-in login for the docker compose setup (`TUBEHAUS_AUTH=basic`)
- [Troubleshooting](docs/TROUBLESHOOTING.md) and [Updating and backups](docs/UPGRADING.md) guides
- Server unit tests and a translation completeness check, both run in CI
- **Prebuilt images** for amd64 and arm64 (`ghcr.io/biolend83/tubehaus`): `docker compose pull` instead of compiling whisper.cpp on your server
- CodeQL code scanning

### Changed
- docker compose uses the prebuilt image (`--build` still builds from the checkout)
- Server log lines are tagged `[tag]` instead of `ha[tag]`

### Fixed
- The library file is always read and written as UTF-8, whatever the system locale
- Subtitle cache paths are validated inside the cache function too (CodeQL)
- Caption text is cleaned with DOMParser instead of innerHTML

## v0.1.0 — 2026-10-22

First public release.

- YouTube-identical web app (home, search, channels, playlists, subscriptions, history, library, trending)
- Cloudflare WARP egress with automatic re-connect when YouTube flags the address
- Live streams with live captions (whisper.cpp)
- One library shared by every device, send to TV, TV mode
- Full-quality offline downloads
- 11 languages
- docker compose setup for x86-64 and ARM64
