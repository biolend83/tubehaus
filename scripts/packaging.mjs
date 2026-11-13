// Package manager files for one app release, from its checksums:
//   node scripts/packaging.mjs 1.0.5
// reads SHA256SUMS-desktop.txt of app-v1.0.5 on GitHub and rewrites
//   Casks/tubehaus.rb                 Homebrew (macOS):  brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
//   bucket/tubehaus.json              Scoop (Windows):   scoop bucket add tubehaus https://github.com/biolend83/tubehaus
//   packaging/winget/...              winget manifests, to send to microsoft/winget-pkgs
//   packaging/chocolatey/...          Chocolatey package, to push to community.chocolatey.org
//   packaging/aur/PKGBUILD, .SRCINFO  Arch Linux (AUR) package tubehaus-bin
// Run after the release's desktop files are attached (the README's download links are bumped then too).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const v = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(v || "")) { console.error("version? e.g. node scripts/packaging.mjs 1.0.5"); process.exit(1); }
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const base = `https://github.com/biolend83/tubehaus/releases/download/app-v${v}`;
const sums = process.argv[3] ? fs.readFileSync(process.argv[3], "utf8") : await (async () => {
  const r = await fetch(`${base}/SHA256SUMS-desktop.txt`);
  if (!r.ok) throw new Error(`SHA256SUMS-desktop.txt of app-v${v}: HTTP ${r.status}`);
  return r.text();
})();
const sha = {};
for (const l of sums.split("\n")) { const m = l.trim().match(/^([0-9a-f]{64})\s+\*?(\S+)$/); if (m) sha[m[2]] = m[1]; }
const need = (name) => { if (!sha[name]) throw new Error("no checksum for " + name); return sha[name]; };
const f = {
  macArm: `TubeHaus-${v}-mac-arm64.dmg`, macX64: `TubeHaus-${v}-mac-x64.dmg`,
  winZip: `TubeHaus-${v}-win-x64-portable.zip`, winArm: `TubeHaus-${v}-win-arm64-portable.zip`, msi: `TubeHaus-${v}.msi`,
  deb: `TubeHaus-${v}-linux-amd64.deb`, debArm: `TubeHaus-${v}-linux-arm64.deb`,
};
function write(rel, text) {
  const p = path.join(root, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, text);
  console.log("wrote", rel);
}
const DESC = "Private YouTube client with no Google account, no ads and no tracking";

// ------------------------------------------------------------------ Homebrew
write("Casks/tubehaus.rb", `# brew tap biolend83/tubehaus https://github.com/biolend83/tubehaus
# brew trust biolend83/tubehaus
# brew install --cask tubehaus
cask "tubehaus" do
  arch arm: "arm64", intel: "x64"

  version "${v}"
  sha256 arm:   "${need(f.macArm)}",
         intel: "${need(f.macX64)}"

  url "https://github.com/biolend83/tubehaus/releases/download/app-v#{version}/TubeHaus-#{version}-mac-#{arch}.dmg"
  name "TubeHaus"
  desc "${DESC}"
  homepage "https://github.com/biolend83/tubehaus"

  livecheck do
    url "https://github.com/biolend83/tubehaus/releases"
    regex(/app-v(\\d+(?:\\.\\d+)+)/i)
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
`);

// ------------------------------------------------------------------ Scoop
write("bucket/tubehaus.json", JSON.stringify({
  version: v,
  description: DESC,
  homepage: "https://github.com/biolend83/tubehaus",
  license: "GPL-3.0-only",
  architecture: {
    "64bit": { url: `${base}/${f.winZip}`, hash: need(f.winZip) },
    arm64: { url: `${base}/${f.winArm}`, hash: need(f.winArm) },
  },
  extract_dir: "TubeHaus",
  shortcuts: [["TubeHaus.exe", "TubeHaus"]],
  checkver: { url: "https://api.github.com/repos/biolend83/tubehaus/releases/latest", jsonpath: "$.tag_name", regex: "app-v([\\d.]+)" },
  autoupdate: {
    architecture: {
      "64bit": { url: "https://github.com/biolend83/tubehaus/releases/download/app-v$version/TubeHaus-$version-win-x64-portable.zip" },
      arm64: { url: "https://github.com/biolend83/tubehaus/releases/download/app-v$version/TubeHaus-$version-win-arm64-portable.zip" },
    },
    hash: { url: "$baseurl/SHA256SUMS-desktop.txt" },
  },
}, null, 4) + "\n");

