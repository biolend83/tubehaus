# Packages

Files for app stores and package managers. Most are rewritten on each release by
[`scripts/packaging.mjs`](../scripts/packaging.mjs) (desktop apps) or by hand (self-hosting stores).

## The desktop and phone apps

| Folder | Store |
|---|---|
| [`winget/`](winget) | Windows Package Manager (sent to microsoft/winget-pkgs) |
| [`chocolatey/`](chocolatey) | Chocolatey: `choco pack chocolatey/tubehaus.nuspec`, then `choco push` with an API key |
| [`aur/`](aur) | Arch Linux AUR, package `tubehaus-bin` |
| [`../Casks`](../Casks), [`../bucket`](../bucket) | Homebrew tap and Scoop bucket in this repository |

## The server, for self-hosting stores

In the stores, the server side of TubeHaus is **two images that run together** (not two editions to choose from):

- `ghcr.io/biolend83/tubehaus`: **the server** (library, captions, offline saves, watch-together rooms). It has no web pages and isn't opened in a browser. (The docker compose setup in this repository pairs it with the plain `caddy` image and the `web/` folder instead of tubehaus-web.)
- `ghcr.io/biolend83/tubehaus-web`: **the web app and its Caddy front door**, the only container with a published port. It serves the pages and routes everything else to the server, found through `TUBEHAUS_SERVER` (`host:port`).

A store app is just the pair: the server asks YouTube itself (since 1.6; before that a store app also ran Invidious, invidious-companion and PostgreSQL). It runs with `HT_WARP=0` (no Cloudflare WARP inside, so no extra capabilities).

| Folder | Store | How it is installed |
|---|---|---|
| [`umbrel/tubehaus`](umbrel/tubehaus) | Umbrel | one app, two containers, port 8796 through `app_proxy` |
| [`casaos/TubeHaus`](casaos/TubeHaus) | CasaOS | one app, two containers, port 8796 |
| [`unraid/`](unraid) | Unraid Community Applications | two templates, **TubeHaus** (server) and **TubeHaus-Web**: install both |
| TrueNAS | [truenas/apps](https://github.com/truenas/apps) `ix-dev/community/tubehaus` | one app, two containers |

The same set without any store: [`deploy/docker-compose.home.yml`](../deploy/docker-compose.home.yml).

Both images get the same version on each server release (`v1.5.0` → `:1.5.0`); keep them on the same version.
