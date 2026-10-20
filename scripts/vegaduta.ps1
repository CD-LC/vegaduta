<#
.SYNOPSIS
  vegaduta - install / run / operate a self-hosted VegaDuta with Docker Compose (Windows).

.DESCRIPTION
  Same commands as scripts/vegaduta (bash):
    .\scripts\vegaduta.ps1 up [-Lite|-Full] [-Profile a,b] [-Build] [-Domain example.com]
    .\scripts\vegaduta.ps1 personal [-Build]   (one-person laptop mode, no Keycloak; the host-JVM -NativeCore option is bash-only: use WSL2)
    .\scripts\vegaduta.ps1 down [-Volumes]
    .\scripts\vegaduta.ps1 status
    .\scripts\vegaduta.ps1 logs [service] [-NoFollow]
    .\scripts\vegaduta.ps1 doctor [-Lite|-Full]
    .\scripts\vegaduta.ps1 secrets [-Domain example.com]
    .\scripts\vegaduta.ps1 backup [DIR]
    .\scripts\vegaduta.ps1 restore DIR [-Yes]
    .\scripts\vegaduta.ps1 upgrade [-Version 1.2.3] [-NoBackup]
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
  [string] $LocalModel = ''
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
  CODE_INTEL_INTERNAL_TOKEN='hex32'; TOOL_SANDBOX_INTERNAL_TOKEN='hex32'; MCP_STDIO_SIDECAR_SHARED_SECRET='hex32'
  APP_BUILDER_CORE_SERVICE_TOKEN='hex32'; APP_BUILDER_ENCRYPTION_KEY='fernet'; RL_TRAINER_INTERNAL_TOKEN='hex32'
  PLATFORM_ALERTS_WEBHOOK_SECRET='b64'; TEMPORAL_DB_PASSWORD='hex16'; LIVEKIT_API_KEY='hex16'; LIVEKIT_API_SECRET='b64'
  GRAFANA_ADMIN_PASSWORD='hex16'; LANGFUSE_POSTGRES_PASSWORD='hex16'; LANGFUSE_CLICKHOUSE_PASSWORD='hex16'
  LANGFUSE_REDIS_AUTH='hex16'; LANGFUSE_S3_SECRET_KEY='hex32'; LANGFUSE_SALT='hex32'; LANGFUSE_ENCRYPTION_KEY='hex32'
  LANGFUSE_NEXTAUTH_SECRET='hex32'; LANGFUSE_INIT_PROJECT_PUBLIC_KEY='lfpk'; LANGFUSE_INIT_PROJECT_SECRET_KEY='lfsk'
  LANGFUSE_INIT_USER_PASSWORD='hex16'
}

