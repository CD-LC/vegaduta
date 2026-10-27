<#
.SYNOPSIS
  vegaduta - install / run / operate a self-hosted VegaDuta with Docker Compose (Windows).

.DESCRIPTION
  Same commands as scripts/vegaduta (bash):
    .\scripts\vegaduta.ps1 up [-Lite|-Full] [-Profile a,b] [-Build] [-Domain example.com]
    .\scripts\vegaduta.ps1 personal [-Build] [-Search|-NoSearch] [-Cloud] [-LocalModel TAG] [-DryRun]
                                              (one-person laptop mode, no Keycloak; the host-JVM -NativeCore option is bash-only: use WSL2;
                                               -DryRun prints the plan and starts nothing; -Search adds private web search (SearXNG))
    .\scripts\vegaduta.ps1 down [-Volumes]
    .\scripts\vegaduta.ps1 status
    .\scripts\vegaduta.ps1 logs [service] [-NoFollow]
    .\scripts\vegaduta.ps1 doctor [-Lite|-Full]
    .\scripts\vegaduta.ps1 secrets [-Domain example.com]
    .\scripts\vegaduta.ps1 backup [DIR]
    .\scripts\vegaduta.ps1 restore DIR [-Yes]
    .\scripts\vegaduta.ps1 upgrade [-Version 1.2.3] [-NoBackup]   (downloads and verifies that release's compose files first)
    .\scripts\vegaduta.ps1 models pull TAG     (download a local model into the install's Ollama; `models list|rm` are bash-only)
    .\scripts\vegaduta.ps1 make-admin you@example.com
    .\scripts\vegaduta.ps1 compose <docker compose args...>
  Works with Windows PowerShell 5.1 and PowerShell 7. Requires Docker Desktop.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)] [string] $Command = 'help',
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest = @(),
  [switch] $Lite,
  [switch] $Full,
  [Alias('Profile')] [string[]] $ProfileNames = @(),
  [switch] $Build,
  [switch] $NativeCore,
  [string] $Domain = '',
  [switch] $Volumes,
  [switch] $Yes,
  [switch] $NoFollow,
  [string] $Version = '',
  [switch] $NoBackup,
  [switch] $Cloud,
  [string] $LocalModel = '',
  [switch] $Search,
  [switch] $NoSearch,
  [switch] $DryRun,
  [switch] $Continued   # internal: `upgrade` re-runs itself with the new release's script after unpacking it
)

$ErrorActionPreference = 'Stop'
$RepoRoot   = Split-Path -Parent $PSScriptRoot
$ComposeDir = Join-Path $RepoRoot 'deploy\compose'
$EnvFile    = if ($env:VEGADUTA_ENV_FILE) { $env:VEGADUTA_ENV_FILE } else { Join-Path $ComposeDir '.env' }
$EnvExample = Join-Path $ComposeDir '.env.example'
$StateFile  = Join-Path $ComposeDir '.vegaduta-state'
$AllProfiles = @('temporal','events','jobs','observability','tracing','voice','rl','code-intel','builder','sandbox','tools','browser')

# NAME = kind (hex16|hex32|b64|fernet|lfpk|lfsk) - keep in sync with scripts/vegaduta
$SecretSpecs = [ordered]@{
  POSTGRES_PASSWORD='hex16'; POSTGRES_APP_PASSWORD='hex16'; VALKEY_PASSWORD='hex16'; APP_ENCRYPTION_KEY='b64'; PLATFORM_MASTER_KEY='b64'
  KEYCLOAK_ADMIN_PASSWORD='hex16'; KEYCLOAK_CLIENT_SECRET='hex32'; KEYCLOAK_DB_PASSWORD='hex16'
  STORAGE_ACCESS_KEY='hex16'; STORAGE_SECRET_KEY='hex32'; SIDECAR_SHARED_SECRET='hex32'
  DOCLING_SHARED_SECRET='hex32'
  CODE_INTEL_INTERNAL_TOKEN='hex32'; TOOL_SANDBOX_INTERNAL_TOKEN='hex32'; MCP_STDIO_SIDECAR_SHARED_SECRET='hex32'; WORKSPACE_RUNNER_SECRET='hex32'
  APP_BUILDER_CORE_SERVICE_TOKEN='hex32'; APP_BUILDER_ENCRYPTION_KEY='fernet'; RL_TRAINER_INTERNAL_TOKEN='hex32'
  PLATFORM_ALERTS_WEBHOOK_SECRET='b64'; TEMPORAL_DB_PASSWORD='hex16'; LIVEKIT_API_KEY='hex16'; LIVEKIT_API_SECRET='b64'
  GRAFANA_ADMIN_PASSWORD='hex16'; LANGFUSE_POSTGRES_PASSWORD='hex16'; LANGFUSE_CLICKHOUSE_PASSWORD='hex16'
  LANGFUSE_REDIS_AUTH='hex16'; LANGFUSE_S3_SECRET_KEY='hex32'; LANGFUSE_SALT='hex32'; LANGFUSE_ENCRYPTION_KEY='hex32'
  LANGFUSE_NEXTAUTH_SECRET='hex32'; LANGFUSE_INIT_PROJECT_PUBLIC_KEY='lfpk'; LANGFUSE_INIT_PROJECT_SECRET_KEY='lfsk'
  LANGFUSE_INIT_USER_PASSWORD='hex16'; SEARXNG_SECRET='hex32'
  WHATSAPP_QR_BRIDGE_SECRET='hex32'; DD_ADMIN_PASSWORD='hex16'; DD_SECRET_KEY='hex32'; DD_CREDENTIAL_AES_256_KEY='hex32'; DD_DATABASE_PASSWORD='hex16'
}

function Say($m)  { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [ok]   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [warn] $m" -ForegroundColor Yellow; $script:Warnings++ }
function Bad($m)  { Write-Host "  [fail] $m" -ForegroundColor Red; $script:Failures++ }
function Die($m)  { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
$script:Warnings = 0; $script:Failures = 0

# Runs a native command with its stderr discarded and returns its stdout lines; the exit code is left in
# $script:NativeExit (1 when the program cannot be started). Under $ErrorActionPreference = 'Stop', Windows
# PowerShell 5.1 turns the first line a redirected native command writes to stderr into a terminating
# NativeCommandError before the caller can look at $LASTEXITCODE (`docker volume inspect` of a volume that
# does not exist, `docker version` with the daemon down, ...). The preference is relaxed here, and only
# here: no other function redirects a native command's stderr (tests/install/vegaduta-ps1.Tests.ps1).
function Invoke-Native([string]$Exe, [string[]]$ArgList = @()) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $script:NativeExit = 1; $out = @()
  try { $out = @(& $Exe @ArgList 2>$null); if ($null -ne $LASTEXITCODE) { $script:NativeExit = $LASTEXITCODE } else { $script:NativeExit = 0 } }
  catch { $out = @(); $script:NativeExit = 1 }
  finally { $ErrorActionPreference = $prev }
  return $out
}
# Memory Docker has (the Docker Desktop / WSL2 VM), in bytes; 0 when Docker does not answer.
function Get-DockerMemBytes {
  $l = @(Invoke-Native docker @('info', '--format', '{{.MemTotal}}'))
  if ($script:NativeExit -eq 0 -and $l.Count -gt 0 -and ("$($l[0])".Trim() -match '^\d+$')) { return [int64]"$($l[0])".Trim() }
  return [int64]0
}
# The computer's own memory in GB (0 when unknown). Core in a container sees only the Docker VM.
function Get-HostRamGb {
  try { return [int][math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB) } catch { return 0 }
}
# What the console's hardware panel is told about the GPU: "nvidia:NAME:GB" with an NVIDIA driver
# (nvidia-smi), else nothing; the same values as host_accelerator in scripts/vegaduta.
function Get-HostAccelerator {
  if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) { return '' }
  $l = @(Invoke-Native nvidia-smi @('--query-gpu=name,memory.total', '--format=csv,noheader,nounits'))
  if ($script:NativeExit -ne 0 -or $l.Count -eq 0) { return '' }
  $line = "$($l[0])"; $i = $line.LastIndexOf(',')
  if ($i -lt 1) { return '' }
  $name = $line.Substring(0, $i).Trim(); $mib = ($line.Substring($i + 1) -replace '[^0-9]', '')
  if (-not $name -or -not $mib) { return '' }
  return ('nvidia:{0}:{1}' -f $name, [int][math]::Floor(([int]$mib + 512) / 1024))
}

# ------------------------------------------------------------ env file I/O
function Read-EnvLines { if (Test-Path $EnvFile) { return [System.IO.File]::ReadAllLines($EnvFile) } return @() }
# Owner-only access for files holding secrets (.env, backups): an explicit
# ACL with inheritance removed on Windows, mode 600 elsewhere (INF-003).
function Protect-SecretFile([string]$Path) {
  try {
    if ($IsLinux -or $IsMacOS) { & chmod 600 $Path; return }
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath $Path -AclObject $acl
  } catch { Write-Host "  [warn] could not restrict access to ${Path}: $_" -ForegroundColor Yellow }
}
function Write-EnvLines($lines) {
  $utf8 = New-Object System.Text.UTF8Encoding($false)   # no BOM, LF endings (compose-friendly)
  [System.IO.File]::WriteAllText($EnvFile, (($lines -join "`n") + "`n"), $utf8)
  Protect-SecretFile $EnvFile
}
# The release these compose files belong to (stamped into .env.example by the
# release workflow; empty in a git checkout).
function Get-ShippedVersion {
  if (-not (Test-Path $EnvExample)) { return '' }
  $v = ''
  foreach ($l in [System.IO.File]::ReadAllLines($EnvExample)) { if ($l.StartsWith('VEGADUTA_VERSION=')) { $v = $l.Substring(17).Trim() } }
  return $v
}
# Every image tag comes from VEGADUTA_VERSION (no `latest` default).
function Resolve-Version {
  $v = Get-EnvValue 'VEGADUTA_VERSION'
  if ($v) { $env:VEGADUTA_VERSION = $v; return }
  if ($script:DoBuild) { $env:VEGADUTA_VERSION = 'local'; return }
  # A git checkout or a download of main has no release stamped in: use the newest published one.
  try {
    # /releases/latest follows whichever release was published most recently -
    # a component release (sdk-v*, pysdk-*) beats the platform tag - so pick the
    # newest tag matching v<digit>.<digit>.<digit> from the list.
    $repo = if ($env:VEGADUTA_REPO) { $env:VEGADUTA_REPO } else { 'CD-LC/vegaduta' }
    $rs = Invoke-RestMethod -UseBasicParsing -TimeoutSec 20 -Headers @{ 'User-Agent' = 'vegaduta-setup' } "https://api.github.com/repos/$repo/releases?per_page=30"
    foreach ($r in $rs) {
      $tag = [string]$r.tag_name
      if ($tag -match '^v(\d+\.\d+\.\d+)$') {
        Say "No release pinned in ${EnvFile}: using the latest published platform release ($($Matches[1]))"
        Set-EnvValue VEGADUTA_VERSION $Matches[1] -Force
        $env:VEGADUTA_VERSION = $Matches[1]; return
      }
    }
  } catch { }
  Die "VEGADUTA_VERSION is empty in $EnvFile and the latest release could not be looked up (no network?). Set a published release (.\scripts\vegaduta.ps1 upgrade -Version 1.2.3) or build from source with -Build."
}
function Get-EnvValue([string]$Key) {
  $v = ''
  foreach ($l in (Read-EnvLines)) {
    if ($l.StartsWith("$Key=")) { $v = $l.Substring($Key.Length + 1).Trim() }
  }
  if ($v.Length -ge 2 -and (($v[0] -eq '"' -and $v[-1] -eq '"') -or ($v[0] -eq "'" -and $v[-1] -eq "'"))) { $v = $v.Substring(1, $v.Length - 2) }
  return $v
}
# Never replaces a non-empty value unless -Force.
function Set-EnvValue([string]$Key, [string]$Value, [switch]$Force) {
  if (-not $Force -and (Get-EnvValue $Key)) { return }
  $lines = @(Read-EnvLines); $found = $false
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i].StartsWith("$Key=")) { $lines[$i] = "$Key=$Value"; $found = $true }
  }
  if (-not $found) { $lines += "$Key=$Value" }
  Write-EnvLines $lines
}

