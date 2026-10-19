variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid GCP project ID (6-30 chars, lowercase letters, digits, hyphens)."
  }
}

variable "project_number" {
  type = string
  validation {
    condition     = can(regex("^[0-9]+$", var.project_number))
    error_message = "project_number must be the numeric GCP project number."
  }
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "billing_account_id" {
  description = "The billing account this project is linked to - format XXXXXX-XXXXXX-XXXXXX, found via `gcloud billing accounts list`"
  type        = string
  validation {
    condition     = can(regex("^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$", var.billing_account_id))
    error_message = "billing_account_id must look like XXXXXX-XXXXXX-XXXXXX (uppercase hex)."
  }
}

variable "budget_display_name" {
  type    = string
  default = "vegaduta-ai budget"
}

variable "budget_amount_usd" {
  description = "Expected monthly spend - set this to your own cost estimate for the current setup, not an arbitrary round number, so the 50/90/100% alerts are meaningful."
  type        = number
  default     = 120
  validation {
    condition     = var.budget_amount_usd > 0
    error_message = "budget_amount_usd must be positive."
  }
}

variable "soft_kill_threshold_ratio" {
  description = "Fraction of budget_amount_usd at which the automated soft-kill fires (e.g. 1.5 = 150% of expected spend). Deliberately above 100% - this is an emergency brake for a runaway/bug, not a normal-operation control. The 50/90/100% alerts are what you're expected to actually react to."
  type        = number
  default     = 1.5
  validation {
    condition     = var.soft_kill_threshold_ratio > 1
    error_message = "soft_kill_threshold_ratio must be greater than 1 (above 100% of expected spend)."
  }
}

variable "alert_emails" {
  type = list(string)
  validation {
    condition     = length(var.alert_emails) > 0 && alltrue([for e in var.alert_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "alert_emails must contain at least one valid email address."
  }
}

variable "guarded_cloud_run_services" {
  description = "Services the soft-kill sets max-instance-count=0 on. Cloud SQL, Secret Manager, and everything else are never touched - this only stops variable Cloud Run compute spend, never risks data."
  type        = list(string)
  default     = ["core", "sidecar", "code-intel", "keycloak", "web"]
}
