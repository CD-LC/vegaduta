<#
.SYNOPSIS
    Restore drill: proves the latest backups actually restore into a throwaway
    Postgres and contain the data we care about. Run monthly (see
    deploy\BACKUP-RUNBOOK.md).

.DESCRIPTION
    THIS SCRIPT STARTS NOTHING ITSELF. The operator starts a throwaway Postgres
    container manually first, then runs this script, then removes the container.

    ---------------------------------------------------------------------------
    OPERATOR: run these commands YOURSELF before/after this script.
    They are deliberately not executed by this script.

      # 1. Start a throwaway Postgres on an unused port (no volume = truly throwaway).
      #    CRITICAL - use the platform's OWN image (deploy-postgres), NOT stock postgres:16.
      #    agentic_ai contains ~148 vector(768) columns and Apache AGE graph schemas.
      #    Stock postgres has neither extension, so every CREATE TABLE carrying a vector
      #    column fails, psql continues past the errors, and you end up with a restore
      #    that LOOKS successful but silently dropped agent_memory, knowledge_chunks,
      #    tenant_memory and all graph memory. Verified the hard way 2026-07-29.
      docker run -d --name vd-restore-test -e POSTGRES_PASSWORD=restore-drill -p 5499:5432 deploy-postgres
      # 1b. Create the extensions before restoring (the dump's own CREATE EXTENSION lines
      #     may run as a non-superuser role that does not exist yet in a bare container):
      docker exec vd-restore-test psql -U postgres -c "create extension if not exists vector; create extension if not exists age;"

      # 2. Wait ~10s for it to accept connections, then run the drill:
      powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup\verify-restore.ps1

      # 3. Tear down when done (nothing is kept):
      docker rm -f vd-restore-test
    ---------------------------------------------------------------------------

    What the drill does:
      1. Finds the newest pg-*.sql.gz and kc-*.sql.gz in ..\vegaduta-backups
         (falls back to the original manual agentic-pg-*/keycloak-pg-* names).
      2. Gunzips them on the host (pure .NET GZipStream - no external gzip needed).
      3. docker cp's the .sql files into the throwaway container and restores each
         with psql (pg_dumpall output: creates roles + databases + data).
      4. Runs 3 sanity checks and prints PASS/FAIL for each:
           a. count of tenant data schemas in agentic_ai              - expect >= 1
           a-ii. count of pgvector columns in agentic_ai              - expect >= 1
                 (0 = restored into an image lacking the vector ext)
           b. control_plane.demo_session row count in agentic_ai      - expect query OK
           c. Keycloak realm rows in keycloak_db                      - expect >= 1
      5. Exits 0 only if ALL checks pass.

    Notes:
      - pg_dumpall dumps were taken with --clean --if-exists, so re-running the
        drill against the same throwaway container is fine.
      - Restore output is noisy (thousands of CREATE/COPY lines); it is captured
        to files in %TEMP% and only summarized here.
      - PowerShell 5.1 compatible: no '&&', no ternary.
#>

# ============================== PARAMETERS ====================================
$RestoreContainer = 'vd-restore-test'   # must match the docker run command above
$RestoreSuperUser = 'postgres'          # superuser of the THROWAWAY container

$RepoRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$BackupRoot = Join-Path (Split-Path -Parent $RepoRoot) 'vegaduta-backups'
$WorkDir    = Join-Path $env:TEMP 'vd-restore-drill'

# ============================== HELPERS =======================================

$script:FailCount = 0

function Report {
    param([bool]$Ok, [string]$Name, [string]$Detail)
    if ($Ok) {
        Write-Host ('PASS  {0}  ({1})' -f $Name, $Detail) -ForegroundColor Green
    } else {
        Write-Host ('FAIL  {0}  ({1})' -f $Name, $Detail) -ForegroundColor Red
        $script:FailCount = $script:FailCount + 1
    }
}

function Find-LatestBackup {
    param([string[]]$Patterns)
    foreach ($p in $Patterns) {
        $hit = Get-ChildItem -Path $BackupRoot -File -Filter $p -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($null -ne $hit) { return $hit }
    }
    return $null
}

function Expand-Gzip {
    # Gunzip on the host via .NET - byte-faithful, no external tools.
    param([string]$GzPath, [string]$OutPath)
    $in  = [System.IO.File]::OpenRead($GzPath)
    try {
        $gz  = New-Object System.IO.Compression.GZipStream($in, [System.IO.Compression.CompressionMode]::Decompress)
        try {
            $out = [System.IO.File]::Create($OutPath)
            try {
                $gz.CopyTo($out)
            } finally { $out.Close() }
        } finally { $gz.Close() }
    } finally { $in.Close() }
}

