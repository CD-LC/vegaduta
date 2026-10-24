# Runs tests/install/vegaduta-ps1.Tests.ps1 (Pester 5) under the PowerShell that runs this script.
#
#   pwsh -NoProfile -File tests/install/run-vegaduta-ps1-tests.ps1        # PowerShell 7
#   powershell -NoProfile -File tests\install\run-vegaduta-ps1-tests.ps1   # Windows PowerShell 5.1
#
# Installs Pester for the current user when the pinned version is missing (Windows PowerShell 5.1
# ships Pester 3.4, which cannot run these tests). The `windows-launcher` job of .github/workflows/ci.yml
# calls this once per PowerShell. Exit code: the number of failed tests.
param([string]$PesterVersion = '5.7.1')

$ErrorActionPreference = 'Stop'
Write-Host ('PowerShell {0} ({1})' -f $PSVersionTable.PSVersion, $PSVersionTable.PSEdition)

$have = @(Get-Module -ListAvailable -Name Pester | Where-Object { $_.Version -eq [version]$PesterVersion })
if ($have.Count -eq 0) {
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
  try { Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Scope CurrentUser | Out-Null } catch { }
  Install-Module -Name Pester -RequiredVersion $PesterVersion -Scope CurrentUser -Force -SkipPublisherCheck
}
Import-Module Pester -RequiredVersion $PesterVersion -Force

$cfg = New-PesterConfiguration
$cfg.Run.Path = Join-Path $PSScriptRoot 'vegaduta-ps1.Tests.ps1'
$cfg.Run.Exit = $true
$cfg.Output.Verbosity = 'Detailed'
Invoke-Pester -Configuration $cfg
