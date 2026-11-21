# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew trust biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "1.7.1"
  sha256 arm:   "f2caf6a8d6b3928e86d50c61be77a2f03e8e92268c04347e262203e7d3bf7414",
         intel: "cb0a9eb5ebabdc2e5ce0c806dbd65b6273df6aeb23cb689e5b51aeba96d34958"

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
