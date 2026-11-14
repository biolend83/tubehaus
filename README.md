<div align="center">

<img src="docs/icon.svg" width="96" height="96" alt="">

# TubeHaus

### YouTube as you know it, without Google looking over your shoulder.

On the TV, the phone, the PC and the iPad. Run it on your own server, or just install the app.

[![Release](https://img.shields.io/github/v/release/biolend83/tubehaus)](https://github.com/biolend83/tubehaus/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Check](https://github.com/biolend83/tubehaus/actions/workflows/check.yml/badge.svg)](https://github.com/biolend83/tubehaus/actions/workflows/check.yml)
![Docker: amd64 | arm64](https://img.shields.io/badge/docker-amd64%20%7C%20arm64-2496ED?logo=docker&logoColor=white)
[![Featured in selfh.st Weekly](https://img.shields.io/badge/featured%20in-selfh.st%20weekly-2ea44f)](https://selfh.st/weekly/2026-10-23/)

**[⬇ Get the app](#get-the-app)** &nbsp;·&nbsp; **[🖥️ Run the server](#quick-start-docker)** &nbsp;·&nbsp; **[✨ What's new in 1.6](#new-in-16-its-own-youtube-engine)** &nbsp;·&nbsp; **English** · [한국어](README.ko.md)

<img src="docs/devices.jpg" alt="TubeHaus on Android TV, iPad and an Android phone" width="860">

<a href="https://www.producthunt.com/products/tubehaus?embed=true&amp;utm_source=badge-featured&amp;utm_medium=badge&amp;utm_campaign=badge-tubehaus" target="_blank" rel="noopener noreferrer"><img alt="TubeHaus - Open-source YouTube client for TV, phone, desktop and iPad | Product Hunt" width="200" height="43" src="https://api.producthunt.com/widgets/embed-image/v1/featured.svg?post_id=1300085&amp;theme=light&amp;t=1793523425652"></a>
<a href="https://fazier.com" target="_blank"><img src="https://fazier.com/api/v1//public/badges/launch_badges.svg?badge_type=launched" width="110" alt="Fazier badge"></a>

<sub>Independent open-source software, not affiliated with YouTube or Google. Please read the [disclaimer](#disclaimer).</sub>

</div>

## At a glance

<table>
<tr>
<td width="33%" valign="top">

### 🎬 It *is* the YouTube layout
Same home grid, same player, same keyboard shortcuts. Nobody in the house has to learn anything new, and the TV gets a remote-friendly version of it.

</td>
<td width="33%" valign="top">

### 🔒 Nothing for Google
No Google account, no ads, no tracking scripts. Subscriptions, history and playlists are yours, on your device or your server, and they export to any format.

</td>
<td width="33%" valign="top">

### 🏠 One library for the house
Pause on the TV, carry on on the phone. Every device and every browser in the house shares one library, or each person gets their own.

</td>
</tr>
</table>

## Pick your TubeHaus

There are two ways to run it. Same name, same look; they differ in *where* the work happens.

| | 📱 **The app** | 🖥️ **The server** (this branch) |
|---|---|---|
| **Best for** | One phone, TV or PC, today, with nothing to set up | A whole household, iPhone/iPad without sideloading, or keeping YouTube away from your devices |
| **Runs on** | Android, Android TV / Google TV, Windows, macOS, Linux, iPad/iPhone | Any Docker host: a VPS, a home PC, a Raspberry Pi. You watch in any browser |
| **Talks to YouTube** | The device itself, with the app's own engine | Your server, with its own engine, through Cloudflare WARP. Devices only talk to your server |
| **YouTube sees** | Your device's address, like any YouTube app | A Cloudflare WARP address, not your home |
| **Library** | On the device, or shared through your TubeHaus server | On the server, for every device and person |
| **Only here** | Offline downloads, background play and picture-in-picture, the mpv player on desktop, on-device recommendations | Live captions (whisper.cpp), send to TV, full-quality downloads, TV mode in any TV browser |
| **Get it** | **[Download](#get-the-app)** | **[docker compose up](#quick-start-docker)** |

**Both?** The app can connect to your server (Settings → *TubeHaus server*): one library everywhere, while each app still plays the video itself.

### Get the app

| Platform | Download | Notes |
|---|---|---|
| 🤖 **Android & TV** | [APK](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1.apk) | Android 8+. On Android TV, Downloader code **3155410**. Updates with [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22io.github.biolend83.tubehaus%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Fbiolend83%2Ftubehaus%22%2C%20%22author%22%3A%20%22biolend83%22%2C%20%22name%22%3A%20%22TubeHaus%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5ETubeHaus%20%5C%22%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22TubeHaus-%5C%5C%5C%5Cd%5C%22%7D%22%7D) |
| 🪟 **Windows** | [MSI](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1.msi) | Or `scoop bucket add tubehaus https://github.com/biolend83/tubehaus` then `scoop install tubehaus` |
| 🍏 **macOS** | [Apple Silicon](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1-mac-arm64.dmg) · [Intel](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1-mac-x64.dmg) | Or `brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus && brew trust biolend83/tubehaus && brew install --cask tubehaus` (mpv is inside the app) |
| 🐧 **Linux** | [.deb](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1-linux-amd64.deb) · [.rpm](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1-linux-x86_64.rpm) | ARM64 and tar.gz in [all files](https://github.com/biolend83/tubehaus/releases/latest); Arch: [`packaging/aur`](packaging/aur) |
| 📱 **iPad / iPhone** | [.ipa](https://github.com/biolend83/tubehaus/releases/download/app-v1.5.1/TubeHaus-1.5.1-ipad.ipa) (beta) | iOS 16+, installed with [Sideloadly](https://sideloadly.io/) and your Apple ID (re-signed every 7 days with a free one). Apple's player up to 4K, background play, picture-in-picture, offline downloads. Rather not sideload? Use the server in Safari |

All files, Windows portable and ARM builds: **[latest release](https://github.com/biolend83/tubehaus/releases/latest)**. Install notes and source per platform: [`android-app`](https://github.com/biolend83/tubehaus/tree/android-app) · [`windows-app`](https://github.com/biolend83/tubehaus/tree/windows-app) · [`mac-app`](https://github.com/biolend83/tubehaus/tree/mac-app) · [`linux-app`](https://github.com/biolend83/tubehaus/tree/linux-app) · [`ios-app`](https://github.com/biolend83/tubehaus/tree/ios-app). The rest of this README is about the server.

## New in 1.6: its own YouTube engine

The server no longer sits on Invidious. It talks to YouTube itself, so the stack is **three containers lighter** (no Invidious, no companion, no PostgreSQL) and it is quicker where it counts. Measured side by side on one server, through the same WARP address, first visits:

| Opening… | TubeHaus 1.6 | Invidious |
|---|---|---|
| a video | **0.60 s** `▇▇▇▇▇▇` | 1.29 s `▇▇▇▇▇▇▇▇▇▇▇▇▇` |
| a channel | **0.27 s** `▇▇▇` | 0.66 s `▇▇▇▇▇▇▇` |
| a channel's videos | **0.21 s** `▇▇` | 0.61 s `▇▇▇▇▇▇` |
| comments | 0.19 s `▇▇` | 0.16 s `▇▇` |
| a search | 0.63 s `▇▇▇▇▇▇` | 0.56 s `▇▇▇▇▇▇` |

Streams came through about 10% faster too (85 vs 75 Mbit/s). It still answers in [Invidious](https://invidious.io)' API format, so the web app, the apps' server mode and other tools that speak that API keep working. Up to 1.5 TubeHaus was built on Invidious and [invidious-companion](https://github.com/iv-org/invidious-companion), and much of what it knows about YouTube was learned from them: thank you, Invidious team. Updating from 1.5? See [below](#quick-start-docker).

<div align="center">
<img src="docs/tour.gif" alt="TubeHaus: Home, a search, a video and its &quot;at a glance&quot; panel" width="860">
</div>

## Why TubeHaus

<table>
<tr>
<td width="50%" valign="top">

**⚡ Instant, and always the best picture**<br>
Video pages come from a cache that refreshes in the background, and the videos you're likely to open next are looked up before you click. Quality never drops on its own: 60 fps first, the codec your device decodes in hardware, a buffer sized for 8K.

</td>
<td width="50%" valign="top">

**🔒 Your devices never talk to Google**<br>
Video, thumbnails and pages all come through your server: no Google cookies or YouTube scripts in your browser. YouTube sees a Cloudflare WARP address, never your home.

</td>
</tr>
<tr>
<td valign="top">

**🎙️ Captions for live streams**<br>
YouTube has none. TubeHaus writes them on the fly with [whisper.cpp](https://github.com/ggml-org/whisper.cpp), a few seconds ahead of playback, on one CPU core. Live streams themselves don't stall: segments are fetched ahead with exact timing.

</td>
<td valign="top">

**📺 Made for the TV, too**<br>
A TV mode driven by the remote's arrows, OK and Back, and **Send to TV**: pick a video on the phone and it starts on the TV, no Chromecast needed. **Watch together** keeps everyone in a room on the same second.

</td>
</tr>
<tr>
<td valign="top">

**🌿 Calm mode**<br>
One switch turns YouTube back into a tool: Home shows only new videos from your subscriptions, Shorts, trending and recommendations disappear, nothing autoplays, comments stay folded. Search still works.

</td>
<td valign="top">

**🧠 Shorts that learn, on your server**<br>
How long you watched, looped, liked or swiped away becomes a score per channel and topic in a small file on your server. Every fourth Short is something new. Nothing leaves the server; reset it any time.

</td>
</tr>
<tr>
<td valign="top">

**🚫 No clickbait, no sponsor reads**<br>
[DeArrow](https://dearrow.ajay.app) titles and thumbnails chosen by people instead of the uploader, [SponsorBlock](https://sponsor.ajay.app) skips with "Jump to the highlight", and dislike counts from Return YouTube Dislike.

</td>
<td valign="top">

**💾 Keep it, in full quality**<br>
1080p and 4K come as separate video and audio; the server fetches both and merges them with ffmpeg. Subtitles are translated for videos without your language.

</td>
</tr>
<tr>
<td valign="top">

**☁️ Happy on a cheap cloud VM**<br>
Cloud addresses often get "Sign in to confirm you're not a bot". TubeHaus goes out through **Cloudflare WARP in proxy mode**, takes a fresh visitor identity, then a new WARP address when YouTube walls it off.

</td>
<td valign="top">

**🌍 13 languages**<br>
한국어 · English · 日本語 · 简体中文 · Español · Português · Français · Deutsch · Русский · हिन्दी · Bahasa Indonesia · Türkçe · বাংলা. View counts and dates follow each language's own style.

</td>
</tr>
</table>

<details>
<summary><b>Everything else it does</b></summary>

- YouTube's layout throughout: home, search, channels, playlists, subscriptions, history, library, trending, and YouTube's own topic pages (Music, Live, News, Gaming, Sports, Learning, Fashion & beauty) shelf by shelf
- A player on [Shaka Player](https://github.com/shaka-project/shaka-player) (DASH/HLS): chapters, storyboard seek previews, the **"Most replayed"** wave over the progress bar, speed, sleep timer, stats, mini player, theater and ambient modes, Media Session (lock screen and Bluetooth keys)
- One library for every device: subscriptions with an RSS-based feed, watch history, resume positions, Watch Later, likes, playlists
- Comments, transcripts, the exact upload time on hover
- Import from Google Takeout, Invidious, NewPipe, FreeTube, OPML or a TubeHaus backup; export to all of them

Optional lookups the browser makes itself: SponsorBlock and DeArrow (both by a hash prefix, not the video ID) and Return YouTube Dislike. DeArrow's thumbnails come through the server, so DeArrow sees the server rather than each viewer.

*(Formerly HomeTube, renamed to avoid a clash with an unrelated project of the same name.)*

</details>

## How it compares

FreeTube and NewPipe are great, mature projects, and TubeHaus owes a lot to them. The difference is mostly where it runs and how it looks:

| | **TubeHaus** | **FreeTube** | **NewPipe** |
|---|---|---|---|
| **Runs on** | Android phones and Android TV / Google TV, Windows, macOS, Linux, iPad/iPhone (sideloaded), and any browser through the server | Windows, macOS, Linux | Android phones and tablets |
| **Looks like** | YouTube itself: same layout, same keyboard shortcuts | Its own design | Its own design |
| **Google account** | Not needed | Not needed | Not needed |
| **Home screen** | Recommendations learned on the device from what you watch, like, skip and hide; nothing leaves it | Subscriptions, trending and popular | Subscriptions feed and trending |
| **One library on several devices** | Optional: connect the apps to your own TubeHaus server | Export and import a file | Export and import a file |
| **SponsorBlock and DeArrow** | Both built in | Both built in | Not in the official app (the Tubular fork has SponsorBlock) |
| **On a TV** | Made for the remote: a TV layout with arrow-key navigation | No TV version | Works, but made for phones |
| **Track record** | Young (2026), beta, one maintainer | Since 2018, large community | Since 2015, large community, on F-Droid |

**When to pick something else:** for a desktop app with the longest track record, FreeTube is excellent. For an Android phone only, with a long history and F-Droid, NewPipe is excellent. TubeHaus is for one YouTube-like app on the TV, the phone, the PC and the iPad, optionally sharing one library.

## Quick start (Docker)

| You need | |
|---|---|
| **A server** | Linux with Docker and docker compose, x86-64 or ARM64 (a free Oracle Cloud ARM VM is plenty) |
| **CPU / RAM** | 2+ cores, about 4 GB RAM; live captions use one more core while someone watches live with captions on |
| **Disk** | about 10-20 GB, mostly images. Videos are streamed, not stored |
| **An address** | a domain or a free wildcard name such as sslip.io for HTTPS, or plain HTTP on a LAN |

```sh
git clone https://github.com/biolend83/tubehaus && cd tubehaus
cp .env.example .env        # set your domain
docker compose pull         # prebuilt images, amd64 and arm64
docker compose up -d
```

That's it: Caddy, the TubeHaus server with its YouTube engine and Cloudflare WARP, the PO-token provider for live streams and whisper.cpp for live captions come up together. Open your domain. WARP registers itself on first start and keeps its registration in a volume.

> [!IMPORTANT]
> **Going on a public domain? Set a login in `.env` first:** `TUBEHAUS_AUTH=basic`, `TUBEHAUS_USER` and `TUBEHAUS_PASSWORD_HASH` (the hash comes from `docker compose run --rm caddy caddy hash-password`; write every `$` as `$$`). Without it, everyone who opens the address shares the same library. More ways: [Add a login before you expose it](#add-a-login-before-you-expose-it).

> [!TIP]
> **Updating from 1.5 or older?** Invidious, its companion and PostgreSQL are no longer needed. After `git pull`, run `docker compose pull && docker compose up -d --remove-orphans` to stop them; their secrets in `.env` can go. Your library is in the `tubehaus-data` volume and stays as it is. Details: [Updating and backups](docs/UPGRADING.md).

<details>
<summary><b>No domain, or behind your own reverse proxy</b></summary>

Caddy can serve plain HTTP on any port. In `.env`:

```sh
# plain HTTP on a LAN, at http://192.168.1.10:8080
TUBEHAUS_SITE=:80
TUBEHAUS_HTTP_BIND=8080
TUBEHAUS_URL=http://192.168.1.10:8080

# behind your own nginx, Traefik or Caddy, which handles HTTPS for tube.example.com
TUBEHAUS_SITE=:80
TUBEHAUS_HTTP_BIND=127.0.0.1:8080       # only your proxy reaches it
TUBEHAUS_HTTPS_BIND=127.0.0.1:8443      # unused, kept off the public 443
TUBEHAUS_URL=https://tube.example.com
```

Then point your proxy at `http://127.0.0.1:8080` for everything, unbuffered (video and live streams), e.g. in nginx:
`proxy_pass http://127.0.0.1:8080; proxy_buffering off; proxy_read_timeout 1h; proxy_set_header Host $host;`
Complete nginx and Traefik examples are in [docs/reverse-proxy.md](docs/reverse-proxy.md).

</details>

<details>
<summary><b>With Flisk instead of docker compose</b></summary>

The same three containers are also described for [Flisk](https://flisk.sh) and read the same `.env`. Optional; docker compose stays the main way.

```sh
npm install -g @flisk-sh/flisk   # Flisk 1.4.26 or later
git clone https://github.com/biolend83/tubehaus && cd tubehaus
cp .env.example .env
flisk up                          # flisk status / flisk logs --service tubehaus / flisk down
```

Pick one of the two for a server, not both. Ports, volumes, updates and moving an existing install are in [docs/FLISK.md](docs/FLISK.md). Thanks to @odykyi for the Flisk setup (#40, #42).

</details>

<details>
<summary><b>Building the image yourself</b></summary>

`docker compose up -d --build` builds the TubeHaus image from your clone (for example after changing the code). The first build takes a few minutes because of whisper.cpp.

</details>

### Add a login before you expose it

TubeHaus has no accounts by default: without a login, everyone who opens the address shares the same library.

- **Built-in password:** `TUBEHAUS_AUTH=basic`, `TUBEHAUS_USER` and `TUBEHAUS_PASSWORD_HASH` in `.env` (hash from `docker compose run --rm caddy caddy hash-password`; every `$` as `$$`)
- **An account for each person:** `TUBEHAUS_AUTH=accounts` gives a sign-in page and a library per person
- **Your own gate:** Authelia, Authentik or a passkey gate in front with `forward_auth`
- **Not exposed at all:** keep it on your home network, or reach it from anywhere over Tailscale or WireGuard

All of them, step by step: [Opening TubeHaus to your household](docs/SELF-HOSTING.md).

### Check that everything works

```sh
docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor
```

One line each for WARP, YouTube, a search, a real video lookup with a few bytes of its stream, and the optional tools. If something fails, see [Troubleshooting](docs/TROUBLESHOOTING.md).

### The two images: tubehaus and tubehaus-web

They aren't two editions to choose from: one is the server, the other the web app, and a working setup always has the server plus one way of serving the web app.

| Image | What it is | Ports |
|---|---|---|
| `ghcr.io/biolend83/tubehaus` | **The server.** Talks to YouTube, keeps the library, live captions, offline saves, watch-together rooms. No pages of its own | 3010 (inside) |
| `ghcr.io/biolend83/tubehaus-web` | **The web app and its front door.** The pages, plus Caddy sending every other request to the server. The only port you open | 8080 |

- **`docker compose up` from this repository** uses only `tubehaus`: the web app comes from your clone's `web/` folder, served by the plain `caddy` image
- **App stores** (Umbrel, CasaOS, TrueNAS) use both: you install one app, only port 8796 opens
- **Unraid** shows two templates, **TubeHaus** and **TubeHaus-Web**; or the whole set with the Docker Compose Manager plugin and [`deploy/docker-compose.home.yml`](deploy/docker-compose.home.yml)
- **A home server without a clone:** [`deploy/docker-compose.home.yml`](deploy/docker-compose.home.yml) is the store set (one port, no WARP, no extra permissions)

Both are published together on each server release with the same version number; keep them on the same version.

## How it fits together

```
browser ──► Caddy ─┬─ /y/*, page navigations ──► web/ (the app)
                   └─ everything else ─────────► TubeHaus server
                                                   │  library, live streams, captions, downloads,
                                                   │  and its own YouTube engine (/api/v1/*,
                                                   │  video streams, captions, pictures)
                                                   ▼
                                       Cloudflare WARP (proxy mode)
                                                   │
                                                YouTube
```

The engine asks YouTube the way YouTube's own apps do: lists (search, channels, playlists, comments) from the web client, the video streams from the visionOS client (plain addresses, no PO token), and the exact upload time and category from the web player, over kept-open, compressed connections. It answers in Invidious' API shapes (`/api/v1/videos`, `/api/v1/search`, ...; `?local=true` included), so tools that speak that API can use it too. Streams, captions and pictures come through the server, so the viewer's browser only ever talks to it. More in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Manual setup

<details>
<summary><b>Without Docker: Python, Caddy or nginx, and the WARP client</b></summary>

You need a Linux server with Python 3.10+ and [Caddy](https://caddyserver.com) (or nginx).

**1. Cloudflare WARP in proxy mode.** Install the official client ([pkg.cloudflareclient.com](https://pkg.cloudflareclient.com)), then **set proxy mode before connecting**, or the whole server's routing moves into WARP:

```sh
warp-cli --accept-tos registration new
warp-cli --accept-tos mode proxy        # listens on 127.0.0.1:40000
warp-cli --accept-tos connect
curl -x http://127.0.0.1:40000 https://www.cloudflare.com/cdn-cgi/trace   # warp=on
```

(Unofficial WARP clients such as `wgcf` get rate-limited by Cloudflare; use the official one.)

**2. The TubeHaus server.**

```sh
git clone https://github.com/biolend83/tubehaus /opt/tubehaus
cd /opt/tubehaus && cp .env.example .env    # edit it
sudo cp deploy/tubehaus.service /etc/systemd/system/ && sudo systemctl enable --now tubehaus
```

Optional extras:
- **Live streams:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) with the [bgutil-ytdlp-pot-provider](https://github.com/Brainicism/bgutil-ytdlp-pot-provider) plugin (PO tokens keep live URLs alive)
- **Live captions:** build whisper.cpp and download `ggml-base-q5_1.bin`; set `HT_WHISPER_BIN` / `HT_WHISPER_MODEL`
- **Offline save:** `ffmpeg`

**3. Caddy or nginx.** Copy `web/` to `/srv/tubehaus/web` and adapt [`deploy/Caddyfile.example`](deploy/Caddyfile.example). For nginx, start from [`deploy/nginx.conf.example`](deploy/nginx.conf.example) and replace the hostname, certificate paths and upstream addresses; it keeps video streams, long polls and downloads unbuffered with extended timeouts.

Without `HT_ACCOUNTS=1`, anyone who can open it shares the same library: put your own login in front (Caddy `basic_auth`, or `forward_auth` to Authelia / Authentik / a passkey gate) before exposing it. Set `window.TUBEHAUS_LOGIN_URL` in `index.html` if expired sessions should be sent to your login page.

</details>

## FAQ

<details>
<summary><b>Do I still need Invidious?</b></summary>

No, not since 1.6: the server asks YouTube itself. It answers in Invidious' API format, so apps that can use an Invidious instance's API (Yattee, FreeTube's API mode, ...) may work with it too; the parts that need an Invidious account (its own subscriptions and login) aren't there. A running Invidious instance doesn't get in the way; TubeHaus just no longer uses it.

</details>

<details>
<summary><b>Where does the content come from? Does it store videos?</b></summary>

Everything comes from YouTube. Nothing is stored except your library (subscriptions, history, playlists) and short-lived files for offline downloads.

</details>

<details>
<summary><b>Is there a recommendation algorithm?</b></summary>

For Shorts, yes, and it runs entirely on your server (see *Shorts that learn* above). For regular videos you get your subscriptions feed, trending and topic pages, and YouTube's related list for each video. Nothing is learned by Google, since no Google account is involved.

</details>

<details>
<summary><b>Age-restricted videos?</b></summary>

They usually don't play, since there's no signed-in account to pass the age check.

</details>

<details>
<summary><b>Can I bring my subscriptions in, and take my data out?</b></summary>

Yes. Under Settings → My data (or the subscriptions page), **Import from file** takes Google Takeout (the subscriptions CSV and `watch-history.json`), Invidious, NewPipe and FreeTube exports, OPML, and TubeHaus backups. **Export** gives you a full TubeHaus backup (subscriptions, history, resume points, playlists, likes), an Invidious-format file, a Takeout-style CSV, or OPML for any feed reader. Your data is never stuck here.

</details>

<details>
<summary><b>Does anything phone home?</b></summary>

No. There is no telemetry. The only outside requests are YouTube (from the server, through WARP), DeArrow's thumbnail server (from the server, for the optional DeArrow thumbnails) and, from the browser, the optional SponsorBlock, DeArrow and Return YouTube Dislike lookups.

</details>

<details>
<summary><b>How do phones and TVs connect?</b></summary>

It's a web app: open your address in the browser, or use "Add to Home Screen" to install it like an app. TV browsers get a TV mode with remote-control navigation. Or use the [TubeHaus apps](#get-the-app), on their own or connected to your server.

</details>

## Running a public instance

<details>
<summary><b>Let people try it without installing: <code>TUBEHAUS_PUBLIC=1</code></b></summary>

Set `TUBEHAUS_PUBLIC=1` in `.env` (or `HT_PUBLIC_MODE=1` for a manual install). Then:

- **Every visitor's library lives in their own browser.** The server stores nothing about its visitors, and visitors never see each other's data. A note in Settings tells them to export a backup now and then
- **The server only does stateless work:** fetching feeds for the channels a browser sends, parsing imports and converting exports, and playing video
- **Switched off:** send to TV and server-side downloads (they would be shared between strangers), and Shorts recommendations don't learn

**Handling a crowd.** TubeHaus protects itself, and your YouTube access, when many people show up at once:

| Setting (`.env`) | Default | What it does |
|---|---|---|
| `TUBEHAUS_MAX_VIEWERS` | `0` (off) | people watching at once; newcomers beyond it get "busy, try again in a few minutes", people already watching are never cut off |
| `TUBEHAUS_MAX_HEIGHT` | `0` (off) | highest quality offered, e.g. `1080`, to keep bandwidth in check |
| `TUBEHAUS_RATE_PER_MIN` | `120` on public instances | API requests per visitor per minute; more get "slow down" (429) |
| `HT_MAX_CONNECTIONS` | `256` | requests handled at the same time; beyond it the server answers "busy" at once instead of falling over |

Always on: when many people open the same video (or the same channel feeds) at once, YouTube is asked once and the answer is shared; live streams are fetched once per stream however many people watch; idle connections are dropped after 30 seconds; and public instances don't pre-load recommended videos.

**The ready-made public setup.** `deploy/docker-compose.public.yml` puts all of this together:

- **Anubis** in front: every visitor's browser solves a short proof-of-work check before anything else loads, which keeps scrapers and bulk bots out
- **Sign-up required** (`HT_ACCOUNTS=1`): a user name and a password, no e-mail or other personal details. Passwords are stored as PBKDF2 hashes (600,000 rounds); sessions are signed HttpOnly cookies; sign-ups and log-ins are rate-limited per network
- Load limits, memory/CPU caps, no access log

```sh
docker compose --env-file .env -f deploy/docker-compose.public.yml up -d
```

Also firewall the box so the containers can reach the internet only: no private networks, no cloud metadata service, none of your other servers. Before you open one:

- **Bandwidth:** video is streamed through your server. Watch your provider's traffic limits
- **YouTube blocks:** many viewers on one address get it flagged sooner; WARP rotation helps but has limits
- **Trust:** visitors' requests pass through your server. Be clear about what you log (TubeHaus logs no viewing history), and don't put the built-in password on it
- Leave `TUBEHAUS_AUTH=none`, and consider rate limiting in Caddy

**Public instances**

| Address | Region | Limits | Run by |
|---|---|---|---|
| *none listed yet* | | | |

Running one? [Add it here](https://github.com/biolend83/tubehaus/issues/new?template=public_instance.yml) (or in the [discussion](https://github.com/biolend83/tubehaus/discussions/18)) so others can find it.

</details>

## Configuration

<details>
<summary><b>All settings</b> (environment variables, see <a href=".env.example"><code>.env.example</code></a>)</summary>

| Variable | Default | |
|---|---|---|
| `HT_BIND`, `HT_PORT` | `127.0.0.1`, `3010` | where the API listens |
| `HT_STATE` | `./state` | library and subtitle cache |
| `HT_PUBLIC_URL` | `http://localhost:8080` | the address browsers use |
| `HT_YT_PROXY` | `127.0.0.1:40000` | WARP proxy (empty = direct) |
| `HT_WARP_ROTATE` | `1` | reconnect WARP when YouTube blocks |
| `HT_REGION` | `US` | default region of searches and trending |
| `HT_HL` | (the viewer's language) | YouTube's language for its texts |
| `HT_YTDLP` | `yt-dlp` | for live streams |
| `HT_WHISPER_BIN`, `HT_WHISPER_MODEL`, `HT_WHISPER_LANG` | empty, empty, `auto` | live captions |
| `HT_SUB_LANG`, `HT_LIBRETRANSLATE` | `en`, empty | default subtitle translation target (the app asks for its own language), optional fallback |
| `HT_DOWNLOAD_DIR` | `/var/tmp/tubehaus-dl` | offline save workspace |
| `HT_PUBLIC_MODE` | `0` | public instance: libraries stay in visitors' browsers (see above) |
| `HT_ACCOUNTS`, `HT_MAX_ACCOUNTS` | `0`, `1000` | require sign-up (user name + password) before anything else |
| `HT_MAX_VIEWERS`, `HT_MAX_HEIGHT`, `HT_RATE_PER_MIN`, `HT_MAX_CONNECTIONS` | `0`, `0`, `120` public / `0`, `256` | load limits (see *Handling a crowd*) |
| `HT_VID_PREFETCH` (`TUBEHAUS_PREFETCH`) | `4` | videos you're likely to click next, looked up ahead so they start at once (0 = only what you ask for) |
| `TUBEHAUS_AUTH=accounts` | | Docker: a sign-in page and a library for each person (`HT_ACCOUNTS=1` in a manual setup); `TUBEHAUS_MAX_ACCOUNTS` closes sign-ups |
| `HT_PUBLIC_PROXIES` | `0` | proxies with public addresses in front of your own (they can't be told from visitors); see [SECURITY.md](SECURITY.md) |
| `HT_MAX_BODY_MB` | `64` | largest request body (YouTube history imports) |
| `HT_NAME`, `HT_LINKS` (`TUBEHAUS_NAME`, `TUBEHAUS_LINKS`) | empty | your instance's own name, and extra side-menu links as JSON `[{"name": "…", "url": "https://…"}]` (or `/path`; a `.apk` link downloads). Any name can be one per language, `{"ko": "홈튜브", "en": "HomeTube"}`: visitors see theirs, else English |

</details>

## Community

- **Questions and setup help:** [Discussions](https://github.com/biolend83/tubehaus/discussions) (please include the `--doctor` output)
- **Bugs and feature requests:** [Issues](https://github.com/biolend83/tubehaus/issues/new/choose)
- **Contributing:** see [CONTRIBUTING.md](CONTRIBUTING.md). Start with a [`good first issue`](https://github.com/biolend83/tubehaus/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22); [ROADMAP.md](ROADMAP.md) shows what's planned and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) maps the code
- **Translations:** interface text lives in `web/y/lang/<code>.js` (the key is the original Korean text). The 13 languages are complete; corrections are very welcome
- **Security problems:** report them privately, see [SECURITY.md](SECURITY.md)
- **What changed:** [CHANGELOG.md](CHANGELOG.md) and [Releases](https://github.com/biolend83/tubehaus/releases)

## Thanks

People who made TubeHaus better, with code, translations, testing or ideas:

**Code and design**
- <img src="https://github.com/onedge.png?size=48" width="24" height="24" alt=""> [@onedge](https://github.com/onedge): "Back to music" in the music player (#68, #69)
- <img src="https://github.com/Aaqibhafeezkhan.png?size=48" width="24" height="24" alt=""> [@Aaqibhafeezkhan](https://github.com/Aaqibhafeezkhan): the house-and-play icon, remembering the playback speed, an nginx example, Spanish polish (#28, #29, #30, #31)
- <img src="https://github.com/bh-official.png?size=48" width="24" height="24" alt=""> [@bh-official](https://github.com/bh-official): the exact upload date on hover, and tests for exact upload times (#26, #27)
- <img src="https://github.com/onyks-os.png?size=48" width="24" height="24" alt=""> [@onyks-os](https://github.com/onyks-os): the first contribution, fixing the install instructions (#1)

**Translations**
- <img src="https://github.com/SakarPaisen.png?size=48" width="24" height="24" alt=""> [@SakarPaisen](https://github.com/SakarPaisen): Turkish (#8)
- <img src="https://github.com/rahul05ranjan.png?size=48" width="24" height="24" alt=""> [@rahul05ranjan](https://github.com/rahul05ranjan): Bengali, and a Hindi review by a native speaker (#16, #17)

**Ideas and bug reports**
- <img src="https://github.com/JamesMowery.png?size=48" width="24" height="24" alt=""> [@JamesMowery](https://github.com/JamesMowery): import from file and data export (which became v0.4.0), and the wrong posted time on new videos (#14, #15, #20)

**Testing the apps** (on [r/DigitalEscapeTools](https://www.reddit.com/r/DigitalEscapeTools/))
- [u/Proper_Witness9173](https://www.reddit.com/user/Proper_Witness9173/): brightness and volume swipes, the tablet layout, comments beside the video, picture-in-picture and most of 0.3.8
- [u/DryYam2258](https://www.reddit.com/user/DryYam2258/): the first Mac tester; asked for text size, removing subscriptions and channel pictures, and suggested subscription groups
- [u/Gloomy_Acadia_445](https://www.reddit.com/user/Gloomy_Acadia_445/): asked for Linux, which led to the .deb, .rpm and tar.gz builds
- [u/Victor_Orbis35](https://www.reddit.com/user/Victor_Orbis35/): reported "mpv is missing" on Windows, which turned out to be a missing Vulkan runtime (fixed in 0.3.5)

Opened a useful issue, sent a pull request or tested an app? You belong here too.

## Credits

- [Invidious](https://github.com/iv-org/invidious) and [invidious-companion](https://github.com/iv-org/invidious-companion) (AGPL-3.0): the backend this sat on until 1.5, and the API format it still answers in
- [Shaka Player](https://github.com/shaka-project/shaka-player) (Apache-2.0): bundled in `web/y/lib/`
- Layout after [varun-jasti/Youtube-UI-UX](https://github.com/varun-jasti/Youtube-UI-UX) (MIT); progress bar and volume after [pncsoares/youtube-video-player-clone](https://github.com/pncsoares/youtube-video-player-clone) (MIT)
- Icons: Material Symbols (Apache-2.0)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT), [SponsorBlock](https://sponsor.ajay.app), [DeArrow](https://dearrow.ajay.app), [Return YouTube Dislike](https://returnyoutubedislike.com)
- Cloudflare WARP

## Disclaimer

> [!CAUTION]
> **TubeHaus is independent, free, open-source software. It is not affiliated with, endorsed by or sponsored by YouTube or Google.** "YouTube" is a trademark of Google LLC.
>
> - TubeHaus does not host, store or distribute any videos. They come straight from YouTube to your own device (the app) or to a server you run yourself (the server version).
> - **Downloaded videos are for your own personal viewing.** Their copyright belongs to their owners. If you share, upload, sell or otherwise distribute them, that is entirely your own responsibility, and the authors are not liable for it.
> - It is provided "as is", without warranty of any kind, as the license says. The authors and contributors are not responsible for how it is used or for anything that results from using it.
> - You are responsible for using it in line with the laws where you live and the terms of the services you connect to.
> - The maintainer does not run a public instance or any other service for it.
> - **Rights holders:** if you believe something in this repository infringes your rights, [open an issue](https://github.com/biolend83/tubehaus/issues/new) with the details (or ask there for a private contact). It will be looked at and, where justified, removed promptly.

## License

AGPL-3.0, like Invidious. See the [disclaimer](#disclaimer) above.
