# ============================================================================
# FULL PLATFORM RESET - agentic-ai-platform
# Wipes ALL tenant data (agents, workflows, knowledge, LLM logs, everything
# in the agentic_ai Postgres database) and ALL Keycloak users in the
# agentic-ai realm. Keeps the Keycloak realm/client/IdP config intact.
# This is IRREVERSIBLE. There is no backup taken by this script.
# ============================================================================

# Run from the repository root (two levels up from this script).
Set-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

Write-Host "=== Step 0: make sure core (Spring Boot) is stopped ===" -ForegroundColor Yellow
Write-Host "If core is running in another terminal, Ctrl+C it now, then press Enter here to continue."
Read-Host

Write-Host "=== Step 1: drop + recreate the agentic_ai Postgres database ===" -ForegroundColor Cyan
docker exec -e PGPASSWORD=changeme agentic-postgres psql -U agentic -d postgres -c "DROP DATABASE IF EXISTS agentic_ai WITH (FORCE);"
docker exec -e PGPASSWORD=changeme agentic-postgres psql -U agentic -d postgres -c "CREATE DATABASE agentic_ai OWNER agentic;"
Write-Host "agentic_ai database recreated empty. Flyway will rebuild control_plane + baseline tables on next core startup."

Write-Host "=== Step 2: delete all Keycloak users in the agentic-ai realm ===" -ForegroundColor Cyan
docker exec agentic-keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password admin

$usersJson = docker exec agentic-keycloak /opt/keycloak/bin/kcadm.sh get users -r agentic-ai --fields id,username
$allUsers = ($usersJson -join "`n") | ConvertFrom-Json

# Service-account users (username starts with "service-account-") back the
# confidential clients' client_credentials grants - agentic-ai-core's is what
# every /api/admin/** call and the signup flow's Keycloak calls authenticate
# as. Deleting it here would silently re-break the Admin API this same
# session just fixed (its manage-users/view-users role grant goes with it),
# and Keycloak does not reliably auto-recreate it on next use. Real users
# only, not service accounts.
$users = $allUsers | Where-Object { $_.username -notlike "service-account-*" }
$skipped = $allUsers | Where-Object { $_.username -like "service-account-*" }

if ($users.Count -eq 0) {
    Write-Host "No real users found - nothing to delete."
} else {
    Write-Host "Deleting $($users.Count) users..."
    foreach ($u in $users) {
        docker exec agentic-keycloak /opt/keycloak/bin/kcadm.sh delete "users/$($u.id)" -r agentic-ai
    }
    Write-Host "Deleted $($users.Count) users. Realm 'agentic-ai' itself (clients, roles, IdP config) is untouched."
}
if ($skipped.Count -gt 0) {
    Write-Host "Kept $($skipped.Count) service-account user(s) (e.g. agentic-ai-core's) - deleting these would break the Admin API's own login." -ForegroundColor DarkGray
}

Write-Host "=== Step 3: flush Redis (clears stale signup rate-limit counters) ===" -ForegroundColor Cyan
docker exec agentic-redis redis-cli FLUSHALL

Write-Host "=== Step 4: restart core ===" -ForegroundColor Cyan
.\run-core.ps1
