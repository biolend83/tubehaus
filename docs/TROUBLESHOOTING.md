# Troubleshooting

Start with the self-check. It tests every piece TubeHaus depends on and prints one line each:

```sh
docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor
```

```
[ok  ] WARP proxy             127.0.0.1:40000 -> warp=on ip=2a09:...
[ok  ] YouTube reachable      via WARP
[ok  ] Invidious              http://invidious:3000 (version 2026.10.19-...)
[ok  ] Video lookup           'Me at the zoo' -> 14 streams
[ok  ] yt-dlp (live streams)  /opt/venv/bin/yt-dlp
[ok  ] ffmpeg (HD downloads)  /usr/bin/ffmpeg
[ok  ] whisper (live captions) /opt/whisper/whisper-cli + ggml-base-q5_1.bin
[ok  ] State directory        /data
```

For a manual install, run `python3 server/tubehaus_server.py --doctor` with the same environment as the service.
When you open an issue, please paste this output together with `docker compose logs tubehaus --tail 100`.

## Videos don't play / "Sign in to confirm you're not a bot"

- **WARP proxy fails:** the container could not register or connect WARP. Check
  `docker compose logs tubehaus | grep -i warp`. Restarting the container usually fixes a first-start race:
  `docker compose restart tubehaus`.
- **WARP is on but videos still fail:** YouTube sometimes flags WARP addresses too. TubeHaus reconnects WARP for
  a fresh address automatically (at most every 45 s and 6 times per 30 min). Look for `[warp] rotated` in the logs.
  If it keeps rotating without success, wait a while; the limit protects you from hammering YouTube.
- **Video lookup fails but WARP and Invidious are fine:** this usually means invidious-companion can't reach
  YouTube or is out of date. Check `docker compose logs companion --tail 50` and pull the latest images:
  `docker compose pull && docker compose up -d`.

## The page loads but nothing plays (black player, spinner forever)

- Open the browser's developer console and look for failed `/companion/` or `/api/manifest/` requests.
- If you put a login in front of TubeHaus, make sure it covers the whole site, not just `/`. The player loads
  video through `/companion/*` on the same address and needs the same session.
- `TUBEHAUS_URL` in `.env` must be exactly the address you open in the browser, including `https://`.

## Live streams don't start

Live streams need yt-dlp and PO tokens from the `bgutil` container:

```sh
docker compose ps bgutil                 # must be running
docker compose logs tubehaus | grep live
```

A brand-new install can take a minute before the first PO token is ready.

## Live captions say "off"

The image was built without whisper.cpp (`WITH_WHISPER=0`), or the model download failed during the build.
Pull the prebuilt image (`docker compose pull tubehaus && docker compose up -d`), or rebuild with `docker compose build --no-cache tubehaus`.

## HTTPS certificate errors

Caddy gets a certificate automatically only if `TUBEHAUS_SITE` is a real hostname that points at your server and
ports 80 and 443 are reachable from the internet. On a LAN without a domain, or behind your own reverse proxy,
use `TUBEHAUS_SITE=:80` with `TUBEHAUS_HTTP_BIND` for the host port: see "No domain, or your own reverse proxy?"
in the README.

## `COMPANION_KEY` errors in the Invidious logs

`COMPANION_KEY` must be exactly 16 characters, and Invidious and the companion must use the same value
(`openssl rand -hex 8` gives you one).

## Health status

`docker compose ps` shows whether each container is healthy. TubeHaus also answers `GET /api/p/health` with a
small JSON status (WARP proxy on/off, rotations in the last 30 minutes, live captions available).
