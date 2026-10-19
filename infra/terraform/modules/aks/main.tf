# Reference AKS cluster module (community-supported, not used by the Compose
# or Helm install paths). It has been `terraform validate`d but is not
# exercised by any shipped environment; run a real plan before relying on it.
#
# This module deliberately does NOT create the resource group, VNet, ACR, Key
# Vault, Log Analytics workspace or Postgres/Redis: those are expected to exist
# already and are passed in by id. It only adds the cluster, its node pools,
# and the identities/role assignments the cluster needs.

locals {
  tags = merge({ workload = "vegaduta", managed_by = "terraform", stack = "aks" }, var.tags)

  # Only pass `zones` when set; an empty list means non-zonal.
  zones = length(var.zones) == 0 ? null : var.zones

  kv_identities = { for k, v in var.workload_identities : k => v if v.key_vault_read }
}

resource "azurerm_kubernetes_cluster" "this" {
  name                = var.cluster_name
  resource_group_name = var.resource_group_name
  location            = var.location
  dns_prefix          = var.cluster_name
  kubernetes_version  = var.kubernetes_version
  sku_tier            = var.sku_tier
  tags                = local.tags

  # --- security posture ----------------------------------------------------
  private_cluster_enabled   = var.private_cluster_enabled
  private_dns_zone_id       = var.private_cluster_enabled ? "System" : null
  local_account_disabled    = true # AAD only - no static admin kubeconfig
  oidc_issuer_enabled       = true
  workload_identity_enabled = true
  azure_policy_enabled      = var.azure_policy_enabled
  image_cleaner_enabled     = true
  run_command_enabled       = false # no az aks command invoke backdoor around network/RBAC

  azure_active_directory_role_based_access_control {
    tenant_id              = var.tenant_id
    azure_rbac_enabled     = true
    admin_group_object_ids = var.admin_group_object_ids
  }

  dynamic "api_server_access_profile" {
    for_each = !var.private_cluster_enabled && length(var.api_server_authorized_ip_ranges) > 0 ? [1] : []
    content {
      authorized_ip_ranges = var.api_server_authorized_ip_ranges
    }
  }

  identity {
    type = "SystemAssigned"
  }

  # --- networking ----------------------------------------------------------
  # Azure CNI Overlay + Cilium dataplane: pods do not consume VNet IPs, and
  # NetworkPolicy (default-deny + explicit allows, per the charts) is enforced.
  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    pod_cidr            = var.pod_cidr
    service_cidr        = var.service_cidr
    dns_service_ip      = var.dns_service_ip
    load_balancer_sku   = "standard"
    outbound_type       = "loadBalancer"
  }

  # --- system pool ---------------------------------------------------------
  default_node_pool {
    name                         = "system"
    vm_size                      = var.system_pool.vm_size
    vnet_subnet_id               = var.node_subnet_id
    auto_scaling_enabled         = true
    min_count                    = var.system_pool.min_count
    max_count                    = var.system_pool.max_count
    zones                        = local.zones
    only_critical_addons_enabled = true # adds the CriticalAddonsOnly=true:NoSchedule taint
    os_disk_type                 = "Ephemeral"
    os_disk_size_gb              = 64
    temporary_name_for_rotation  = "systemtmp"
    node_labels                  = { "vegaduta.ai/pool" = "system" }

    upgrade_settings {
      max_surge = "33%"
    }
  }

  # --- add-ons -------------------------------------------------------------
  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  dynamic "oms_agent" {
    for_each = var.log_analytics_workspace_id == null ? [] : [1]
    content {
      log_analytics_workspace_id      = var.log_analytics_workspace_id
      msi_auth_for_monitoring_enabled = true
    }
  }

  dynamic "monitor_metrics" {
    for_each = var.enable_managed_prometheus ? [1] : []
    content {}
  }

  # --- upgrades ------------------------------------------------------------
  automatic_upgrade_channel = var.automatic_upgrade_channel
  node_os_upgrade_channel   = var.node_os_upgrade_channel

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = var.maintenance_window.duration
    day_of_week = var.maintenance_window.day_of_week
    start_time  = var.maintenance_window.start_time
    utc_offset  = var.maintenance_window.utc_offset
  }

  maintenance_window_node_os {
    frequency   = "Weekly"
    interval    = 1
    duration    = var.maintenance_window.duration
    day_of_week = var.maintenance_window.day_of_week
    start_time  = var.maintenance_window.start_time
    utc_offset  = var.maintenance_window.utc_offset
  }

  lifecycle {
    ignore_changes = [
      # Cluster autoscaler owns the live count.
      default_node_pool[0].node_count,
      # Patch upgrades float under automatic_upgrade_channel.
      kubernetes_version,
    ]
    precondition {
      condition     = !var.enable_managed_prometheus || var.monitor_workspace_id != null
      error_message = "enable_managed_prometheus requires monitor_workspace_id."
    }
    # A public API server with no authorized ranges is reachable from the whole
    # internet (AAD auth still applies). Require an explicit allow-list unless
    # the cluster is private, or the caller explicitly opts out.
    precondition {
      condition     = var.private_cluster_enabled || length(var.api_server_authorized_ip_ranges) > 0 || var.allow_unrestricted_public_api_server
      error_message = "api_server_authorized_ip_ranges must be non-empty when private_cluster_enabled = false (or set allow_unrestricted_public_api_server = true to accept an internet-reachable API server)."
    }
  }
}

# --- additional node pools --------------------------------------------------

