# Read-only diagnostic - makes ZERO changes to Keycloak. Prints everything
# relevant to the "sign-in redirects to trycloudflare.com" bug in one shot:
# the container's actual live environment variables (not just what's in the
# compose file - a container only picks those up on recreate), the realm's
# Frontend URL, every client's rootUrl/baseUrl/adminUrl/redirectUris/
# webOrigins, any configured Identity Providers, and the live discovery
# document Keycloak is currently serving.
#
# Usage: `.\scripts\dev\diagnose-keycloak.ps1` (works from anywhere - it
# only talks to localhost:8180 and the docker CLI, no repo-relative paths)

$KC = "http://localhost:8180"
$Realm = "agentic-ai"
$Admin = "admin"
$AdminPassword = "admin"
$ContainerName = "agentic-keycloak"

Write-Host "=== 1. Live container environment (what's ACTUALLY running, not just the compose file) ===" -ForegroundColor Cyan
docker exec $ContainerName env | Select-String "KC_HOSTNAME|KC_PROXY|KC_HTTP"

Write-Host ""
Write-Host "=== 2. Container image/start time (has it actually been recreated recently?) ===" -ForegroundColor Cyan
docker inspect $ContainerName --format "Image: {{.Image}}`nStarted: {{.State.StartedAt}}`nCreated: {{.Created}}"

Write-Host ""
Write-Host "=== 3. Admin token ===" -ForegroundColor Cyan
try {
    $tokenResp = Invoke-RestMethod -Method Post -Uri "$KC/realms/master/protocol/openid-connect/token" -Body @{
        client_id  = "admin-cli"; username = $Admin; password = $AdminPassword; grant_type = "password"
    }
    Write-Host "Got token OK."
} catch {
    Write-Host "FAILED to get admin token - stopping here." -ForegroundColor Red
    throw
}
$headers = @{ Authorization = "Bearer $($tokenResp.access_token)" }

Write-Host ""
Write-Host "=== 4. Realm '$Realm' attributes (Frontend URL etc.) ===" -ForegroundColor Cyan
$realm = Invoke-RestMethod -Method Get -Uri "$KC/admin/realms/$Realm" -Headers $headers
Write-Host "frontendUrl = '$($realm.attributes.frontendUrl)'"
Write-Host "sslRequired = '$($realm.sslRequired)'"

Write-Host ""
Write-Host "=== 5. Every client's URLs ===" -ForegroundColor Cyan
$clients = Invoke-RestMethod -Method Get -Uri "$KC/admin/realms/$Realm/clients" -Headers $headers
foreach ($c in $clients) {
    Write-Host "--- $($c.clientId) ---"
    Write-Host "  rootUrl:      $($c.rootUrl)"
    Write-Host "  baseUrl:      $($c.baseUrl)"
    Write-Host "  adminUrl:     $($c.adminUrl)"
    Write-Host "  redirectUris: $($c.redirectUris -join ', ')"
    Write-Host "  webOrigins:   $($c.webOrigins -join ', ')"
}

Write-Host ""
Write-Host "=== 6. Identity Providers (social logins etc.) ===" -ForegroundColor Cyan
$idps = Invoke-RestMethod -Method Get -Uri "$KC/admin/realms/$Realm/identity-provider/instances" -Headers $headers
if ($idps.Count -eq 0) {
    Write-Host "  none configured"
} else {
    foreach ($idp in $idps) {
        Write-Host "--- $($idp.alias) ($($idp.providerId)) ---"
        Write-Host "  authorizationUrl: $($idp.config.authorizationUrl)"
        Write-Host "  tokenUrl:         $($idp.config.tokenUrl)"
    }
}

Write-Host ""
Write-Host "=== 7. LIVE discovery document (what a browser actually gets right now) ===" -ForegroundColor Cyan
try {
    $disco = Invoke-RestMethod -Method Get -Uri "$KC/realms/$Realm/.well-known/openid-configuration"
    Write-Host "issuer:                 $($disco.issuer)" -ForegroundColor $(if ($disco.issuer -like "*trycloudflare*") { "Red" } else { "Green" })
    Write-Host "authorization_endpoint: $($disco.authorization_endpoint)" -ForegroundColor $(if ($disco.authorization_endpoint -like "*trycloudflare*") { "Red" } else { "Green" })
} catch {
    Write-Host "FAILED to fetch discovery document: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "=== Done - paste everything above back ===" -ForegroundColor Cyan
