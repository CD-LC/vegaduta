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

variable "vnet_cidr" {
  type    = string
  default = "10.20.0.0/16"
  validation {
    condition     = can(cidrhost(var.vnet_cidr, 0))
    error_message = "vnet_cidr must be a valid CIDR block."
  }
}

variable "container_apps_subnet_cidr" {
  type        = string
  default     = "10.20.0.0/23" # /23 minimum for Container Apps VNet integration (Consumption-only envs)
  description = "Must be inside vnet_cidr. /23 or larger."
  validation {
    condition     = can(cidrhost(var.container_apps_subnet_cidr, 0)) && try(tonumber(split("/", var.container_apps_subnet_cidr)[1]) <= 23, false)
    error_message = "container_apps_subnet_cidr must be a valid CIDR block of size /23 or larger."
  }
}

variable "database_subnet_cidr" {
  type    = string
  default = "10.20.2.0/24"
  validation {
    condition     = can(cidrhost(var.database_subnet_cidr, 0))
    error_message = "database_subnet_cidr must be a valid CIDR block."
  }
}

variable "private_endpoints_subnet_cidr" {
  type    = string
  default = "10.20.3.0/24"
  validation {
    condition     = can(cidrhost(var.private_endpoints_subnet_cidr, 0))
    error_message = "private_endpoints_subnet_cidr must be a valid CIDR block."
  }
}