function Say($m)  { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [ok]   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [warn] $m" -ForegroundColor Yellow; $script:Warnings++ }
function Bad($m)  { Write-Host "  [fail] $m" -ForegroundColor Red; $script:Failures++ }
function Die($m)  { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
$script:Warnings = 0; $script:Failures = 0

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
    $r = Invoke-RestMethod -UseBasicParsing -TimeoutSec 20 -Headers @{ 'User-Agent' = 'vegaduta-setup' } "https://api.github.com/repos/$(if ($env:VEGADUTA_REPO) { $env:VEGADUTA_REPO } else { 'CD-LC/vegaduta' })/releases/latest"
    $tag = [string]$r.tag_name
    if ($tag -match '^v(\d.*)$') {
      Say "No release pinned in ${EnvFile}: using the latest published release ($($Matches[1]))"
      Set-EnvValue VEGADUTA_VERSION $Matches[1] -Force
      $env:VEGADUTA_VERSION = $Matches[1]; return
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
function Test-LocalUrl([string]$u) { return ($u -match 'localhost|127\.0\.0\.1|\[::1\]') }

# ----------------------------------------------------------------- secrets
function Invoke-Secrets([string]$DomainArg) {
  if (-not (Test-Path $EnvFile)) {
    if (-not (Test-Path $EnvExample)) { Die "missing $EnvExample" }
    Write-EnvLines ([System.IO.File]::ReadAllLines($EnvExample))
    Say "Created $EnvFile from .env.example"
  }
  Protect-SecretFile $EnvFile
  $shipped = Get-ShippedVersion
  if ($shipped) { Set-EnvValue VEGADUTA_VERSION $shipped }
  $filled = 0
  foreach ($k in $SecretSpecs.Keys) {
    if (-not (Get-EnvValue $k)) { Set-EnvValue $k (New-Secret $SecretSpecs[$k]); $filled++ }
  }
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

function Add-Profile([string]$p) { if ($p -and -not $script:Profiles.Contains($p)) { $script:Profiles.Add($p) } }
function Load-State {
  if (-not $script:Tier -and (Test-Path $StateFile)) {
    foreach ($l in [System.IO.File]::ReadAllLines($StateFile)) {
      if ($l -like 'tier=*') { $script:Tier = $l.Substring(5) }
      elseif ($l -like 'profiles=*' -and $script:Profiles.Count -eq 0) { foreach ($p in ($l.Substring(9) -split ' ')) { Add-Profile $p } }
      elseif ($l -eq 'build=1') { $script:DoBuild = $true }
      elseif ($l -like 'local_ai=*') { $script:LocalAi = $l.Substring(9) }
      elseif ($l -like 'local_model=*') { $script:LocalModelTag = $l.Substring(12) }
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
  $txt = "tier=$($script:Tier)`nprofiles=$($script:Profiles -join ' ')`nbuild=$b`nlocal_ai=$($script:LocalAi)`nlocal_model=$($script:LocalModelTag)`ncloud=$c`n"
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
  if ($env:VEGADUTA_ASSUME_NO -eq '1' -or (Test-HostOllama)) { return }
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
  $gb = 0
  try { $gb = [int][math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB) } catch { $gb = 0 }
  if ($gb -eq 0 -or $gb -ge 8) { Install-HostOllama }
  $hostOllama = Test-HostOllama
  # In the container the model only gets the memory Docker has (a WSL2 VM is often smaller than the computer).
  $modelGb = $gb
  if (-not $hostOllama) {
    try { $dg = [int][math]::Floor([int64](& docker info --format '{{.MemTotal}}' 2>$null) / 1GB) } catch { $dg = 0 }
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
    $a += @('-f', (Join-Path $ComposeDir 'personal.yml'))
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
          try { $dgb = [int][math]::Floor([int64](& docker info --format '{{.MemTotal}}' 2>$null) / 1GB) } catch { $dgb = 0 }
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
  foreach ($p in $script:Profiles) { $a += @('--profile', $p) }
  $env:COMPOSE_PROFILES = ''
  return ,$a
}
function Invoke-Dc([string[]]$ComposeArgs, [string[]]$More) {
  & docker compose @ComposeArgs @More | Out-Host
  return $LASTEXITCODE
}
function Prepare { Load-State; Resolve-Profiles; return (Get-ComposeArgs) }

# ------------------------------------------------------------------ doctor
function Test-PortInUse([int]$port) {
  $c = New-Object System.Net.Sockets.TcpClient
  try { $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null); $okc = $iar.AsyncWaitHandle.WaitOne(300); if ($okc) { $c.EndConnect($iar); return $true }; return $false }
  catch { return $false } finally { $c.Close() }
}
function Invoke-Doctor {
  $ca = Prepare
  $script:Warnings = 0; $script:Failures = 0
  Say "Checking prerequisites (tier: $($script:Tier); profiles: $($script:Profiles -join ' '))"
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Bad 'docker not found - install Docker Desktop'; return $false }
  $sv = & docker version --format '{{.Server.Version}}' 2>$null
  if ($LASTEXITCODE -ne 0) { Bad 'Docker daemon not running - start Docker Desktop'; return $false } else { Ok "Docker daemon reachable ($sv)" }
  $cv = (& docker compose version --short 2>$null) -replace '^v',''
  if (-not $cv) { Bad 'docker compose v2 not found' }
  elseif ([version]($cv -replace '[^0-9.].*$','') -ge [version]'2.24.0') { Ok "docker compose $cv" }
  else { Bad "docker compose $cv is too old (need >= 2.24.0)" }
  $needGb = if ($script:Tier -eq 'full') { 12 } else { 6 }
  $diskNeed = if ($script:Tier -eq 'full') { 40 } else { 15 }
  $memB = [int64](& docker info --format '{{.MemTotal}}' 2>$null)
  $memGb = [math]::Floor($memB / 1GB)
  if ($memGb -ge $needGb) { Ok "Docker memory $memGb GB (>= $needGb GB for $($script:Tier))" } else { Warn "Docker has $memGb GB RAM; $($script:Tier) wants >= $needGb GB (Docker Desktop > Settings > Resources)" }
  $drive = (Get-Item $RepoRoot).PSDrive
  $freeGb = [math]::Floor($drive.Free / 1GB)
  if ($freeGb -ge $diskNeed) { Ok "Free disk $freeGb GB" } else { Warn "Only $freeGb GB free; $($script:Tier) wants >= $diskNeed GB" }
  if (Test-Path $EnvFile) { Ok ".env present ($EnvFile)" } else { Bad '.env missing - run: .\scripts\vegaduta.ps1 secrets'; return $false }
  $missing = @($SecretSpecs.Keys | Where-Object { -not (Get-EnvValue $_) })
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
  $running = & docker compose @ca ps -q 2>$null
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
  $sv = & docker version --format '{{.Server.Version}}' 2>$null
  if ($LASTEXITCODE -ne 0) { Bad 'Docker daemon not running - start Docker Desktop'; return $false } else { Ok "Docker daemon reachable ($sv)" }
  $memGb = [math]::Floor([int64](& docker info --format '{{.MemTotal}}' 2>$null) / 1GB)
  if ($memGb -ge 4) { Ok "Docker memory $memGb GB (>= 4 GB)" } else { Warn "Docker has $memGb GB RAM; personal wants >= 4 GB (raise Docker Desktop's memory limit)" }
  foreach ($k in @('POSTGRES_PASSWORD','POSTGRES_APP_PASSWORD','VALKEY_PASSWORD','APP_ENCRYPTION_KEY')) {
    if (-not (Get-EnvValue $k)) { Bad "$k is empty - run .\scripts\vegaduta.ps1 secrets" }
  }
  foreach ($port in @(3000, 8080)) { if (Test-PortInUse $port) { Bad "Port $port is already in use" } }
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
  if ($script:Tier -eq 'full' -and (& docker compose @ca ps -q keycloak-db 2>$null)) { $dbs += ,@('keycloak-db', 'keycloak-db.dump') }
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

# ---------------------------------------------------------------- commands
function Parse-TierFlags {
  if ($Lite) { $script:Tier = 'lite' }
  if ($Full) { $script:Tier = 'full' }
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
    Resolve-Profiles
    Resolve-Version
    Resolve-LocalModel
    if (-not (Invoke-PersonalDoctor)) { Die 'fix the problems above, then re-run' }
    $ca = Get-ComposeArgs
    Save-State
    if ($script:LocalAi -eq 'host' -and $script:LocalModelTag) {
      # The chat model, and the embedding model documents and memory use when no sidecar runs.
      foreach ($m in @($script:LocalModelTag, 'nomic-embed-text')) {
        Say "Downloading model '$m' into Ollama on this machine (once; can take a while)"
        try { Invoke-RestMethod -Method Post -TimeoutSec 3600 -Uri 'http://127.0.0.1:11434/api/pull' -Body (@{ model = $m; stream = $false } | ConvertTo-Json) | Out-Null }
        catch { Warn "could not download '$m' into Ollama: $($_.Exception.Message) - run: ollama pull $m" }
      }
    }
    # The Ollama container reports healthy only once its model is downloaded (several GB on a first start).
    $wt = if ($env:VEGADUTA_WAIT_TIMEOUT) { $env:VEGADUTA_WAIT_TIMEOUT } elseif ($script:LocalAi -eq 'container') { '3600' } else { '900' }
    $upArgs = @('up', '-d', '--remove-orphans', '--wait', '--wait-timeout', $wt)
    if ($script:DoBuild) { $upArgs += '--build' }
    Say 'Starting (personal) - first start pulls/builds images and runs migrations; this can take several minutes'
    if ((Invoke-Dc $ca ($upArgs + $Rest)) -ne 0) { & docker compose @ca ps | Out-Host; Die 'some services did not become healthy. Inspect: .\scripts\vegaduta.ps1 logs <service>' }
    $app = Get-EnvValue 'PUBLIC_APP_URL'; if (-not $app) { $app = 'http://localhost:3000' }
    Write-Host ''
    Write-Host 'VegaDuta (personal) is up' -ForegroundColor Green
    Write-Host "  Console  $app"
    # Core keeps the one-time first-run link in first-run-setup.txt until the owner has chosen a password.
    $setup = ''
    $txt = (& docker compose @ca exec -T core cat /data/storage/.auth/first-run-setup.txt 2>$null) -join "`n"
    $m = [regex]::Matches($txt, 'https?://\S+/login\?setup=[A-Za-z0-9_-]+')
    if ($m.Count -gt 0) { $setup = $m[$m.Count - 1].Value }
    if ($setup) { Write-Host "  First-run setup  $setup" } else { Write-Host '  Open the console and sign in (first-run setup is already done).' }
    if ($script:LocalAi) { Write-Host "  Local model  $($script:LocalModelTag) via $($script:LocalAi) Ollama (free; agents can use it right away)" }
    else { Write-Host '  Add an LLM provider key in the console (Settings -> Providers), or run Ollama on this machine.' }
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
    if ((Invoke-Dc $ca ($upArgs + $Rest)) -ne 0) { & docker compose @ca ps | Out-Host; Die 'some services did not become healthy. Inspect: .\scripts\vegaduta.ps1 logs <service>' }
    Invoke-Reconcile $ca
    Show-Urls
  }
  { $_ -in 'down','stop' } {
    $ca = Prepare
    foreach ($p in ($AllProfiles + 'rag')) { $ca += @('--profile', $p) }
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
    $ca = Prepare
    # 1. Backup first; abort when it fails (database migrations are one-way).
    $bdir = ''
    if (-not $NoBackup) {
      if (-not (& docker compose @ca ps -q postgres 2>$null)) { Die 'upgrade aborted: postgres is not running, so no backup can be taken. Start the stack or pass -NoBackup.' }
      $bdir = Invoke-Backup $ca ''
      if (-not $bdir) { Die 'upgrade aborted: backup failed (fix it, or re-run with -NoBackup at your own risk)' }
    } else { Warn '-NoBackup: upgrading without a database backup' }
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
  default { Get-Help $PSCommandPath -Detailed | Out-Host }
}
