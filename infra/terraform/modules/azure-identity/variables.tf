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

variable "service_names" {
  type        = list(string)
  default     = ["core", "web", "keycloak"]
  description = "One user-assigned managed identity is created per entry."
  validation {
    condition     = alltrue([for s in var.service_names : can(regex("^[a-z][a-z0-9-]*$", s))])
    error_message = "service_names entries must be lowercase letters, digits and hyphens."
  }
}
