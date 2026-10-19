# cloudflare_ai_gateway only exists in the cloudflare/cloudflare v5 provider
# (see versions.tf). Attribute names were checked against the v5 provider
# schema (`terraform providers schema -json`) and `terraform validate`.
# In v5, cache_ttl, rate_limiting_limit and rate_limiting_interval are
# required, so "disabled" is expressed as 0 rather than null.

resource "cloudflare_ai_gateway" "this" {
  account_id = var.account_id
  id         = var.name

  collect_logs = var.collect_logs

  cache_invalidate_on_update = true
  cache_ttl                  = var.cache_enabled ? var.cache_ttl_seconds : 0

  rate_limiting_limit     = var.rate_limiting_enabled ? var.rate_limiting_limit : 0
  rate_limiting_interval  = var.rate_limiting_enabled ? var.rate_limiting_interval_seconds : 0
  rate_limiting_technique = var.rate_limiting_technique
}
