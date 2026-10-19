variable "resource_group_name" {
  type = string
}

variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*$", var.name_prefix))
    error_message = "name_prefix must be lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "service_name" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*[a-z0-9]$", var.service_name))
    error_message = "service_name must be lowercase letters, digits and hyphens (Container App naming rules)."
  }
}

variable "container_app_environment_id" {
  type = string
}

variable "identity_id" {
  description = "Resource ID of this service's user-assigned managed identity (from azure-identity)."
  type        = string
}

variable "registry_login_server" {
  type = string
}

variable "image" {
  type        = string
  description = "Full image reference with an explicit, immutable tag or digest (not :latest)."
  validation {
    condition     = can(regex("(@sha256:[0-9a-f]{64}|:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$", var.image)) && !endswith(var.image, ":latest")
    error_message = "image must carry an explicit tag or digest, and must not use :latest."
  }
}

variable "container_command" {
  description = <<-EOT
    REPLACES the image's own ENTRYPOINT (Kubernetes/Container Apps `command`
    semantics — see container_args for why this distinction matters).
    Only needed when the image's built-in
    entrypoint has to be swapped out entirely, e.g. minio's default
    docker-entrypoint.sh needing to become a plain `sh -c '<bootstrap
    script>'` the way docker-compose's own `entrypoint:` override does for
    langfuse-minio. Leave null to keep the image's own ENTRYPOINT.
  EOT
  type        = list(string)
  default     = null
}

variable "container_args" {
  # Container Apps' `command` field REPLACES the image's ENTRYPOINT
  # (Kubernetes semantics); it does not append args the way Docker
  # Compose's `command:` does. Passing ["start-dev", "--import-realm"] as
  # `command` makes the runtime exec a non-existent binary "start-dev" — a
  # silent, log-less CrashLoopBackOff. `args` is passed to the image's own
  # ENTRYPOINT, matching Docker Compose's `command:` semantics.
  description = "Arguments appended to the image's own entrypoint, e.g. [\"start-dev\", \"--import-realm\"]. Leave null to use the image's own default CMD."
  type        = list(string)
  default     = null
}

variable "cpu" {
  type    = number
  default = 0.5
  validation {
    condition     = var.cpu > 0
    error_message = "cpu must be positive."
  }
}

variable "memory" {
  type    = string
  default = "1Gi"
  validation {
    condition     = can(regex("^[0-9]+(\\.[0-9]+)?Gi$", var.memory))
    error_message = "memory must be expressed in Gi, e.g. \"1Gi\" or \"0.5Gi\"."
  }
}

variable "container_port" {
  type    = number
  default = 8080
  validation {
    condition     = var.container_port >= 1 && var.container_port <= 65535
    error_message = "container_port must be between 1 and 65535."
  }
}

variable "health_check_path" {
  type    = string
  default = "/actuator/health/readiness"
}

variable "health_check_port" {
  # Example: Keycloak 25+ serves /health/* on its separate MANAGEMENT
  # interface (port 9000 by default) once KC_HEALTH_ENABLED=true, not on the
  # main HTTP port. Probing the main port fails every check and Container
  # Apps keeps restarting an otherwise healthy Keycloak. Probes run over the
  # container's internal network, not through ingress, so pointing them at a
  # different port than `container_port` is safe.
  description = "Port the liveness/readiness probes hit. Defaults to container_port; override when health endpoints live on a different port (e.g. Keycloak's management interface, 9000)."
  type        = number
  default     = null
}

variable "health_check_initial_delay_seconds" {
  type    = number
  default = 10
}

variable "health_probes_enabled" {
  description = "false for apps with no HTTP server at all (e.g. a pure background worker) — the module's default liveness/readiness HTTP probes would just crash-loop such an app forever."
  type        = bool
  default     = true
}

variable "health_check_transport" {
  description = "HTTP (default) or TCP — TCP for apps with no HTTP endpoint at all (e.g. Redis) where a plain \"can we connect\" check is the only option."
  type        = string
  default     = "HTTP"
  validation {
    condition     = contains(["HTTP", "TCP"], var.health_check_transport)
    error_message = "health_check_transport must be HTTP or TCP."
  }
}

variable "min_replicas" {
  type    = number
  default = 0
  validation {
    condition     = var.min_replicas >= 0
    error_message = "min_replicas must be >= 0."
  }
}

variable "max_replicas" {
  type    = number
  default = 3
  validation {
    condition     = var.max_replicas >= 1
    error_message = "max_replicas must be >= 1."
  }
}

