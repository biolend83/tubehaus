# TubeHaus for Apple TV (beta)

A SwiftUI app for tvOS 17+. It runs the same YouTube engine as the iPad app
([`ios/engine/engine.js`](../ios/engine/engine.js)) in JavaScriptCore and plays videos with
Apple's own player (HLS), so the Siri Remote, subtitles and AirPlay work as in any Apple TV app.

- Home: continue watching, then the newest videos from your subscriptions
- Search with suggestions, channels, playlists, "Up next" on every video
- Subscriptions, Watch later and history, kept on the Apple TV only (no account, no server)
- The engine updates itself from GitHub, so a YouTube change doesn't need a new install

## Installing

Apple TV apps can't be sideloaded the way the iPad app can. You need one of:

- **A Mac with Xcode** (a free Apple ID is enough, but the app then has to be reinstalled every 7 days):
  ```sh
  brew install xcodegen
  cd tvos && xcodegen generate && open TubeHausTV.xcodeproj
  ```
  Pick your team under Signing & Capabilities, choose your Apple TV (paired in Xcode → Devices) and press Run.
- **A paid Apple developer account**: build and upload it to TestFlight.

Every push builds an unsigned `TubeHaus-AppleTV.ipa` (Actions → Apple TV → artifacts) and runs the
app in an Apple TV simulator, keeping screenshots.

I don't own an Apple TV, so this is tested in the simulator only. Reports are very welcome.
