<#
.SYNOPSIS
    Nightly Postgres backup for the VegaDuta stack (Windows host, Docker containers).

.DESCRIPTION
    Dumps BOTH Postgres containers:
      1. agentic-postgres    - ALL tenant schemas + control_plane (db: agentic_ai) + everything
                               else in that cluster, via pg_dumpall.
      2. agentic-keycloak-db - Keycloak's own database (db: keycloak_db). Keycloak does NOT
                               live in the main Postgres container (deploy/docker-compose.yml
                               defines it as a separate service/volume), so it needs its own dump.

    Output:  ..\vegaduta-backups\pg-YYYYMMDD-HHmm.sql.gz   (main cluster)
             ..\vegaduta-backups\kc-YYYYMMDD-HHmm.sql.gz   (keycloak cluster)
    Log:     ..\vegaduta-backups\backup-log.txt
    (paths relative to the repo root; the backups folder is a SIBLING of the repo,
     so a repo wipe/re-clone never touches backups)

    Rotation: keep 7 daily + 4 weekly (Sunday) + 3 monthly (1st of month); delete older.
    Safety:   aborts (loudly, logged, exit 1) if free disk on the backup drive < 15 GB.

    WHY the dump is gzipped INSIDE the container and copied out with `docker cp`,
    instead of `docker exec ... | gzip > file` on the host:
    Windows PowerShell 5.1 treats native-command stdout as TEXT. Piping or `>`-redirecting
    binary gzip bytes through PowerShell decodes them with the console codepage and
    re-encodes them (often as UTF-16), silently corrupting the archive. Running
    `pg_dumpall | gzip -c > /tmp/file` entirely inside the (Linux) container keeps the
    bytes on a byte-faithful path, and `docker cp` transfers the file verbatim.
    `bash -o pipefail` makes a pg_dumpall failure fail the whole pipeline (otherwise
    gzip's exit code would mask it and we'd archive a truncated dump).

.NOTES
    PowerShell 5.1 compatible: no '&&', no ternary, no null-coalescing.
    Intended to be run by the Windows Scheduled Task registered by
    scripts\backup\register-backup-task.ps1 (see deploy\BACKUP-RUNBOOK.md).
#>

# ============================== PARAMETERS ====================================
# Container names / users come from deploy/docker-compose.yml + deploy/.env.
# Verified 2026-07-25: postgres service -> container_name agentic-postgres,
# POSTGRES_USER=agentic; keycloak-db service -> container_name agentic-keycloak-db,
# KEYCLOAK_DB_USER=keycloak_user.
$PgContainer   = 'agentic-postgres'
$PgUser        = 'agentic'
$KcContainer   = 'agentic-keycloak-db'
$KcUser        = 'keycloak_user'

$MinFreeGB     = 15          # abort if backup drive has less free space than this
# Size floors are a WEAK smoke test, not a correctness check - see the trailer
# verification in Invoke-ContainerDump, which is the authoritative one.
# Calibrated 2026-07-29 against a real run: the main cluster (75 schemas, 138k lines)
# gzips to ~640 KB and Keycloak to ~60 KB. The previous 1MB main floor was set
# optimistically and rejected a VERIFIED-COMPLETE dump, which is why no backup had
# ever succeeded. Keep these low enough to pass a healthy small cluster and high
# enough to catch a genuinely empty/failed dump.
$MinMainBytes  = 256KB       # main dump smaller than this = something is wrong
$MinKcBytes    = 20KB        # keycloak dump is much smaller; still must be non-trivial

$KeepDaily     = 7
$KeepWeekly    = 4           # Sundays
$KeepMonthly   = 3           # 1st of month

# Backup dir: <parent-of-repo>\vegaduta-backups  (repo = two levels up from this script)
$RepoRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$BackupRoot = Join-Path (Split-Path -Parent $RepoRoot) 'vegaduta-backups'
$LogFile    = Join-Path $BackupRoot 'backup-log.txt'

# ============================== HELPERS =======================================

function Write-Log {
    param([string]$Message)
    $line = ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message)
    Write-Host $line
    if (Test-Path $BackupRoot) {
        Add-Content -Path $LogFile -Value $line -Encoding UTF8
    }
}

function Fail {
    param([string]$Reason)
    Write-Log ('FAILURE: {0}' -f $Reason)
    exit 1
}

