# Opening TubeHaus to your household

A personal TubeHaus has one shared library and doesn't ask who you are, so the real question when
you set it up is **who can reach the address**. Pick one of these three; the first two need no
password at all, because nobody outside can get to the server.

| | Who can open it | Needs |
|---|---|---|
| [Home network only](#1-home-network-only) | devices on your Wi-Fi | nothing extra |
| [Over a VPN](#2-over-a-vpn-tailscale-or-wireguard) | your own phones and laptops, anywhere | Tailscale or WireGuard on each device |
| [On the internet with a login](#3-on-the-internet-with-a-login) | anyone with the password | a domain (or sslip.io) and a login |

Install TubeHaus first with the [Quick start](../README.md#quick-start-docker), then come back here.

## 1. Home network only

For a server at home (a mini PC, a Raspberry Pi 4/5, a NAS that runs Docker). In `.env`:

```sh
TUBEHAUS_SITE=:80
TUBEHAUS_HTTP_BIND=8080
TUBEHAUS_URL=http://192.168.1.10:8080      # the server's address on your network
```

`docker compose up -d`, then open `http://192.168.1.10:8080` on any device at home. Don't forward
that port on your router: then it stays inside your home.

Give the server a fixed address in your router (often called "DHCP reservation"), so the address
doesn't change after a power cut.

## 2. Over a VPN (Tailscale or WireGuard)

The server stays closed to the internet, and your own devices reach it as if they were at home,
from anywhere. This is the safest way to use a cloud server.

### Tailscale (easiest)

1. Install Tailscale on the server (`curl -fsSL https://tailscale.com/install.sh | sh`, then
   `sudo tailscale up`) and on every phone and laptop that should open TubeHaus, signed in to the
   same account.
2. Let Tailscale give TubeHaus an HTTPS address inside your tailnet. In `.env`:

   ```sh
   TUBEHAUS_SITE=:80
   TUBEHAUS_HTTP_BIND=127.0.0.1:8080
   TUBEHAUS_HTTPS_BIND=127.0.0.1:8443
   TUBEHAUS_URL=https://myserver.your-tailnet.ts.net   # shown by "tailscale serve status"
   ```

   ```sh
   docker compose up -d
   sudo tailscale serve --bg 8080
   ```

3. Open the `https://….ts.net` address on your devices. Nothing is open to the internet: in the
   cloud provider's firewall you can close everything except SSH.

To share it with family members, invite them to your tailnet or
[share the machine](https://tailscale.com/kb/1084/sharing) with their Tailscale account.

### WireGuard

If you'd rather not depend on Tailscale's coordination server, run WireGuard yourself:

1. On the server: `sudo apt install wireguard`, create `/etc/wireguard/wg0.conf` with an address
   such as `10.8.0.1/24` and one `[Peer]` per device, and `sudo systemctl enable --now wg-quick@wg0`.
2. Open **UDP 51820** in the cloud firewall (Oracle Cloud: the subnet's security list *and*
   the instance's `iptables`). That is the only port that needs to be open.
3. Set TubeHaus to listen only on the WireGuard address: `TUBEHAUS_HTTP_BIND=10.8.0.1:8080`,
   `TUBEHAUS_URL=http://10.8.0.1:8080`, and the other lines as in *Home network only*.
4. Import each device's config into the WireGuard app (a QR code with `qrencode -t ansiutf8 < phone.conf`).

## 3. On the internet with a login

Use this when someone can't install a VPN app (a smart TV's browser, a work laptop). You need a
hostname: your own domain, or a free one from [sslip.io](https://sslip.io) such as
`tube.203-0-113-7.sslip.io` for the server at 203.0.113.7. Caddy gets the HTTPS certificate by
itself; ports 80 and 443 must be open.

**Always set a login**, or everyone who finds the address shares your library:

- **Built-in password:** `TUBEHAUS_AUTH=basic`, `TUBEHAUS_USER` and `TUBEHAUS_PASSWORD_HASH` in `.env`
  (the hash comes from `docker compose run --rm caddy caddy hash-password`; write every `$` as `$$`).
- **A sign-in for each person:** `TUBEHAUS_AUTH=accounts`. Everyone signs up with a name and a
  password on TubeHaus' own sign-in page and gets their own subscriptions, history and playlists;
  the first account keeps the library that was there before. Once everyone has an account, set
  `TUBEHAUS_MAX_ACCOUNTS` to that number so nobody else can sign up. Passwords can be changed in
  Settings.
- **Your own sign-in service:** Authelia, Authentik or a passkey gate, with Caddy's `forward_auth`.

Already running nginx, Traefik or another Caddy? See [reverse-proxy.md](reverse-proxy.md).

## Phones, tablets and TVs

- **Phones and tablets:** open the address in the browser and choose *Add to Home Screen*; it
  opens full screen like an app.
- **TVs:** the TV's browser works with a remote. Videos can also be sent from a phone to a TV that
  has TubeHaus open (*Cast* in the player).
- **The TubeHaus apps** (Android, Windows, macOS, Linux, iPad) don't need this server at all: they
  talk to YouTube directly and keep everything on the device.

## After it's running

- `docker compose exec tubehaus python3 /app/server/tubehaus_server.py --doctor` checks every part.
- [Updating and backups](UPGRADING.md): what to copy so a new server has the same library.
- [Troubleshooting](TROUBLESHOOTING.md) when videos stop playing.
- [SECURITY.md](../SECURITY.md): what the server protects against, and what it doesn't.
