variable "zone_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.zone_id))
    error_message = "zone_id must be a 32-character hex Cloudflare zone ID."
  }
}

variable "zone_name" {
  type = string
  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.zone_name))
    error_message = "zone_name must be a lowercase domain such as example.com."
  }
}

variable "tunnel_id" {
  description = "Cloudflare Tunnel UUID. The apex domain (example.com) also serves the marketing site through the same tunnel as app./www."
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.tunnel_id))
    error_message = "tunnel_id must be a tunnel UUID."
  }
}

variable "google_site_verification" {
  description = "TXT record value for Google Search Console / GA4 domain verification, once known. Leave empty until the user has it."
  type        = string
  default     = ""
}

variable "caa_issuers" {
  description = "CAs allowed to issue certificates for the zone (CAA issue + issuewild). Must include every CA Cloudflare edge certificates use."
  type        = list(string)
  default     = ["pki.goog", "letsencrypt.org", "ssl.com"]
}

variable "caa_iodef_email" {
  description = "Where CAs send reports of refused issuance requests (CAA iodef) and TLS-RPT reports go. Empty = security@<zone_name>. Keep in step with web/public/.well-known/security.txt."
  type        = string
  default     = ""
}

variable "dmarc_rua" {
  description = "DMARC aggregate-report address (e.g. a Cloudflare DMARC Management inbox). Empty = dmarc@<zone_name>."
  type        = string
  default     = ""
}

variable "dmarc_policy" {
  description = "DMARC policy for failing mail: none, quarantine or reject."
  type        = string
  default     = "reject"
  validation {
    condition     = contains(["none", "quarantine", "reject"], var.dmarc_policy)
    error_message = "dmarc_policy must be none, quarantine or reject."
  }
}

variable "spf_includes" {
  description = "SPF include: domains of the providers allowed to send mail for the zone (e.g. [\"_spf.google.com\"]). Empty = v=spf1 -all (no senders)."
  type        = list(string)
  default     = []
}
