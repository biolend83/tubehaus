$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v2.0.0/TubeHaus-2.0.0.msi' `
  -Checksum64 '13d70158ad9654c70feecf73be79df6d60edd95b002520aad66c19eaf7b58dd5' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
