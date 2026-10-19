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
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9-]*$", var.name_prefix))
    error_message = "name_prefix must be letters, digits and hyphens, starting with a letter."
  }
}

variable "name_suffix" {
  type        = string
  description = "Random/short suffix for global Key Vault name uniqueness (e.g. from random_id in the root module)."
  validation {
    condition     = can(regex("^[a-zA-Z0-9]+$", var.name_suffix))
    error_message = "name_suffix must be alphanumeric."
  }
}

variable "purge_protection_enabled" {
  type        = bool
  default     = true
  description = "Purge protection (default true). Cannot be disabled again once enabled, and a purge-protected vault name stays reserved for the soft-delete retention period after destroy. Set false only for throwaway test vaults."
}

variable "soft_delete_retention_days" {
  type        = number
  default     = 7
  description = "Soft-delete retention (7-90 days)."
  validation {
    condition     = var.soft_delete_retention_days >= 7 && var.soft_delete_retention_days <= 90
    error_message = "soft_delete_retention_days must be between 7 and 90."
  }
}

variable "public_network_access_enabled" {
  type        = bool
  default     = true
  description = "Keep true when Azure Container Apps resolve Key Vault secret references (they use the public endpoint); access is still restricted by the network ACL (default Deny + allowed_ip_cidrs/allowed_subnet_ids + AzureServices bypass). Set false only with a private endpoint in place."
}

variable "network_default_action" {
  type        = string
  default     = "Deny"
  description = "Key Vault firewall default action. Deny (default) allows only trusted Azure services, allowed_ip_cidrs and allowed_subnet_ids."
  validation {
    condition     = contains(["Allow", "Deny"], var.network_default_action)
    error_message = "network_default_action must be Allow or Deny."
  }
}

variable "allowed_ip_cidrs" {
  type        = list(string)
  default     = []
  description = "Public IPs/CIDRs allowed through the Key Vault firewall — include the egress IP of whatever runs `terraform apply`, or secret writes will be denied."
  validation {
    condition     = alltrue([for c in var.allowed_ip_cidrs : can(cidrhost(c, 0)) || can(regex("^(\\d{1,3}\\.){3}\\d{1,3}$", c))])
    error_message = "allowed_ip_cidrs entries must be IPv4 addresses or CIDR blocks."
  }
}

variable "allowed_subnet_ids" {
  type        = list(string)
  default     = []
  description = "Subnet resource IDs (with the Microsoft.KeyVault service endpoint enabled) allowed through the Key Vault firewall."
}

variable "secret_expiration_date" {
  type        = string
  default     = null
  description = "Optional RFC 3339 expiration date applied to every secret (e.g. 2027-01-01T00:00:00Z). null = no expiry."
  validation {
    condition     = var.secret_expiration_date == null || can(formatdate("YYYY", var.secret_expiration_date))
    error_message = "secret_expiration_date must be an RFC 3339 timestamp."
  }
}

variable "secrets" {
  # Deliberately separate from the values: Terraform refuses to `for_each`
  # over a map that contains a
  # sensitive value ("Sensitive values ... cannot be used as for_each
  # arguments"), and each `value` here IS sensitive by construction (callers
  # pass in sensitive root variables like app_encryption_key). The for_each
  # key set (secret ids + accessor lists) is never secret on its own — only
  # the actual secret string is — so it's split into its own sensitive-only
  # variable (secret_values below) that's referenced solely as a resource
  # attribute VALUE, never as a for_each driver.
  #
  # accessor_principal_ids (list of principal_id VALUES) also had to become
  # accessor_service_names (list of static service-name STRINGS) for the
  # same for_each reason one level down: a principal_id doesn't exist until
  # its azurerm_user_assigned_identity is created, so a for_each key built
  # from one is unknown at plan time. Service names ("core", "keycloak", ...)
  # are static; the actual principal_id is looked up via the
  # identity_principal_ids map, only as a resource attribute VALUE.
  description = "Map of secret_id => {accessor_service_names}. accessor_service_names are keys into identity_principal_ids for the identities that get 'Key Vault Secrets User' on that one secret."
  type = map(object({
    accessor_service_names = list(string)
  }))
}

variable "identity_principal_ids" {
  description = "map of service_name => principal_id (e.g. module.identity.principal_ids) — resolves each secret's accessor_service_names to an actual principal_id."
  type        = map(string)
}

variable "secret_values" {
  description = "Map of secret_id => the actual secret value. Keys MUST match var.secrets' keys exactly — kept as a separate variable (not nested in `secrets`) solely so `secrets` itself stays non-sensitive and usable in for_each."
  type        = map(string)
  sensitive   = true
}
