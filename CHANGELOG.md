# Changelog

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
