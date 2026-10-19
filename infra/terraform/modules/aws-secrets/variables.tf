variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9-]*$", var.name_prefix))
    error_message = "name_prefix must be letters, digits and hyphens, starting with a letter."
  }
}

variable "secrets" {
  # Keys only, deliberately separate from the values: Terraform cannot
  # for_each over a map containing sensitive values (same split as
  # azure-secrets / gcp-secrets).
  type        = set(string)
  description = "Secret ids to create, for example [\"app-encryption-key\", \"keycloak-admin-password\"]. Stored as <name_prefix>/<id>."
}

variable "secret_values" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = "id => value. Keys must match var.secrets exactly. Values end up in Terraform state: use an encrypted remote backend. Prefer generating high-entropy values outside Terraform and leaving them out here (see create_values)."
}

variable "create_values" {
  type        = bool
  default     = true
  description = "false creates only the empty secret containers; you then put the values with the AWS CLI or console and no secret ever touches Terraform state."
}

variable "kms_key_arn" {
  type        = string
  default     = null
  description = "Optional customer-managed KMS key. null = aws/secretsmanager."
}

variable "recovery_window_days" {
  type        = number
  default     = 30
  description = "Days a deleted secret stays recoverable (7-30). 0 deletes immediately and is rejected here."
  validation {
    condition     = var.recovery_window_days >= 7 && var.recovery_window_days <= 30
    error_message = "recovery_window_days must be between 7 and 30."
  }
}

variable "reader_role_arns" {
  type        = list(string)
  default     = []
  description = "Roles (for example the External Secrets Operator IRSA role) allowed to read these secrets. The module outputs a ready-made IAM policy document; it does not attach it."
}

variable "tags" {
  type    = map(string)
  default = {}
}
