<div align="center">

# TubeHaus for Windows

**YouTube on your own terms — and everything runs on your computer.**

No server, no account, no Google sign-in, no tracking. The app talks to YouTube directly,
keeps your subscriptions, history and playlists on the device, and works out recommendations
there too.

**[⬇ Windows 10/11 installer](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.0/TubeHaus-0.5.0.msi)** (0.5.0, beta)

[Android](https://github.com/biolend83/tubehaus/tree/android-app) · **Windows** · [macOS](https://github.com/biolend83/tubehaus/tree/mac-app) · [Linux](https://github.com/biolend83/tubehaus/tree/linux-app) · [Web app (self-hosted)](https://github.com/biolend83/tubehaus) · [All releases](https://github.com/biolend83/tubehaus/releases)

</div>

## What it does

- **Android TV / Google TV** — the Android APK runs on TVs too: it shows on the TV's home
  screen, the remote moves a highlighted selection from card to card, and videos fill the screen
  (OK pauses, left and right jump 10 seconds, hold OK on a card for its menu).
- **Home that learns on your device** — built from videos related to what you watched, new uploads
  from your subscriptions and the topics you like, with room for something new so it doesn't
  collapse into one subject. Settings → *What TubeHaus learned about you* shows every score and
  lets you change or wipe it.
- **Shorts with their own engine** — learns from skipping, finishing, looping and likes.
- **Subscriptions without an account** — from each channel's public feed. Import from YouTube
  (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML.
- **A real player** — adaptive quality up to 4K/8K (AV1, VP9, H.264 picked by what your hardware
  decodes), captions with auto-translate, speed, resume where you stopped.
  - Android: background play with notification controls, picture-in-picture, opens YouTube links.
  - Windows, macOS and Linux: mpv with GPU decoding, the same controls as on the phone plus a
    volume bar, keyboard shortcuts (space/K, arrows, J/L, F, M) and the mouse wheel for Shorts.
- **Comments beside the video** — in a side panel next to the video, which keeps playing.
- **Calm mode** — no view counts, no Shorts, nothing starts by itself.
- **Focus switches** — hide thumbnails, comments, Shorts or related videos, and turn off the home
  feed or the subscription feed, each on its own (Settings → Focus).
- **Your data moves freely** — export a TubeHaus backup (also readable by the TubeHaus web app),
  Invidious, CSV or OPML.

## How it works

The app has its own small YouTube engine (`common/src/app/tubehaus/engine`). It calls InnerTube,
the JSON API YouTube's own apps use: the web client for search, channels, playlists, related
videos and comments; for streams, clients whose answers carry ready-to-play URLs. Nothing goes
through a TubeHaus server, because there isn't one.

One set of Kotlin sources (`common/src`) is compiled twice: by the Android app with Jetpack
Compose and by the desktop app with Compose for Desktop. Only the player differs —
ExoPlayer (Media3) on Android, libmpv on Windows, macOS and Linux.

## Install

[Download the installer](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.0/TubeHaus-0.5.0.msi) and run it. mpv is built in, so there's nothing else to install.

Windows may warn that the publisher is unknown, because the installer isn't code-signed:
choose *More info* → *Run anyway*. Each new version installs over the previous one and keeps
your library. The app checks for a newer version once a day and offers it
(Settings → *Check for updates*).

## Build

JDK 21+.

```sh
./gradlew :desktop:run                 # needs libmpv-2.dll in desktop/resources/windows
./gradlew :desktop:packageMsi          # needs a JDK with jpackage
```

libmpv for Windows comes from [shinchiro's mpv builds](https://github.com/shinchiro/mpv-winbuild-cmake/releases)
(`mpv-dev-x86_64-*.7z`, the file `libmpv-2.dll`), with the [Vulkan loader](https://vulkan.lunarg.com/sdk/home)
(`vulkan-1.dll`) next to it. The release workflow downloads both.

## License

GPL-3.0. Not affiliated with YouTube or Google.
