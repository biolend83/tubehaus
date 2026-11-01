# Contributing

Thanks for helping out! Bug reports, translations and pull requests are all welcome.

## Where to start

- Issues labelled [`good first issue`](https://github.com/biolend83/tubehaus/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
  are small and self-contained. Comment that you are taking one so nobody does it twice
- [ROADMAP.md](ROADMAP.md) lists what's planned and where help is wanted
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is a map of the code: which file does what

First pull requests get a reply within a day or two, usually sooner.

## Reporting a bug

Open an [issue](https://github.com/biolend83/tubehaus/issues/new/choose) and include your setup
(docker compose or manual), device and browser, and the logs (`docker compose logs tubehaus --tail 100`).
If plain Invidious fails the same way, the problem is probably upstream in Invidious or its companion.

## Pull requests

1. Fork the repo and create a branch
2. Make your change. For anything big, open an issue first so we can agree on the approach
3. Open a pull request describing what changed and how you tested it

### Using AI tools

Welcome, and TubeHaus itself is built with their help. Two things, so reviews stay quick:

- **Say so in the pull request**, in a line (e.g. "drafted with an AI assistant, reviewed and tested by me")
- **Run it yourself before you send it.** Open the app and try the change, and run the checks below.
  Pull requests that were clearly never run may be closed without a detailed review

The code is deliberately dependency-free:

- `web/` is plain ES modules, no build step. Edit and reload
- `server/tubehaus_server.py` uses only the Python standard library
- Shaka Player in `web/y/lib/` is vendored; don't edit it

Before you push, the same checks CI runs:

```sh
for f in web/y/*.js web/y/lang/*.js; do cp "$f" /tmp/c.mjs && node --check /tmp/c.mjs || echo "FAIL $f"; done
python3 -m py_compile server/tubehaus_server.py
python3 -m unittest discover -s tests
node scripts/i18n-check.mjs
```

## The apps (Android, Windows, macOS, Linux, iPad)

The apps live on their own branches, not on `main` (which is the server):

- `android-app` is the one to work on and to send pull requests to. `windows-app`, `mac-app` and `linux-app`
  have the same code with a different README; changes are copied there on release
- `ios-app` adds the iPad/iPhone build on top of the same shared code

Inside the app branch:

- `common/src/app/tubehaus/` is shared by every platform: `engine/` talks to YouTube, `data/` keeps the library,
  `ui/` is the Compose interface
- `android/` and `desktop/` are the platform parts (Media3 player on Android, libmpv on the desktop)
- `common/jvm/` is the engine for Android and the desktop; the iPad uses `ios/engine/engine.js` instead

Build and check (JDK 21, e.g. the one inside Android Studio):

```sh
git clone -b android-app https://github.com/biolend83/tubehaus
cd tubehaus
./gradlew :desktop:run              # run the desktop app (needs mpv / libmpv installed)
./gradlew :android:assembleDebug    # build the Android APK
./gradlew :desktop:engineCheck      # live check of every YouTube call
```

App text is in `common/src/app/tubehaus/ui/Strings.kt`, keyed by the English text. It has English and Korean so
far; another language is a new map next to `KO` and an entry in `LANGS`. The web app's translations in
`web/y/lang/` on `main` are a good starting point.

## Translations

Interface text lives in `web/y/lang/<code>.js`, keyed by the original Korean text. TubeHaus has 13 languages and
isn't taking new ones for now, so every language can be kept complete with each release. Fixes to the existing
translations are very welcome; several were machine-assisted. Edit the values only and keep the keys and the
`{0}` placeholders as they are.

`node scripts/i18n-check.mjs` lists missing keys and `{0}` placeholders that don't match English.
CI runs it in strict mode.

## License

By contributing you agree that your work is released under the [AGPL-3.0](LICENSE), like the rest of the project.
