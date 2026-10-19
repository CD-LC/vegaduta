# Optional second authentication gate in front of admin.example.com, ahead of
# Keycloak's own login+MFA. Free Cloudflare Zero Trust covers up to 50 seated
# users, well above what a platform-admin allowlist needs. Not applied to
# app.example.com (the tenant console) - that would put an extra login step
# in front of every paying customer, which isn't the threat model here.

resource "cloudflare_zero_trust_access_application" "admin_console" {
  account_id       = var.account_id
  zone_id          = var.zone_id
  name             = "vegaduta-ai platform admin console"
  domain           = var.admin_hostname
  type             = "self_hosted"
  session_duration = "8h"
}

resource "cloudflare_zero_trust_access_policy" "admin_allowlist" {
  account_id     = var.account_id
  application_id = cloudflare_zero_trust_access_application.admin_console.id
  name           = "platform-admin allowlist"
  decision       = "allow"
  precedence     = 1

  include {
    email = var.allowed_emails
  }
}
