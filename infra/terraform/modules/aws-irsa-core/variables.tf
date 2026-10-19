variable "role_name" {
  type        = string
  default     = "vegaduta-core"
  description = "IAM role name. Use the same name when you build the role ARN for aws-s3.allowed_principal_arns."
}

variable "oidc_provider_arn" {
  type        = string
  description = "aws-eks.oidc_provider_arn"
}

variable "oidc_provider_url" {
  type        = string
  description = "aws-eks.oidc_provider_url (issuer without https://)."
  validation {
    condition     = !startswith(var.oidc_provider_url, "https://")
    error_message = "oidc_provider_url must not include the https:// prefix."
  }
}

variable "namespace" {
  type        = string
  default     = "vegaduta"
  description = "Kubernetes namespace of the release."
}

variable "service_account_names" {
  type        = list(string)
  default     = ["vegaduta-core", "vegaduta-core-jobs", "vegaduta-core-worker"]
  description = "ServiceAccounts allowed to assume the role. The chart names them <release>-core, <release>-core-jobs and <release>-core-worker; the defaults assume a release called \"vegaduta\". Check with `kubectl get sa -n <namespace>`."
  validation {
    condition     = length(var.service_account_names) > 0 && !contains(var.service_account_names, "*")
    error_message = "List explicit ServiceAccount names; wildcards are not accepted."
  }
}

variable "bucket_name" {
  type        = string
  description = "Name of the application bucket (aws-s3.bucket_name). Taken as a name, not an ARN, so aws-s3 and this module do not depend on each other."
}

variable "enable_rds_iam_auth" {
  type        = bool
  default     = false
  description = "Grant rds-db:connect for db_username on db_resource_id. Requires aws-rds-postgres.iam_database_authentication_enabled = true and a DB role granted rds_iam. Core does not mint IAM auth tokens yet; this only prepares the permission."
}

variable "db_resource_id" {
  type        = string
  default     = null
  description = "aws-rds-postgres.resource_id (the db-XXXX DbiResourceId). Required with enable_rds_iam_auth."
}

variable "db_username" {
  type        = string
  default     = "vegaduta"
  description = "Database user allowed to connect with an IAM token."
}

variable "kms_key_arns" {
  type        = list(string)
  default     = []
  description = "KMS keys the role may use (Decrypt, GenerateDataKey, DescribeKey): the S3 SSE-KMS key, and the key of any secret read directly. Empty = no KMS permissions."
}

variable "tags" {
  type    = map(string)
  default = {}
}
