output "tunnel_id" {
  value = cloudflare_zero_trust_tunnel_cloudflared.this.id
}

# Connector run token (what `cloudflared service install <token>` takes).
# Store it straight into your secret manager (e.g. gcp-secrets with
# manage_version = false, then `gcloud secrets versions add`), never in a
# committed file.
output "tunnel_token" {
  value     = cloudflare_zero_trust_tunnel_cloudflared.this.tunnel_token
  sensitive = true
}

output "tunnel_cname" {
  value = cloudflare_zero_trust_tunnel_cloudflared.this.cname
}
