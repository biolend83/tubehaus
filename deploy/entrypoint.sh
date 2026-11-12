#!/bin/sh
# Start WARP in proxy mode, share it with the other containers, then run TubeHaus.
# HT_WARP=0 skips WARP: on a home connection YouTube needs no detour, and the container then needs no
# extra capabilities (app stores such as Umbrel, CasaOS and TrueNAS run it that way). HT_PROXY then
# names an optional proxy of your own (host:port) for YouTube.
set -e

if [ "${HT_WARP:-1}" = "0" ]; then
  export HT_YT_PROXY="${HT_PROXY:-}" HT_WARP_ROTATE=0
  echo "WARP is off${HT_PROXY:+ (YouTube through $HT_PROXY)}"
  mkdir -p "$HT_STATE"
  exec python3 /app/server/tubehaus_server.py
fi

W="warp-cli --accept-tos"

warp-svc >/var/log/warp-svc.log 2>&1 &
for i in $(seq 1 30); do $W status >/dev/null 2>&1 && break; sleep 1; done

# the registration is kept in the /var/lib/cloudflare-warp volume
$W registration show >/dev/null 2>&1 || $W registration new
# proxy mode BEFORE connect, otherwise WARP would take over all routing
$W mode proxy
$W proxy port 40000
$W connect
# wait until traffic actually flows (the status line can stay at "Validating" in a container)
for i in $(seq 1 40); do
  curl -s -m 5 -x http://127.0.0.1:40000 https://www.cloudflare.com/cdn-cgi/trace | grep -q "^warp=on" && echo "WARP is on" && break
  sleep 1
done

# WARP's proxy only listens on 127.0.0.1; re-expose it so invidious-companion can use it.
# socat forks a process per connection: cap them (it waits instead of failing when the
# container's process limit is near) and restart it if it ever exits, or every video
# request through the companion fails until the container restarts.
(while :; do
  socat TCP-LISTEN:1080,fork,reuseaddr,max-children=150 TCP:127.0.0.1:40000 || echo "socat exited ($?)"   # set -e must not end the loop
  echo "socat restarting"; sleep 1
done) &

mkdir -p "$HT_STATE"
exec python3 /app/server/tubehaus_server.py