function New-RandomBytes([int]$n) {
  $b = New-Object byte[] $n
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($b); $rng.Dispose()
  return $b
}
function New-Secret([string]$kind) {
  switch ($kind) {
    'hex16'  { return (-join ((New-RandomBytes 16) | ForEach-Object { $_.ToString('x2') })) }
    'hex32'  { return (-join ((New-RandomBytes 32) | ForEach-Object { $_.ToString('x2') })) }
    'b64'    { return [Convert]::ToBase64String((New-RandomBytes 32)) }
    'fernet' { return [Convert]::ToBase64String((New-RandomBytes 32)).Replace('+','-').Replace('/','_') }
    'lfpk'   { return 'pk-lf-' + (-join ((New-RandomBytes 16) | ForEach-Object { $_.ToString('x2') })) }
    'lfsk'   { return 'sk-lf-' + (-join ((New-RandomBytes 16) | ForEach-Object { $_.ToString('x2') })) }
    default  { Die "unknown secret kind $kind" }
  }
}
# A name a compose file requires (${NAME:?...}) that is plainly a secret: generated, never asked for.
# BASE_DOMAIN, VEGADUTA_VERSION and provider API keys are not (they carry meaning, not entropy).
function Test-SecretLike([string]$n) {
  if ($n -match '_API_KEY$') { return ($n -eq 'LIVEKIT_API_KEY') }
  return [bool]($n -match 'SECRET|_PASSWORD$|_PASS$|_KEY$|_TOKEN$|_AUTH$|_SALT$')
}
# The table plus every secret-like variable any compose file under deploy\compose requires (hex32 when
# the table does not name it). The table decides the format; the scan is the safety net, so an overlay
# that requires a new secret is served before anyone notices the table was not updated. Compose checks
# ${X:?} even for a service behind an inactive profile, so every file counts.
function Get-AllSecretSpecs {
  $all = [ordered]@{}
  foreach ($k in $SecretSpecs.Keys) { $all[$k] = $SecretSpecs[$k] }
  try {
    foreach ($f in @(Get-ChildItem -Path $ComposeDir -Recurse -File -Include '*.yml','*.yaml' -ErrorAction SilentlyContinue)) {
      foreach ($m in [regex]::Matches([System.IO.File]::ReadAllText($f.FullName), '\$\{([A-Z0-9_]+):\?')) {
        $n = $m.Groups[1].Value
        if (-not $all.Contains($n) -and (Test-SecretLike $n)) { $all[$n] = 'hex32' }
      }
    }
  } catch { }
  return $all
}
function Test-LocalUrl([string]$u) { return ($u -match 'localhost|127\.0\.0\.1|\[::1\]') }

