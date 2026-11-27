$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v2.1.0/TubeHaus-2.1.0.msi' `
  -Checksum64 '86d79dfbab5630831fee5bb2782dc69d7b8dee0f59289d27a1f98a56047281fa' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
