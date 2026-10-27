<div align="center">

# TubeHaus for Linux

**YouTube on your own terms — and everything runs on your computer.**

No server, no account, no Google sign-in, no tracking. The app talks to YouTube directly,
keeps your subscriptions, history and playlists on the device, and works out recommendations
there too.

**[⬇ .deb (Debian, Ubuntu, Mint)](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-amd64.deb)** · **[⬇ .rpm (Fedora, openSUSE)](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-x86_64.rpm)** · **[⬇ tar.gz (Arch, CachyOS, any distro)](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-x64.tar.gz)** (0.5.3, beta)

[Android](https://github.com/biolend83/tubehaus/tree/android-app) · [Windows](https://github.com/biolend83/tubehaus/tree/windows-app) · [macOS](https://github.com/biolend83/tubehaus/tree/mac-app) · **Linux** · [Web app (self-hosted)](https://github.com/biolend83/tubehaus) · [All releases](https://github.com/biolend83/tubehaus/releases)

</div>

## What it does

- **Offline downloads** — save a video in the quality you pick and watch it without internet
  (Library → Downloads). Nothing is converted: the app keeps YouTube's own files.
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

TubeHaus uses your system's mpv library for playback, so install that first. x86-64 only for now.

**Debian, Ubuntu, Mint, Pop!_OS**
```sh
sudo apt install libmpv2 ./TubeHaus-0.3.9-linux-amd64.deb
```

**Fedora, openSUSE**
```sh
sudo dnf install mpv-libs ./TubeHaus-0.3.9-linux-x86_64.rpm
```

**Arch, CachyOS, Manjaro, and anything else**
```sh
sudo pacman -S mpv            # or your distro's mpv / libmpv package
tar xzf TubeHaus-0.3.9-linux-x64.tar.gz
./TubeHaus/bin/TubeHaus
```

The app checks for a newer version once a day and offers it (Settings → *Check for updates*).
The Linux build is new and hasn't been tested on many distros yet: reports are welcome in the
[issues](https://github.com/biolend83/tubehaus/issues).

## Build

JDK 21+ and libmpv (see above).

```sh
./gradlew :desktop:run               # run from source
./gradlew :desktop:packageDeb        # or packageRpm, packageAppImage (needs a JDK with jpackage)
```

## License

GPL-3.0. Not affiliated with YouTube or Google.
