variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.name_prefix))
    error_message = "name_prefix must be 2-31 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets in at least two AZs (aws-network.private_subnet_ids)."
  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "subnet_ids needs at least two subnets in different availability zones."
  }
}

variable "allowed_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security groups allowed to connect on 5432 (aws-eks.cluster_security_group_id). The list length must be known at plan time."
}

variable "allowed_cidr_blocks" {
  type        = list(string)
  default     = []
  description = "Extra private CIDRs allowed on 5432 (for example a VPN range). 0.0.0.0/0 is rejected."
  validation {
    condition     = !contains(var.allowed_cidr_blocks, "0.0.0.0/0")
    error_message = "allowed_cidr_blocks must not contain 0.0.0.0/0."
  }
}

variable "engine_version" {
  type        = string
  default     = "16"
  description = "PostgreSQL version. The chart and migrations target 16; \"16\" lets RDS pick the current 16.x minor (auto minor upgrades are on)."
  validation {
    condition     = can(regex("^16(\\.[0-9]+)?$", var.engine_version))
    error_message = "engine_version must be 16 or 16.x; VegaDuta is built and tested against PostgreSQL 16."
  }
}

variable "instance_class" {
  type    = string
  default = "db.m6g.large"
}

variable "allocated_storage_gb" {
  type    = number
  default = 100
}

variable "max_allocated_storage_gb" {
  type        = number
  default     = 500
  description = "Storage autoscaling ceiling. 0 disables autoscaling."
}

variable "storage_type" {
  type    = string
  default = "gp3"
}

variable "multi_az" {
  type        = bool
  default     = true
  description = "Synchronous standby in another AZ. Set false only for dev."
}

variable "database_name" {
  type        = string
  default     = "vegaduta"
  description = "Initial database (global.postgresql.database)."
}

variable "master_username" {
  type        = string
  default     = "vegaduta"
  description = "Master user (global.postgresql.username). Core creates a schema per tenant and runs Flyway, so it needs CREATE on the database, which the master user has."
}

variable "rds_managed_master_password" {
  type        = bool
  default     = false
  description = "false (default): Terraform generates the master password into a Secrets Manager secret and never rotates it; the password is in Terraform state. true: RDS manages the password (not in state) but ROTATES it every 7 days by default. The chart reads the password from an environment variable fixed at pod start, so after each rotation the Kubernetes Secret must be re-synced AND the core pods restarted, or new database connections fail. Use true only with a restart mechanism (for example Reloader watching the Secret)."
}

variable "master_password_kms_key_arn" {
  type        = string
  default     = null
  description = "Optional KMS key ARN encrypting the master password secret in Secrets Manager. null = aws/secretsmanager."
}

variable "final_snapshot_identifier" {
  type        = string
  default     = null
  description = "Name of the final snapshot taken on destroy. null = \"<name_prefix>-postgres-final\". A snapshot with that name must not already exist: after destroying and recreating the instance, delete the old snapshot or set a new identifier before the next destroy."
}

variable "kms_key_arn" {
  type        = string
  default     = null
  description = "Optional customer-managed KMS key for storage, snapshots and Performance Insights. null = the AWS-managed aws/rds key. Storage is always encrypted."
}

variable "iam_database_authentication_enabled" {
  type        = bool
  default     = false
  description = "Allow IAM database authentication (rds-db:connect). This only enables the server side: each DB user must be granted the rds_iam role, and core must connect with a token. Core does NOT mint IAM tokens yet (see ADR-cloud-portability, gap 2), so leave false unless you use it from your own tooling."
}

variable "backup_retention_days" {
  type    = number
  default = 7
  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be 1-35 (automated backups are required for point-in-time recovery)."
  }
}

variable "backup_window" {
  type    = string
  default = "02:00-03:00"
}

variable "maintenance_window" {
  type    = string
  default = "sun:04:00-sun:05:00"
}

variable "deletion_protection" {
  type        = bool
  default     = true
  description = "Refuse deletion of the instance. Turn off (and apply) before terraform destroy."
}

variable "skip_final_snapshot" {
  type        = bool
  default     = false
  description = "false = take a final snapshot on destroy."
}

variable "performance_insights_enabled" {
  type    = bool
  default = true
}

variable "max_connections" {
  type        = string
  default     = null
  description = "Optional max_connections override (static parameter, needs a reboot). null = RDS default for the instance class. Size it for all core pods x Hikari pool size."
}

variable "tags" {
  type    = map(string)
  default = {}
}
