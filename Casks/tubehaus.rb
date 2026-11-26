# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew trust biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "2.0.2"
  sha256 arm:   "b37c9d35286f2876f5cfa58cf074701183beb9880a260db91a3bafed4d992af0",
         intel: "e63c4e0e64574fa8a7ec18ddabed4138bc8c637270871076786074ce8aecf9c7"

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