# ----------------------------------------------------------------- secrets
# Install secrets are also kept outside the install folder (%LOCALAPPDATA%\VegaDuta\secrets.env, shared
# with scripts/vegaduta under WSL on the same Docker), so a re-install into a new folder reuses them:
# Docker keeps the old volumes, and a new database password or APP_ENCRYPTION_KEY would lock this
# install out of its own data.
function Get-SecretsKeepFile {
  if ($env:VEGADUTA_SECRETS_KEEP) { return $env:VEGADUTA_SECRETS_KEEP }
  return (Join-Path $env:LOCALAPPDATA 'VegaDuta\secrets.env')
}
function Get-KeptSecret([string]$Key) {
  $f = Get-SecretsKeepFile; $v = ''
  if (Test-Path $f) { foreach ($l in [System.IO.File]::ReadAllLines($f)) { if ($l.StartsWith("$Key=")) { $v = $l.Substring($Key.Length + 1).Trim() } } }
  return $v
}
function Save-KeptSecrets {
  try {
    $f = Get-SecretsKeepFile
    New-Item -ItemType Directory -Force -Path (Split-Path $f) | Out-Null
    $text = (@(@((Get-AllSecretSpecs).Keys) + @($script:GuardSecrets) + 'VEGADUTA_PERSONAL_PROJECT' | ForEach-Object { $v = Get-EnvValue $_; if ($v) { "$_=$v" } }) -join "`n") + "`n"
    [System.IO.File]::WriteAllText($f, $text, (New-Object System.Text.UTF8Encoding $false))
    Protect-SecretFile $f
  } catch { }
}
# A new .env with newly generated secrets, while Docker still holds the data of an earlier personal
# install (its folder deleted, or one made before secrets were kept): that database and its encrypted
# values belong to secrets this install does not have. Leave it untouched and give this install a
# project of its own (personal.yml reads VEGADUTA_PERSONAL_PROJECT). This only chooses the name: it never
# touches a container, because `secrets` and a dry run reach it, and a run that only prepares files must
# not stop someone's running stack. The old containers' ports are freed when this install really starts
# (Stop-OldInstallContainers).
function Split-FromOldData {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return }
  [void](Invoke-Native docker @('volume', 'inspect', 'vegaduta-personal_postgres-data'))
  if ($script:NativeExit -ne 0) { return }
  $n = 2
  while ($true) { [void](Invoke-Native docker @('volume', 'inspect', "vegaduta-personal-$($n)_postgres-data")); if ($script:NativeExit -ne 0) { break }; $n++ }
  Set-EnvValue VEGADUTA_PERSONAL_PROJECT "vegaduta-personal-$n" -Force
  Warn "An earlier VegaDuta install left its data in Docker, made with secrets this install does not have. It is kept untouched; this install starts with its own data (project vegaduta-personal-$n). To delete the old data later: docker volume ls -q --filter name=vegaduta-personal_ (then docker volume rm each)"
}
# Called only on a real start. Stops a container of the default `vegaduta-personal` project, and only
# when it publishes a host port this install needs, so a stack that runs beside it (another project,
# other ports) is left alone. Does nothing when this install IS that project.
function Stop-OldInstallContainers {
  $proj = Get-EnvValue 'VEGADUTA_PERSONAL_PROJECT'
  if (-not $proj -or $proj -eq 'vegaduta-personal') { return }
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return }
  $want = @()
  foreach ($pk in @(@('WEB_HOST_PORT', 3000), @('CORE_HOST_PORT', 8080))) {
    $v = Get-EnvValue $pk[0]
    if ($v -match '^\d+$') { $want += [int]$v } else { $want += $pk[1] }
  }
  foreach ($id in @(Invoke-Native docker @('ps', '-q', '--filter', 'label=com.docker.compose.project=vegaduta-personal'))) {
    if (-not "$id".Trim()) { continue }
    foreach ($line in @(Invoke-Native docker @('port', "$id".Trim()))) {
      if ("$line" -match ':(\d+)\s*$' -and ($want -contains [int]$Matches[1])) {
        [void](Invoke-Native docker @('stop', "$id".Trim()))
        Say "Stopped a container of the earlier install: it held port $($Matches[1]), which this install needs."
        break
      }
    }
  }
}
function Invoke-Secrets([string]$DomainArg) {
  $created = $false
  if (-not (Test-Path $EnvFile)) {
    $created = $true
    if (-not (Test-Path $EnvExample)) { Die "missing $EnvExample" }
    Write-EnvLines ([System.IO.File]::ReadAllLines($EnvExample))
    Say "Created $EnvFile from .env.example"
  }
  Protect-SecretFile $EnvFile
  $shipped = Get-ShippedVersion
  if ($shipped) { Set-EnvValue VEGADUTA_VERSION $shipped }
  $filled = 0; $reused = 0; $specs = Get-AllSecretSpecs
  foreach ($k in $specs.Keys) {
    if (-not (Get-EnvValue $k)) {
      $kept = Get-KeptSecret $k
      if ($kept) { Set-EnvValue $k $kept; $reused++ } else { Set-EnvValue $k (New-Secret $specs[$k]); $filled++ }
    }
  }
  if ($reused -gt 0) { Say "Reused $reused secret(s) from an earlier install ($(Get-SecretsKeepFile)), so its data still opens." }
  if ($created) {
    if ($reused -gt 0) { $kp = Get-KeptSecret 'VEGADUTA_PERSONAL_PROJECT'; if ($kp) { Set-EnvValue VEGADUTA_PERSONAL_PROJECT $kp } }
    else { Split-FromOldData }
  }
  Save-KeptSecrets
  if (-not (Get-EnvValue 'LANGFUSE_OTEL_AUTH_HEADER')) {
    $pair = (Get-EnvValue 'LANGFUSE_INIT_PROJECT_PUBLIC_KEY') + ':' + (Get-EnvValue 'LANGFUSE_INIT_PROJECT_SECRET_KEY')
    Set-EnvValue 'LANGFUSE_OTEL_AUTH_HEADER' ('Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)))
  }
  if ($DomainArg) {
    Set-EnvValue BASE_DOMAIN $DomainArg -Force
    Set-EnvValue PUBLIC_APP_URL "https://app.$DomainArg" -Force
    Set-EnvValue PUBLIC_ADMIN_URL "https://admin.$DomainArg" -Force
    Set-EnvValue PUBLIC_API_URL "https://api.$DomainArg" -Force
    Set-EnvValue PUBLIC_AUTH_URL "https://auth.$DomainArg" -Force
    Set-EnvValue PUBLIC_CODE_INTEL_URL "https://codeintel.$DomainArg" -Force
    Set-EnvValue PUBLIC_BUILDER_URL "https://builder.$DomainArg" -Force
    Set-EnvValue PUBLIC_LIVEKIT_URL "wss://livekit.$DomainArg" -Force
    Say "Public URLs set for https://*.$DomainArg"
  }
  if ($filled -gt 0) {
    Say "Generated $filled secret(s) in $EnvFile (existing values untouched)."
    Write-Host '    Back up this file. APP_ENCRYPTION_KEY must never change after first start:'
    Write-Host '    every stored provider key / channel secret is encrypted with it.'
  } else { Say "All secrets already present in $EnvFile - nothing changed." }
}

# ---------------------------------------------------------- compose wiring
$script:Tier = ''; $script:Profiles = New-Object System.Collections.Generic.List[string]; $script:DoBuild = $false
$script:SearchOn = ''   # web search (SearXNG): '1' on, '0' off, '' not chosen

function Add-Profile([string]$p) { if ($p -and -not $script:Profiles.Contains($p)) { $script:Profiles.Add($p) } }
function Load-State {
  if (-not $script:Tier -and (Test-Path $StateFile)) {
    foreach ($l in [System.IO.File]::ReadAllLines($StateFile)) {
      if ($l -like 'tier=*') { $script:Tier = $l.Substring(5) }
      elseif ($l -like 'profiles=*' -and $script:Profiles.Count -eq 0) { foreach ($p in ($l.Substring(9) -split ' ')) { Add-Profile $p } }
      elseif ($l -eq 'build=1') { $script:DoBuild = $true }
      elseif ($l -like 'local_ai=*') { $script:LocalAi = $l.Substring(9) }
      elseif ($l -like 'local_model=*') { $script:LocalModelTag = $l.Substring(12) }
      elseif ($l -like 'search=*' -and -not $script:SearchOn) { $script:SearchOn = $l.Substring(7) }
    }
  }
  if (-not $script:Tier) { $script:Tier = 'lite' }
}
# The local-model choice, in the same state-file keys the bash launcher uses.
function Read-StateValue([string]$Key) {
  if (-not (Test-Path $StateFile)) { return '' }
  foreach ($l in [System.IO.File]::ReadAllLines($StateFile)) { if ($l -like "$Key=*") { return $l.Substring($Key.Length + 1) } }
  return ''
}
function Save-State {
  $b = if ($script:DoBuild) { '1' } else { '0' }
  $c = if ($script:LocalAi) { '0' } elseif ($script:CloudChoice) { '1' } else { '0' }
  # search=, tools= and browser= are the keys scripts/vegaduta writes, so the Setup app and a bash run on the same
  # folder (WSL) read the same choices; tools and browser are profiles here, so they are derived from the list.
  $t = if ($script:Profiles.Contains('tools')) { '1' } else { '0' }
  $br = if ($script:Profiles.Contains('browser')) { '1' } else { '0' }
  $sr = if ($script:SearchOn -eq '1') { '1' } else { '0' }
  $txt = "tier=$($script:Tier)`nprofiles=$($script:Profiles -join ' ')`nbuild=$b`nlocal_ai=$($script:LocalAi)`nlocal_model=$($script:LocalModelTag)`nsearch=$sr`ncloud=$c`ntools=$t`nbrowser=$br`n"
  [System.IO.File]::WriteAllText($StateFile, $txt, (New-Object System.Text.UTF8Encoding($false)))
}
# Memory in GB -> the catalog's recommended model (deploy/models/catalog.txt "#ladder|minGB|tag" lines).
function Get-RecommendedModel([int]$Gb) {
  $cat = Join-Path $RepoRoot 'deploy\models\catalog.txt'
  if (Test-Path $cat) {
    foreach ($l in [System.IO.File]::ReadAllLines($cat)) {
      $f = $l -split '\|'
      if ($f.Count -ge 3 -and $f[0] -eq '#ladder' -and $f[1] -match '^\d+$' -and $Gb -ge [int]$f[1]) { return $f[2] }
    }
  }
  if ($Gb -ge 32) { return 'gpt-oss:20b' } elseif ($Gb -ge 24) { return 'qwen3.5:9b' } elseif ($Gb -ge 12) { return 'qwen3.5:4b' }
  return 'qwen3.5:2b'
}
# The catalog's "RAM GB wanted" for a tag (field 4), or 0.
function Get-ModelRamGb([string]$Tag) {
  $cat = Join-Path $RepoRoot 'deploy\models\catalog.txt'
  if (Test-Path $cat) {
    foreach ($l in [System.IO.File]::ReadAllLines($cat)) {
      $f = $l -split '\|'
      if ($f.Count -ge 4 -and $f[0] -eq $Tag -and $f[3] -match '^\d+') { return [int]($f[3] -replace '\..*$', '') }
    }
  }
  return 0
}
function Test-HostOllama {
  try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 'http://127.0.0.1:11434/api/tags' | Out-Null; return $true } catch { return $false }
}
# Installs (per-user, no administrator prompt) and starts Ollama when it is not running, so the
# model runs natively and can use the GPU; the container stays the fallback. Never fails the run.
function Install-HostOllama {
  if ($env:VEGADUTA_ASSUME_NO -eq '1' -or $DryRun -or (Test-HostOllama)) { return }
  $app = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
  if (-not (Test-Path $app) -and -not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { return }
    Say 'Installing Ollama (free open models on this computer, no account)'
    & winget install -e --id Ollama.Ollama --silent --accept-source-agreements --accept-package-agreements | Out-Host
    if ($LASTEXITCODE -ne 0) { Warn 'could not install Ollama - the model runs in a container instead'; return }
  }
  if (Test-Path $app) { Start-Process -FilePath $app -WindowStyle Hidden } else { Start-Process -FilePath 'ollama' -ArgumentList 'serve' -WindowStyle Hidden }
  for ($i = 0; $i -lt 40; $i++) { if (Test-HostOllama) { Ok 'Ollama is running'; return }; Start-Sleep -Seconds 3 }
  Warn 'Ollama did not start in 2 minutes - the model runs in a container instead'
}
# A personal install gets the free local model that fits this machine's RAM: through an Ollama
# already running here, else the Ollama container (it downloads the model on first start).
# -Cloud opts out and is remembered; below 4 GB of RAM the cloud-key path stays.
function Resolve-LocalModel {
  $script:LocalAi = Read-StateValue 'local_ai'
  $script:LocalModelTag = if ($LocalModel) { $LocalModel } else { Read-StateValue 'local_model' }
  $script:CloudChoice = $Cloud -or ((Read-StateValue 'cloud') -eq '1' -and -not $LocalModel)
  if ($Cloud) { $script:LocalAi = ''; $script:LocalModelTag = ''; return }
  if ($script:CloudChoice) { return }
  # An install that uses (or could use) the computer's Ollama: start it if it is installed but not
  # running (after a restart, or its tray app was closed), or the model never answers.
  $ollamaApp = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
  if (($script:LocalAi -eq 'host' -or ($script:LocalAi -eq 'container' -and ((Test-Path $ollamaApp) -or (Get-Command ollama -ErrorAction SilentlyContinue)))) -and -not (Test-HostOllama)) { Install-HostOllama }
  if ($script:LocalAi -eq 'host' -and -not (Test-HostOllama)) {
    $script:LocalAi = 'container'
    Warn "Ollama on this computer is not answering - the model runs in a container instead ('$($script:LocalModelTag)')"
    return
  }
  # Ollama installed on this computer after an install that used the container: switch to it
  # (all of the computer's memory, its GPU, and the models already pulled there).
  if ($script:LocalAi -eq 'container' -and (Test-HostOllama)) {
    $script:LocalAi = 'host'
    Say "Ollama found on this machine: agents now use it instead of the container ('$($script:LocalModelTag)')"
    return
  }
  if ($script:LocalAi) { return }
  $gb = Get-HostRamGb
  if ($gb -eq 0 -or $gb -ge 8) { Install-HostOllama }
  $hostOllama = Test-HostOllama
  # In the container the model only gets the memory Docker has (a WSL2 VM is often smaller than the computer).
  $modelGb = $gb
  if (-not $hostOllama) {
    $dg = [int][math]::Floor((Get-DockerMemBytes) / 1GB)
    if ($dg -gt 0 -and ($modelGb -le 0 -or $dg -lt $modelGb)) { $modelGb = $dg }
  }
  if (-not $script:LocalModelTag) { $script:LocalModelTag = Get-RecommendedModel $modelGb }
  if ($hostOllama) {
    $script:LocalAi = 'host'
    Say "Ollama found on this machine: agents will use '$($script:LocalModelTag)' (change with -LocalModel, opt out with -Cloud)"
    return
  }
  # Local AI stays the default on small machines (CPU only is fine); only a machine that cannot
  # hold even the smallest model falls back to a cloud key.
  if ($gb -gt 0 -and $gb -lt 4) {
    Say "This machine has $gb GB RAM - too little for a local model next to the stack; add a cloud provider key in the console"
    $script:LocalModelTag = ''
    return
  }
  $script:LocalAi = 'container'
  Say "Agents will use the free local model '$($script:LocalModelTag)' (downloaded once on first start; change with -LocalModel, opt out with -Cloud)"
  if ($modelGb -gt 0 -and $modelGb -lt 8) { Warn "$modelGb GB is tight: '$($script:LocalModelTag)' runs on the CPU and the first reply can take a minute; close other apps while it loads" }
}
function Resolve-Profiles {
  foreach ($p in ((Get-EnvValue 'COMPOSE_PROFILES') -split ',')) { Add-Profile $p.Trim() }
  if ($script:Profiles.Contains('jobs')) { Add-Profile 'temporal' }
  if ($script:Profiles.Contains('temporal')) { Add-Profile 'events' }
  if ($script:Profiles.Contains('builder')) { Add-Profile 'code-intel' }
  foreach ($p in $script:Profiles) { if (($AllProfiles + 'rag' + 'workspace') -notcontains $p) { Die "unknown profile '$p' (valid: $($AllProfiles -join ' '); lite: rag; personal: rag workspace)" } }
  if ($script:Tier -eq 'personal') {
    # The Assistant's build terminal: workspace-runner plus the projects folder, mounted into it and core.
    if ($script:Profiles.Contains('workspace')) {
      $projects = Get-EnvValue 'VEGADUTA_PROJECTS_DIR'; if (-not $projects) { $projects = Join-Path $HOME 'VegaDuta-Projects' }
      New-Item -ItemType Directory -Force -Path $projects | Out-Null
      $env:VEGADUTA_PROJECTS_DIR = $projects
      if (-not $env:APP_ASSISTANT_LOCAL_SHELL_RUNNER_URL) { $env:APP_ASSISTANT_LOCAL_SHELL_RUNNER_URL = 'http://workspace-runner:8090' }
    }
    # With rag the sidecar exists, so core must use it (the personal profile defaults it off).
    if ($script:Profiles.Contains('rag')) { $env:APP_SIDECAR_ENABLED = 'true' }
    # STDIO MCP servers run in the mcp-stdio-runner container; core must relay to it (the slim core has no npx/uvx).
    if ($script:Profiles.Contains('tools') -or $script:Profiles.Contains('browser')) { $env:MCP_STDIO_SIDECAR_ENABLED = 'true' }
    return
  }
  if ($script:Tier -eq 'lite') { return }
  $extra = ''
  if ($script:Profiles.Contains('events')) { $extra = ',workflow' }
  if ($script:Profiles.Contains('temporal')) { $extra = ',workflow,temporal'; $env:WORKFLOW_EXECUTION_ENGINE = 'temporal' }
  if ($extra -and -not (Get-EnvValue 'CORE_PROFILES_EXTRA')) { $env:CORE_PROFILES_EXTRA = $extra }
  if ($script:Profiles.Contains('code-intel')) { $env:CODE_INTEL_ENABLED = 'true' }
  if ($script:Profiles.Contains('sandbox')) { $env:TOOL_SANDBOX_ENABLED = 'true' }
  # mcp-stdio-runner (sandbox/tools/browser): STDIO MCP servers - tenant ones
  # and the `mcp` profile's built-ins - run there, not in the slim core image.
  if ($script:Profiles.Contains('sandbox') -or $script:Profiles.Contains('tools') -or $script:Profiles.Contains('browser')) {
    $env:MCP_STDIO_SIDECAR_ENABLED = 'true'
    if (-not (Get-EnvValue 'CORE_MCP_PROFILE')) { $env:CORE_MCP_PROFILE = ',mcp' }
  }
  if ($script:Profiles.Contains('browser')) {
    $env:MCP_STDIO_RUNNER_VARIANT = '-browser'; $env:MCP_STDIO_RUNNER_WITH_BROWSERS = 'true'
    if (-not (Get-EnvValue 'MCP_STDIO_RUNNER_MEM_LIMIT')) { $env:MCP_STDIO_RUNNER_MEM_LIMIT = '2g' }
  }
}
function Get-ComposeArgs {
  $a = @('--project-directory', $ComposeDir, '--env-file', $EnvFile)
  # Compose requires VEGADUTA_VERSION; read-only commands only need a valid value.
  if (-not $env:VEGADUTA_VERSION -and -not (Get-EnvValue 'VEGADUTA_VERSION')) { $env:VEGADUTA_VERSION = 'unset' }
  $d = Get-EnvValue 'BASE_DOMAIN'
  if ($script:Tier -eq 'personal') {
    $a += @('-f', (Join-Path $ComposeDir 'personal.yml'), '--profile', 'valkey')
    if ($script:DoBuild) { $a += @('-f', (Join-Path $ComposeDir 'personal.build.yml')) }
    # The agent's own browser (services/browser-runner): `-Profile browser` layers its overlay; core attaches to it.
    if ($script:Profiles.Contains('browser')) { $a += @('-f', (Join-Path $ComposeDir 'browser.yml')) }
    if ($script:LocalAi) {
      if ($script:LocalModelTag) { $env:LOCAL_LLM_MODEL = $script:LocalModelTag }
      $a += @('-f', (Join-Path $ComposeDir 'docker-compose.local-ai.yml'))
      if ($script:LocalAi -eq 'host') {
        $a += @('-f', (Join-Path $ComposeDir 'docker-compose.local-ai.host.yml'))
        if (-not $env:LOCAL_LLM_BASE_URL) { $env:LOCAL_LLM_BASE_URL = 'http://host.docker.internal:11434' }
        if (-not $env:LOCAL_LLM_ALLOWED_HOST) { $env:LOCAL_LLM_ALLOWED_HOST = 'host.docker.internal:11434' }
      } else {
        $a += @('--profile', 'local-ai')
        # personal.yml allow-lists the machine's own Ollama for the console's model wizard; keep it next to the container.
        $allow = Get-EnvValue 'APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS'
        if ((",$allow,") -notlike '*,host.docker.internal:11434,*') { $allow = if ($allow) { "host.docker.internal:11434,$allow" } else { 'host.docker.internal:11434' } }
        $env:APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS = $allow
        # A small Docker VM cannot hold a 32k-token context next to the stack; scale it down with Docker's memory.
        if (-not $env:LOCAL_LLM_NUM_CTX -and -not (Get-EnvValue 'LOCAL_LLM_NUM_CTX')) {
          $dgb = [int][math]::Floor((Get-DockerMemBytes) / 1GB)
          if ($dgb -gt 0 -and $dgb -lt 6) { $env:LOCAL_LLM_NUM_CTX = '8192' } elseif ($dgb -gt 0 -and $dgb -lt 10) { $env:LOCAL_LLM_NUM_CTX = '16384' }
        }
        # The container's memory cap follows the model (the 8g default cannot hold the larger rows); never lowered.
        if (-not $env:OLLAMA_MEM_LIMIT -and -not (Get-EnvValue 'OLLAMA_MEM_LIMIT')) {
          $want = Get-ModelRamGb $script:LocalModelTag
          if ($want -gt 8) { $env:OLLAMA_MEM_LIMIT = "${want}g" }
        }
      }
    }
  } elseif ($script:Tier -eq 'lite') {
    $a += @('-f', (Join-Path $ComposeDir 'lite.yml'))
    if ($script:DoBuild) { $a += @('-f', (Join-Path $ComposeDir 'lite.build.yml')) }
  } else {
    $a += @('-f', (Join-Path $ComposeDir 'docker-compose.yml'))
    if ($script:DoBuild) { $a += @('-f', (Join-Path $ComposeDir 'docker-compose.build.yml')) }
    if ($d) {
      $a += @('-f', (Join-Path $ComposeDir 'docker-compose.domain.yml'))
      $env:PUBLIC_APP_URL = "https://app.$d"; $env:PUBLIC_ADMIN_URL = "https://admin.$d"; $env:PUBLIC_API_URL = "https://api.$d"
      $env:PUBLIC_AUTH_URL = "https://auth.$d"; $env:PUBLIC_CODE_INTEL_URL = "https://codeintel.$d"
      $env:PUBLIC_BUILDER_URL = "https://builder.$d"; $env:PUBLIC_LIVEKIT_URL = "wss://livekit.$d"
    }
  }
  # Free private web search for agents (SearXNG, docs/configuration.md "Web search"): the overlay plus core pointed
  # at it, as search_args does in scripts/vegaduta. lite.yml and personal.yml leave core on the implicit default
  # network, which the base overlay would replace, so they also need the .lite companion.
  if ($script:SearchOn -eq '1') {
    $a += @('-f', (Join-Path $ComposeDir 'docker-compose.search.yml'))
    if ($script:Tier -eq 'lite' -or $script:Tier -eq 'personal') { $a += @('-f', (Join-Path $ComposeDir 'docker-compose.search.lite.yml')) }
    $a += @('--profile', 'search')
    if (-not $env:WEB_SEARCH_PROVIDER) { $env:WEB_SEARCH_PROVIDER = 'searxng' }
    if (-not $env:WEB_SEARCH_SEARXNG_BASE_URL) { $env:WEB_SEARCH_SEARXNG_BASE_URL = 'http://searxng:8080' }
  }
  foreach ($p in $script:Profiles) { $a += @('--profile', $p) }
  $env:COMPOSE_PROFILES = ''
  return ,$a
}
function Invoke-Dc([string[]]$ComposeArgs, [string[]]$More) {
  & docker compose @ComposeArgs @More | Out-Host
  return $LASTEXITCODE
}
function Prepare { Load-State; Resolve-Profiles; return (Get-ComposeArgs) }
# A Postgres data volume left by an earlier install keeps that install's superuser password, while a
# new folder's secrets are new: db-roles cannot log in ("password authentication failed") and nothing
# starts. The postgres container's local socket needs no password, so set the superuser's password to
# this install's (read there from POSTGRES_PASSWORD, never on a command line). Data is kept.
# Runs docker compose and returns its stdout+stderr as one string, exit code in $script:DcExit. With
# $ErrorActionPreference = 'Stop', a bare `& docker ... 2>&1` throws on the first line Compose writes to
# stderr (the error becomes a NativeCommandError), which is how a missing variable once stopped the
# installer inside Repair-DbPassword instead of being reported.
function Get-DcOutput([string[]]$ComposeArgs, [string[]]$More) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $out = @(& docker compose @ComposeArgs @More 2>&1 | ForEach-Object { "$_" }); $script:DcExit = $LASTEXITCODE }
  finally { $ErrorActionPreference = $prev }
  return ($out -join "`n")
}
# Last line of defence before `up`: ask Compose itself which variables it still finds missing (the
# exact "required variable X is missing a value" that stops every later compose call) and fill the
# secret-like ones, so no table, scan or hand-edited .env can leave an install stuck on one. Anything
# else that is missing stops here with the one line that says what to set.
$script:GuardSecrets = @()   # names Repair-ComposeEnv generated this run, so Save-KeptSecrets copies them too
function Repair-ComposeEnv([string[]]$ComposeArgs) {
  $filled = 0
  for ($round = 0; $round -lt 20; $round++) {
    $err = Get-DcOutput $ComposeArgs @('config', '-q')
    if ($script:DcExit -eq 0) { if ($filled -gt 0) { Ok "generated $filled secret(s) the compose files required" }; return }
    # Compose names every missing variable at once, one line each.
    $names = @([regex]::Matches($err, 'required variable "?([A-Za-z0-9_]+)"? is missing a value') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($names.Count -eq 0) { return }
    $plain = @()
    foreach ($n in $names) {
      if (Get-EnvValue $n) { return }
      if (Test-SecretLike $n) { Set-EnvValue $n (New-Secret 'hex32'); $filled++; $script:GuardSecrets += $n }
      else { $plain += $n }
    }
    if ($filled -gt 0) { Save-KeptSecrets }
    if ($plain.Count -gt 0) { Die ("required by the compose files but empty in ${EnvFile}: " + ($plain -join ' ') + ". Set them there, then re-run. ($err)") }
  }
  Warn "still missing variables after $round rounds: $err"
}
function Repair-DbPassword([string[]]$ComposeArgs) {
  $log = Get-DcOutput $ComposeArgs @('logs', '--no-log-prefix', 'db-roles')
  if ($log -notmatch 'password authentication failed') { return $false }
  Warn "the database was created by an earlier install with another password - switching it to this install's password (data is kept)"
  $sql = "\getenv pw POSTGRES_PASSWORD`nSELECT format('ALTER ROLE %I PASSWORD %L', current_user, :'pw') \gexec`n"
  $sql | & docker compose @ComposeArgs exec -T postgres sh -c 'tr -d \\r | psql -X -q -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d postgres' | Out-Null
  if ($LASTEXITCODE -ne 0) { Warn 'could not reset the database password; to start over with an empty database run: docker volume rm <project>_postgres-data (docker volume ls)'; return $false }
  Ok 'database password updated'
  return $true
}
# `up` that repairs what it can (Repair-ComposeEnv, Repair-DbPassword) and tries once more.
function Invoke-UpHeal([string[]]$ComposeArgs, [string[]]$More) {
  Repair-ComposeEnv $ComposeArgs
  if ((Invoke-Dc $ComposeArgs $More) -eq 0) { return 0 }
  if (Repair-DbPassword $ComposeArgs) { return (Invoke-Dc $ComposeArgs $More) }
  return 1
}

# ------------------------------------------------------------------ doctor
function Test-PortInUse([int]$port) {
  $c = New-Object System.Net.Sockets.TcpClient
  try { $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null); $okc = $iar.AsyncWaitHandle.WaitOne(300); if ($okc) { $c.EndConnect($iar); return $true } }
  catch { } finally { $c.Close() }
  # Nothing answers, but Windows may still refuse the port (a Hyper-V/WinNAT excluded range): try to bind it.
  $l = $null
  try { $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $port); $l.Start(); return $false }
  catch { return $true } finally { if ($l) { $l.Stop() } }
}
# Containers of this install's compose project that are running (their ports are ours, not a conflict).
function Test-OwnStackRunning {
  $proj = Get-EnvValue 'VEGADUTA_PERSONAL_PROJECT'
  if (-not $proj) { $proj = $env:COMPOSE_PROJECT_NAME }; if (-not $proj) { $proj = Get-EnvValue 'COMPOSE_PROJECT_NAME' }; if (-not $proj) { $proj = 'vegaduta-personal' }
  $ids = @(Invoke-Native docker @('ps', '-q', '--filter', "label=com.docker.compose.project=$proj"))
  return (@($ids | Where-Object { "$_".Trim() }).Count -gt 0)
}
function Invoke-Doctor {
  $ca = Prepare
  $script:Warnings = 0; $script:Failures = 0
  Say "Checking prerequisites (tier: $($script:Tier); profiles: $($script:Profiles -join ' '))"
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Bad 'docker not found - install Docker Desktop'; return $false }
  $sv = (Invoke-Native docker @('version', '--format', '{{.Server.Version}}')) -join ''
  if ($script:NativeExit -ne 0) { Bad 'Docker daemon not running - start Docker Desktop'; return $false } else { Ok "Docker daemon reachable ($sv)" }
  $cv = ((Invoke-Native docker @('compose', 'version', '--short')) -join '') -replace '^v',''
  if (-not $cv) { Bad 'docker compose v2 not found' }
  elseif ([version]($cv -replace '[^0-9.].*$','') -ge [version]'2.24.0') { Ok "docker compose $cv" }
  else { Bad "docker compose $cv is too old (need >= 2.24.0)" }
  $needGb = if ($script:Tier -eq 'full') { 12 } else { 6 }
  $diskNeed = if ($script:Tier -eq 'full') { 40 } else { 15 }
  $memGb = [math]::Floor((Get-DockerMemBytes) / 1GB)
  if ($memGb -ge $needGb) { Ok "Docker memory $memGb GB (>= $needGb GB for $($script:Tier))" } else { Warn "Docker has $memGb GB RAM; $($script:Tier) wants >= $needGb GB (Docker Desktop > Settings > Resources)" }
  $drive = (Get-Item $RepoRoot).PSDrive
  $freeGb = [math]::Floor($drive.Free / 1GB)
  if ($freeGb -ge $diskNeed) { Ok "Free disk $freeGb GB" } else { Warn "Only $freeGb GB free; $($script:Tier) wants >= $diskNeed GB" }
  if (Test-Path $EnvFile) { Ok ".env present ($EnvFile)" } else { Bad '.env missing - run: .\scripts\vegaduta.ps1 secrets'; return $false }
  $missing = @((Get-AllSecretSpecs).Keys | Where-Object { -not (Get-EnvValue $_) })
  if ($missing.Count -eq 0) { Ok 'All secrets set' } else { Bad ("Empty secrets: " + ($missing -join ' ') + ' - run: .\scripts\vegaduta.ps1 secrets') }
  # Older .env.example files shipped CADDY_AUTO_HTTPS=on, which is not a Caddy auto_https value
  # (Caddy refuses to start). Empty means automatic HTTPS, which is what "on" meant.
  if ((Get-EnvValue 'CADDY_AUTO_HTTPS') -match '^(?i:on|true|yes|1)$') {
    Set-EnvValue 'CADDY_AUTO_HTTPS' '' -Force
    Warn 'CADDY_AUTO_HTTPS=on in .env is not a valid Caddy value; reset to empty (automatic HTTPS)'
  }
  try { $kl = [Convert]::FromBase64String((Get-EnvValue 'APP_ENCRYPTION_KEY')).Length } catch { $kl = 0 }
  if ($kl -ge 32) { Ok "APP_ENCRYPTION_KEY decodes to $kl bytes" } else { Bad 'APP_ENCRYPTION_KEY must be base64 of >= 32 bytes. If data already exists, restore the ORIGINAL key instead.' }
  $ver = Get-EnvValue 'VEGADUTA_VERSION'; $shipped = Get-ShippedVersion
  if (-not $ver) { if ($script:DoBuild) { Ok 'VEGADUTA_VERSION empty: images build from source as :local' } else { Bad 'VEGADUTA_VERSION is empty - set a release (upgrade -Version X.Y.Z) or use -Build' } }
  elseif ($ver -eq 'latest') { Warn 'VEGADUTA_VERSION=latest floats: compose files and images can drift apart. Pin a release: .\scripts\vegaduta.ps1 upgrade -Version X.Y.Z' }
  elseif ($shipped -and $ver -ne $shipped) { Warn "Images pinned to $ver but these compose files are from $shipped - run: .\scripts\vegaduta.ps1 upgrade -Version $shipped" }
  else { Ok "Release $ver" }
  $papp = Get-EnvValue 'POSTGRES_APP_USER'; $psu = Get-EnvValue 'POSTGRES_USER'; if (-not $psu) { $psu = 'agentic' }
  if ($papp -and $papp -eq $psu) { Warn 'POSTGRES_APP_USER equals the superuser: core runs with full database superuser rights' }
  $d = Get-EnvValue 'BASE_DOMAIN'
  if ($d) {
    if ($script:Tier -eq 'lite') { Warn 'BASE_DOMAIN is set but lite mode ignores it (use -Full)' }
    $badUrls = @('PUBLIC_APP_URL','PUBLIC_ADMIN_URL','PUBLIC_API_URL','PUBLIC_AUTH_URL','PUBLIC_LIVEKIT_URL') | Where-Object { Test-LocalUrl (Get-EnvValue $_) }
    if ($badUrls) { Bad ("BASE_DOMAIN=$d but still localhost: " + ($badUrls -join ' ') + " - run: .\scripts\vegaduta.ps1 secrets -Domain $d") } else { Ok "Public URLs use $d" }
    if ((Get-EnvValue 'KEYCLOAK_START_COMMAND') -eq 'start-dev') { Bad 'KEYCLOAK_START_COMMAND=start-dev with BASE_DOMAIN set: Keycloak dev mode must never face the internet - remove that line' } else { Ok 'Keycloak runs in production mode (start)' }
    if ((Get-EnvValue 'KC_VERIFY_EMAIL') -ne 'true') { Warn 'KC_VERIFY_EMAIL is off: anyone can sign up with an address they do not own (configure KEYCLOAK_SMTP_* and set KC_VERIFY_EMAIL=true)' }
    try { [void][System.Net.Dns]::GetHostAddresses("app.$d"); Ok "DNS app.$d resolves" } catch { Warn "DNS app.$d does not resolve yet" }
  } else { Ok 'Local mode (no BASE_DOMAIN): URLs on localhost' }
  $running = Invoke-Native docker (@('compose') + $ca + @('ps', '-q'))
  if ($running) { Ok 'Stack already running - skipping port checks' }
  else {
    # The ports the stack will actually publish: *_HOST_PORT from .env, else the default.
    function HostPort([string]$Key, [int]$Default) { $v = Get-EnvValue $Key; if ($v) { [int]$v } else { $Default } }
    $ports = @((HostPort 'WEB_HOST_PORT' 3000), (HostPort 'CORE_HOST_PORT' 8080), (HostPort 'KEYCLOAK_HOST_PORT' 8180), (HostPort 'POSTGRES_HOST_PORT' 5432), (HostPort 'VALKEY_HOST_PORT' 6379))
    if ($script:Tier -eq 'full') { $ports += @((HostPort 'SIDECAR_HOST_PORT' 8001), 8333) }
    if ($script:Profiles.Contains('temporal')) { $ports += @(7233, 8088) }
    if ($script:Profiles.Contains('observability')) { $ports += @(3003, 9090) }
    if ($d -and $script:Tier -eq 'full') { $ports += @(80, 443) }
    $busy = @($ports | Where-Object { Test-PortInUse $_ })
    if ($busy.Count -eq 0) { Ok ("Ports free: " + ($ports -join ' ')) } else { Bad ("Ports already in use: " + ($busy -join ' ')) }
  }
  if ($script:Failures -gt 0) { Write-Host "$($script:Failures) problem(s), $($script:Warnings) warning(s)." -ForegroundColor Red; return $false }
  Write-Host "Looks good ($($script:Warnings) warning(s))." -ForegroundColor Green
  return $true
}

