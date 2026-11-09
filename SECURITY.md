# Security

## Reporting a problem

Please report security problems privately through
[GitHub's private vulnerability reporting](https://github.com/biolend83/tubehaus/security/advisories/new)
instead of a public issue.

## Who can see what

A **personal server** (the default) has one shared library: subscriptions, history, playlists and
saved positions. TubeHaus doesn't ask who you are, so **whoever can open the address sees and
changes that library**. That's fine on your home network or behind a VPN, but before it can be
reached from the internet, put a login in front of it:

- the built-in password (`TUBEHAUS_AUTH=basic` in `.env`, see the README),
- Authelia, Authentik or a passkey gate through `forward_auth`,
- or don't expose it at all and reach it over WireGuard or Tailscale: [docs/SELF-HOSTING.md](docs/SELF-HOSTING.md).

A **public instance** (`HT_PUBLIC_MODE=1`) keeps every visitor's library in their own browser, and
the server refuses the library, download and casting calls entirely. With `HT_ACCOUNTS=1`, people
sign up with a user name and password before they can use it at all.

## What the server does to protect you

- **Other web sites can't use it.** A page on another site open in the same browser can't read your
  library or change it (add a playlist, start a download, send a video to your TV). The server
  refuses any request for the personal parts that the browser marks as coming from another site
  (`Sec-Fetch-Site`, `Origin`), and it doesn't give other sites CORS permission for them. Only the
  playback parts (streams, captions, thumbnails, health) are open to every origin, like Invidious' API.
- **Passwords** (with `HT_ACCOUNTS=1`) are stored as PBKDF2-SHA256 hashes with 600,000 rounds and a
  random salt. Sessions are HMAC-signed cookies (`HttpOnly`, `Secure`, `SameSite=Lax`) with a key
  that never leaves the server; removing an account ends its sessions.
- **Guessing is slowed down:** 10 sign-in attempts per address and per user name every 10 minutes,
  3 new accounts per address an hour, 10 wrong admin keys per address every 10 minutes.
- **The visitor's address can't be made up.** `X-Forwarded-For` is only believed from a reverse
  proxy on the same machine or a private network, and it's read from the end, so a visitor can't
  get around the limits by writing a different address in it. In a manual setup with a proxy that has
  a public address in front of yours and passes the header on, set `HT_PUBLIC_PROXIES` to how many.
- **Requests have limits:** request bodies over `HT_MAX_BODY_MB` (64 MB) are refused before they're
  read, connections that send nothing for 30 seconds are closed, and `HT_RATE_PER_MIN` /
  `HT_MAX_VIEWERS` / `HT_MAX_CONNECTIONS` cap what one visitor or a crowd can take.
- **Input is checked before it's used:** video, channel and playlist IDs, languages and itags must
  match their exact format; picture and stream addresses are only fetched from YouTube's and
  Google's own hosts, so the server can't be used to reach other machines on your network.
- **Nothing about visitors is kept** on a public instance: only hourly counts, told apart by a hash
  whose key exists only in memory.

## What it doesn't protect against

- Anyone who can already open your personal server: they share the library. Use a login or a VPN.
- Someone on your own home network writing a made-up `X-Forwarded-For`: addresses from private
  networks are trusted as proxies.
- YouTube itself: videos are fetched from YouTube through Cloudflare WARP, so YouTube sees a WARP
  address, not yours, but it does see which videos are fetched.

## The apps

The Android, Windows, macOS, Linux and iPad apps don't use a server at all. Everything they keep
(subscriptions, history, downloads) stays on the device, and they talk to YouTube directly.
