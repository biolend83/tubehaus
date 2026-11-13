# TubeHaus for Apple TV (beta)

A SwiftUI app for tvOS 17+. It runs the same YouTube engine as the iPad app
([`ios/engine/engine.js`](../ios/engine/engine.js)) in JavaScriptCore and plays videos with
Apple's own player (HLS), so the Siri Remote, subtitles and AirPlay work as in any Apple TV app.

- The same screens as the Android TV app: the TubeHaus bar along the top, the side menu (Home,
  Shorts, Subscriptions, Library, Resume, Settings), big cards two across with a teal frame on the
  one the remote is on; hold OK on a video for Watch later, Not interested or Don't recommend this channel
- Home greets you and starts with **Continue watching** (videos you stopped part-way), as in TubeHaus 1.5
- Home: recommendations worked out on the TV from what you watch and like (the same recommender as
  the Android app; nothing leaves the TV), the region to follow, and Discover for topics you don't
  usually watch. Library shows what it learned, and each score can be removed
- A video plays on its own page with Up next or the comments beside it; OK opens it full screen in
  Apple's player (Siri Remote, AirPlay, audio tracks), Back returns to the page
- **This video at a glance** under a video: the words it keeps coming back to and its key moments
  (OK plays from there), made on the TV from the captions (Settings → Video at a glance)
- Shorts, one at a time: up and down on the remote to go on
- Import your subscriptions with your phone: scan the QR code and send a YouTube Takeout, NewPipe,
  FreeTube, Invidious, TubeHaus or OPML file (or paste channel links) over your home network
- Subtitles (YouTube's own tracks, or auto-translated into the TV's language) and playback speed in
  the full screen player's top bar; the next video plays on its own (Settings)
- Search with filters (videos, channels, playlists) as a list or as cards; a pasted link or @handle
  opens it. Channels with their Videos, Shorts, Live and Playlists; likes, dislikes, Watch later and
  history, kept on the Apple TV only (no account, no server)
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
