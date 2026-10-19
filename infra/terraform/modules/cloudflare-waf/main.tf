resource "cloudflare_zone_settings_override" "this" {
  zone_id = var.zone_id
  settings {
    ssl                      = "strict"
    always_use_https         = "on"
    min_tls_version          = "1.2"
    automatic_https_rewrites = "on"
    browser_check            = "on"
  }
}

# Edge-level rate limiting on the endpoints that are explicitly permitAll()
# in core's SecurityConfig (no JWT to rate-limit by identity there) - a
# second layer in front of, not a replacement for, the existing app-level
# SignupRateLimiter/DemoSessionRateLimiter.
resource "cloudflare_ruleset" "rate_limits" {
  zone_id     = var.zone_id
  name        = "vegaduta-ai edge rate limits"
  description = "Rate limits on signup, demo, and the Keycloak token endpoint"
  kind        = "zone"
  phase       = "http_ratelimit"

  rules {
    action      = "block"
    expression  = "(http.request.uri.path contains \"/api/signup\")"
    description = "Signup burst protection"
    ratelimit {
      characteristics     = ["cf.colo.id", "ip.src"]
      period              = 60
      requests_per_period = 10
      mitigation_timeout  = 600
    }
  }

  rules {
    action      = "block"
    expression  = "(http.request.uri.path contains \"/api/demo\")"
    description = "Demo-session burst protection"
    ratelimit {
      characteristics     = ["cf.colo.id", "ip.src"]
      period              = 60
      requests_per_period = 20
      mitigation_timeout  = 300
    }
  }

  rules {
    action      = "block"
    expression  = "(http.request.uri.path contains \"/realms/\" and http.request.uri.path contains \"/protocol/openid-connect/token\")"
    description = "Keycloak token-endpoint brute-force protection"
    ratelimit {
      characteristics     = ["cf.colo.id", "ip.src"]
      period              = 60
      requests_per_period = 15
      mitigation_timeout  = 300
    }
  }
}

# Blocks public access to /actuator/* at the edge, across every hostname.
# core's SecurityConfig permitAll()s /actuator/health/**, /actuator/info,
# and /actuator/prometheus at the app layer - in the Compose install a
# reverse proxy blocks these externally (see SecurityConfig's own comment).
# A Cloudflare Tunnel setup has no equivalent unless it's added here -
# without this rule,
# api.example.com/actuator/prometheus (Micrometer metrics, including
# per-tenant-shaped data) is reachable by anyone on the internet. GCP's own
# Cloud Run health probes never go through this edge path at all, so
# blocking it publicly costs nothing functionally.
resource "cloudflare_ruleset" "block_actuator" {
  zone_id     = var.zone_id
  name        = "vegaduta-ai block public actuator access"
  description = "Blocks /actuator/* at the edge - internal health checks don't route through here"
  kind        = "zone"
  phase       = "http_request_firewall_custom"

  rules {
    action      = "block"
    expression  = "(http.request.uri.path contains \"/actuator/\")"
    description = "No public path ever needs /actuator/*"
  }
}

# Cloudflare's account-level Managed Ruleset (OWASP-equivalent core rules)
# requires at least a Pro zone plan to execute - left commented since the
# Free plan doesn't carry it. Uncomment once the zone is upgraded:
#
# resource "cloudflare_ruleset" "managed_waf" {
#   zone_id = var.zone_id
#   name    = "vegaduta-ai managed WAF"
#   kind    = "zone"
#   phase   = "http_request_firewall_managed"
#   rules {
#     action = "execute"
#     expression = "true"
#     action_parameters {
#       id = "efb7b8c949ac4650a09736fc376e9aee" # Cloudflare Managed Ruleset
#     }
#   }
# }