# ------------------------------------------------------------------ keycloak
function Invoke-Reconcile($ca) {
  Say 'Reconciling Keycloak realm (idempotent)'
  $realm = Get-EnvValue 'KEYCLOAK_REALM'; if (-not $realm) { $realm = 'agentic-ai' }
  $sp = Get-EnvValue 'KEYCLOAK_SMTP_PORT'; if (-not $sp) { $sp = '587' }
  $st = Get-EnvValue 'KEYCLOAK_SMTP_STARTTLS'; if (-not $st) { $st = 'true' }
  # Values go through this process's ENVIRONMENT (`-e NAME` without a value
  # inherits it), never the docker command line, so the SMTP password and IdP
  # client secrets are not visible in the process list (INF-022).
  $vals = [ordered]@{ REALM = $realm; SH = (Get-EnvValue KEYCLOAK_SMTP_HOST); SP = $sp
    SF = (Get-EnvValue KEYCLOAK_SMTP_FROM); SD = (Get-EnvValue KEYCLOAK_SMTP_FROM_DISPLAY)
    SU = (Get-EnvValue KEYCLOAK_SMTP_USER); SW = (Get-EnvValue KEYCLOAK_SMTP_PASSWORD); ST = $st
    GGID = (Get-EnvValue GOOGLE_CLIENT_ID); GGSEC = (Get-EnvValue GOOGLE_CLIENT_SECRET)
    GHID = (Get-EnvValue GITHUB_CLIENT_ID); GHSEC = (Get-EnvValue GITHUB_CLIENT_SECRET)
    MSID = (Get-EnvValue MICROSOFT_CLIENT_ID); MSSEC = (Get-EnvValue MICROSOFT_CLIENT_SECRET) }
  $e = @()
  foreach ($k in $vals.Keys) { Set-Item -Path "Env:$k" -Value ([string]$vals[$k]); $e += @('-e', $k) }
  try {
    # Normalise to LF so bash inside the container never sees CRLF.
    $script = ([System.IO.File]::ReadAllText((Join-Path $RepoRoot 'deploy\keycloak\reconcile-realm.kcadm.sh'))) -replace "`r`n", "`n"
    $script | & docker compose @ca exec -T @e keycloak bash -s | Out-Host
    if ($LASTEXITCODE -ne 0) { Warn 'Keycloak reconcile failed - see deploy/keycloak/reconcile-keycloak.sh' }
  } finally { foreach ($k in $vals.Keys) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue } }
}

