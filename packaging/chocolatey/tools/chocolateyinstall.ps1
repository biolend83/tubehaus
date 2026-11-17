$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v1.6.3/TubeHaus-1.6.3.msi' `
  -Checksum64 '6cd4eeb2cfbed73473276ba6c19b8c263a0535a736ae04fb6e02f3f213d086ae' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