// ------------------------------------------------------------------ winget
const wg = `packaging/winget/manifests/b/biolend83/TubeHaus/${v}`;
fs.rmSync(path.join(root, "packaging/winget/manifests"), { recursive: true, force: true });
write(`${wg}/biolend83.TubeHaus.yaml`, `# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.1.9.0.schema.json
PackageIdentifier: biolend83.TubeHaus
PackageVersion: ${v}
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.9.0
`);
write(`${wg}/biolend83.TubeHaus.installer.yaml`, `# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json
PackageIdentifier: biolend83.TubeHaus
PackageVersion: ${v}
MinimumOSVersion: 10.0.17763.0
InstallerType: wix
UpgradeBehavior: install
ReleaseDate: ${new Date().toISOString().slice(0, 10)}
Installers:
- Architecture: x64
  InstallerUrl: ${base}/${f.msi}
  InstallerSha256: ${need(f.msi).toUpperCase()}
ManifestType: installer
ManifestVersion: 1.9.0
`);
write(`${wg}/biolend83.TubeHaus.locale.en-US.yaml`, `# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.1.9.0.schema.json
PackageIdentifier: biolend83.TubeHaus
PackageVersion: ${v}
PackageLocale: en-US
Publisher: biolend83
PublisherUrl: https://github.com/biolend83
PublisherSupportUrl: https://github.com/biolend83/tubehaus/issues
PackageName: TubeHaus
PackageUrl: https://github.com/biolend83/tubehaus
License: GPL-3.0
LicenseUrl: https://github.com/biolend83/tubehaus/blob/android-app/LICENSE
ShortDescription: ${DESC}
Description: |-
  TubeHaus is a free, open-source YouTube client. It needs no Google account, shows no ads and
  doesn't track you: subscriptions, history and playlists stay on your computer. It has its own
  YouTube engine, plays up to 4K with mpv, and can sync its library with a TubeHaus server you run
  yourself. Not affiliated with YouTube or Google.
Tags:
- youtube
- video
- privacy
- open-source
ReleaseNotesUrl: https://github.com/biolend83/tubehaus/releases/tag/app-v${v}
ManifestType: defaultLocale
ManifestVersion: 1.9.0
`);

// ------------------------------------------------------------------ Chocolatey
// choco pack packaging/chocolatey/tubehaus.nuspec, then choco push with an API key from community.chocolatey.org
const x = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
write("packaging/chocolatey/tubehaus.nuspec", `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://schemas.microsoft.com/packaging/2015/06/nuspec.xsd">
  <metadata>
    <id>tubehaus</id>
    <version>${v}</version>
    <title>TubeHaus</title>
    <authors>biolend83</authors>
    <owners>biolend83</owners>
    <projectUrl>https://github.com/biolend83/tubehaus</projectUrl>
    <packageSourceUrl>https://github.com/biolend83/tubehaus/tree/main/packaging/chocolatey</packageSourceUrl>
    <projectSourceUrl>https://github.com/biolend83/tubehaus/tree/android-app</projectSourceUrl>
    <docsUrl>https://github.com/biolend83/tubehaus#readme</docsUrl>
    <bugTrackerUrl>https://github.com/biolend83/tubehaus/issues</bugTrackerUrl>
    <releaseNotes>https://github.com/biolend83/tubehaus/releases/tag/app-v${v}</releaseNotes>
    <iconUrl>https://cdn.jsdelivr.net/gh/biolend83/tubehaus@main/web/y/icon-512.png</iconUrl>
    <licenseUrl>https://github.com/biolend83/tubehaus/blob/android-app/LICENSE</licenseUrl>
    <requireLicenseAcceptance>false</requireLicenseAcceptance>
    <tags>youtube video player privacy open-source foss</tags>
    <summary>${x(DESC)}</summary>
    <description>${x(`TubeHaus is a free, open-source YouTube client. It needs no Google account, shows no ads and doesn't track you: subscriptions, history and playlists stay on your computer. It has its own YouTube engine, plays up to 4K with mpv (included), and can sync its library with a TubeHaus server you run yourself.

Not affiliated with YouTube or Google.`)}</description>
  </metadata>
  <files>
    <file src="tools\\**" target="tools" />
  </files>
</package>
`);
write("packaging/chocolatey/tools/chocolateyinstall.ps1", `$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' \`
  -Url64bit '${base}/${f.msi}' \`
  -Checksum64 '${need(f.msi)}' -ChecksumType64 'sha256' \`
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
`);