function Invoke-PersonalDoctor {
  $script:Warnings = 0; $script:Failures = 0
  Say 'Checking prerequisites (tier: personal)'
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Bad 'docker not found - install Docker Desktop'; return $false }
  $sv = (Invoke-Native docker @('version', '--format', '{{.Server.Version}}')) -join ''
  if ($script:NativeExit -ne 0) { Bad 'Docker daemon not running - start Docker Desktop'; return $false } else { Ok "Docker daemon reachable ($sv)" }
  $memGb = [math]::Floor((Get-DockerMemBytes) / 1GB)
  if ($memGb -ge 4) { Ok "Docker memory $memGb GB (>= 4 GB)" } else { Warn "Docker has $memGb GB RAM; personal wants >= 4 GB (raise Docker Desktop's memory limit)" }
  foreach ($k in @('POSTGRES_PASSWORD','POSTGRES_APP_PASSWORD','VALKEY_PASSWORD','APP_ENCRYPTION_KEY')) {
    if (-not (Get-EnvValue $k)) { Bad "$k is empty - run .\scripts\vegaduta.ps1 secrets" }
  }
  if (-not (Test-OwnStackRunning)) {
    foreach ($pk in @(@('WEB_HOST_PORT', 3000), @('CORE_HOST_PORT', 8080))) {
      $v = Get-EnvValue $pk[0]; $port = if ($v) { [int]$v } else { $pk[1] }
      if (Test-PortInUse $port) { Bad "Port $port is already in use or reserved by Windows - close the program using it, or set $($pk[0])=<free port> in $EnvFile (a reserved range clears with: net stop winnat; net start winnat, as administrator)" }
    }
  }
  if ($script:Failures -gt 0) { return $false }
  return $true
}
function Show-Urls {
  $app = Get-EnvValue 'PUBLIC_APP_URL'; if ($env:PUBLIC_APP_URL) { $app = $env:PUBLIC_APP_URL }; if (-not $app) { $app = 'http://localhost:3000' }
  $api = Get-EnvValue 'PUBLIC_API_URL'; if ($env:PUBLIC_API_URL) { $api = $env:PUBLIC_API_URL }; if (-not $api) { $api = 'http://localhost:8080' }
  $auth = Get-EnvValue 'PUBLIC_AUTH_URL'; if ($env:PUBLIC_AUTH_URL) { $auth = $env:PUBLIC_AUTH_URL }; if (-not $auth) { $auth = 'http://localhost:8180' }
  $admin = Get-EnvValue 'PUBLIC_ADMIN_URL'; if ($env:PUBLIC_ADMIN_URL) { $admin = $env:PUBLIC_ADMIN_URL }; if (-not $admin) { $admin = 'http://localhost:3000' }
  Write-Host ''
  Write-Host "VegaDuta is up ($($script:Tier); profiles: $($script:Profiles -join ' '))" -ForegroundColor Green
  Write-Host "  Console              $app"
  Write-Host "  API                  $api"
  Write-Host "  Identity (Keycloak)  $auth"
  if ($script:Tier -eq 'full') { Write-Host "  Platform admin       $admin" }
  if ($script:Profiles.Contains('temporal')) { Write-Host '  Temporal UI          http://localhost:8088' }
  if ($script:Profiles.Contains('observability')) { Write-Host '  Grafana              http://localhost:3003' }
  if ($script:Profiles.Contains('tracing')) { Write-Host '  Langfuse             http://localhost:3002' }
  Write-Host ''
  Write-Host 'First login'
  Write-Host "  1. Open $app and choose 'Sign up' - the first account creates your organisation (tenant admin)."
  Write-Host '  2. Make it a platform operator:  .\scripts\vegaduta.ps1 make-admin you@example.com'
  Write-Host '  3. Add an LLM provider key in the console (Settings -> Providers), then create an agent.'
  Write-Host "  Keycloak admin console: http://localhost:8180/admin  (KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD in $EnvFile)"
}

