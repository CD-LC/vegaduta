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

variable "service_name" {
  type = string
  validation {
    condition     = can(regex("^[a-z]([a-z0-9-]{0,47}[a-z0-9])?$", var.service_name))
    error_message = "service_name must be 1-49 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "image" {
  description = "Full image reference including an explicit tag or digest (not :latest), e.g. us-central1-docker.pkg.dev/PROJECT/vegaduta-ai-images/core:v1.2.3"
  type        = string
  validation {
    condition     = can(regex("(@sha256:[0-9a-f]{64}|:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$", var.image)) && !endswith(var.image, ":latest")
    error_message = "image must carry an explicit tag or digest, and must not use :latest."
  }
}

variable "service_account_email" {
  type = string
}

variable "vpc_connector_id" {
  type = string
}

variable "min_instance_count" {
  type    = number
  default = 0
  validation {
    condition     = var.min_instance_count >= 0
    error_message = "min_instance_count must be >= 0."
  }
}

variable "max_instance_count" {
  type    = number
  default = 3
  validation {
    condition     = var.max_instance_count >= 1
    error_message = "max_instance_count must be >= 1."
  }
}

variable "cpu" {
  type    = string
  default = "1"
}

variable "memory" {
  type    = string
  default = "512Mi"
}

variable "container_port" {
  type    = number
  default = 8080
  validation {
    condition     = var.container_port >= 1 && var.container_port <= 65535
    error_message = "container_port must be between 1 and 65535."
  }
}

variable "env_vars" {
  description = "Plain (non-secret) env vars"
  type        = map(string)
  default     = {}
}

variable "secret_env_vars" {
  description = "Map of env var name => Secret Manager secret_id (latest version is mounted)"
  type        = map(string)
  default     = {}
}

variable "startup_probe_path" {
  type    = string
  default = "/actuator/health"
}

# Set true only for the one service (core) that runs PgBouncer as a
# same-revision sidecar in session-pooling mode - see this module's
# README.md for why this must never become transaction mode.
variable "pgbouncer_sidecar" {
  type    = bool
  default = false
}

variable "pgbouncer_image" {
  type    = string
  default = "edoburu/pgbouncer:v1.24.1-p1"
  validation {
    condition     = can(regex("(@sha256:[0-9a-f]{64}|:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$", var.pgbouncer_image)) && !endswith(var.pgbouncer_image, ":latest")
    error_message = "pgbouncer_image must carry an explicit tag or digest, and must not use :latest."
  }
}

variable "cloudsql_instance_connection_name" {
  description = "Required when pgbouncer_sidecar = true"
  type        = string
  default     = null
}

variable "db_host" {
  description = "Cloud SQL private IP - required when pgbouncer_sidecar = true"
  type        = string
  default     = null
}

variable "db_name" {
  type    = string
  default = null
}

variable "db_user_secret_id" {
  type    = string
  default = null
}

variable "db_password_secret_id" {
  type    = string
  default = null
}

variable "invoker_service_accounts" {
  description = "Service accounts granted roles/run.invoker on this service (e.g. the tunnel VM's SA, or another Cloud Run service's SA for internal service-to-service calls)"
  type        = list(string)
  default     = []
}

# Metrics via a Grafana Alloy sidecar scraping this service's own
# /actuator/prometheus over localhost and remote-writing to a
# Prometheus-compatible endpoint (e.g. Grafana Cloud) - same
# same-revision-sidecar pattern as pgbouncer_sidecar above, just for metrics
# instead of DB pooling. See this module's README.md.
variable "monitoring_sidecar" {
  type    = bool
  default = false
}

variable "alloy_image" {
  type    = string
  default = "grafana/alloy:v1.5.1"
  validation {
    condition     = can(regex("(@sha256:[0-9a-f]{64}|:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$", var.alloy_image)) && !endswith(var.alloy_image, ":latest")
    error_message = "alloy_image must carry an explicit tag or digest, and must not use :latest."
  }
}

variable "metrics_path" {
  description = "Path on this service's own container that Alloy scrapes over localhost"
  type        = string
  default     = "/actuator/prometheus"
}

variable "grafana_remote_write_url_secret_id" {
  type    = string
  default = null
}

variable "grafana_remote_write_user_secret_id" {
  type    = string
  default = null
}

variable "grafana_remote_write_api_key_secret_id" {
  type    = string
  default = null
}

variable "alloy_config_secret_id" {
  description = "Secret Manager secret_id holding the rendered Alloy config (mounted at /etc/alloy/config.alloy; not sensitive content, Secret Manager is just the file-delivery mechanism)"
  type        = string
  default     = null
}
