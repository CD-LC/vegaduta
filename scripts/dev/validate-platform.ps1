# Validates, in order: Admin API accessibility, login flow, token
# generation, role resolution, tenant/organization resolution, and
# tenant-admin access - using only clients/roles/endpoints that already
# exist in deploy/keycloak/agentic-ai-realm.json and application.yml. No new
# config is created; this only reads.
#
# Requires a real tenant-admin user's username + password (any user who
# self-signed-up gets tenant-admin on their own tenant automatically - see
# TenantProvisioningService).
#
# Usage: from the repo root,
#   .\scripts\dev\validate-platform.ps1 -Username "you@example.com" -Password "..."
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [Parameter(Mandatory = $true)][string]$Password
)

$KC = "http://localhost:8180"
$Realm = "agentic-ai"
$CoreBase = "http://localhost:8080"
$ClientId = "agentic-ai-core"

# This script lives in scripts\dev\ now - core\.env.local is two levels up
# at the repo root (the script itself sat at the root before the 2026-08-17
# root-folder cleanup), so re-base off $PSScriptRoot accordingly.
$envFile = Join-Path $PSScriptRoot "..\..\core\.env.local"
$clientSecret = $null
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*KEYCLOAK_CLIENT_SECRET\s*=\s*(.+)$') { $clientSecret = $matches[1].Trim().Trim('"').Trim("'") }
    }
}
if (-not $clientSecret) {
    Write-Host "Could not read KEYCLOAK_CLIENT_SECRET from core\.env.local - cannot obtain a token as agentic-ai-core. Run run-core.ps1 once first (it fails fast if this is blank)." -ForegroundColor Red
    exit 1
}

function Decode-JwtPayload($jwt) {
    $parts = $jwt.Split('.')
    $payload = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) { 2 { $payload += '==' } 3 { $payload += '=' } }
    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
}

$failures = @()

Write-Host "=== 1. Login flow + token generation (password grant against agentic-ai-core, direct-access enabled) ===" -ForegroundColor Cyan
try {
    $tokenResp = Invoke-RestMethod -Method Post -Uri "$KC/realms/$Realm/protocol/openid-connect/token" -Body @{
        client_id     = $ClientId
        client_secret = $clientSecret
        grant_type    = "password"
        username      = $Username
        password      = $Password
    }
    Write-Host "OK - access token obtained" -ForegroundColor Green
} catch {
    Write-Host "FAILED - could not obtain a token for '$Username'. Either the credentials are wrong, or KEYCLOAK_CLIENT_SECRET in core\.env.local does not match Keycloak's actual configured secret for agentic-ai-core (Clients -> agentic-ai-core -> Credentials tab) - a mismatch here fails EVERY grant, not just this one." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== 2. Role resolution (realm_access.roles claim -> what KeycloakRoleConverter turns into ROLE_* authorities) ===" -ForegroundColor Cyan
$claims = Decode-JwtPayload $tokenResp.access_token
$roles = $claims.realm_access.roles
Write-Host ("Roles in token: {0}" -f ($(if ($roles) { $roles -join ", " } else { "(none)" })))
if ($roles -contains "tenant-admin") {
    Write-Host "OK - token carries 'tenant-admin' (maps to ROLE_tenant-admin, required by /api/admin/**)" -ForegroundColor Green
} else {
    Write-Host "FAILED - token does NOT carry 'tenant-admin'. Every /api/admin/** call will get a 403 from SecurityConfig's hasAuthority(\"ROLE_tenant-admin\") check regardless of anything else being correct." -ForegroundColor Red
    $failures += "role resolution"
}

Write-Host ""
Write-Host "=== 3. Tenant/organization resolution (tenant_id claim -> what TenantContextFilter reads) ===" -ForegroundColor Cyan
if ($claims.tenant_id) {
    Write-Host "OK - tenant_id = $($claims.tenant_id)" -ForegroundColor Green
} else {
    Write-Host "FAILED - token has no tenant_id claim. This is the exact cause of the '...not linked to an organization...' 403 from TenantContextFilter. Fix with:" -ForegroundColor Red
    Write-Host "  .\scripts\keycloak\check-tenant-claim.ps1  (repairs this specific user's tenant_id attribute in Keycloak)" -ForegroundColor Yellow
    $failures += "tenant resolution"
}

Write-Host ""
Write-Host "=== 4. Admin API accessibility (core reachable at all) ===" -ForegroundColor Cyan
try {
    $null = Invoke-WebRequest -Uri "$CoreBase/actuator/health" -TimeoutSec 5 -UseBasicParsing
    Write-Host "OK - core is reachable at $CoreBase" -ForegroundColor Green
} catch {
    Write-Host "FAILED - core is not reachable at $CoreBase at all (connection refused/timeout). Start it with .\start-all.ps1 or .\run-core.ps1 first - nothing below this can be tested until core is actually running." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== 5. Tenant-admin access to /api/admin/users (end-to-end: auth + role + tenant all correct) ===" -ForegroundColor Cyan
try {
    $users = Invoke-RestMethod -Method Get -Uri "$CoreBase/api/admin/users" -Headers @{ Authorization = "Bearer $($tokenResp.access_token)" }
    Write-Host "OK - /api/admin/users returned $($users.Count) user(s) for this tenant" -ForegroundColor Green
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    Write-Host "FAILED - /api/admin/users returned HTTP $status" -ForegroundColor Red
    if ($status -eq 403) {
        Write-Host "  If the error body mentions Keycloak/realm-management, the agentic-ai-core service account is still missing its realm-management roles - run: bash deploy/keycloak/reconcile-keycloak.sh (its step 3 grants realm-admin, a superset of the old manage-users/view-users grant)" -ForegroundColor Yellow
    }
    $failures += "admin API access"
}

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "=== ALL CHECKS PASSED ===" -ForegroundColor Green
} else {
    Write-Host ("=== FAILED: {0} ===" -f ($failures -join ", ")) -ForegroundColor Red
    exit 1
}