variable "env_vars" {
  description = "Plain (non-secret) environment variables."
  type        = map(string)
  default     = {}
}

variable "secret_env_vars" {
  description = <<-EOT
    map of env-var-name => Key Vault secret versionless resource ID.
    IMPORTANT: if pgbouncer_sidecar=true, this map MUST include entries whose
    keys match pgbouncer_db_user_secret_name and pgbouncer_db_password_secret_name
    (defaults: "pgbouncer-db-user" / "pgbouncer-db-password") — the sidecar
    references those secret names directly and the apply will fail on an
    undefined secret reference if they're missing.
  EOT
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "ip_security_restrictions" {
  description = <<-EOT
    Optional IP allow-list for external ingress — e.g. to make an
    externally-TCP-ingressed app (internal TCP ingress does not route
    reliably on Container Apps) reachable ONLY from this same environment's
    own outbound IP, an "internal via IP-restricted external" pattern rather
    than genuinely open to the public internet. Empty list (default) means
    no restriction; set it for any app with ingress_external = true.
  EOT
  type = list(object({
    name             = string
    ip_address_range = string
    action           = string
  }))
  default = []
  validation {
    condition = alltrue([
      for r in var.ip_security_restrictions :
      contains(["Allow", "Deny"], r.action) && (can(cidrhost(r.ip_address_range, 0)) || can(regex("^(\\d{1,3}\\.){3}\\d{1,3}$", r.ip_address_range)))
    ])
    error_message = "Each ip_security_restrictions entry needs action Allow or Deny and an IPv4 address or CIDR ip_address_range."
  }
}

variable "ingress_transport" {
  description = "auto/http/http2 for ordinary HTTP(S) apps; \"tcp\" for a raw-TCP protocol with no HTTP framing (e.g. mcp-stdio-runner)."
  type        = string
  default     = "auto"
  validation {
    condition     = contains(["auto", "http", "http2", "tcp"], var.ingress_transport)
    error_message = "ingress_transport must be one of auto, http, http2, tcp."
  }
}

variable "ingress_external" {
  description = "Expose this app on the environment's public ingress. Default false (internal-only); enable only for a service that must be reachable without a tunnel/edge proxy in front, and pair it with ip_security_restrictions where possible. Subject to azure-container-environment's internal_only ceiling."
  type        = bool
  default     = false
}

variable "pgbouncer_sidecar" {
  type    = bool
  default = false
}

variable "pgbouncer_image" {
  type    = string
  default = "edoburu/pgbouncer:v1.24.1-p1" # pinned; same tag as the gcp-compute-run module
  validation {
    condition     = can(regex("(@sha256:[0-9a-f]{64}|:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$", var.pgbouncer_image)) && !endswith(var.pgbouncer_image, ":latest")
    error_message = "pgbouncer_image must carry an explicit tag or digest, and must not use :latest."
  }
}

variable "pgbouncer_db_host" {
  type    = string
  default = ""
}

variable "pgbouncer_db_name" {
  type    = string
  default = ""
}

variable "pgbouncer_db_user_secret_name" {
  type    = string
  default = "pgbouncer-db-user"
}

variable "pgbouncer_db_password_secret_name" {
  type    = string
  default = "pgbouncer-db-password"
}

variable "volume_mounts" {
  description = <<-EOT
    Azure Files-backed persistent volumes for the main container, e.g.
    [{ volume_name = "data", storage_name = azurerm_container_app_environment_storage.foo.name, mount_path = "/data" }].
    storage_name must reference an azurerm_container_app_environment_storage
    already created against this same container_app_environment_id — the
    module doesn't create the share/storage account itself, only the mount.
  EOT
  type = list(object({
    volume_name  = string
    storage_name = string
    mount_path   = string
  }))
  default = []
}

variable "sticky_sessions" {
  # For apps that keep per-user state in the replica (e.g. a JSON-file
  # store). Default false so no other app's behaviour changes.
  #
  # TODO(azurerm): azurerm 4.x has no argument for Container Apps ingress
  # sticky sessions — the ARM field
  # (properties.configuration.ingress.stickySessions.affinity) exists in the
  # provider's SDK models but isn't exposed on azurerm_container_app, and
  # azapi is not used by this module. So this input cannot enforce the
  # setting yet; it only surfaces the exact CLI command via the
  # `sticky_sessions_command` output for whoever runs the apply. Because
  # azurerm PUTs the whole ingress block, an apply that updates this app may
  # drop an out-of-band sticky setting — re-run the command after every
  # apply that touches the app. Replace with a native argument (or an
  # azapi_update_resource) once one is available.
  description = "Desired ingress session affinity (sticky). NOT enforced by azurerm yet — see the sticky_sessions_command output and the TODO above."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Reliability options (probes, scaling, termination grace).
#
# Every variable below defaults to the value that reproduces what this module
# rendered before they existed (null = "leave the provider default", false =
# "block not emitted"). Nothing changes until a caller sets one.
#
# Limits are the azurerm schema limits (verified against the provider source,
# internal/services/containerapps/helpers/container_apps.go):
#   startup_probe:   failure_count_threshold 1-240, initial_delay 0-60, interval 1-240, timeout 1-240
#   liveness_probe:  failure_count_threshold 1-30,  initial_delay 0-60, interval 1-240, timeout 1-240
#   readiness_probe: failure_count_threshold 1-48,  success_count_threshold 1-10
#   termination_grace_period_seconds 0-600
# Consumption plan: a probe timeout must not exceed its period
# (https://learn.microsoft.com/en-us/azure/container-apps/health-probes and
# community troubleshooting write-ups: the platform logs a 1 s timeout otherwise).
# ---------------------------------------------------------------------------

variable "startup_probe_enabled" {
  description = "Emit a startup probe. While it is failing the liveness and readiness probes are not evaluated, so a slow cold boot is never killed by the liveness budget."
  type        = bool
  default     = false
}

variable "startup_probe_path" {
  description = "HTTP path for the startup probe. Null = health_check_path (core: /actuator/health/readiness, which also waits for tenant migration)."
  type        = string
  default     = null
}

variable "startup_probe_initial_delay_seconds" {
  type     = number
  default  = 30
  nullable = false # null from a caller falls back to the default
}

variable "startup_probe_interval_seconds" {
  type     = number
  default  = 10
  nullable = false
}

variable "startup_probe_failure_threshold" {
  description = "Startup budget = initial_delay + interval x failure_threshold. Defaults give 30 + 10 x 24 = 270 s (core cold boot is 70-140 s measured)."
  type        = number
  default     = 24
  nullable    = false
}

variable "startup_probe_timeout_seconds" {
  type     = number
  default  = 5
  nullable = false
}

variable "liveness_probe_path" {
  description = "HTTP path for the liveness probe. Null = health_check_path (today's behaviour: liveness hits the readiness endpoint). Core should use /actuator/health/liveness."
  type        = string
  default     = null
}

variable "liveness_probe_initial_delay_seconds" {
  description = "Null = health_check_initial_delay_seconds (today's behaviour)."
  type        = number
  default     = null
}

variable "liveness_probe_interval_seconds" {
  type     = number
  default  = 10 # today's literal
  nullable = false
}

variable "liveness_probe_failure_threshold" {
  type     = number
  default  = 6 # today's literal
  nullable = false
}

variable "liveness_probe_timeout_seconds" {
  description = "Null = provider default (1 s), which is what is rendered today."
  type        = number
  default     = null
}

variable "readiness_probe_path" {
  description = "Null = health_check_path (today's behaviour)."
  type        = string
  default     = null
}

variable "readiness_probe_interval_seconds" {
  description = "Null = provider default (10 s), which is what is rendered today."
  type        = number
  default     = null
}

variable "readiness_probe_failure_threshold" {
  description = "Null = provider default (3), which is what is rendered today."
  type        = number
  default     = null
}

variable "readiness_probe_success_threshold" {
  description = "Null = provider default (3), which is what is rendered today."
  type        = number
  default     = null
}

variable "readiness_probe_timeout_seconds" {
  description = "Null = provider default (1 s), which is what is rendered today."
  type        = number
  default     = null
}

variable "termination_grace_period_seconds" {
  description = "Seconds between SIGTERM and SIGKILL on a replica (0-600). Null = leave the provider default (the platform then uses its own 30 s). Raise it for core so a rolling revision lets open SSE chat streams and in-flight jobs finish."
  type        = number
  default     = null
}

variable "scale_http_concurrent_requests" {
  description = "Add an HTTP scale rule at this many concurrent requests per replica. Null = no rule (today's behaviour). Chat is SSE: one open stream counts as one concurrent request for its whole duration, so size this for streams, not requests per second."
  type        = number
  default     = null
}

variable "scale_cpu_utilization_percent" {
  description = "Add a CPU utilization scale rule at this percentage. Null = no rule (today's behaviour)."
  type        = number
  default     = null
}
