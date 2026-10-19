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
  description = "Security groups allowed to connect on 6379 (aws-eks.cluster_security_group_id). The list length must be known at plan time."
}

variable "engine" {
  type        = string
  default     = "valkey"
  description = "valkey or redis. Both speak the protocol the app uses; Valkey is cheaper on ElastiCache."
  validation {
    condition     = contains(["valkey", "redis"], var.engine)
    error_message = "engine must be valkey or redis."
  }
}

variable "engine_version" {
  type        = string
  default     = "8.0"
  description = "Engine version (8.0 for Valkey, 7.1 for Redis, for example). The parameter group family is derived from the major version."
  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+$", var.engine_version))
    error_message = "engine_version must look like 8.0 or 7.1."
  }
}

variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "num_cache_clusters" {
  type        = number
  default     = 2
  description = "Primary plus replicas (cluster mode disabled; the app uses KEYS in the tenant-purge path, so cluster mode is not supported). 2 or more gives automatic failover across AZs."
  validation {
    condition     = var.num_cache_clusters >= 1 && var.num_cache_clusters <= 6
    error_message = "num_cache_clusters must be between 1 and 6."
  }
}

variable "auth_token" {
  type        = string
  default     = null
  sensitive   = true
  description = "Optional AUTH password (16-128 printable chars, no @ \" / or spaces). null = generate one with the random provider. Either way it is stored in Secrets Manager and, necessarily, in Terraform state: use an encrypted remote backend."
  validation {
    condition     = var.auth_token == null || can(regex("^[A-Za-z0-9!&#$^<>-]{16,128}$", var.auth_token))
    error_message = "auth_token must be 16-128 characters from A-Z a-z 0-9 ! & # $ ^ < > -."
  }
}

variable "kms_key_arn" {
  type        = string
  default     = null
  description = "Optional customer-managed KMS key for at-rest encryption and the auth token secret. null = AWS-owned / aws/secretsmanager keys. At-rest and in-transit encryption are always on."
}

variable "snapshot_retention_days" {
  type    = number
  default = 3
}

variable "maintenance_window" {
  type    = string
  default = "sun:05:00-sun:06:00"
}

variable "tags" {
  type    = map(string)
  default = {}
}
