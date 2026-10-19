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

variable "vnet_id" {
  type = string
}

variable "private_endpoints_subnet_id" {
  type = string
}

variable "sku_name" {
  type        = string
  default     = "Balanced_B0"
  description = "Azure Managed Redis SKU, e.g. Balanced_B0 (smallest), Balanced_B1, MemoryOptimized_M10."
  validation {
    condition     = can(regex("^(Balanced|ComputeOptimized|MemoryOptimized|FlashOptimized)_[A-Z][0-9]+$", var.sku_name))
    error_message = "sku_name must look like Balanced_B0, ComputeOptimized_X3, MemoryOptimized_M10 or FlashOptimized_A250."
  }
}

variable "client_protocol" {
  type        = string
  default     = "Encrypted"
  description = "Encrypted (TLS, default) or Plaintext. Use Plaintext only for clients that cannot speak TLS; the cache is reachable only through the private endpoint either way."
  validation {
    condition     = contains(["Encrypted", "Plaintext"], var.client_protocol)
    error_message = "client_protocol must be Encrypted or Plaintext."
  }
}
