variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid GCP project ID (6-30 chars, lowercase letters, digits, hyphens)."
  }
}

variable "secrets" {
  description = <<-EOT
    Map of secret_id => { accessor_service_accounts, manage_version }.
    Non-sensitive on purpose: Terraform cannot for_each over sensitive
    values, so the actual values live in `secret_values`.
    When `manage_version = true` the value from `secret_values` is written
    as the version - rotate by running `terraform apply` again with a new
    value. Set `manage_version = false` for secrets whose real value is
    written out-of-band (e.g. the Cloudflare tunnel run-token, added via
    `gcloud secrets versions add`) - Terraform then only owns the secret
    container + IAM bindings, never overwriting a hand-added version.
  EOT
  type = map(object({
    accessor_service_accounts = list(string)
    manage_version            = optional(bool, true)
  }))
  validation {
    condition     = alltrue([for k in keys(var.secrets) : can(regex("^[A-Za-z0-9_-]{1,255}$", k))])
    error_message = "secret ids must be 1-255 characters of letters, digits, hyphens or underscores."
  }
}

variable "secret_values" {
  description = "Map of secret_id => value, for every secret with manage_version = true. Keys must match var.secrets."
  type        = map(string)
  default     = {}
  sensitive   = true
}
