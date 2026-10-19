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

variable "database_subnet_id" {
  type = string
}

variable "postgres_private_dns_zone_id" {
  type = string
}

variable "sku_name" {
  type        = string
  nullable    = false # null from a caller falls back to this default
  default     = "B_Standard_B1ms" # burstable, 1 vCPU/2GiB — cheapest tier that supports VNet integration
  description = "Postgres Flexible Server compute tier. B1ms is fine for evaluation/light traffic; move to a General Purpose (GP_*) tier before real load or HA."
  validation {
    condition     = can(regex("^(B|GP|MO)_[A-Za-z0-9_]+$", var.sku_name))
    error_message = "sku_name must look like B_Standard_B1ms, GP_Standard_D2s_v3 or MO_Standard_E2s_v3."
  }
}

variable "storage_mb" {
  type     = number
  default  = 32768 # 32 GiB, Flexible Server's minimum
  nullable = false # null from a caller falls back to this default
  # Storage can only grow. Setting this BELOW the live size (e.g. if the
  # server was grown by hand) makes the provider plan a destroy+create.
  # Check `az postgres flexible-server show -g <rg> -n <name> --query storage.storageSizeGb`.
  validation {
    condition     = var.storage_mb >= 32768
    error_message = "storage_mb must be at least 32768 (32 GiB)."
  }
}

variable "admin_username" {
  type    = string
  default = "vegadutaadmin"
  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{0,62}$", var.admin_username)) && !contains(["admin", "administrator", "root", "postgres", "azure_superuser", "azure_pg_admin", "guest", "public"], var.admin_username)
    error_message = "admin_username must be lowercase alphanumeric/underscore, start with a letter, and not be a reserved name (admin, root, postgres, ...)."
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

# ---------------------------------------------------------------------------
# Reliability options. Defaults: no HA, 7-day retention, no geo-redundant
# backup, provider-default storage tier and auto-grow, no restore server.
# Nothing here changes until a caller sets it.
# ---------------------------------------------------------------------------

variable "backup_retention_days" {
  type     = number
  default  = 7
  nullable = false
  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be between 7 and 35."
  }
}

variable "geo_redundant_backup_enabled" {
  description = "Geo-redundant backups. ForceNew: can only be chosen at server creation; changing it later destroys and recreates the server. Guarded by a lifecycle precondition: also needs allow_server_replacement."
  type        = bool
  default     = false
  nullable    = false
}

variable "allow_server_replacement" {
  description = "Acknowledge that a change which forces the Flexible Server to be destroyed and recreated is intended (new-server-with-geo-backup migration). Never set this on the live environment without a restore plan."
  type        = bool
  default     = false
  nullable    = false
}

variable "high_availability_mode" {
  description = "Disabled (default), SameZone or ZoneRedundant. Requires a non-Burstable sku_name."
  type        = string
  default     = "Disabled"
  nullable    = false
  validation {
    condition     = contains(["Disabled", "SameZone", "ZoneRedundant"], var.high_availability_mode)
    error_message = "high_availability_mode must be Disabled, SameZone or ZoneRedundant."
  }
}

variable "high_availability_standby_zone" {
  description = "Optional standby zone for HA. Leave null: Azure picks one, and a failover swaps the zones anyway."
  type        = string
  default     = null
}

variable "storage_tier" {
  description = "IOPS tier (P4 ... P80). Null = leave Azure's computed tier untouched."
  type        = string
  default     = null
}

variable "auto_grow_enabled" {
  description = "Let Azure grow storage automatically as it fills (storage never shrinks). Default false."
  type        = bool
  default     = false
  nullable    = false
}

variable "pitr_restore_enabled" {
  description = "Create a throwaway server by point-in-time restore of this one (drill / recovery rehearsal). Default false = no extra server."
  type        = bool
  default     = false
  nullable    = false
}

variable "pitr_restore_time_utc" {
  description = "RFC 3339 UTC time to restore to, e.g. 2026-10-04T03:30:00Z. Must lie inside the retention window."
  type        = string
  default     = null
}

variable "pitr_restore_name_suffix" {
  description = "Restore server name is <name_prefix>-pg-<suffix>."
  type        = string
  default     = "drill"
  nullable    = false
}
