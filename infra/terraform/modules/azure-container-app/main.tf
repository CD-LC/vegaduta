# Azure equivalent of gcp-compute-run — one parametrized module, invoked
# once per service (e.g. core, web, keycloak), mirroring that
# module's shape: image/cpu/memory/env/secret-env/ingress as inputs, an
# optional PgBouncer sidecar. Azure Container Apps supports multiple
# containers per revision natively, so the sidecar pattern carries over
# directly.
#
# PgBouncer sidecar: POOL_MODE is hardcoded to "session", never exposed as
# a variable — same non-negotiable as the GCP module, same reason
# (schema-per-tenant + Hibernate's schema-switching breaks under
# transaction pooling: queries can be routed to the wrong tenant schema).

resource "azurerm_container_app" "this" {
  name                         = "${var.name_prefix}-${var.service_name}"
  container_app_environment_id = var.container_app_environment_id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identity_id]
  }

  registry {
    server   = var.registry_login_server
    identity = var.identity_id
  }

  # Azure Container Apps' secret names must be lowercase alphanumeric/hyphen
  # only (the API rejects anything else with "secret.0.name must consist of
  # lower case alphanumeric characters, '-'"). The actual env var NAME the
  # app reads (e.g. APP_ENCRYPTION_KEY) has to stay uppercase-with-
  # underscores — only the internal Azure "secret" resource name and its
  # secret_name back-reference (below) need this transform, applied
  # identically in both places so they still match each other.
  dynamic "secret" {
    for_each = var.secret_env_vars
    content {
      name                = lower(replace(secret.key, "_", "-"))
      identity            = var.identity_id
      key_vault_secret_id = secret.value
    }
  }

  template {
    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    # Reliability options. Every input below
    # defaults to null/false, which renders to exactly the arguments this
    # module produced before (the provider's own schema default applies for
    # a null termination_grace_period_seconds), so a plain apply changes no
    # app. See variables.tf for what each one does and the cost notes.
    termination_grace_period_seconds = var.termination_grace_period_seconds

    dynamic "http_scale_rule" {
      for_each = var.scale_http_concurrent_requests == null ? [] : [1]
      content {
        name = "http-concurrency"
        # azurerm models this as a string even though it is a number.
        concurrent_requests = tostring(var.scale_http_concurrent_requests)
      }
    }

    dynamic "custom_scale_rule" {
      for_each = var.scale_cpu_utilization_percent == null ? [] : [1]
      content {
        name             = "cpu-utilization"
        custom_rule_type = "cpu"
        metadata = {
          type  = "Utilization"
          value = tostring(var.scale_cpu_utilization_percent)
        }
      }
    }

    container {
      name    = var.service_name
      image   = var.image
      cpu     = var.cpu
      memory  = var.memory
      command = var.container_command
      args    = var.container_args

      dynamic "env" {
        for_each = var.env_vars
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = var.secret_env_vars
        content {
          name        = env.key                           # real env var name, stays uppercase
          secret_name = lower(replace(env.key, "_", "-")) # must match the `secret` block's (transformed) name above
        }
      }

      # Optional startup probe. While a startup probe is
      # failing, Container Apps does not run the liveness/readiness probes, so
      # a slow cold boot (core: 70-140 s, Flyway + per-tenant migration) is
      # never judged by the (shorter) liveness budget. Budget =
      # initial_delay + interval x failure_threshold. Off by default.
      dynamic "startup_probe" {
        for_each = var.health_probes_enabled && var.startup_probe_enabled ? [1] : []
        content {
          transport               = var.health_check_transport
          path                    = var.health_check_transport == "HTTP" ? coalesce(var.startup_probe_path, var.health_check_path) : null
          port                    = coalesce(var.health_check_port, var.container_port)
          initial_delay           = var.startup_probe_initial_delay_seconds
          interval_seconds        = var.startup_probe_interval_seconds
          failure_count_threshold = var.startup_probe_failure_threshold
          timeout                 = var.startup_probe_timeout_seconds
        }
      }

      dynamic "liveness_probe" {
        for_each = var.health_probes_enabled ? [1] : []
        content {
          transport = var.health_check_transport
          # TCP probes don't take a path at all — Azure rejects the field
          # being set for transport=TCP.
          # liveness_probe_path (default null) lets a
          # service probe /actuator/health/liveness instead of the readiness
          # endpoint. Core's readiness group includes the tenantMigration gate
          # (not the DB or Redis), so today a slow or stuck tenant migration can
          # restart the JVM; liveness no longer depends on it. Null keeps
          # health_check_path.
          path                    = var.health_check_transport == "HTTP" ? coalesce(var.liveness_probe_path, var.health_check_path) : null
          port                    = coalesce(var.health_check_port, var.container_port)
          initial_delay           = coalesce(var.liveness_probe_initial_delay_seconds, var.health_check_initial_delay_seconds)
          interval_seconds        = var.liveness_probe_interval_seconds
          failure_count_threshold = var.liveness_probe_failure_threshold
          timeout                 = var.liveness_probe_timeout_seconds
        }
      }

      dynamic "readiness_probe" {
        for_each = var.health_probes_enabled ? [1] : []
        content {
          transport = var.health_check_transport
          path      = var.health_check_transport == "HTTP" ? coalesce(var.readiness_probe_path, var.health_check_path) : null
          port      = coalesce(var.health_check_port, var.container_port)
          # All null by default = the provider's own
          # schema defaults, i.e. exactly today's rendered probe.
          interval_seconds        = var.readiness_probe_interval_seconds
          failure_count_threshold = var.readiness_probe_failure_threshold
          success_count_threshold = var.readiness_probe_success_threshold
          timeout                 = var.readiness_probe_timeout_seconds
        }
      }

      # Azure Files-backed volume mounts, for services that hold persistent
      # data in a named Docker volume under Compose (MinIO's object data,
      # Grafana/Prometheus/Loki state, per-tenant checkout dirs, etc.) —
      # Container Apps' own ephemeral container filesystem loses everything
      # on every scale-to-zero/restart, so anything that needs to survive a
      # restart needs one of these, backed by an
      # azurerm_container_app_environment_storage resource the caller
      # creates and references by name via volume_mounts.
      dynamic "volume_mounts" {
        for_each = var.volume_mounts
        content {
          name = volume_mounts.value.volume_name
          path = volume_mounts.value.mount_path
        }
      }
    }

    dynamic "volume" {
      for_each = var.volume_mounts
      content {
        name         = volume.value.volume_name
        storage_type = "AzureFile"
        storage_name = volume.value.storage_name
      }
    }

    dynamic "container" {
      for_each = var.pgbouncer_sidecar ? [1] : []
      content {
        name   = "pgbouncer"
        image  = var.pgbouncer_image
        cpu    = 0.25
        memory = "0.5Gi"

        # edoburu/pgbouncer's entrypoint.sh reads DB_HOST/DB_PORT/DB_NAME/
        # DB_USER/DB_PASSWORD specifically, not a generic "DATABASE_*"
        # convention. With the wrong names it exits with "You must set
        # DB_HOST env", nothing listens on 6432, and the app container then
        # fails to start its JPA entityManager.
        env {
          name  = "DB_HOST"
          value = var.pgbouncer_db_host
        }
        env {
          name  = "DB_PORT"
          value = "5432"
        }
        env {
          name  = "DB_NAME"
          value = var.pgbouncer_db_name
        }
        env {
          name        = "DB_USER"
          secret_name = var.pgbouncer_db_user_secret_name
        }
        env {
          name        = "DB_PASSWORD"
          secret_name = var.pgbouncer_db_password_secret_name
        }
        env {
          name  = "POOL_MODE"
          value = "session" # NOT a variable — see module header comment
        }
        env {
          name  = "LISTEN_PORT"
          value = "6432"
        }
        env {
          name  = "MAX_CLIENT_CONN"
          value = "200"
        }
        env {
          name  = "DEFAULT_POOL_SIZE"
          value = "25"
        }
        # edoburu/pgbouncer's entrypoint defaults to `auth_type = md5`
        # unless AUTH_TYPE overrides it. Azure Database for PostgreSQL
        # Flexible Server uses scram-sha-256 password encryption (the
        # Postgres 16 default), so md5 fails with "cannot do SCRAM
        # authentication: wrong password type".
        env {
          name  = "AUTH_TYPE"
          value = "scram-sha-256"
        }
      }
    }
  }

  ingress {
    external_enabled = var.ingress_external
    target_port      = var.container_port
    transport        = var.ingress_transport
    # exposed_port is only meaningful (and only accepted by the API) for
    # transport = "tcp" — raw-TCP protocols (e.g. mcp-stdio-runner's custom
    # binary framing protocol) have no HTTP Host-header/SNI to route on, so
    # TCP ingress binds a distinct port instead of the implicit 443 every
    # HTTP-mode app shares.
    exposed_port = var.ingress_transport == "tcp" ? var.container_port : null

    dynamic "ip_security_restriction" {
      for_each = var.ip_security_restrictions
      content {
        name             = ip_security_restriction.value.name
        ip_address_range = ip_security_restriction.value.ip_address_range
        action           = ip_security_restriction.value.action
      }
    }

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }
}
