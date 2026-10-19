# Azure equivalent of gcp-database. Postgres Flexible Server, VNet-integrated
# (private access only, no public network access) — same posture as Cloud
# SQL's ipv4_enabled=false. IMPORTANT: schema-per-tenant requires
# SESSION-mode connection pooling, never transaction pooling (Hibernate's
# schema-switching needs a stable session-scoped connection — transaction
# pooling can silently route queries to the wrong tenant schema).
# This module does NOT run PgBouncer itself — that's a sidecar container on
# `core`'s Container App (see azure-container-app's pgbouncer_sidecar
# input), same split as the GCP modules.

resource "random_password" "admin" {
  length  = 32
  special = false # JDBC-URL-safe, same reasoning as the GCP module
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                = "${var.name_prefix}-pg"
  resource_group_name = var.resource_group_name
  location            = var.location

  version    = "16"
  sku_name   = var.sku_name # burstable B1ms default — cheapest that isn't the free-tier-ineligible Cloud SQL floor
  storage_mb = var.storage_mb
  # Both null/false by default (provider default for auto-grow is false; a
  # null storage_tier leaves Azure's own computed tier alone).
  storage_tier      = var.storage_tier
  auto_grow_enabled = var.auto_grow_enabled

  administrator_login    = var.admin_username
  administrator_password = random_password.admin.result

  delegated_subnet_id = var.database_subnet_id
  private_dns_zone_id = var.postgres_private_dns_zone_id
  # VNet-delegated Flexible Server requires this explicitly false (Azure
  # rejects the combination with
  # "ConflictingPublicNetworkAccessAndVirtualNetworkConfiguration"); it isn't
  # implied by setting delegated_subnet_id alone.
  public_network_access_enabled = false

  # Azure allows 7-35 days and PITR works anywhere inside the window. Raising
  # it is an in-place change (no restart); it only costs extra backup storage.
  backup_retention_days = var.backup_retention_days
  # geo_redundant_backup_enabled is ForceNew: Azure only accepts it at server
  # CREATE time, and flipping it on an existing server destroys and recreates
  # the server and its data. Decide before the first apply. The lifecycle
  # precondition below refuses to plan a change unless allow_server_replacement
  # is also set, so it cannot be flipped by accident.
  geo_redundant_backup_enabled = var.geo_redundant_backup_enabled

  zone = "1"

  # High availability (ZoneRedundant/SameZone) is not supported on the
  # Burstable tier (Azure: "HANotSupportedForBurstableSkuWithMoreInfo"), even
  # though `az postgres flexible-server list-skus` may list it. HA requires a
  # General Purpose SKU (e.g. GP_Standard_D2s_v3+), a large cost step up.
  # In-place, no destroy; the lifecycle precondition below turns the Burstable
  # rejection into a plan-time error instead of an apply-time one. Disabled =
  # block omitted. standby_availability_zone defaults to null on purpose: a
  # failover swaps primary and standby zones, and `zone` above already needs
  # ignore_changes for the same reason.
  dynamic "high_availability" {
    for_each = var.high_availability_mode == "Disabled" ? [] : [1]
    content {
      mode                      = var.high_availability_mode
      standby_availability_zone = var.high_availability_standby_zone
    }
  }

  lifecycle {
    ignore_changes = [zone] # Azure sometimes reassigns on maintenance; don't fight it every apply

    precondition {
      condition     = var.high_availability_mode == "Disabled" || !startswith(var.sku_name, "B_")
      error_message = "high_availability_mode needs a General Purpose or Memory Optimized sku_name (GP_Standard_D2ds_v5 or larger). Azure rejects HA on Burstable (HANotSupportedForBurstableSkuWithMoreInfo)."
    }

    precondition {
      condition     = !var.geo_redundant_backup_enabled || var.allow_server_replacement
      error_message = "geo_redundant_backup_enabled can only be set when the server is CREATED; flipping it on the live server destroys and recreates it (and every database on it). Set allow_server_replacement = true only for a deliberate new-server-with-geo-backup migration with a restore plan."
    }
  }
}

# Optional SECOND, throwaway server created by point-in-time restore of the
# live one, for a PITR drill or a "Postgres loss" recovery. count = 0 unless pitr_restore_enabled, so a
# plain apply creates nothing. It never touches the source server. Destroy it
# after the drill (terraform destroy -target=module.database.azurerm_postgresql_flexible_server.pitr_restore).
resource "azurerm_postgresql_flexible_server" "pitr_restore" {
  count = var.pitr_restore_enabled ? 1 : 0

  name                = "${var.name_prefix}-pg-${var.pitr_restore_name_suffix}"
  resource_group_name = var.resource_group_name
  location            = var.location

  create_mode                       = "PointInTimeRestore"
  source_server_id                  = azurerm_postgresql_flexible_server.this.id
  point_in_time_restore_time_in_utc = var.pitr_restore_time_utc

  # Restores are at least as large as the source; same sku keeps the RTO
  # measurement honest. administrator_login/password are NOT set (only
  # accepted for create_mode = Default); the restored server keeps the
  # source's admin credentials.
  sku_name   = var.sku_name
  storage_mb = var.storage_mb

  delegated_subnet_id           = var.database_subnet_id
  private_dns_zone_id           = var.postgres_private_dns_zone_id
  public_network_access_enabled = false

  lifecycle {
    ignore_changes = [zone]

    precondition {
      condition     = var.pitr_restore_time_utc != null
      error_message = "pitr_restore_time_utc (RFC 3339, e.g. 2026-10-04T03:30:00Z) must be set when pitr_restore_enabled = true, and must lie inside the source server's backup retention window."
    }
  }
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = var.database_name
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# KNOWN GAP: the GCP module creates a dedicated non-admin app user
# (google_sql_user) so `core` never connects as the Postgres superuser.
# Doing that here needs an actual psql session against the server — and this
# server is deliberately VNet-private with NO public network access, so a
# hosted CI runner cannot reach it to run that SQL. (Cloud SQL's
# google_sql_user is a control-plane API call, so the GCP module doesn't hit
# this.)
#
# As shipped, callers therefore connect `core` with the admin credentials
# (traffic still never leaves the VNet, and the password is only exposed via
# a sensitive output / Key Vault). Before any real tenant data lands here,
# run psql from inside the VNet (e.g. an azurerm_container_app_job invoked
# once post-apply) to create `${var.database_name}_app` with least-privilege
# grants and switch the app to it.
