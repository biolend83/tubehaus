# How TubeHaus is put together

A map for finding your way around the code. TubeHaus is two small pieces with no build step and no
dependencies of their own: a web app (plain ES modules) and a server (Python standard library only).

```
browser ──► Caddy ─┬─ /y/*, page navigations ─────────► web/            (the app, static files)
                   ├─ /api/p/*, /api/v1/videos/* ─────► TubeHaus server (server/tubehaus_server.py)
                   ├─ /companion/* ───────────────────► invidious-companion
                   └─ everything else ────────────────► Invidious
                                                                 │
                                                 Cloudflare WARP (proxy mode) ──► YouTube
```

- **Invidious** and **invidious-companion** talk to YouTube: search, channels, video info, stream URLs.
  TubeHaus uses their API as is and never edits their code.
- **The TubeHaus server** sits next to them and adds what they don't have (live streams, captions, the
  shared library, ...). It also answers `/api/v1/videos/*` itself, from a cache in front of Invidious.
- **The web app** calls the Invidious API (`/api/v1/...`) for YouTube data and the TubeHaus API
  (`/api/p/...`) for everything else.

## The web app: `web/`

`web/index.html` loads `y/main.js`. Every file starts with a comment saying what it is for.

| File | What lives there |
|---|---|
| `y/main.js` | Entry point: header (search, notifications, account menu), side menu, the routes, global shortcuts |
| `y/core.js` | App skeleton: router, shared state, video cards, popup menus, dialogs, the video-info cache and prefetch |
| `y/pages.js` | List pages: home, search, channel, playlist, subscriptions, history, library, trending, **settings** |
| `y/watch.js` | Watch page, Shorts, mini player |
| `y/player.js` | The player on top of Shaka: controls, captions, storyboards, chapters, SponsorBlock, quality and codec choice, keyboard shortcuts |
| `y/util.js` | Shared helpers: DOM, number/time formatting, `api()`, settings, icons |
| `y/i18n.js` | Interface language: `$t()` lookups, language picker, number and date styles |
| `y/lang/<code>.js` | Translations, keyed by the original Korean text |
| `y/locallib.js` | The library kept in the browser (used on public instances, where the server stores nothing) |
| `y/cast.js` | "Send to TV" |
| `y/tv.js` | TV mode: spatial navigation with a remote |
| `y/app.css` | All styles |
| `y/lib/` | Shaka Player, vendored. Don't edit |

Good to know:

- **Text in the code is Korean** (the original language) and wrapped in `$t("...")`. To add a string,
  write it in Korean inside `$t()` and add the same key to every `y/lang/*.js` file
  (`node scripts/i18n-check.mjs` tells you what is missing). If you don't speak Korean, write it in
  English inside `$t()`, add it to `en.js`, and say so in the pull request. The maintainer adds the Korean key
- **Pages are functions that render HTML strings.** Routes are listed in `main.js` as `[regex, handler, options]`;
  the handler gets the `#page` element and the regex match and fills it
- **Settings** are plain properties: `settings.theme`, `settings.theme = "dark"` (`util.js` saves them and fires a
  `settings` event). Defaults are in `DEFAULTS` in `util.js`; the settings page is in `pages.js`

## The server: `server/tubehaus_server.py`

One file, split into sections that each start with a `# ---------- ... ----------` comment.
Search for the comment to jump there:

| Section | What it does |
|---|---|
| `/api/v1/videos cache` | Answers video info instantly from a cache; refreshes it in the background; warms the next likely videos |
| `machine-translated subtitles` | Subtitles in your language for videos that have none |
| `live pull-ahead` | Live streams as HLS: fetches segments before the player asks, rebuilds a longer playlist |
| `self-healing egress` | Reconnects WARP for a fresh address when YouTube starts refusing |
| `library` | Subscriptions, history, resume points, playlists (JSON files in `HT_STATE`) |
| `import / export` | Getting your data in and out |
| `Shorts recommendations` | Shorts ranked from what you watched, learned on this server only |
| `high-quality offline save` | Downloads 720p+ video and audio separately and merges them with ffmpeg |
| `"send to TV"` | Screens long-poll for commands, a phone posts them |
| `live captions` | whisper.cpp transcription of live streams |
| `self-check` | `--doctor` and `GET /api/p/health` |
| `accounts` | Sign-up and sign-in, used only on public instances |

Requests are handled at the bottom of the file in `class H` (`do_GET`, `do_POST`): plain `if` checks on the
path, one after another. Settings come from environment variables at the top (`HT_*`, see `.env.example`).

## Tests and checks

- `tests/test_server.py`: unit tests for the server (`python3 -m unittest discover -s tests`)
- `scripts/i18n-check.mjs`: translations complete and placeholders matching
- CI (`.github/workflows/check.yml`) runs both, plus a syntax check of every JS file

## The container

`Dockerfile` builds one image with the server, the official Cloudflare WARP client, yt-dlp (+ the PO token
plugin) and whisper.cpp. `deploy/entrypoint.sh` starts WARP in proxy mode, re-exposes it on port 1080 for the
companion, then starts the server. `docker-compose.yml` adds Caddy, Invidious, the companion and PostgreSQL.
