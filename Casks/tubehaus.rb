# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew trust biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "1.6.3"
  sha256 arm:   "9f6d2ec22b1c0038a03cfc685f3c9ff6b3a3cbe73e199095572b985f1f08fb8c",
         intel: "377b95bd00c0ca9f084e40d6e351bee972fda999f416eec92e37b9fdffa19b30"

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
