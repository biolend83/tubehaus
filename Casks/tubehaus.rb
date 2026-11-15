# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew trust biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "1.6.1"
  sha256 arm:   "e39e75fddb03ae53d7d446d896e333741b921b1ec03d9fe8594d0b33e99cc871",
         intel: "20e99fa7849ab26504ba28bf0febbabecff57c4ae3ba6bbb6dc96caa2cc47304"

  url "https://github.com/biolend83/tubehaus/releases/download/app-v#{version}/TubeHaus-#{version}-mac-#{arch}.dmg"
  name "TubeHaus"
  desc "Private YouTube client with no Google account, no ads and no tracking"
  homepage "https://github.com/biolend83/tubehaus"

  livecheck do
    url "https://github.com/biolend83/tubehaus/releases"
    regex(/app-v(\d+(?:\.\d+)+)/i)
    strategy :github_releases
  end

  depends_on macos: ">= :big_sur"

  app "TubeHaus.app"

  # the app isn't signed by Apple yet: without this macOS says it is damaged
  postflight do
    system_command "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "#{appdir}/TubeHaus.app"], sudo: false
  end

  zap trash: [
    "~/Library/Application Support/TubeHaus",
    "~/Library/Caches/TubeHaus",
  ]

  caveats <<~EOS
    TubeHaus isn't signed by Apple yet. If macOS still refuses to open it,
    right-click the app, choose Open, then Open again.
  EOS
end