# ------------------------------------------------------------ backup
# pg_dump (custom format) of the app database (+ Keycloak's on the full stack)
# and a copy of .env, owner-only. Dumps are written inside the container and
# copied out with `docker compose cp` (PowerShell 5.1 pipes mangle binary).
# Returns the backup directory, or '' on failure.
function Invoke-Backup($ca, [string]$Dir) {
  if (-not $Dir) { $Dir = Join-Path $ComposeDir ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
  New-Item -ItemType Directory -Force -Path $Dir | Out-Null
  Say "Backing up to $Dir"
  $dbs = @(@('postgres', 'app-db.dump'))
  if ($script:Tier -eq 'full' -and (Invoke-Native docker (@('compose') + $ca + @('ps', '-q', 'keycloak-db')))) { $dbs += ,@('keycloak-db', 'keycloak-db.dump') }
  foreach ($pair in $dbs) {
    $rc = Invoke-Dc $ca @('exec', '-T', $pair[0], 'sh', '-c', 'umask 077; pg_dump -U $POSTGRES_USER -d $POSTGRES_DB -Fc -f /tmp/vegaduta-backup.dump')
    if ($rc -ne 0) { Bad "pg_dump in $($pair[0]) failed"; return '' }
    $out = Join-Path $Dir $pair[1]
    $rc = Invoke-Dc $ca @('cp', "$($pair[0]):/tmp/vegaduta-backup.dump", $out)
    [void](Invoke-Dc $ca @('exec', '-T', $pair[0], 'rm', '-f', '/tmp/vegaduta-backup.dump'))
    if ($rc -ne 0 -or -not (Test-Path $out) -or (Get-Item $out).Length -eq 0) { Bad "could not copy the $($pair[0]) dump out"; return '' }
    Protect-SecretFile $out
    Ok "$($pair[0]) -> $($pair[1])"
  }
  $envCopy = Join-Path $Dir 'env'
  Copy-Item $EnvFile $envCopy; Protect-SecretFile $envCopy
  Ok '.env -> env (holds APP_ENCRYPTION_KEY - store it like a password)'
  Warn 'Uploaded files (seaweedfs-data / core-storage volume) are not in this backup on Windows; back that volume up separately.'
  Say "Backup complete: $Dir  (restore: .\scripts\vegaduta.ps1 restore $Dir)"
  return $Dir
}

# ------------------------------------------------------------------ models
# Downloads a model into the Ollama on this computer through its API (the way `personal` always did).
# Returns '' when it is there, else why not.
function Invoke-OllamaPull([string]$Tag) {
  try {
    $r = Invoke-RestMethod -Method Post -TimeoutSec 3600 -Uri 'http://127.0.0.1:11434/api/pull' -ContentType 'application/json' -Body (@{ model = $Tag; stream = $false } | ConvertTo-Json)
    if ($r -and $r.error) { return [string]$r.error }
    return ''
  } catch {
    $why = $_.Exception.Message
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $why = $_.ErrorDetails.Message }
    return $why
  }
}
# Where `models` pulls: the install's own Ollama container, else the one on this computer (as ollama_mode in
# scripts/vegaduta); '' when there is none.
function Get-OllamaMode {
  $m = Read-StateValue 'local_ai'
  if ($m -eq 'container' -or $m -eq 'host') { return $m }
  if (Test-HostOllama) { return 'host' }
  return ''
}