function Restore-DumpIntoContainer {
    # Copies a plain-SQL pg_dumpall file into the container and replays it.
    # Returns $true on psql exit 0.
    param([string]$SqlPath, [string]$Label)
    $inContainer = ('/tmp/restore-{0}.sql' -f $Label)
    docker cp $SqlPath ('{0}:{1}' -f $RestoreContainer, $inContainer)
    if ($LASTEXITCODE -ne 0) {
        Write-Host ('docker cp of {0} dump failed' -f $Label) -ForegroundColor Red
        return $false
    }
    $outLog = Join-Path $WorkDir ('restore-{0}.out.txt' -f $Label)
    # ON_ERROR_STOP deliberately NOT set: pg_dumpall --clean emits harmless
    # "role does not exist" style notices on a fresh cluster. We rely on the
    # sanity SELECTs below, not on a zero-warning restore.
    docker exec $RestoreContainer psql -U $RestoreSuperUser -d postgres -f $inContainer > $outLog
    $code = $LASTEXITCODE
    docker exec $RestoreContainer rm -f $inContainer | Out-Null
    if ($code -ne 0) {
        Write-Host ('psql restore of {0} dump exited {1} - see {2}' -f $Label, $code, $outLog) -ForegroundColor Red
        return $false
    }
    Write-Host ('Restored {0} dump (psql exit 0, output in {1})' -f $Label, $outLog)
    return $true
}

function Invoke-ScalarQuery {
    # Runs a single-value SELECT via psql -tA and returns the trimmed string,
    # or $null on error.
    param([string]$Database, [string]$Sql)
    $raw = docker exec $RestoreContainer psql -U $RestoreSuperUser -d $Database -tA -c $Sql
    if ($LASTEXITCODE -ne 0) { return $null }
    if ($null -eq $raw) { return $null }
    return ([string]::Join('', $raw)).Trim()
}

# ============================== MAIN ==========================================

Write-Host '=== VegaDuta restore drill ==='
Write-Host ('Backup dir : {0}' -f $BackupRoot)
Write-Host ('Target     : container {0} (throwaway - started by YOU, see header)' -f $RestoreContainer)
Write-Host ''

if (-not (Test-Path $BackupRoot)) {
    Write-Host ('FAIL  backup directory not found: {0}' -f $BackupRoot) -ForegroundColor Red
    exit 1
}
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# --- 0. Throwaway container reachable? ---------------------------------------
docker exec $RestoreContainer pg_isready -U $RestoreSuperUser | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host ('FAIL  throwaway container "{0}" is not running/ready.' -f $RestoreContainer) -ForegroundColor Red
    Write-Host '      Start it first (see the OPERATOR block at the top of this script).'
    exit 1
}

# --- 1. Locate newest backups -------------------------------------------------
$pgGz = Find-LatestBackup -Patterns @('pg-*.sql.gz', 'agentic-pg-*.sql.gz')
$kcGz = Find-LatestBackup -Patterns @('kc-*.sql.gz', 'keycloak-pg-*.sql.gz')
if ($null -eq $pgGz) {
    Write-Host 'FAIL  no main-cluster backup (pg-*.sql.gz) found.' -ForegroundColor Red
    exit 1
}
if ($null -eq $kcGz) {
    Write-Host 'FAIL  no keycloak backup (kc-*.sql.gz) found.' -ForegroundColor Red
    exit 1
}
Write-Host ('Using main dump    : {0} ({1:N1} MB)' -f $pgGz.Name, ($pgGz.Length / 1MB))
Write-Host ('Using keycloak dump: {0} ({1:N1} MB)' -f $kcGz.Name, ($kcGz.Length / 1MB))

# --- 2. Gunzip on host --------------------------------------------------------
$pgSql = Join-Path $WorkDir 'main.sql'
$kcSql = Join-Path $WorkDir 'keycloak.sql'
try {
    Expand-Gzip -GzPath $pgGz.FullName -OutPath $pgSql
    Expand-Gzip -GzPath $kcGz.FullName -OutPath $kcSql
} catch {
    Write-Host ('FAIL  gunzip failed: {0}' -f $_.Exception.Message) -ForegroundColor Red
    exit 1
}
Write-Host ('Gunzipped OK -> {0}' -f $WorkDir)
Write-Host ''

