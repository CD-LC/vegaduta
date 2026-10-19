# Fixes "We are sorry... Invalid parameter: redirect_uri" on web-admin
# (port 3001) sign-in.
#
# Root cause: Keycloak's `start-dev --import-realm` (deploy/docker-compose.yml)
# only imports deploy/keycloak/agentic-ai-realm.json the FIRST time the
# 'agentic-ai' realm doesn't already exist - it does not re-sync the file on
# every restart. The 'agentic-ai-platform-admin' client (redirect URIs for
# port 3001) was added to that JSON file after this realm was already
# created and imported once, so it was never actually created in the live
# Keycloak database - the checked-in JSON and the running realm have
# silently diverged. web-admin's auth.js requests this exact client id
# (see that file's own comment), so Keycloak rejects the login redirect
# outright: either the client doesn't exist at all, or exists without the
# port-3001 redirect URIs this script defines below.
#
# This script makes ZERO changes beyond this one client - it does not touch
# realm settings, roles, or any other client (agentic-ai-core/agentic-ai-web/
# agentic-ai-mobile/agentic-ai-app are all left exactly as they are). The
# values below are copied verbatim from agentic-ai-realm.json's own
# "agentic-ai-platform-admin" client entry - this script does not invent
# any new configuration, it only makes sure the live Keycloak actually has
# what the checked-in file already says it should.
#
# Usage: from the repo root, `.\scripts\keycloak\ensure-platform-admin-client.ps1`
# (Keycloak must already be running - see start-all.ps1)

$KC = "http://localhost:8180"
$Realm = "agentic-ai"
$Admin = if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { "admin" }
$AdminPassword = if ($env:KEYCLOAK_ADMIN_PASSWORD) { $env:KEYCLOAK_ADMIN_PASSWORD } else { "admin" }
$ClientId = "agentic-ai-platform-admin"

Write-Host "=== 1. Master-realm admin token ===" -ForegroundColor Cyan
try {
    $tokenResp = Invoke-RestMethod -Method Post -Uri "$KC/realms/master/protocol/openid-connect/token" -Body @{
        client_id = "admin-cli"; username = $Admin; password = $AdminPassword; grant_type = "password"
    }
} catch {
    Write-Host "FAILED to get admin token against $KC - is Keycloak running? (see start-all.ps1)" -ForegroundColor Red
    throw
}
$headers = @{ Authorization = "Bearer $($tokenResp.access_token)" }
Write-Host "OK" -ForegroundColor Green

# Exact copy of agentic-ai-realm.json's "agentic-ai-platform-admin" client
# entry - see that file for the source of truth this mirrors.
$desiredClient = @{
    clientId                  = $ClientId
    name                      = "Agentic AI Platform Admin Console"
    protocol                  = "openid-connect"
    publicClient              = $true
    standardFlowEnabled       = $true
    directAccessGrantsEnabled = $false
    redirectUris              = @("http://localhost:3001/*", "http://127.0.0.1:3001/*")
    webOrigins                = @("http://localhost:3001", "http://127.0.0.1:3001")
    attributes                = @{ "pkce.code.challenge.method" = "S256" }
}

Write-Host ""
Write-Host "=== 2. Checking whether '$ClientId' already exists in realm '$Realm' ===" -ForegroundColor Cyan
$clients = Invoke-RestMethod -Method Get -Uri "$KC/admin/realms/$Realm/clients?clientId=$ClientId" -Headers $headers

if ($clients.Count -eq 0) {
    Write-Host "Not found - creating it now." -ForegroundColor Yellow
    $body = $desiredClient | ConvertTo-Json -Depth 5
    Invoke-RestMethod -Method Post -Uri "$KC/admin/realms/$Realm/clients" `
        -Headers ($headers + @{ "Content-Type" = "application/json" }) -Body $body | Out-Null
    Write-Host "Created '$ClientId' with redirect URIs for port 3001." -ForegroundColor Green
} else {
    $existing = $clients[0]
    Write-Host "Found existing client (id: $($existing.id))." -ForegroundColor Green
    Write-Host "Current redirectUris: $($existing.redirectUris -join ', ')"
    Write-Host "Current webOrigins:   $($existing.webOrigins -join ', ')"

    $needsUpdate = (
        (Compare-Object $existing.redirectUris $desiredClient.redirectUris) -or
        (Compare-Object $existing.webOrigins $desiredClient.webOrigins) -or
        (-not $existing.publicClient) -or
        (-not $existing.standardFlowEnabled)
    )

    if ($needsUpdate) {
        Write-Host "Redirect config differs from agentic-ai-realm.json - patching to match." -ForegroundColor Yellow
        $existing.redirectUris = $desiredClient.redirectUris
        $existing.webOrigins = $desiredClient.webOrigins
        $existing.publicClient = $true
        $existing.standardFlowEnabled = $true
        $existing.directAccessGrantsEnabled = $false
        $body = $existing | ConvertTo-Json -Depth 6
        Invoke-RestMethod -Method Put -Uri "$KC/admin/realms/$Realm/clients/$($existing.id)" `
            -Headers ($headers + @{ "Content-Type" = "application/json" }) -Body $body | Out-Null
        Write-Host "Updated." -ForegroundColor Green
    } else {
        Write-Host "Already correct - nothing to change." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Done. Reload web-admin (http://localhost:3001) and try signing in again." -ForegroundColor Green
