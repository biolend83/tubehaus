# Updating and backups

## Update

```sh
cd tubehaus
git pull
docker compose pull            # newer TubeHaus and Caddy images
docker compose up -d --remove-orphans   # add --build if you build the TubeHaus image yourself
```

Your library and the WARP registration live in Docker volumes, so they survive updates.

**From 1.5 or older to 1.6:** the server now asks YouTube itself, so Invidious, invidious-companion and
PostgreSQL are gone from `docker-compose.yml`. `--remove-orphans` above stops their old containers. Then, if you
like, delete what they left: `docker volume rm tubehaus_postgres-data tubehaus_companion-cache` (the names start
with your folder's name; `docker volume ls` shows them), and the `COMPANION_KEY`, `INVIDIOUS_HMAC_KEY`,
`POSTGRES_PASSWORD`, `TUBEHAUS_DOMAIN`, `TUBEHAUS_HTTPS` and `TUBEHAUS_EXTERNAL_PORT` lines in `.env`.

YouTube changes things often. If videos suddenly stop playing, `docker compose pull && docker compose up -d`
(after a `git pull`) is the first thing to try. Watch this repository's releases
(Watch → Custom → Releases) to hear about TubeHaus updates.

## What to back up

| Volume | Contents | Needed? |
|---|---|---|
| `tubehaus-data` | your library: subscriptions, history, resume points, playlists (`ytlib.json`), subtitle cache | **yes** |
| `warp-data` | the WARP registration; a new one is created if it's missing | no |
| `caddy-data` | HTTPS certificates; Caddy gets new ones if it's missing | no |

The easiest backup is in the app: **Settings → My data → Export → TubeHaus backup**. It's one JSON file with
everything (subscriptions, history, resume points, playlists, likes, Shorts preferences), and **Import from file**
restores it on any TubeHaus.

From the command line instead:

```sh
docker compose cp tubehaus:/data/ytlib.json ./ytlib-backup.json
```

Restore it:

```sh
docker compose cp ./ytlib-backup.json tubehaus:/data/ytlib.json
docker compose restart tubehaus
```

## Moving to another server

1. Back up `ytlib.json` as above
2. Install TubeHaus on the new server with the same `.env`
3. Restore `ytlib.json`, then point your DNS at the new server
