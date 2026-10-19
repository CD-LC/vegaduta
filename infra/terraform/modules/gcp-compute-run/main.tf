locals {
  # core talks to Postgres via the pgbouncer sidecar on localhost, never
  # directly to Cloud SQL's private IP - this is what makes session pooling
  # actually apply per-instance. See this module's README for why transaction
  # mode must never be substituted here.
  effective_env_vars = var.pgbouncer_sidecar ? merge(var.env_vars, {
    DB_HOST = "127.0.0.1"
    DB_PORT = "6432"
  }) : var.env_vars
}

resource "google_cloud_run_v2_service" "this" {
  project  = var.project_id
  name     = var.service_name
  location = var.region

  ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account       = var.service_account_email
    execution_environment = "EXECUTION_ENVIRONMENT_GEN2"

    scaling {
      min_instance_count = var.min_instance_count
      max_instance_count = var.max_instance_count
    }

    vpc_access {
      connector = var.vpc_connector_id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    dynamic "volumes" {
      for_each = var.monitoring_sidecar ? [1] : []
      content {
        name = "alloy-config"
        secret {
          secret = var.alloy_config_secret_id
          items {
            version = "latest"
            path    = "config.alloy"
          }
        }
      }
    }

    containers {
      name  = var.service_name
      image = var.image

      depends_on = var.pgbouncer_sidecar ? ["pgbouncer"] : null

      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
      }

      ports {
        container_port = var.container_port
      }

      dynamic "env" {
        for_each = local.effective_env_vars
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = var.secret_env_vars
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }

      startup_probe {
        http_get {
          path = var.startup_probe_path
        }
        initial_delay_seconds = 10
        period_seconds        = 10
        failure_threshold     = 6
      }
    }

    dynamic "containers" {
      for_each = var.pgbouncer_sidecar ? [1] : []
      content {
        name  = "pgbouncer"
        image = var.pgbouncer_image

        # edoburu/pgbouncer's entrypoint reads DB_HOST/DB_PORT/DB_NAME/
        # DB_USER/DB_PASSWORD - not DATABASE_*. With the wrong names it
        # exits with "You must set DB_HOST env" and nothing listens on 6432.
        env {
          name  = "DB_HOST"
          value = var.db_host
        }
        env {
          name  = "DB_PORT"
          value = "5432"
        }
        env {
          name  = "DB_NAME"
          value = var.db_name
        }
        env {
          name = "DB_USER"
          value_source {
            secret_key_ref {
              secret  = var.db_user_secret_id
              version = "latest"
            }
          }
        }
        env {
          name = "DB_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = var.db_password_secret_id
              version = "latest"
            }
          }
        }
        # Session pooling, not transaction - transaction mode can silently
        # route schema-per-tenant queries to the wrong tenant schema, which
        # makes this non-negotiable regardless of connection-count pressure.
        env {
          name  = "POOL_MODE"
          value = "session"
        }
        env {
          name  = "LISTEN_PORT"
          value = "6432"
        }
        # The entrypoint defaults to md5; Cloud SQL for PostgreSQL 14+ stores
        # scram-sha-256 password hashes.
        env {
          name  = "AUTH_TYPE"
          value = "scram-sha-256"
        }

        resources {
          limits = {
            cpu    = "0.5"
            memory = "256Mi"
          }
        }
      }
    }

    # Grafana Alloy scrapes this service's OWN /actuator/prometheus over
    # localhost (same container network namespace, never leaves the
    # revision) and remote_writes to a Prometheus-compatible endpoint (e.g.
    # Grafana Cloud). The Alloy config is mounted from alloy_config_secret_id.
    dynamic "containers" {
      for_each = var.monitoring_sidecar ? [1] : []
      content {
        name  = "alloy"
        image = var.alloy_image
        args  = ["run", "/etc/alloy/config.alloy", "--server.http.listen-addr=0.0.0.0:12345"]

        env {
          name  = "SCRAPE_ADDRESS"
          value = "127.0.0.1:${var.container_port}"
        }
        env {
          name  = "METRICS_PATH"
          value = var.metrics_path
        }
        env {
          name = "GRAFANA_REMOTE_WRITE_URL"
          value_source {
            secret_key_ref {
              secret  = var.grafana_remote_write_url_secret_id
              version = "latest"
            }
          }
        }
        env {
          name = "GRAFANA_REMOTE_WRITE_USER"
          value_source {
            secret_key_ref {
              secret  = var.grafana_remote_write_user_secret_id
              version = "latest"
            }
          }
        }
        env {
          name = "GRAFANA_REMOTE_WRITE_API_KEY"
          value_source {
            secret_key_ref {
              secret  = var.grafana_remote_write_api_key_secret_id
              version = "latest"
            }
          }
        }

        volume_mounts {
          name       = "alloy-config"
          mount_path = "/etc/alloy"
        }

        resources {
          limits = {
            cpu    = "0.5"
            memory = "256Mi"
          }
        }
      }
    }
  }

  # No `lifecycle { ignore_changes }` on the image here on purpose: if both
  # image deploys and infra-only changes run plain `terraform apply`,
  # Terraform can't tell those callers apart. Source `var.image` for both
  # from one place that is updated after every successful deploy, so an
  # infra-only apply always re-supplies the current live tag rather than a
  # stale placeholder.

  lifecycle {
    precondition {
      condition     = !var.pgbouncer_sidecar || (var.db_host != null && var.db_name != null && var.db_user_secret_id != null && var.db_password_secret_id != null)
      error_message = "pgbouncer_sidecar = true requires db_host, db_name, db_user_secret_id and db_password_secret_id."
    }
    precondition {
      condition     = !var.monitoring_sidecar || (var.alloy_config_secret_id != null && var.grafana_remote_write_url_secret_id != null && var.grafana_remote_write_user_secret_id != null && var.grafana_remote_write_api_key_secret_id != null)
      error_message = "monitoring_sidecar = true requires alloy_config_secret_id and the three grafana_remote_write_*_secret_id inputs."
    }
  }
}

resource "google_cloud_run_v2_service_iam_member" "invokers" {
  for_each = toset(var.invoker_service_accounts)
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.this.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${each.value}"
}
