variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid GCP project ID (6-30 chars, lowercase letters, digits, hyphens)."
  }
}

variable "region" {
  type = string
}

variable "zone" {
  description = "Must be in us-west1, us-central1, or us-east1 to stay inside the Compute Engine Always Free e2-micro quota."
  type        = string
  default     = "us-central1-a"
  validation {
    condition     = can(regex("^[a-z]+-[a-z]+[0-9]+-[a-z]$", var.zone))
    error_message = "zone must be a GCP zone such as us-central1-a."
  }
}

variable "name_prefix" {
  type    = string
  default = "vegaduta-ai"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}$", var.name_prefix))
    error_message = "name_prefix must be 2-19 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "network_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "service_account_email" {
  type = string
}

variable "tunnel_token_secret_id" {
  description = "Secret Manager secret_id holding the Cloudflare Tunnel run token"
  type        = string
}
