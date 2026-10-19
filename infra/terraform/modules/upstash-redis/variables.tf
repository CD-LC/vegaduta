variable "rest_url" {
  description = "REST URL from an Upstash database created in the Upstash console (see this module's README)."
  type        = string
  validation {
    condition     = can(regex("^https://", var.rest_url))
    error_message = "rest_url must be an https:// URL."
  }
}

variable "rest_token" {
  type      = string
  sensitive = true
  validation {
    condition     = length(var.rest_token) > 0
    error_message = "rest_token must not be empty."
  }
}
