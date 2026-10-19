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

variable "sql_peering_connection" {
  description = "google_service_networking_connection.network output from gcp-network - forces this module to wait for peering before creating the instance"
  type        = string
}

variable "tier" {
  description = "Cloud SQL machine tier. db-g1-small is the smallest usable production tier (no Always Free tier exists for Cloud SQL)."
  type        = string
  default     = "db-g1-small"
  validation {
    condition     = can(regex("^db-", var.tier))
    error_message = "tier must be a Cloud SQL tier such as db-g1-small or db-custom-2-7680."
  }
}

variable "database_name" {
  type    = string
  default = "vegaduta_ai"
  validation {
    condition     = can(regex("^[a-z_][a-z0-9_]{0,62}$", var.database_name))
    error_message = "database_name must be a lowercase Postgres identifier (letters, digits, underscore)."
  }
}

variable "db_user_name" {
  type    = string
  default = "vegaduta_ai_app"
  validation {
    condition     = can(regex("^[a-z_][a-z0-9_]{0,62}$", var.db_user_name)) && var.db_user_name != "postgres"
    error_message = "db_user_name must be a lowercase Postgres identifier and not the postgres superuser."
  }
}
