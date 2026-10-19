variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid GCP project ID (6-30 chars, lowercase letters, digits, hyphens)."
  }
}

variable "project_number" {
  description = "Numeric project number, needed for the Binary Authorization attestor's note IAM binding"
  type        = string
}

variable "region" {
  type = string
}

variable "name_prefix" {
  type    = string
  default = "vegaduta-ai"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}$", var.name_prefix))
    error_message = "name_prefix must be 2-19 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "service_names" {
  description = "One service account is created per entry - the Cloud Run services plus the tunnel VM."
  type        = list(string)
  default     = ["core", "sidecar", "code-intel", "keycloak", "web", "tunnel-vm"]
  validation {
    condition     = contains(var.service_names, "core") && alltrue([for s in var.service_names : can(regex("^[a-z][a-z0-9-]*$", s))])
    error_message = "service_names must include \"core\" (it gets roles/cloudsql.client) and contain only lowercase letters, digits and hyphens."
  }
}
