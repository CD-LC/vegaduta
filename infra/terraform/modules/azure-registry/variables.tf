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

variable "sku" {
  type    = string
  default = "Basic" # cheapest; Premium is required for private endpoints, geo-replication, retention and content trust
  validation {
    condition     = contains(["Basic", "Standard", "Premium"], var.sku)
    error_message = "sku must be Basic, Standard or Premium."
  }
}

variable "reader_principal_ids" {
  # Map, not list: a `toset(list(string))` built from
  # `values(module.identity.principal_ids)` fails at plan time — Terraform
  # can't compute for_each instance keys from principal_id values that don't
  # exist until the identities themselves are created. A map keyed by the
  # (statically known) service name keeps the for_each key set knowable at
  # plan time; only the map's VALUES are apply-time results, which for_each
  # over a map allows.
  description = "map of service_name => principal_id (e.g. module.identity.principal_ids) to grant AcrPull."
  type        = map(string)
  default     = {}
}
