$ErrorActionPreference = 'Stop'
# the MSI from the GitHub release; Chocolatey uninstalls it through Windows' own entry
Install-ChocolateyPackage -PackageName 'tubehaus' -FileType 'msi' `
  -Url64bit 'https://github.com/biolend83/tubehaus/releases/download/app-v1.5.0/TubeHaus-1.5.0.msi' `
  -Checksum64 '1ae9c6fe1f2baa52016105792cbfb4f9cf30f2ec9217a0bdbba3d12915d7f5bb' -ChecksumType64 'sha256' `
  -SilentArgs '/qn /norestart' -ValidExitCodes @(0, 3010, 1641)