# --- 3. Restore both dumps ----------------------------------------------------
$okMain = Restore-DumpIntoContainer -SqlPath $pgSql -Label 'main'
$okKc   = Restore-DumpIntoContainer -SqlPath $kcSql -Label 'keycloak'
if (-not $okMain) { exit 1 }
if (-not $okKc)   { exit 1 }
Write-Host ''

# --- 4. Sanity checks ---------------------------------------------------------
Write-Host '--- Sanity checks ---'

# 4a. Tenant schema count (schema-per-tenant).
#     CORRECTED 2026-07-29: this check previously looked for a 'tenant_%' prefix and so
#     could NEVER pass - TenantProvisioningService names each schema after the tenant
#     itself (acmea, bahuth, demo_0002281b, audit_org_..., plus a '<name>_kg' graph
#     schema each), never 'tenant_'. The drill reported FAIL on a perfectly restorable
#     backup for that reason alone. Count real, non-system schemas instead and exclude
#     the graph (_kg) siblings so the number means "tenant data schemas".
$tenantSql = "SELECT count(*) FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' " +
             "AND nspname NOT IN ('information_schema','public','control_plane','ag_catalog') " +
             "AND nspname NOT LIKE '%\_kg';"
$tenantCount = Invoke-ScalarQuery -Database 'agentic_ai' -Sql $tenantSql
$ok = $false
if ($null -ne $tenantCount) {
    $n = 0
    if ([int]::TryParse($tenantCount, [ref]$n)) {
        if ($n -ge 1) { $ok = $true }
    }
}
Report -Ok $ok -Name 'tenant schemas restored' -Detail ('tenant data schema count = {0}' -f $tenantCount)

# 4a-ii. Vector tables must exist. THIS IS THE CHECK THAT CATCHES THE WRONG-IMAGE
#        RESTORE: agentic_ai has ~148 vector(768) columns (agent_memory,
#        knowledge_chunks, tenant_memory). Restoring into stock postgres:16 - which the
#        operator instructions used to tell you to do - fails every one of those CREATE
#        TABLEs because the 'vector' extension does not exist, and psql marches on past
#        the errors, so you get a "successful" restore that silently lost all agent
#        memory, knowledge chunks and graph memory. Verified 2026-07-29.
$vectorSql = "SELECT count(*) FROM information_schema.columns WHERE udt_name = 'vector';"
$vectorCount = Invoke-ScalarQuery -Database 'agentic_ai' -Sql $vectorSql
$ok = $false
if ($null -ne $vectorCount) {
    $n = 0
    if ([int]::TryParse($vectorCount, [ref]$n)) {
        if ($n -ge 1) { $ok = $true }
    }
}
Report -Ok $ok -Name 'pgvector columns restored' -Detail ('vector column count = {0} (0 means the restore target lacks the vector extension - use the deploy-postgres image, NOT stock postgres)' -f $vectorCount)

# 4b. control_plane.demo_session row count (table from migration V126+).
$demoCount = Invoke-ScalarQuery -Database 'agentic_ai' -Sql 'SELECT count(*) FROM control_plane.demo_session;'
$ok = $false
if ($null -ne $demoCount) {
    $n = 0
    if ([int]::TryParse($demoCount, [ref]$n)) { $ok = $true }   # table exists + queryable; 0 rows is legal
}
Report -Ok $ok -Name 'control_plane.demo_session queryable' -Detail ('row count = {0}' -f $demoCount)

# 4c. Keycloak realm rows (expect at least master + agentic-ai).
$realms = Invoke-ScalarQuery -Database 'keycloak_db' -Sql 'SELECT count(*) FROM realm;'
$ok = $false
if ($null -ne $realms) {
    $n = 0
    if ([int]::TryParse($realms, [ref]$n)) {
        if ($n -ge 1) { $ok = $true }
    }
}
$realmNames = Invoke-ScalarQuery -Database 'keycloak_db' -Sql 'SELECT string_agg(name, '','') FROM realm;'
Report -Ok $ok -Name 'keycloak realms restored' -Detail ('count = {0}; names = {1}' -f $realms, $realmNames)

# --- 5. Verdict ---------------------------------------------------------------
Write-Host ''
if ($script:FailCount -eq 0) {
    Write-Host '=== RESTORE DRILL: ALL CHECKS PASSED ===' -ForegroundColor Green
    Write-Host ('Remember to tear down:  docker rm -f {0}' -f $RestoreContainer)
    exit 0
} else {
    Write-Host ('=== RESTORE DRILL: {0} CHECK(S) FAILED - backups may not be restorable, investigate NOW ===' -f $script:FailCount) -ForegroundColor Red
    exit 1
}
