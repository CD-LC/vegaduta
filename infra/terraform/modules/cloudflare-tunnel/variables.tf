variable "account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.account_id))
    error_message = "account_id must be a 32-character hex Cloudflare account ID."
  }
}

variable "zone_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.zone_id))
    error_message = "zone_id must be a 32-character hex Cloudflare zone ID."
  }
}

variable "name_prefix" {
  type    = string
  default = "vegaduta-ai"
}

variable "ingress_rules" {
  description = "Map of hostname => internal service URL, e.g. { \"api.example.com\" = \"https://core-xyz.a.run.app\" }"
  type        = map(string)
  validation {
    condition     = alltrue([for h, s in var.ingress_rules : can(regex("^([a-z0-9*]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", h)) && can(regex("^(https?|tcp|ssh|rdp|unix|http_status):", s))])
    error_message = "ingress_rules keys must be lowercase hostnames and values service URLs (http://, https://, tcp://, ssh://, unix:, http_status:...)."
  }
}
