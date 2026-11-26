$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v2.0.3/TubeHaus-2.0.3.msi' `
  -Checksum64 'a127899816eab0ff9c32797d37a0764e19c4b1bbd88c9bcbf5b253eb58378f21' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
