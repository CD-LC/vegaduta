resource "random_password" "db_user" {
  length  = 32
  special = false # avoid JDBC-URL-unsafe characters in the connection string
}

resource "google_sql_database_instance" "this" {
  project             = var.project_id
  name                = "${var.name_prefix}-pg"
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = true

  settings {
    tier              = var.tier
    availability_type = "ZONAL" # regional (HA) is a later upgrade once real traffic justifies 2x the cost
    disk_autoresize   = true
    disk_size         = 20

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 14
      }
    }

    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "off" # app connects with the dedicated SQL user below, not IAM DB auth
    }

    # Audit-friendly logging defaults (no statement/parameter logging).
    dynamic "database_flags" {
      for_each = {
        log_checkpoints            = "on"
        log_connections            = "on"
        log_disconnections         = "on"
        log_lock_waits             = "on"
        log_temp_files             = "0"
        log_min_duration_statement = "-1"
      }
      content {
        name  = database_flags.key
        value = database_flags.value
      }
    }
  }

  depends_on = [var.sql_peering_connection]
}

resource "google_sql_database" "this" {
  project  = var.project_id
  instance = google_sql_database_instance.this.name
  name     = var.database_name
}

# Least-privilege: this is the app's own user, not the instance's built-in
# postgres superuser - core connects as this user, never as postgres.
resource "google_sql_user" "app" {
  project  = var.project_id
  instance = google_sql_database_instance.this.name
  name     = var.db_user_name
  password = random_password.db_user.result
}
