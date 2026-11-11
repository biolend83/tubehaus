# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "1.0.5"
  sha256 arm:   "d32061497a82d0932743ea729b3636c13296c8eb3b51427069a6247533b65d4f",
         intel: "69171ccb0ea5e18307a71d2ad370c5be579bca4e093b719d5b12ecf33a621833"

  url "https://github.com/biolend83/tubehaus/releases/download/app-v#{version}/TubeHaus-#{version}-mac-#{arch}.dmg"
  name "TubeHaus"
  desc "Private YouTube client with no Google account, no ads and no tracking"
  homepage "https://github.com/biolend83/tubehaus"

  livecheck do
    url "https://github.com/biolend83/tubehaus/releases"
    regex(/app-v(\d+(?:\.\d+)+)/i)
    strategy :github_releases
  end

  depends_on formula: "mpv"
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
