# Updating and backups

## Update

```sh
cd tubehaus
git pull
docker compose pull            # newer TubeHaus, Invidious, companion, Caddy, PostgreSQL images
docker compose up -d            # add --build if you build the TubeHaus image yourself
```

Your library, the WARP registration and the Invidious database live in Docker volumes, so they survive updates.

YouTube changes things often, and most breakage is fixed upstream in invidious-companion. If videos suddenly stop
playing, `docker compose pull && docker compose up -d` is the first thing to try. Watch this repository's releases
(Watch → Custom → Releases) to hear about TubeHaus updates.

## What to back up

| Volume | Contents | Needed? |
|---|---|---|
| `tubehaus-data` | your library: subscriptions, history, resume points, playlists (`ytlib.json`), subtitle cache | **yes** |
| `postgres-data` | Invidious' database (mostly cache) | optional |
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
