<#
.SYNOPSIS
    Registers the nightly VegaDuta Postgres backup as a Windows Scheduled Task.

.DESCRIPTION
    Creates (or replaces) a Scheduled Task that runs scripts\backup\pg-backup.ps1
    every night at 02:30:
      - runs whether the user is logged on or not (S4U logon - no password stored)
      - wakes the computer to run if it is asleep
      - if the 02:30 slot is missed (machine off), runs as soon as possible after boot
      - 2-hour execution time limit

    RUN THIS ONCE, MANUALLY, FROM AN ELEVATED (Administrator) POWERSHELL:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup\register-backup-task.ps1

    Prerequisites / caveats:
      - Docker Desktop must be running at 02:30 for the backup to work. Enable
        "Start Docker Desktop when you sign in" AND keep the machine signed in,
        or the engine will not be up. A failed run writes a FAILURE line to
        ..\vegaduta-backups\backup-log.txt and exits nonzero (visible as
        "Last Run Result" 0x1 in Task Scheduler).
      - S4U tasks run without network credentials; everything this backup touches
        is local (Docker named pipe + local disk), so that is fine.

    PowerShell 5.1 compatible: no '&&', no ternary.
#>

$ErrorActionPreference = 'Stop'

$TaskName    = 'VegaDuta-PG-Backup'
$BackupScript = Join-Path $PSScriptRoot 'pg-backup.ps1'

if (-not (Test-Path $BackupScript)) {
    Write-Error ('Backup script not found at {0} - run this from the repo checkout.' -f $BackupScript)
    exit 1
}

# Require elevation: Register-ScheduledTask with S4U principal needs admin.
$identity  = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
$isAdmin   = $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error 'This script must be run from an elevated (Administrator) PowerShell.'
    exit 1
}

$action = New-ScheduledTaskAction `
    -Execute 'powershell.exe' `
    -Argument ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $BackupScript) `
    -WorkingDirectory $PSScriptRoot

$trigger = New-ScheduledTaskTrigger -Daily -At '02:30'

$settings = New-ScheduledTaskSettingsSet `
    -WakeToRun `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) `
    -MultipleInstances IgnoreNew

# S4U = "run whether user is logged on or not" WITHOUT storing a password.
$taskPrincipal = New-ScheduledTaskPrincipal `
    -UserId $identity.Name `
    -LogonType S4U `
    -RunLevel Highest

# Replace any existing registration so re-running this script is idempotent.
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    Write-Host ('Task {0} already exists - replacing it.' -f $TaskName)
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $taskPrincipal `
    -Description 'Nightly pg_dumpall of agentic-postgres + agentic-keycloak-db to ..\vegaduta-backups (see deploy\BACKUP-RUNBOOK.md)' | Out-Null

Write-Host ('Registered scheduled task "{0}" - nightly at 02:30.' -f $TaskName)
Write-Host 'Verify with:  Get-ScheduledTask -TaskName VegaDuta-PG-Backup | Get-ScheduledTaskInfo'
Write-Host 'Test-fire now (optional):  Start-ScheduledTask -TaskName VegaDuta-PG-Backup'