function Test-GzipFile {
    # Returns $true if the file starts with the gzip magic bytes 0x1F 0x8B.
    param([string]$Path)
    $ok = $false
    try {
        $fs = [System.IO.File]::OpenRead($Path)
        try {
            $b0 = $fs.ReadByte()
            $b1 = $fs.ReadByte()
            if ($b0 -eq 0x1F) { if ($b1 -eq 0x8B) { $ok = $true } }
        } finally {
            $fs.Close()
        }
    } catch {
        $ok = $false
    }
    return $ok
}

function Invoke-ContainerDump {
    # Dumps one container's whole cluster (pg_dumpall) to a gz file on the host.
    # Returns the full path of the written file; calls Fail on any problem.
    param(
        [string]$Container,
        [string]$User,
        [string]$Prefix,       # 'pg' or 'kc'
        [long]  $MinBytes,
        [string]$Stamp
    )

    $fileName = ('{0}-{1}.sql.gz' -f $Prefix, $Stamp)
    $destPath = Join-Path $BackupRoot $fileName
    $tmpInContainer = ('/tmp/vd-backup-{0}.sql.gz' -f $Prefix)

    Write-Log ('Dumping {0} (user {1}) -> {2}' -f $Container, $User, $fileName)

    # 1. Dump + gzip entirely inside the container (byte-faithful; see header comment).
    #    --clean --if-exists makes the restore drill idempotent on a throwaway server.
    $dumpCmd = ('set -o pipefail; pg_dumpall -U {0} --clean --if-exists | gzip -c > {1}' -f $User, $tmpInContainer)
    docker exec $Container bash -c $dumpCmd
    if ($LASTEXITCODE -ne 0) {
        Fail ('pg_dumpall inside {0} exited with code {1}' -f $Container, $LASTEXITCODE)
    }

    # 1b. Verify the dump is COMPLETE, not merely non-empty.
    #     pg_dumpall writes a fixed terminator as its last content line; if the dump
    #     was truncated (disk full, OOM-killed backend, broken pipe) the terminator is
    #     absent even though the gzip stream may still decompress cleanly. This is the
    #     real correctness check - the byte-size floor below is only a smoke test.
    #     Run inside the container so the compressed bytes never cross the PowerShell
    #     text boundary (same reasoning as the dump pipeline itself, see header).
    #     `gzip -t` additionally proves the stream is not truncated mid-member.
    $verifyCmd = ('set -o pipefail; gzip -t {0} && zcat {0} | tail -20 | grep -q "PostgreSQL database cluster dump complete"' -f $tmpInContainer)
    docker exec $Container bash -c $verifyCmd
    if ($LASTEXITCODE -ne 0) {
        Fail ('{0} dump inside {1} is INCOMPLETE - missing pg_dumpall completion trailer or failed gzip integrity test' -f $Prefix, $Container)
    }

    # 2. Copy out to the host.
    docker cp ('{0}:{1}' -f $Container, $tmpInContainer) $destPath
    if ($LASTEXITCODE -ne 0) {
        Fail ('docker cp from {0} exited with code {1}' -f $Container, $LASTEXITCODE)
    }

    # 3. Remove the temp file inside the container (do not leave dumps accumulating
    #    on the container layer - disk pressure on this host is a known issue).
    docker exec $Container rm -f $tmpInContainer
    if ($LASTEXITCODE -ne 0) {
        # Non-fatal, but log it: leftover temp files eat Docker VHD space.
        Write-Log ('WARNING: could not remove {0} inside {1}' -f $tmpInContainer, $Container)
    }

    # 4. Verify the artifact.
    if (-not (Test-Path $destPath)) {
        Fail ('expected output file missing: {0}' -f $destPath)
    }
    $len = (Get-Item $destPath).Length
    if ($len -lt $MinBytes) {
        Fail ('{0} is only {1} bytes (minimum {2}) - dump looks truncated/empty' -f $fileName, $len, $MinBytes)
    }
    if (-not (Test-GzipFile -Path $destPath)) {
        Fail ('{0} does not have a valid gzip header - archive is corrupt' -f $fileName)
    }

    Write-Log ('OK: {0} written ({1:N1} MB), gzip header valid' -f $fileName, ($len / 1MB))
    return $destPath
}