// ------------------------------------------------------------------ AUR (tubehaus-bin)
const pkgbuild = `# Maintainer: biolend83 <258010694+biolend83@users.noreply.github.com>
pkgname=tubehaus-bin
pkgver=${v}
pkgrel=1
pkgdesc="${DESC}"
arch=('x86_64' 'aarch64')
url="https://github.com/biolend83/tubehaus"
license=('GPL-3.0-only')
depends=('mpv' 'alsa-lib' 'fontconfig' 'freetype2' 'libglvnd' 'libx11' 'libxext' 'libxi' 'libxrender' 'libxtst' 'xdg-utils')
provides=('tubehaus')
conflicts=('tubehaus')
options=('!strip')
source_x86_64=("TubeHaus-\${pkgver}-linux-amd64.deb::https://github.com/biolend83/tubehaus/releases/download/app-v\${pkgver}/TubeHaus-\${pkgver}-linux-amd64.deb")
source_aarch64=("TubeHaus-\${pkgver}-linux-arm64.deb::https://github.com/biolend83/tubehaus/releases/download/app-v\${pkgver}/TubeHaus-\${pkgver}-linux-arm64.deb")
sha256sums_x86_64=('${need(f.deb)}')
sha256sums_aarch64=('${need(f.debArm)}')
noextract=("TubeHaus-\${pkgver}-linux-amd64.deb" "TubeHaus-\${pkgver}-linux-arm64.deb")

package() {
  local deb
  for deb in "\${srcdir}"/TubeHaus-\${pkgver}-linux-*.deb; do
    bsdtar -xOf "\${deb}" 'data.tar.*' | bsdtar -xf - -C "\${pkgdir}"
  done
  install -Dm644 "\${pkgdir}/opt/tubehaus/lib/tubehaus-TubeHaus.desktop" "\${pkgdir}/usr/share/applications/tubehaus.desktop"
  install -Dm644 "\${pkgdir}/opt/tubehaus/lib/TubeHaus.png" "\${pkgdir}/usr/share/pixmaps/tubehaus.png"
  install -d "\${pkgdir}/usr/bin"
  ln -s /opt/tubehaus/bin/TubeHaus "\${pkgdir}/usr/bin/tubehaus"
}
`;
write("packaging/aur/PKGBUILD", pkgbuild);
write("packaging/aur/.SRCINFO", `pkgbase = tubehaus-bin
\tpkgdesc = ${DESC}
\tpkgver = ${v}
\tpkgrel = 1
\turl = https://github.com/biolend83/tubehaus
\tarch = x86_64
\tarch = aarch64
\tlicense = GPL-3.0-only
\tdepends = mpv
\tdepends = alsa-lib
\tdepends = fontconfig
\tdepends = freetype2
\tdepends = libglvnd
\tdepends = libx11
\tdepends = libxext
\tdepends = libxi
\tdepends = libxrender
\tdepends = libxtst
\tdepends = xdg-utils
\tprovides = tubehaus
\tconflicts = tubehaus
\tnoextract = TubeHaus-${v}-linux-amd64.deb
\tnoextract = TubeHaus-${v}-linux-arm64.deb
\toptions = !strip
\tsource_x86_64 = TubeHaus-${v}-linux-amd64.deb::${base}/${f.deb}
\tsha256sums_x86_64 = ${need(f.deb)}
\tsource_aarch64 = TubeHaus-${v}-linux-arm64.deb::${base}/${f.debArm}
\tsha256sums_aarch64 = ${need(f.debArm)}

pkgname = tubehaus-bin
`);