# ------------------------------------------------------------------ upgrade
# Downloads the release's source tarball, checks it against the release's SHA256SUMS (and the Sigstore
# bundle of SHA256SUMS when cosign is installed) and unpacks it over this install: compose files, realm and
# scripts of that release. Files the tarball does not hold (.env, state, backups) stay. Throws on any
# problem and unpacks nothing unless every check passed. Same checks as fetch_release in scripts/vegaduta.
function Install-Release([string]$Ver) {
  $repo = if ($env:VEGADUTA_REPO) { $env:VEGADUTA_REPO } else { 'CD-LC/vegaduta' }
  $base = "https://github.com/$repo/releases/download/v$Ver"; $name = "vegaduta-$Ver.tar.gz"
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('vegaduta-upgrade.' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  $ProgressPreference = 'SilentlyContinue'   # the progress bar makes Windows PowerShell 5.1 download many times slower
  try {
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
    Say "Downloading release v$Ver"
    try { Invoke-WebRequest -UseBasicParsing -Uri "$base/$name" -OutFile (Join-Path $tmp $name) }
    catch { throw "download failed: $base/$name ($($_.Exception.Message))" }
    try { Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS') }
    catch { throw "download failed: $base/SHA256SUMS (refusing an unverified upgrade)" }
    if (Get-Command cosign -ErrorAction SilentlyContinue) {
      $bundle = $null
      try { Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS.sigstore.json" -OutFile (Join-Path $tmp 'SHA256SUMS.sigstore.json'); $bundle = Join-Path $tmp 'SHA256SUMS.sigstore.json' } catch { }
      if ($bundle) {
        & cosign verify-blob (Join-Path $tmp 'SHA256SUMS') --bundle $bundle --certificate-identity-regexp ('^https://github\.com/' + [regex]::Escape($repo) + '/\.github/workflows/release\.yml@refs/tags/v') --certificate-oidc-issuer https://token.actions.githubusercontent.com | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "cosign could NOT verify SHA256SUMS of v$Ver - not upgrading" }
        Ok 'SHA256SUMS signature verified (Sigstore)'
      } elseif ($env:VEGADUTA_REQUIRE_SIGNATURE -eq '1') { throw "v$Ver has no SHA256SUMS.sigstore.json" }
      else { Warn "v$Ver publishes no signature bundle; checking SHA-256 only" }
    } elseif ($env:VEGADUTA_REQUIRE_SIGNATURE -eq '1') { throw 'VEGADUTA_REQUIRE_SIGNATURE=1 but cosign is not installed' }
    $want = ''
    foreach ($l in [System.IO.File]::ReadAllLines((Join-Path $tmp 'SHA256SUMS'))) {
      if ($l -match '^([0-9a-fA-F]{64})\s+\*?(?:\./)?(.+?)\s*$' -and $Matches[2] -eq $name) { $want = $Matches[1].ToLowerInvariant(); break }
    }
    if (-not $want) { throw "SHA256SUMS has no entry for $name" }
    $got = (Get-FileHash -LiteralPath (Join-Path $tmp $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($got -ne $want) { throw "checksum mismatch for $name (expected $want, got $got) - not upgrading" }
    Ok "SHA-256 of $name verified"
    # The tar of Windows 10 1803+ (bsdtar). Run from the temp folder with a relative archive name: GNU tar
    # (Git for Windows may be first on PATH) reads `C:\x.tar` as a file `x.tar` on a host called `C`.
    $tar = $null
    if ($env:SystemRoot) { $sys = Join-Path (Join-Path $env:SystemRoot 'System32') 'tar.exe'; if (Test-Path -LiteralPath $sys) { $tar = $sys } }
    if (-not $tar) { $c = Get-Command tar -ErrorAction SilentlyContinue; if ($c) { $tar = $c.Source } }
    if (-not $tar) { throw 'tar was not found (Windows 10 version 1803 and later include it)' }
    Push-Location -LiteralPath $tmp
    try { & $tar -xzf $name -C $RepoRoot --strip-components=1 | Out-Host; $rc = $LASTEXITCODE } finally { Pop-Location }
    if ($rc -ne 0) { throw "could not unpack $name (tar exit code $rc)" }
    Ok "Compose files, realm and scripts updated to v$Ver"
  } finally { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}

# ---------------------------------------------------------------- commands
# A yes/no choice: the environment, else .env, else what the last run remembered (as the bash launcher's
# VEGADUTA_SEARCH). Returns '1', '0' or ''.
function Resolve-Toggle([string]$Name, [string]$Remembered) {
  $v = [Environment]::GetEnvironmentVariable($Name)
  if (-not $v) { $v = Get-EnvValue $Name }
  if ($v -match '^(?i:0|off|false|no)$') { return '0' }
  if ($v -match '^(?i:1|on|true|yes)$') { return '1' }
  return $Remembered
}
function Parse-TierFlags {
  if ($Lite) { $script:Tier = 'lite' }
  if ($Full) { $script:Tier = 'full' }
  if ($Search) { $script:SearchOn = '1' } elseif ($NoSearch) { $script:SearchOn = '0' }
  foreach ($p in $ProfileNames) { foreach ($q in ($p -split ',')) { Add-Profile $q.Trim() } }
  if ($Build) { $script:DoBuild = $true }
}

switch ($Command) {
  'personal' {
    if ($NativeCore) { Die '-NativeCore is bash-only (it runs the core jar on the host JVM): use scripts/vegaduta personal --native-core from WSL2.' }
    $script:Tier = 'personal'
    foreach ($p in $ProfileNames) { foreach ($q in ($p -split ',')) { Add-Profile $q.Trim() } }
    if ($Build) { $script:DoBuild = $true }
    Invoke-Secrets ''
    # Web search: -Search / -NoSearch, else VEGADUTA_SEARCH (environment or .env), else what the last run chose.
    $script:SearchOn = if ($Search) { '1' } elseif ($NoSearch) { '0' } else { Resolve-Toggle 'VEGADUTA_SEARCH' (Read-StateValue 'search') }
    Resolve-Profiles
    Resolve-Version
    Resolve-LocalModel
    # A containerised core measures its own cgroup (the Docker VM), not this computer: tell it what the
    # computer is. Written to .env too, so a later `compose` call sees the same numbers (as scripts/vegaduta).
    if (-not $env:HOST_RAM_GB) { $env:HOST_RAM_GB = [string](Get-HostRamGb) }
    if (-not $env:HOST_ACCELERATOR) { $env:HOST_ACCELERATOR = Get-HostAccelerator }
    Set-EnvValue HOST_RAM_GB ([string]$env:HOST_RAM_GB) -Force
    Set-EnvValue HOST_ACCELERATOR ([string]$env:HOST_ACCELERATOR) -Force
    $ca = Get-ComposeArgs
    if ($DryRun) {
      # What `personal` would do, without Docker: the choices, the state they leave and the compose command.
      Save-State
      $planProfiles = 'none'; if ($script:Profiles.Count -gt 0) { $planProfiles = $script:Profiles -join ' ' }
      $planAi = 'none'; if ($script:LocalAi) { $planAi = $script:LocalAi }
      $planModel = 'default'; if ($script:LocalModelTag) { $planModel = $script:LocalModelTag }
      $planSearch = '0'; if ($script:SearchOn -eq '1') { $planSearch = '1' }
      Write-Host "plan: tier=personal profiles=$planProfiles local_ai=$planAi local_model=$planModel search=$planSearch"
      Write-Host ('plan: docker compose ' + ($ca -join ' ') + ' up -d')
      exit 0
    }
    Stop-OldInstallContainers
    if (-not (Invoke-PersonalDoctor)) { Die 'fix the problems above, then re-run' }
    Save-State
    if ($script:LocalAi -eq 'host' -and $script:LocalModelTag) {
      # The chat model, and the embedding model documents and memory use when no sidecar runs.
      foreach ($m in @($script:LocalModelTag, 'nomic-embed-text')) {
        Say "Downloading model '$m' into Ollama on this machine (once; can take a while)"
        $why = Invoke-OllamaPull $m
        if ($why) { Warn "could not download '$m' into Ollama: $why - run: ollama pull $m" }
      }
    }
    # The Ollama container reports healthy only once its model is downloaded (several GB on a first start).
    $wt = if ($env:VEGADUTA_WAIT_TIMEOUT) { $env:VEGADUTA_WAIT_TIMEOUT } elseif ($script:LocalAi -eq 'container') { '3600' } else { '900' }
    $upArgs = @('up', '-d', '--remove-orphans', '--wait', '--wait-timeout', $wt)
    if ($script:DoBuild) { $upArgs += '--build' }
    Say 'Starting (personal) - first start pulls/builds images and runs migrations; this can take several minutes'
    if ((Invoke-UpHeal $ca ($upArgs + $Rest)) -ne 0) { & docker compose @ca ps | Out-Host; Die 'some services did not become healthy. Inspect: .\scripts\vegaduta.ps1 logs <service>' }
    $app = Get-EnvValue 'PUBLIC_APP_URL'; if (-not $app) { $app = 'http://localhost:3000' }
    Write-Host ''
    Write-Host 'VegaDuta (personal) is up' -ForegroundColor Green
    Write-Host "  Console  $app"
    # Core keeps the one-time first-run link in first-run-setup.txt until the owner has chosen a password.
    $setup = ''
    $txt = (Invoke-Native docker (@('compose') + $ca + @('exec', '-T', 'core', 'cat', '/data/storage/.auth/first-run-setup.txt'))) -join "`n"
    $m = [regex]::Matches($txt, 'https?://\S+/login\?setup=[A-Za-z0-9_-]+')
    if ($m.Count -gt 0) { $setup = $m[$m.Count - 1].Value }
    if ($setup) { Write-Host "  First-run setup  $setup" } else { Write-Host '  Open the console and sign in (first-run setup is already done).' }
    if ($script:LocalAi) { Write-Host "  Local model  $($script:LocalModelTag) via $($script:LocalAi) Ollama (free; agents can use it right away)" }
    else { Write-Host '  Add an LLM provider key in the console (Settings -> Providers), or run Ollama on this machine.' }
    if ($script:SearchOn -eq '1') { Write-Host '  Web search   on (a private SearXNG container, no API key). Turn off: .\scripts\vegaduta.ps1 personal -NoSearch' }
  }
  'up' {
    Parse-TierFlags
    Invoke-Secrets $Domain
    Resolve-Version
    if (-not (Invoke-Doctor)) { Die 'fix the problems above, then re-run (.\scripts\vegaduta.ps1 doctor)' }
    $ca = Get-ComposeArgs
    Save-State
    $wt = if ($env:VEGADUTA_WAIT_TIMEOUT) { $env:VEGADUTA_WAIT_TIMEOUT } else { '900' }
    $upArgs = @('up', '-d', '--remove-orphans', '--wait', '--wait-timeout', $wt)
    if ($script:DoBuild) { $upArgs += '--build' }
    Say "Starting ($($script:Tier)) - first start pulls/builds images and runs migrations; this can take several minutes"
    if ((Invoke-UpHeal $ca ($upArgs + $Rest)) -ne 0) { & docker compose @ca ps | Out-Host; Die 'some services did not become healthy. Inspect: .\scripts\vegaduta.ps1 logs <service>' }
    Invoke-Reconcile $ca
    Show-Urls
  }
  { $_ -in 'down','stop' } {
    $ca = Prepare
    foreach ($p in ($AllProfiles + 'rag' + 'search')) { $ca += @('--profile', $p) }
    if ($Volumes) {
      if (-not $Yes) {
        $ans = Read-Host "This DELETES all data volumes of this $($script:Tier) install. Type 'delete my data' to continue"
        if ($ans -ne 'delete my data') { Die 'aborted' }
      }
      [void](Invoke-Dc $ca @('down', '--volumes', '--remove-orphans'))
    } else { [void](Invoke-Dc $ca @('down', '--remove-orphans')) }
  }
  { $_ -in 'status','ps' } {
    $ca = Prepare
    Say "Tier: $($script:Tier)  profiles: $($script:Profiles -join ' ')"
    [void](Invoke-Dc $ca @('ps', '--format', 'table {{.Service}}\t{{.State}}\t{{.Health}}\t{{.Ports}}'))
  }
  'logs' {
    $ca = Prepare
    $tail = if ($env:VEGADUTA_LOG_TAIL) { $env:VEGADUTA_LOG_TAIL } else { '200' }
    $la = @('logs', '--tail', $tail); if (-not $NoFollow) { $la += '-f' }
    [void](Invoke-Dc $ca ($la + $Rest))
  }
  'doctor' { Parse-TierFlags; if (-not (Invoke-Doctor)) { exit 1 } }
  'reset-password' {
    # Owner forgot the password: ask core to write a one-time reset link to password-reset.txt on
    # this machine (file access = proof of ownership, like the first-run link), then print it.
    $ca = Prepare
    $email = if ($Rest.Count -gt 0) { $Rest[0] } else { '' }
    $api = Get-EnvValue 'PUBLIC_API_URL'; if (-not $api) { $port = Get-EnvValue 'CORE_HOST_PORT'; if (-not $port) { $port = '8080' }; $api = "http://localhost:$port" }
    $body = if ($email) { @{ email = $email } | ConvertTo-Json -Compress } else { '{}' }
    try {
      Invoke-RestMethod -Method Post -Uri "$api/api/auth/local/reset-request" -ContentType 'application/json' -Body $body | Out-Null
    } catch { Die "core at $api did not answer the reset request - is it running? (.\scripts\vegaduta.ps1 status)" }
    $txt = (Invoke-Native docker (@('compose') + $ca + @('exec', '-T', 'core', 'cat', '/data/storage/.auth/password-reset.txt'))) -join "`n"
    $m = [regex]::Matches($txt, 'https?://\S+/login\?setup=[A-Za-z0-9_-]+')
    if ($m.Count -eq 0) { Write-Host $txt; Die 'no reset link in password-reset.txt - check the account e-mail and try again' }
    Write-Host ''
    Write-Host 'Password reset link (one use):'
    Write-Host "  $($m[$m.Count - 1].Value)"
    Write-Host 'Open it in your browser, choose a new password, and you are signed in.'
  }
  'secrets' { Invoke-Secrets $Domain }
  'backup' { $ca = Prepare; [void](Invoke-Backup $ca $(if ($Rest.Count -gt 0) { $Rest[0] } else { '' })) }
  'restore' {
    if ($Rest.Count -lt 1) { Die 'usage: .\scripts\vegaduta.ps1 restore BACKUP_DIR [-Yes]' }
    $dir = $Rest[0]
    if (-not (Test-Path (Join-Path $dir 'app-db.dump'))) { Die "restore: $dir\app-db.dump not found" }
    $ca = Prepare
    if (-not $Yes) {
      $ans = Read-Host "This REPLACES the current database(s) with $dir. Type 'restore' to continue"
      if ($ans -ne 'restore') { Die 'aborted' }
    }
    [void](Invoke-Dc $ca @('stop', 'core', 'core-worker', 'core-jobs', 'web', 'web-admin', 'keycloak', 'pgbouncer'))
    [void](Invoke-Dc $ca @('up', '-d', '--wait', 'postgres'))
    foreach ($pair in @(@('postgres', 'app-db.dump'), @('keycloak-db', 'keycloak-db.dump'))) {
      $f = Join-Path $dir $pair[1]
      if (-not (Test-Path $f)) { continue }
      if ($pair[0] -eq 'keycloak-db') { if ($script:Tier -ne 'full') { continue }; [void](Invoke-Dc $ca @('up', '-d', '--wait', 'keycloak-db')) }
      if ((Invoke-Dc $ca @('cp', $f, "$($pair[0]):/tmp/restore.dump")) -ne 0) { Die "restore: could not copy $f into $($pair[0])" }
      $rc = Invoke-Dc $ca @('exec', '-T', $pair[0], 'sh', '-c', 'pg_restore -U $POSTGRES_USER -d $POSTGRES_DB --clean --if-exists --no-owner --exit-on-error /tmp/restore.dump; rc=$?; rm -f /tmp/restore.dump; exit $rc')
      if ($rc -ne 0) { Die "restore: pg_restore into $($pair[0]) failed (the stack is left stopped)" }
      Ok "$($pair[1]) restored"
    }
    Say 'Restored. Starting the stack: .\scripts\vegaduta.ps1 up'
    & $PSCommandPath up
  }
  'upgrade' {
    if (-not (Test-Path $EnvFile)) { Die "no $EnvFile - install first (.\scripts\vegaduta.ps1 up)" }
    $from = Get-EnvValue 'VEGADUTA_VERSION'
    $target = $Version; if (-not $target) { $target = Get-ShippedVersion }; if (-not $target) { $target = $from }
    if (-not $target) { Die 'upgrade: no target version - pass -Version X.Y.Z' }
    if ($target -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') { Die "upgrade: '$target' is not a release version (pass -Version X.Y.Z)" }
    $ca = Prepare
    # 1. Backup first; abort when it fails (database migrations are one-way).
    $bdir = ''
    if ($Continued) { $bdir = [string]$env:VEGADUTA_UPGRADE_BACKUP }   # re-run after the download: the backup was taken before it
    elseif (-not $NoBackup) {
      if (-not (Invoke-Native docker (@('compose') + $ca + @('ps', '-q', 'postgres')))) { Die 'upgrade aborted: postgres is not running, so no backup can be taken. Start the stack or pass -NoBackup.' }
      $bdir = Invoke-Backup $ca ''
      if (-not $bdir) { Die 'upgrade aborted: backup failed (fix it, or re-run with -NoBackup at your own risk)' }
    } else { Warn '-NoBackup: upgrading without a database backup' }
    # 2. The compose files, realm and scripts of the target release. A folder from a release tarball is
    # brought to it here; a git checkout switches with `git checkout vX.Y.Z` instead. The new script (which
    # may differ) then finishes the upgrade in a process of its own.
    $shipped = Get-ShippedVersion
    if (-not $Continued -and $target -ne 'local' -and $target -ne $shipped) {
      if (Test-Path -LiteralPath (Join-Path $RepoRoot '.git')) {
        if ($shipped) { Warn "git checkout: compose files are from $shipped; run 'git checkout v$target' so files and images match" }
      } else {
        try { Install-Release $target } catch { Die "upgrade aborted: $($_.Exception.Message)" }
        $env:VEGADUTA_UPGRADE_BACKUP = $bdir
        $ps = (Get-Process -Id $PID).Path
        $re = @('-NoProfile'); if ($env:OS -eq 'Windows_NT') { $re += @('-ExecutionPolicy', 'Bypass') }
        & $ps @re -File $PSCommandPath upgrade -Version $target -NoBackup -Continued
        exit $LASTEXITCODE
      }
    }
    $bak = "$EnvFile.bak." + (Get-Date -Format 'yyyyMMddHHmmss')
    Copy-Item $EnvFile $bak; Protect-SecretFile $bak
    Set-EnvValue VEGADUTA_VERSION $target -Force
    Invoke-Secrets ''
    Resolve-Version
    $ca = Prepare
    Say "Upgrading $from -> $target"
    if ($script:DoBuild) { [void](Invoke-Dc $ca @('build', '--pull')) } else { [void](Invoke-Dc $ca @('pull', '--ignore-buildable')) }
    if ((Invoke-Dc $ca @('up', '-d', '--remove-orphans', '--wait', '--wait-timeout', '900')) -ne 0) {
      Write-Host "Upgrade to $target did not become healthy. Roll back (migrations are forward-only, so restore the backup too):" -ForegroundColor Red
      Write-Host "  .\scripts\vegaduta.ps1 upgrade -Version $from -NoBackup"
      Write-Host "  .\scripts\vegaduta.ps1 restore $bdir"
      exit 1
    }
    Invoke-Reconcile $ca
    Say "Upgrade complete ($target). Database migrations ran on core start."
    if ($bdir) { Write-Host "    Pre-upgrade backup: $bdir" }
  }
  'models' {
    # Only `pull` exists here (the Setup app runs it for a chosen model); `list` and `rm` are bash-only (WSL2).
    $sub = if ($Rest.Count -gt 0) { $Rest[0] } else { '' }
    if ($sub -ne 'pull' -and $sub -ne 'install') { Die "models: unknown subcommand '$sub' (only: pull TAG; list and rm are bash-only, use WSL2)" }
    $tag = if ($Rest.Count -gt 1) { $Rest[1] } else { '' }
    if (-not $tag) { Die 'models pull needs a model tag, e.g. qwen3.5:4b' }
    if ($tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._:/@-]*$') { Die "models pull: '$tag' is not a model tag" }
    $mode = Get-OllamaMode
    if (-not $mode) { Die "no local Ollama: start the stack with '.\scripts\vegaduta.ps1 personal' (a container), or install Ollama from https://ollama.com/download" }
    $catalog = Join-Path $RepoRoot 'deploy\models\catalog.txt'
    if ((Test-Path $catalog) -and -not (@([System.IO.File]::ReadAllLines($catalog)) | Where-Object { $_.StartsWith("$tag|") })) { Warn "'$tag' is not in the catalog (deploy/models/catalog.txt); pulling it anyway" }
    Say "Downloading model '$tag' into Ollama ($mode; once, can take a while)"
    if ($mode -eq 'container') {
      $ca = Prepare
      if ((Invoke-Dc $ca @('exec', '-T', 'ollama', 'ollama', 'pull', $tag)) -ne 0) { Die "could not download '$tag' into the Ollama container" }
    } else {
      $why = Invoke-OllamaPull $tag
      if ($why) { Die "could not download '$tag' into Ollama: $why - run: ollama pull $tag" }
    }
    Ok "'$tag' is installed. To use it for the free pool: Platform Admin -> LLM providers (base URL as for the existing Ollama row, model '$tag')."
  }
  'make-admin' {
    if ($Rest.Count -lt 1) { Die 'usage: .\scripts\vegaduta.ps1 make-admin USERNAME_OR_EMAIL' }
    $ca = Prepare
    $realm = Get-EnvValue 'KEYCLOAK_REALM'; if (-not $realm) { $realm = 'agentic-ai' }
    $inner = @'
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
ID=$($K get users -r "$REALM" -q "username=$TARGET_USER" -q exact=true --fields id --format csv --noquotes | head -1)
[ -n "$ID" ] || ID=$($K get users -r "$REALM" -q "email=$TARGET_USER" -q exact=true --fields id --format csv --noquotes | head -1)
[ -n "$ID" ] || { echo "no such user: $TARGET_USER" >&2; exit 1; }
$K add-roles -r "$REALM" --uid "$ID" --rolename platform-admin
'@
    ($inner -replace "`r`n", "`n") | & docker compose @ca exec -T -e "TARGET_USER=$($Rest[0])" -e "REALM=$realm" keycloak bash -s | Out-Host
    if ($LASTEXITCODE -eq 0) { Say "Granted platform-admin to $($Rest[0]) (sign out and back in)." } else { Die "could not grant the role - has '$($Rest[0])' signed up yet?" }
  }
  'compose' { $ca = Prepare; exit (Invoke-Dc $ca $Rest) }
  'help' { Get-Help $PSCommandPath -Detailed | Out-Host }
  default {
    Write-Host "ERROR: unknown command '$Command'" -ForegroundColor Red
    Get-Help $PSCommandPath -Detailed | Out-Host
    exit 2
  }
}
