<div align="center">

# TubeHaus for iPad and iPhone

**YouTube on your own terms, on the iPad: no account, no tracking, and no server needed.**

The same TubeHaus app as on Android and the desktop, built for iOS. It talks to YouTube directly,
keeps your subscriptions, history and playlists on the iPad, and works out recommendations there too.

**[⬇ TubeHaus-0.5.15-ipad.ipa](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-ipad.ipa)** (iOS 16+, beta) · install it with **[Sideloadly](https://sideloadly.io/)**

<<<<<<< HEAD
Install and update TubeHaus from a source: **AltStore** `https://raw.githubusercontent.com/biolend83/tubehaus/ios-app/altstore/altstore.json` · **SideStore** `https://raw.githubusercontent.com/biolend83/tubehaus/ios-app/altstore/sidestore.json`
=======
**[⬇ Android APK](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15.apk)** · **[⬇ Windows](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15.msi)** · **[⬇ macOS](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-mac-arm64.dmg)** · **[⬇ Linux .deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-amd64.deb)** · **[⬇ iPad / iPhone](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-ipad.ipa)** (0.5.15, beta)
>>>>>>> origin/android-app

[Android](https://github.com/biolend83/tubehaus/tree/android-app) · [Windows](https://github.com/biolend83/tubehaus/tree/windows-app) · [macOS](https://github.com/biolend83/tubehaus/tree/mac-app) · [Linux](https://github.com/biolend83/tubehaus/tree/linux-app) · **iPad / iPhone** · [Web app (self-hosted)](https://github.com/biolend83/tubehaus) · [All releases](https://github.com/biolend83/tubehaus/releases)

<img src="docs/ipad-start.png" alt="TubeHaus on an iPad, the first start: pick a few topics" width="480">

</div>

## Disclaimer

> [!CAUTION]
> **TubeHaus is independent, free, open-source software. It is not affiliated with, endorsed by or sponsored by YouTube or Google.** "YouTube" is a trademark of Google LLC.
>
> - TubeHaus does not host, store or distribute any videos. They come straight from YouTube to your own device (the app) or to a server you run yourself (the server version).
> - **Downloaded videos are for your own personal viewing.** Their copyright belongs to their owners. If you share, upload, sell or otherwise distribute them, that is entirely your own responsibility, and the authors are not liable for it.
> - It is provided "as is", without warranty of any kind, as the license says. The authors and contributors are not responsible for how it is used or for anything that results from using it.
> - You are responsible for using it in line with the laws where you live and the terms of the services you connect to.
> - The app talks only to YouTube (for videos), GitHub (to check for updates) and, if you connect one, your own TubeHaus server. The maintainer runs no server for it.
> - **Rights holders:** if you believe something in this repository infringes your rights, [open an issue](https://github.com/biolend83/tubehaus/issues/new) with the details (or ask there for a private contact). It will be looked at and, where justified, removed promptly.

## Why Sideloadly?

TubeHaus isn't in the App Store, and an iPad only installs apps Apple signed. With
[Sideloadly](https://sideloadly.io/) (free, Windows or Mac) you sign the app yourself with your own Apple ID.
There is no jailbreak and no paid developer account, and your Apple ID only goes to Apple.

## Install

<<<<<<< HEAD
1. Download **[TubeHaus-0.5.15-ipad.ipa](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-ipad.ipa)** and install **[Sideloadly](https://sideloadly.io/)** on your PC or Mac.
2. Connect the iPad with a cable (tap *Trust This Computer* on the iPad if it asks).
3. Drop the .ipa into Sideloadly, enter your Apple ID and press **Start**.
4. On the iPad: Settings → General → **VPN & Device Management** → tap your Apple ID → *Trust*.
5. If it asks, turn on Settings → Privacy & Security → **Developer Mode** and restart.
=======
- **Android 8.0+** — [the APK](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15.apk)
  (beta). Open it on the phone and allow installing from your browser when asked.
  Updates: the app tells you itself, or use Obtainium: [one tap to add it](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22io.github.biolend83.tubehaus%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Fbiolend83%2Ftubehaus%22%2C%20%22author%22%3A%20%22biolend83%22%2C%20%22name%22%3A%20%22TubeHaus%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5ETubeHaus%20%5C%22%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22TubeHaus-%5C%5C%5C%5Cd%5C%22%7D%22%7D) (already set up), or by hand:
  *Add app* → source URL `https://github.com/biolend83/tubehaus` → under *Filter release titles by
  regular expression* enter `^TubeHaus `, turn on *Fallback to older releases*, and under
  *Filter APKs by regular expression* enter `TubeHaus-\d`. The filter
  matters: the same repository also has server releases ("Server 0.7.x") with no APK in them.
- **Android TV / Google TV** — the same APK. In the *Downloader* app, enter the code **3155410**
  (or the address `aftv.news/3155410`): it always gives the newest version. Or use *Send Files to TV*; TubeHaus then shows on the TV's home screen. Settings → *TV mode* turns the
  TV layout on for boxes that don't say they are a TV.
- **Windows 10/11** — [the installer](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15.msi) or [setup .exe](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-win-x64-setup.exe), or a
  [portable .zip](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-win-x64-portable.zip) that needs no install; on Windows on ARM, the
  [ARM .zip](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-win-arm64-portable.zip) (beta, mpv built in). Windows may warn that the publisher is unknown, because the installer
  isn't code-signed: choose *More info* → *Run anyway*.
- **macOS** — [Apple Silicon .dmg](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-mac-arm64.dmg) or [Intel .dmg](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-mac-x64.dmg) (beta;
  .zip versions are on the release page).
  It uses Homebrew's mpv: `brew install mpv`. The app isn't signed by Apple yet, so the first time
  right-click it → *Open* → *Open*.
- **iPad / iPhone (iOS 16+)** — [the .ipa](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-ipad.ipa) (beta). Apple installs only apps it signed, so
  you sign it yourself with **[Sideloadly](https://sideloadly.io/)** (free, Windows or Mac) and your Apple ID: connect the
  iPad with a cable, drop the .ipa into Sideloadly, enter your Apple ID, press *Start*. Then on the
  iPad: Settings → General → *VPN & Device Management* → trust your Apple ID, and turn on
  Settings → Privacy & Security → *Developer Mode* if it asks. With a free Apple ID the app has to be
  re-signed every 7 days (Sideloadly can do that by itself over Wi-Fi). It's the same app, built for
  iOS: Apple's player with picture-in-picture, background play and offline downloads. More on the
  [`ios-app`](https://github.com/biolend83/tubehaus/tree/ios-app) branch.
- **Linux (x86-64 and ARM64)** — [.deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-amd64.deb) for Debian/Ubuntu, [.rpm](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-x86_64.rpm)
  for Fedora/openSUSE, or [a tar.gz](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-x64.tar.gz) to unpack anywhere and run `TubeHaus/bin/TubeHaus`.
  ARM64 (Raspberry Pi 4/5 and others): [.deb](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-arm64.deb), [.rpm](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-aarch64.rpm),
  [tar.gz](https://github.com/biolend83/tubehaus/releases/download/app-v0.5.15/TubeHaus-0.5.15-linux-arm64.tar.gz).
  It uses your system's mpv: `sudo apt install libmpv2` (Fedora: `sudo dnf install mpv-libs`, Arch: `mpv`). New and not yet tested much: reports welcome.
>>>>>>> origin/android-app

**Every 7 days:** with a free Apple ID the signature lasts 7 days. Sideloadly can re-sign the app by
itself over Wi-Fi (turn on its auto-refresh), or just repeat step 3. Your library stays.

**Updates:** install a newer .ipa over the old one the same way; your library stays. The YouTube
engine (see below) updates itself in between, so most fixes for YouTube changes arrive without
reinstalling.

## What it does

Everything the Android app does, with Apple's own player:

- **Player:** AVPlayer, adaptive quality up to 4K, captions, speed, resume where you stopped
- **Background play and picture-in-picture**, also starting by itself when you leave the app
- **Offline downloads:** save a video and watch it without internet (Library → Downloads)
- **Subscriptions without an account,** sorted into groups if you like. Import from YouTube (Google Takeout),
  NewPipe, FreeTube, Invidious, PocketTube (with its groups), TubeHaus or OPML. Pick the files in the Files app
- **Home that learns on the iPad,** Shorts, comments beside the video, search history, Calm mode, Focus switches
- **Sync with your own TubeHaus server (optional):** Settings → *TubeHaus server*, one library on the iPad,
  your other devices and the web version
- **Your files in the Files app:** a "TubeHaus" folder on the iPad holds backups you export and the engine files

## How it works

- **One code base:** the same Kotlin sources as the Android and desktop apps (`common/src`), compiled
  by Kotlin/Native into a framework, with Compose Multiplatform for the screens. The iOS-only parts
  (player, picture-in-picture, files, downloads) are in `ios/src` and `common/ios`.
- **A YouTube engine that updates itself:** on iOS the engine runs as JavaScript in Apple's
  JavaScriptCore (`ios/engine/engine.js`). Every 6 hours the app checks for a newer one from this
  branch and uses it, so a fix for a YouTube change doesn't need a new .ipa or re-signing.
- **TubePatch:** a small Kotlin compiler plugin (`ios/patch`) lets a `patch.js` replace marked
  functions and texts without a new build, for the same reason. It is used only in the iOS build;
  Android and the desktop apps don't contain it.

## Build

You need a Mac with Xcode 16+ and JDK 21. Or let GitHub do it: `.github/workflows/ios.yml` builds an
unsigned .ipa on GitHub's macOS runners and tries it in the iPad simulator.

```sh
./gradlew :ios:linkReleaseFrameworkIosArm64     # the Kotlin side: TubeHausKit.framework
brew install xcodegen
cd ios/app && xcodegen generate                 # TubeHaus.xcodeproj
xcodebuild -project TubeHaus.xcodeproj -scheme TubeHaus -sdk iphoneos -configuration Release   CODE_SIGNING_ALLOWED=NO                       # then zip Payload/TubeHaus.app as a .ipa
```

The Android and desktop builds are explained on the
[`android-app`](https://github.com/biolend83/tubehaus/tree/android-app) branch.

Most of the code is written with Claude Code.

## License

GPL-3.0.
