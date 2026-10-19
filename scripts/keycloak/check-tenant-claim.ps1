# Looks up a Keycloak user's correct tenant_id (via the Postgres control_plane
# app_user/tenant join), then patches their Keycloak user attributes if it's
# missing. Run from the agentic-ai-platform root (it reads core\.env.local
# relative to the CURRENT directory, not this script's location):
#   .\scripts\keycloak\check-tenant-claim.ps1
#
# Reads the client secret straight from core\.env.local - never pasted or
# shown anywhere else.

$secretLine = Get-Content "core\.env.local" | Where-Object { $_ -match '^KEYCLOAK_CLIENT_SECRET=' } | Select-Object -Last 1
$clientSecret = ($secretLine -split '=', 2)[1].Trim()

$base = "http://localhost:8180"
$realm = "agentic-ai"

Write-Host "`n--- Step 1: getting an admin token via agentic-ai-core client_credentials ---"
try {
    $token = Invoke-RestMethod -Method Post -Uri "$base/realms/$realm/protocol/openid-connect/token" -Body @{
        grant_type    = "client_credentials"
        client_id     = "agentic-ai-core"
        client_secret = $clientSecret
    } -ContentType "application/x-www-form-urlencoded"
    Write-Host "OK - got admin token"
} catch {
    Write-Host "FAILED: $($_.Exception.Message)"
    Write-Host "If this is a 401, the KEYCLOAK_CLIENT_SECRET in core\.env.local does not match Keycloak's actual secret for agentic-ai-core."
    return
}
$headers = @{ Authorization = "Bearer $($token.access_token)" }

$username = Read-Host "`nEnter the Keycloak username to check/fix"
$user = Invoke-RestMethod -Uri "$base/admin/realms/$realm/users?username=$username&exact=true" -Headers $headers
if ($user.Count -eq 0) {
    Write-Host "No user found with username '$username'"
    return
}
$kcUserId = $user[0].id
Write-Host "`nFound Keycloak user id: $kcUserId"
Write-Host "Current attributes:"
$user[0].attributes | ConvertTo-Json -Depth 5

Write-Host "`n--- Looking up the correct tenant via Postgres (control_plane.app_user/tenant) ---"
$sql = "SELECT t.schema_name, t.name FROM control_plane.app_user u JOIN control_plane.tenant t ON u.tenant_id = t.id WHERE u.keycloak_user_id = '$kcUserId';"
$result = docker exec agentic-postgres psql -U agentic -d agentic_ai -t -A -F"|" -c $sql
if (-not $result) {
    Write-Host "No matching control_plane.app_user row found for this Keycloak user id."
    Write-Host "This user may predate the app_user table, or was created outside the normal signup/admin flow."
    return
}
$parts = $result -split '\|'
$correctSchema = $parts[0].Trim()
$tenantName = $parts[1].Trim()
Write-Host "Found: this user belongs to tenant '$tenantName' (schema: $correctSchema)"

$currentTenantId = $null
if ($user[0].attributes -and $user[0].attributes.tenant_id) {
    $currentTenantId = $user[0].attributes.tenant_id[0]
}

if ($currentTenantId -eq $correctSchema) {
    Write-Host "`nAlready correct - tenant_id attribute already matches '$correctSchema'. Nothing to fix."
    return
}

Write-Host "`ntenant_id attribute is currently '$currentTenantId' but should be '$correctSchema'."
$confirm = Read-Host "Patch this user's tenant_id attribute now? (y/n)"
if ($confirm -ne "y") {
    Write-Host "Skipped - no changes made."
    return
}

$existingAttrs = @{}
if ($user[0].attributes) {
    $user[0].attributes.PSObject.Properties | ForEach-Object { $existingAttrs[$_.Name] = $_.Value }
}
$existingAttrs["tenant_id"] = @($correctSchema)

$patchBody = @{ attributes = $existingAttrs } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Put -Uri "$base/admin/realms/$realm/users/$kcUserId" -Headers $headers -ContentType "application/json" -Body $patchBody
Write-Host "`nDone. '$username' now has tenant_id = '$correctSchema'."
Write-Host "They'll need to log out and back in (or refresh their token) to get a new JWT carrying the corrected claim."
