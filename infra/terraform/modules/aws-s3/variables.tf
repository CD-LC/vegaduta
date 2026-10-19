variable "bucket_name" {
  type        = string
  description = "Globally unique bucket name (global.objectStorage.bucket)."
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be 3-63 characters: lowercase letters, digits, dots and hyphens."
  }
}

variable "allowed_principal_arns" {
  type        = list(string)
  description = "IAM role ARNs that may read/write objects (the aws-irsa-core role). Every other principal is denied the object actions listed in the bucket policy (read, write, tag, delete, list, multipart, restore), including account administrators, who can still change or remove the policy itself. Because the Terraform principal is denied too, terraform destroy with force_destroy = true needs it in this list. The ARN may be built from the role name before the role exists (arn:aws:iam::<account>:role/<name>)."
  validation {
    condition     = length(var.allowed_principal_arns) > 0
    error_message = "allowed_principal_arns must list at least one role, or nothing could use the bucket."
  }
}

variable "kms_key_arn" {
  type        = string
  default     = null
  description = "Customer-managed KMS key for SSE-KMS. null = SSE-S3 (AES256). With a key, the allowed principals also need kms:Decrypt/GenerateDataKey (aws-irsa-core.kms_key_arns)."
}

variable "versioning_enabled" {
  type        = bool
  default     = false
  description = "Keep previous object versions. Protects against accidental overwrite/delete; storage grows, so noncurrent_version_expiration_days applies."
}

variable "noncurrent_version_expiration_days" {
  type        = number
  default     = 30
  description = "Delete noncurrent versions after this many days (only used with versioning_enabled)."
}

variable "abort_incomplete_multipart_days" {
  type    = number
  default = 7
}

variable "expiration_rules" {
  type = map(object({
    prefix = string
    days   = number
  }))
  default     = {}
  description = "Optional expiry per key prefix, for example { tmp = { prefix = \"tmp/\", days = 7 } }. Empty by default: uploaded knowledge files are never deleted automatically. Core does not install its demo-image rule on this bucket (the IRSA role cannot, see the README); if you run the public demo sandbox, add { demo_images = { prefix = \"demo-images/\", days = 5 } } (app.demo.lifetime-days)."
}

variable "force_destroy" {
  type        = bool
  default     = false
  description = "Allow terraform destroy to delete a non-empty bucket. Default false."
}

variable "tags" {
  type    = map(string)
  default = {}
}