function Invoke-Rotation {
    # Keeps: $KeepDaily newest overall, $KeepWeekly newest Sunday dumps,
    #        $KeepMonthly newest 1st-of-month dumps. Deletes the rest.
    # Only touches files matching <prefix>-YYYYMMDD-HHmm.sql.gz, so the original
    # manual backups (agentic-pg-*.sql.gz / keycloak-pg-*.sql.gz) are never deleted.
    param([string]$Prefix)

    $pattern = ('^{0}-(\d{{8}})-(\d{{4}})\.sql\.gz$' -f $Prefix)
    $entries = @()
    $files = Get-ChildItem -Path $BackupRoot -File -Filter ('{0}-*.sql.gz' -f $Prefix)
    foreach ($f in $files) {
        if ($f.Name -match $pattern) {
            $dateStr = $Matches[1]
            $parsed = $null
            try {
                $parsed = [datetime]::ParseExact($dateStr, 'yyyyMMdd', $null)
            } catch {
                continue   # unparseable name: leave the file alone
            }
            $entries += New-Object PSObject -Property @{ File = $f; Date = $parsed }
        }
    }
    if ($entries.Count -eq 0) { return }

    $sorted = $entries | Sort-Object -Property @{Expression = { $_.File.Name }} -Descending

    $keep = @{}
    $i = 0
    foreach ($e in $sorted) {
        if ($i -lt $KeepDaily) { $keep[$e.File.FullName] = $true }
        $i = $i + 1
    }
    $i = 0
    foreach ($e in $sorted) {
        if ($e.Date.DayOfWeek -eq [System.DayOfWeek]::Sunday) {
            if ($i -lt $KeepWeekly) { $keep[$e.File.FullName] = $true }
            $i = $i + 1
        }
    }
    $i = 0
    foreach ($e in $sorted) {
        if ($e.Date.Day -eq 1) {
            if ($i -lt $KeepMonthly) { $keep[$e.File.FullName] = $true }
            $i = $i + 1
        }
    }

    foreach ($e in $sorted) {
        if (-not $keep.ContainsKey($e.File.FullName)) {
            try {
                Remove-Item -Path $e.File.FullName -Force -Confirm:$false -ErrorAction Stop
                Write-Log ('Rotated out (deleted): {0}' -f $e.File.Name)
            } catch {
                Write-Log ('WARNING: rotation could not delete {0}: {1}' -f $e.File.Name, $_.Exception.Message)
            }
        }
    }
}

# ============================== MAIN ==========================================

$exitCode = 0
try {
    # Backup dir must exist before we can log to it.
    if (-not (Test-Path $BackupRoot)) {
        New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
    }

    Write-Log ('=== Backup run starting (containers: {0}, {1}) ===' -f $PgContainer, $KcContainer)

    # --- 1. Disk-space gate FIRST (this host has hit 99% disk twice) -----------
    $driveRoot = [System.IO.Path]::GetPathRoot((Resolve-Path $BackupRoot).Path)
    $drive = New-Object System.IO.DriveInfo($driveRoot)
    $freeGB = [math]::Round($drive.AvailableFreeSpace / 1GB, 1)
    if ($freeGB -lt $MinFreeGB) {
        Fail ('ABORTING BEFORE DUMP - only {0} GB free on {1} (minimum {2} GB). Free disk space, then re-run.' -f $freeGB, $driveRoot, $MinFreeGB)
    }
    Write-Log ('Disk check OK: {0} GB free on {1}' -f $freeGB, $driveRoot)

    # --- 2. Docker / container availability ------------------------------------
    docker inspect --format '{{.State.Running}}' $PgContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Fail ('container {0} not found or Docker engine not reachable' -f $PgContainer)
    }
    docker inspect --format '{{.State.Running}}' $KcContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Fail ('container {0} not found or Docker engine not reachable' -f $KcContainer)
    }

    # --- 3. Dumps ---------------------------------------------------------------
    $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
    Invoke-ContainerDump -Container $PgContainer -User $PgUser -Prefix 'pg' -MinBytes $MinMainBytes -Stamp $stamp | Out-Null
    Invoke-ContainerDump -Container $KcContainer -User $KcUser -Prefix 'kc' -MinBytes $MinKcBytes  -Stamp $stamp | Out-Null

    # --- 4. Rotation ------------------------------------------------------------
    Invoke-Rotation -Prefix 'pg'
    Invoke-Rotation -Prefix 'kc'

    Write-Log '=== Backup run finished OK ==='
} catch {
    Write-Log ('FAILURE: unhandled error: {0}' -f $_.Exception.Message)
    $exitCode = 1
}

exit $exitCode
