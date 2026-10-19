resource "cloudflare_record" "apex" {
  zone_id = var.zone_id
  name    = "@"
  content = "${var.tunnel_id}.cfargotunnel.com"
  type    = "CNAME"
  proxied = true
}

resource "cloudflare_record" "www" {
  zone_id = var.zone_id
  name    = "www"
  content = var.zone_name
  type    = "CNAME"
  proxied = true
}

# SPF: with no spf_includes this is "no senders authorized" (v=spf1 -all) - an
# absent SPF record is worse than a strict one for a domain that might
# otherwise get spoofed for phishing. Once core's SMTP_* points at a real mail
# provider, list that provider's SPF include (e.g. "_spf.google.com") so core's
# transactional mail passes SPF instead of passing DMARC on DKIM alone.
resource "cloudflare_record" "spf" {
  zone_id = var.zone_id
  name    = "@"
  type    = "TXT"
  content = length(var.spf_includes) == 0 ? "\"v=spf1 -all\"" : "\"v=spf1 ${join(" ", [for i in var.spf_includes : "include:${i}"])} ~all\""
}

# DMARC: dmarc_policy applies to 100% of failing mail. Start at "quarantine"
# while the aggregate reports still show unaligned senders, then "reject".
resource "cloudflare_record" "dmarc" {
  zone_id = var.zone_id
  name    = "_dmarc"
  type    = "TXT"
  content = "\"v=DMARC1; p=${var.dmarc_policy}; pct=100; rua=mailto:${local.dmarc_rua}\""
}

# TLS-RPT: receivers report failed TLS deliveries to us (DNS-only; MTA-STS,
# which needs an HTTPS policy host, is a follow-up).
resource "cloudflare_record" "tlsrpt" {
  zone_id = var.zone_id
  name    = "_smtp._tls"
  type    = "TXT"
  content = "\"v=TLSRPTv1; rua=mailto:${local.security_email}\""
}

resource "cloudflare_record" "google_site_verification" {
  count   = var.google_site_verification != "" ? 1 : 0
  zone_id = var.zone_id
  name    = "@"
  type    = "TXT"
  content = "\"google-site-verification=${var.google_site_verification}\""
}

# CAA (RFC 8659): only the CAs Cloudflare's Universal/Advanced SSL actually
# uses may issue for this zone and its subdomains (the live edge certs are
# Google Trust Services). Omitting one of these silently breaks edge-cert
# renewal, so when moving a hostname off Cloudflare's proxy onto a cert from
# another CA (e.g. a platform's managed certs from DigiCert), add that CA
# here FIRST. iodef: CAs report refused requests to the security.txt contact.
locals {
  security_email = var.caa_iodef_email != "" ? var.caa_iodef_email : "security@${var.zone_name}"
  dmarc_rua      = var.dmarc_rua != "" ? var.dmarc_rua : "dmarc@${var.zone_name}"

  caa_records = concat(
    flatten([
      for ca in var.caa_issuers : [
        { tag = "issue", value = ca },
        { tag = "issuewild", value = ca },
      ]
    ]),
    [{ tag = "iodef", value = "mailto:${local.security_email}" }],
  )
}

# Written for the pinned cloudflare provider v5 (cloudflare_dns_record, data as
# an attribute). The older cloudflare_record resources above predate v5 and no
# longer validate against it - migrate them separately.
resource "cloudflare_dns_record" "caa" {
  for_each = { for r in local.caa_records : "${r.tag}-${r.value}" => r }
  zone_id  = var.zone_id
  name     = var.zone_name
  type     = "CAA"
  ttl      = 1 # automatic
  data = {
    flags = 0
    tag   = each.value.tag
    value = each.value.value
  }
}
