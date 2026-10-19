# The shared Container Apps Environment — one per deployment, hosts every
# service as a separate azurerm_container_app inside it. Closest Azure
# analog to "the implicit regional environment every Cloud Run service in
# gcp-compute-run deploys into." The intended end state is internal-only
# (internal_only = true): the only public entry point is a Cloudflare Tunnel
# (or equivalent edge proxy) pointed at whichever service(s) need it,
# matching the GCP modules' "tunnel VM is the only path in" posture.
# internal_only = false allows individual apps to opt into direct external
# ingress while no tunnel is in place.

resource "azurerm_log_analytics_workspace" "this" {
  name                = "${var.name_prefix}-logs"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "PerGB2018"
  # 90 days is a common production floor for incident/audit investigation;
  # raise it if compliance needs more.
  retention_in_days = var.log_retention_in_days
}

resource "azurerm_container_app_environment" "this" {
  name                       = "${var.name_prefix}-env"
  resource_group_name        = var.resource_group_name
  location                   = var.location
  log_analytics_workspace_id = azurerm_log_analytics_workspace.this.id

  infrastructure_subnet_id       = var.container_apps_subnet_id
  internal_load_balancer_enabled = var.internal_only

  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }
}
