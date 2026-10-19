variable "account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.account_id))
    error_message = "account_id must be a 32-character hex Cloudflare account ID."
  }
}

variable "name" {
  description = "Gateway id/slug - becomes part of the routing URL: gateway.ai.cloudflare.com/v1/{account_id}/{name}/{provider}"
  type        = string
  default     = "vegaduta-ai"
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9_-]{0,63}$", var.name))
    error_message = "name must be 1-64 lowercase letters, digits, hyphens or underscores."
  }
}

variable "collect_logs" {
  description = "Whether Cloudflare logs request/response metadata for every call through this gateway. On by default in Cloudflare's own UI; kept explicit here since it's the thing to flip off first if a privacy/compliance review ever calls for it."
  type        = bool
  default     = true
}

variable "cache_enabled" {
  type    = bool
  default = true
}

variable "cache_ttl_seconds" {
  description = "Default cache TTL for identical requests (e.g. repeated onboarding/demo prompts). Cloudflare's documented max is 1 month (2592000s); keep this far shorter by default since LLM responses for anything user-specific should not be cached long, or at all - see this module's README."
  type        = number
  default     = 300
  validation {
    condition     = var.cache_ttl_seconds >= 0 && var.cache_ttl_seconds <= 2592000
    error_message = "cache_ttl_seconds must be between 0 and 2592000 (30 days)."
  }
}

variable "rate_limiting_enabled" {
  type    = bool
  default = true
}

variable "rate_limiting_limit" {
  description = "Max requests allowed per rate_limiting_interval_seconds, applied uniformly to every request through this gateway (not per API key) - see this module's README for why per-key limits still need to live elsewhere."
  type        = number
  default     = 60
  validation {
    condition     = var.rate_limiting_limit > 0
    error_message = "rate_limiting_limit must be positive (use rate_limiting_enabled = false to disable)."
  }
}

variable "rate_limiting_interval_seconds" {
  type    = number
  default = 60
  validation {
    condition     = var.rate_limiting_interval_seconds > 0
    error_message = "rate_limiting_interval_seconds must be positive."
  }
}

variable "rate_limiting_technique" {
  description = "\"fixed\" or \"sliding\" - sliding is stricter (no burst-then-pause gaming of a fixed window) and is the safer default for a cost-sensitive free-tier pool."
  type        = string
  default     = "sliding"
  validation {
    condition     = contains(["fixed", "sliding"], var.rate_limiting_technique)
    error_message = "rate_limiting_technique must be fixed or sliding."
  }
}
