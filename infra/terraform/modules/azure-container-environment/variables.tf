variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.name_prefix))
    error_message = "name_prefix must be 2-21 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "container_apps_subnet_id" {
  type = string
}

variable "internal_only" {
  type        = bool
  default     = false
  description = <<-EOT
    This is an ENVIRONMENT-WIDE ceiling, not per-service: false allows individual
    Container Apps to opt into external ingress (each still defaults closed via
    its own `ingress_external` variable in azure-container-app — see that
    module), true makes it impossible for ANY app in this environment to be
    internet-reachable regardless of its own ingress setting. Use false only
    while some app (e.g. `web`) must be exposed directly because no Cloudflare
    Tunnel/edge proxy fronts the environment yet. Once one does, set this to
    true and lock the environment down completely — that's the intended end
    state, matching gcp-compute-run's INGRESS_TRAFFIC_INTERNAL_ONLY posture.
  EOT
}

variable "log_retention_in_days" {
  type        = number
  default     = 90
  description = "Log Analytics retention for the environment's logs."
  validation {
    condition     = var.log_retention_in_days >= 30 && var.log_retention_in_days <= 730
    error_message = "log_retention_in_days must be between 30 and 730."
  }
}
