$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v1.8.0/TubeHaus-1.8.0.msi' `
  -Checksum64 'a0c0d85efb0a77ec24e30c0eb46c5b4274d4f8c2b0d85133be6ef48c8e67b973' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
