$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v2.0.2/TubeHaus-2.0.2.msi' `
  -Checksum64 'c21b5bdd6f91c9c11f87dd5fd48a7bd601f6ac578ddeb1ecbc886a434920e0b2' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
