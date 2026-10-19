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

variable "admin_hostname" {
  type        = string
  description = "Public hostname of the platform-admin console, e.g. admin.example.com."
  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.admin_hostname))
    error_message = "admin_hostname must be a lowercase DNS hostname such as admin.example.com."
  }
}

variable "allowed_emails" {
  description = "Email addresses allowed through the Access gate before they even reach the Keycloak login page - the actual platform-admin allowlist, kept small on purpose."
  type        = list(string)
  validation {
    condition     = length(var.allowed_emails) > 0 && alltrue([for e in var.allowed_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "allowed_emails must contain at least one valid email address."
  }
}
