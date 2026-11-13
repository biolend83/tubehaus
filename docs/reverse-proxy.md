# Running TubeHaus behind your own reverse proxy

If nginx, Traefik or another proxy already owns ports 80 and 443 on your server, let it handle HTTPS
and pass everything to TubeHaus's Caddy on a local port. This page is for the Docker setup from the
[Quick start](../README.md#quick-start-docker). For a setup without Docker, see
[`deploy/nginx.conf.example`](../deploy/nginx.conf.example) instead.

## 1. Move TubeHaus's Caddy to a local port

In `.env` (replace `tube.example.com` with your hostname):

```sh
TUBEHAUS_SITE=:80
TUBEHAUS_HTTP_BIND=127.0.0.1:8080       # only your proxy reaches it
TUBEHAUS_HTTPS_BIND=127.0.0.1:8443      # unused, kept off the public 443
TUBEHAUS_URL=https://tube.example.com
```

Then `docker compose up -d`. `curl -I http://127.0.0.1:8080/` on the server should answer.

**Add a login before the proxy makes it public:** `TUBEHAUS_AUTH=basic`, `TUBEHAUS_USER` and
`TUBEHAUS_PASSWORD_HASH` in `.env` (see [Add a login](../README.md#add-a-login-before-you-expose-it)).
Or use your proxy's own login (nginx `auth_request`, a Traefik `forwardAuth` middleware with
Authelia or Authentik) for the whole site.

## What your proxy has to do

Whatever proxy you use, four things matter:

| | Why |
|---|---|
| **Everything** goes to `http://127.0.0.1:8080` | Caddy inside the stack decides what goes to the app and what to the server. Don't split paths yourself. |
| **Buffering off** | Video, live streams and downloads are long responses; a buffering proxy holds them back or fills its disk. |
| **A long read timeout** (1 hour) | Live streams and large downloads stay open for a long time. |
| **The `Host` header passed through**, plus `X-Forwarded-Proto` | So links and redirects use your hostname and `https`. |

Library imports are uploads of up to about 25 MB, so allow request bodies at least that big.

## nginx

A complete server block, for example `/etc/nginx/sites-available/tubehaus` (then link it into
`sites-enabled` and `nginx -t && systemctl reload nginx`). The certificate paths are certbot's:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name tube.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;                      # nginx 1.25.1 or later; on older versions write "listen 443 ssl http2;"
    server_name tube.example.com;

    ssl_certificate     /etc/letsencrypt/live/tube.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/tube.example.com/privkey.pem;

    client_max_body_size 25m;      # library imports

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";

        proxy_buffering off;           # video and live streams
        proxy_request_buffering off;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

## Traefik (Docker labels)

If Traefik runs in Docker, it can reach TubeHaus's Caddy over a shared Docker network, and no host port
is needed at all. This assumes a Traefik v2 or v3 that already has:

- an external Docker network, here called `traefik` (`docker network create traefik`),
- an HTTPS entry point called `websecure` and a certificate resolver called `letsencrypt`,
- `exposedByDefault=false` (so only containers with `traefik.enable=true` are published).

Change those three names in the labels below if yours are different. Put this next to
`docker-compose.yml` as `docker-compose.override.yml`; docker compose reads it automatically:

```yaml
services:
  caddy:
    ports: !reset []                  # nothing on the host; Traefik comes in over the network (compose 2.24+)
    networks: [default, traefik]
    labels:
      - traefik.enable=true
      - traefik.docker.network=traefik
      - traefik.http.routers.tubehaus.rule=Host(`tube.example.com`)
      - traefik.http.routers.tubehaus.entrypoints=websecure
      - traefik.http.routers.tubehaus.tls.certresolver=letsencrypt
      - traefik.http.services.tubehaus.loadbalancer.server.port=80
      - traefik.http.services.tubehaus.loadbalancer.passhostheader=true

networks:
  traefik:
    external: true
```

Keep the `.env` from step 1 (`TUBEHAUS_SITE=:80` matters: Caddy then serves plain HTTP on port 80
inside its container). If your docker compose is older than 2.24 and doesn't know `!reset`, leave the
`ports:` line out; the host port then stays on `127.0.0.1:8080` and does no harm.

Traefik streams responses without buffering and has no response timeout by default, so video works
as is. Two things to check in your Traefik's own configuration if you changed them:

- don't add a `buffering` middleware to this router,
- `entryPoints.websecure.transport.respondingTimeouts.writeTimeout` must stay `0` (the default),
  or long videos are cut off.

Then `docker compose up -d` and open `https://tube.example.com`.

## Checking it

```sh
docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor
```

Then open a video and seek around in it, and open a live stream if you use them. If video stops after
a minute or so, the read timeout or buffering is the usual reason.

These examples are written from the settings above; if one of them doesn't work on your setup, or you
run a different proxy, please open an issue or a pull request.
