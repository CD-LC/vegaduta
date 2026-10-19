# Deletes a Keycloak user by username. Use for orphaned test accounts that
# predate control_plane.app_user tracking (no tenant_id can be derived for
# them). Run from the agentic-ai-platform root (it reads core\.env.local
# relative to the CURRENT directory, not this script's location):
#   .\scripts\keycloak\delete-orphaned-user.ps1

$secretLine = Get-Content "core\.env.local" | Where-Object { $_ -match '^KEYCLOAK_CLIENT_SECRET=' } | Select-Object -Last 1
$clientSecret = ($secretLine -split '=', 2)[1].Trim()

$base = "http://localhost:8180"
$realm = "agentic-ai"

$token = Invoke-RestMethod -Method Post -Uri "$base/realms/$realm/protocol/openid-connect/token" -Body @{
    grant_type    = "client_credentials"
    client_id     = "agentic-ai-core"
    client_secret = $clientSecret
} -ContentType "application/x-www-form-urlencoded"
$headers = @{ Authorization = "Bearer $($token.access_token)" }

$username = Read-Host "Enter the Keycloak username to delete"
$user = Invoke-RestMethod -Uri "$base/admin/realms/$realm/users?username=$username&exact=true" -Headers $headers
if ($user.Count -eq 0) {
    Write-Host "No user found with username '$username' - nothing to delete."
    return
}
$kcUserId = $user[0].id
Write-Host "Found user id $kcUserId for username '$username' (email: $($user[0].email))"
$confirm = Read-Host "Really delete this Keycloak user? This cannot be undone. (y/n)"
if ($confirm -ne "y") {
    Write-Host "Cancelled - no changes made."
    return
}
Invoke-RestMethod -Method Delete -Uri "$base/admin/realms/$realm/users/$kcUserId" -Headers $headers
Write-Host "Deleted. You can now sign up fresh through the web console's normal signup flow with a new email/org."