resource "azurerm_kubernetes_cluster_node_pool" "general" {
  name                  = "general"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  vm_size               = var.general_pool.vm_size
  vnet_subnet_id        = var.node_subnet_id
  auto_scaling_enabled  = true
  min_count             = var.general_pool.min_count
  max_count             = var.general_pool.max_count
  zones                 = local.zones
  os_disk_type          = "Ephemeral"
  os_disk_size_gb       = 64
  node_labels           = { "vegaduta.ai/pool" = "general" }
  tags                  = local.tags

  upgrade_settings {
    max_surge = "33%"
  }

  lifecycle {
    ignore_changes = [node_count]
  }
}

# Zone-pinned pool for Redpanda / Temporal. Managed disks are zonal, so the
# pool (and therefore the PVs) must stay in one zone. Fixed size: stateful
# brokers must not be scaled down by the autoscaler. For a real 3-broker
# Redpanda add one pool per zone (copy this resource) - see README.
resource "azurerm_kubernetes_cluster_node_pool" "stateful" {
  count = var.stateful_pool.enabled ? 1 : 0

  name                  = "stateful"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  vm_size               = var.stateful_pool.vm_size
  vnet_subnet_id        = var.node_subnet_id
  auto_scaling_enabled  = false
  node_count            = var.stateful_pool.node_count
  zones                 = [var.stateful_pool.zone]
  os_disk_type          = "Managed" # ephemeral disk would be lost on node reimage; keep OS disk managed here
  node_labels           = { "vegaduta.ai/pool" = "stateful", "workload" = "stateful" }
  node_taints           = ["workload=stateful:NoSchedule"]
  tags                  = local.tags

  upgrade_settings {
    max_surge = "1"
  }
}

# Untrusted-code pool (tool-sandbox, mcp-stdio-runner). Tainted so nothing
# else lands here, scales to zero by default. See README for the gVisor/Kata
# note. Pair with a NetworkPolicy that blocks the IMDS (169.254.169.254) and
# in-cluster ranges.
resource "azurerm_kubernetes_cluster_node_pool" "sandbox" {
  count = var.sandbox_pool.enabled ? 1 : 0

  name                  = "sandbox"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  vm_size               = var.sandbox_pool.vm_size
  vnet_subnet_id        = var.node_subnet_id
  auto_scaling_enabled  = true
  min_count             = var.sandbox_pool.min_count
  max_count             = var.sandbox_pool.max_count
  zones                 = local.zones
  os_disk_type          = "Ephemeral"
  os_disk_size_gb       = 64
  os_sku                = var.sandbox_pool.os_sku
  workload_runtime      = var.sandbox_pool.workload_runtime
  node_labels           = { "vegaduta.ai/pool" = "sandbox", "workload" = "sandbox" }
  node_taints           = ["workload=sandbox:NoSchedule"]
  tags                  = local.tags

  upgrade_settings {
    max_surge = "33%"
  }

  lifecycle {
    ignore_changes = [node_count]
  }
}

# --- ACR pull ---------------------------------------------------------------

resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                            = var.acr_id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
  skip_service_principal_aad_check = true
}

# --- cluster admin via Azure RBAC ------------------------------------------

resource "azurerm_role_assignment" "cluster_admins" {
  for_each             = toset(var.admin_group_object_ids)
  scope                = azurerm_kubernetes_cluster.this.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = each.value
}

# --- workload identities (one per app ServiceAccount) -----------------------

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = var.workload_identities
  name                = "${var.cluster_name}-${each.key}"
  resource_group_name = var.resource_group_name
  location            = var.location
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "workload" {
  for_each  = var.workload_identities
  name      = "${each.value.namespace}-${each.value.service_account_name}"
  parent_id = azurerm_user_assigned_identity.workload[each.key].id
  audience  = ["api://AzureADTokenExchange"]
  issuer    = azurerm_kubernetes_cluster.this.oidc_issuer_url
  subject   = "system:serviceaccount:${each.value.namespace}:${each.value.service_account_name}"
}

resource "azurerm_role_assignment" "workload_kv_read" {
  for_each             = local.kv_identities
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload[each.key].principal_id
}

# --- Managed Prometheus (optional) ------------------------------------------
# The Azure Monitor workspace (and any Managed Grafana reading it) is expected
# to exist already, so this only wires the cluster's metrics into that
# workspace.

resource "azurerm_monitor_data_collection_endpoint" "prom" {
  count               = var.enable_managed_prometheus ? 1 : 0
  name                = "${var.cluster_name}-prom-dce"
  resource_group_name = var.resource_group_name
  location            = var.location
  kind                = "Linux"
  tags                = local.tags
}

resource "azurerm_monitor_data_collection_rule" "prom" {
  count                       = var.enable_managed_prometheus ? 1 : 0
  name                        = "${var.cluster_name}-prom-dcr"
  resource_group_name         = var.resource_group_name
  location                    = var.location
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.prom[0].id
  kind                        = "Linux"
  tags                        = local.tags

  destinations {
    monitor_account {
      monitor_account_id = var.monitor_workspace_id
      name               = "prometheus"
    }
  }

  data_flow {
    streams      = ["Microsoft-PrometheusMetrics"]
    destinations = ["prometheus"]
  }

  data_sources {
    prometheus_forwarder {
      streams = ["Microsoft-PrometheusMetrics"]
      name    = "PrometheusDataSource"
    }
  }
}

resource "azurerm_monitor_data_collection_rule_association" "prom" {
  count                   = var.enable_managed_prometheus ? 1 : 0
  name                    = "${var.cluster_name}-prom-dcra"
  target_resource_id      = azurerm_kubernetes_cluster.this.id
  data_collection_rule_id = azurerm_monitor_data_collection_rule.prom[0].id
}

resource "azurerm_monitor_data_collection_rule_association" "prom_endpoint" {
  count                       = var.enable_managed_prometheus ? 1 : 0
  target_resource_id          = azurerm_kubernetes_cluster.this.id
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.prom[0].id
}
