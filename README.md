<div align="center">

# TubeHaus for Android, Windows, macOS and Linux

**YouTube on your own terms — and everything runs on your device.**

No server, no account, no Google sign-in, no tracking. The app talks to YouTube directly,
keeps your subscriptions, history and playlists on the device, and works out recommendations
there too.

**[⬇ Android APK](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3.apk)** · **[⬇ Windows](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3.msi)** · **[⬇ macOS](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-mac-arm64.dmg)** · **[⬇ Linux .deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-amd64.deb)** (0.5.3, beta)

**Android** · [Windows](https://github.com/biolend83/tubehaus/tree/windows-app) · [macOS](https://github.com/biolend83/tubehaus/tree/mac-app) · [Linux](https://github.com/biolend83/tubehaus/tree/linux-app) · [Web app (self-hosted)](https://github.com/biolend83/tubehaus) · [All releases](https://github.com/biolend83/tubehaus/releases)

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
  - Android: background play with notification controls, picture-in-picture, opens YouTube links,
    swipe on the left/right in full screen for brightness/volume, and a wide layout on tablets.
  - Windows: mpv with GPU decoding, the same controls as on the phone plus a volume bar,
    keyboard shortcuts (space/K, arrows, J/L, F, M) and the mouse wheel for Shorts.
- **Comments beside the video** — on tablets and desktops in a side panel, on phones under the
  video, which keeps playing.
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
ExoPlayer (Media3) on Android, libmpv on Windows.

## Install

- **Android 8.0+** — [the APK](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3.apk)
  (beta). Open it on the phone and allow installing from your browser when asked.
  Updates: [Obtainium](https://obtainium.imranr.dev) with `https://github.com/biolend83/tubehaus`
  (each "TubeHaus 0.x" release has every platform's file).
- **Android TV / Google TV** — the same APK. Install it with an app like *Downloader* or
  *Send Files to TV*; TubeHaus then shows on the TV's home screen. Settings → *TV mode* turns the
  TV layout on for boxes that don't say they are a TV.
- **Windows 10/11 (64-bit)** — [the installer](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3.msi)
  (beta, mpv built in). Windows may warn that the publisher is unknown, because the installer
  isn't code-signed: choose *More info* → *Run anyway*.
- **macOS (Apple Silicon)** — [the .dmg](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-mac-arm64.dmg) (beta, new and barely tested).
  It uses Homebrew's mpv: `brew install mpv`. The app isn't signed by Apple yet, so the first time
  right-click it → *Open* → *Open*.
- **Linux (x86-64)** — [.deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-amd64.deb) for Debian/Ubuntu, [.rpm](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-x86_64.rpm)
  for Fedora/openSUSE, or [a tar.gz](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.3/TubeHaus-0.5.3-linux-x64.tar.gz) to unpack anywhere and run `TubeHaus/bin/TubeHaus`.
  It uses your system's mpv: `sudo apt install libmpv2` (Fedora: `sudo dnf install mpv-libs`, Arch: `mpv`). New and not yet tested much: reports welcome.

There is no iOS version: apps outside the App Store can't be installed on an iPhone without
re-signing them every week. On iPhone and iPad, use the TubeHaus web app.

## Build

JDK 21+ and the Android SDK.

```sh
./gradlew :android:assembleRelease     # android/build/outputs/apk/release
./gradlew :desktop:run                 # needs libmpv-2.dll in desktop/resources/windows
./gradlew :desktop:packageMsi          # needs a JDK with jpackage
```

libmpv for Windows comes from [shinchiro's mpv builds](https://github.com/shinchiro/mpv-winbuild-cmake/releases)
(`mpv-dev-x86_64-*.7z`, the file `libmpv-2.dll`). The release workflow downloads it.

## License

GPL-3.0. Not affiliated with YouTube or Google.
