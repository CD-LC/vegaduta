# Written against the cloudflare/cloudflare v4 provider (see versions.tf).
# The provider has renamed this resource more than once (cloudflare_tunnel ->
# cloudflare_argo_tunnel -> cloudflare_zero_trust_tunnel_cloudflared) and v5
# changes the schema again (tunnel_secret, config as an attribute), so do not
# bump the major version without porting this module.

# v4 requires the tunnel secret up front (base64, >= 32 bytes). It lives only
# in Terraform state — keep state in an encrypted, access-controlled backend.
resource "random_id" "tunnel_secret" {
  byte_length = 32
}

resource "cloudflare_zero_trust_tunnel_cloudflared" "this" {
  account_id = var.account_id
  name       = "${var.name_prefix}-tunnel"
  secret     = random_id.tunnel_secret.b64_std
  config_src = "cloudflare"
}

resource "cloudflare_zero_trust_tunnel_cloudflared_config" "this" {
  account_id = var.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.this.id

  config {
    dynamic "ingress_rule" {
      for_each = var.ingress_rules
      content {
        hostname = ingress_rule.key
        service  = ingress_rule.value
      }
    }

    # Cloudflare Tunnel config requires a catch-all final rule.
    ingress_rule {
      service = "http_status:404"
    }
  }
}

resource "cloudflare_record" "hostnames" {
  for_each = var.ingress_rules
  zone_id  = var.zone_id
  name     = trimsuffix(each.key, ".${data.cloudflare_zone.this.name}")
  content  = "${cloudflare_zero_trust_tunnel_cloudflared.this.id}.cfargotunnel.com"
  type     = "CNAME"
  proxied  = true
}

data "cloudflare_zone" "this" {
  zone_id = var.zone_id
}
