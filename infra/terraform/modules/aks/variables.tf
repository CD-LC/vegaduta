# Reference module - see README.md for the design and the list of values to
# change for production use.

variable "resource_group_name" {
  type        = string
  description = "Existing resource group the cluster is created in."
}

variable "location" {
  type = string
}

variable "cluster_name" {
  type        = string
  description = "AKS cluster name, e.g. vegaduta-aks. Also used as the DNS prefix."
  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9-]{0,52}[a-zA-Z0-9]$", var.cluster_name))
    error_message = "cluster_name must be 2-54 characters: letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "tenant_id" {
  type        = string
  description = "Entra ID tenant used for AAD-integrated RBAC."
  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.tenant_id))
    error_message = "tenant_id must be a GUID."
  }
}

variable "kubernetes_version" {
  type        = string
  default     = null
  description = "Minor version (e.g. \"1.33\"). null = the AKS default. With automatic_upgrade_channel set, patch versions float."
}

variable "sku_tier" {
  type        = string
  default     = "Free"
  description = "Free (no uptime SLA, cost-safe default), Standard or Premium. Production: Standard (financially-backed API server SLA)."
  validation {
    condition     = contains(["Free", "Standard", "Premium"], var.sku_tier)
    error_message = "sku_tier must be Free, Standard or Premium."
  }
}

# --- existing platform resources (passed in by id) ---------------------------

variable "node_subnet_id" {
  type        = string
  description = "Subnet for all node pools. With Azure CNI Overlay only nodes consume VNet IPs; pods use pod_cidr."
}

variable "acr_id" {
  type        = string
  description = "Existing ACR; the kubelet identity is granted AcrPull on it."
}

variable "key_vault_id" {
  type        = string
  description = "Existing Key Vault (RBAC mode). Workload identities get Key Vault Secrets User here."
}

variable "log_analytics_workspace_id" {
  type        = string
  default     = null
  description = "Existing Log Analytics workspace. When set, Container Insights (oms_agent) is enabled. This is a real ingestion cost - add a data-collection filter before prod."
}

variable "monitor_workspace_id" {
  type        = string
  default     = null
  description = "Existing Azure Monitor workspace (Managed Prometheus). Required when enable_managed_prometheus = true."
}

variable "enable_managed_prometheus" {
  type        = bool
  default     = false
  description = "Azure Managed Prometheus scraping via DCE/DCR. Needs monitor_workspace_id."
}

# --- networking -------------------------------------------------------------

variable "pod_cidr" {
  type        = string
  default     = "10.244.0.0/16"
  description = "Overlay pod CIDR. Must not overlap the VNet or any peered network."
  validation {
    condition     = can(cidrhost(var.pod_cidr, 0))
    error_message = "pod_cidr must be a valid CIDR block."
  }
}

variable "service_cidr" {
  type        = string
  default     = "10.245.0.0/16"
  description = "Cluster service CIDR. Must not overlap the VNet, pod_cidr, or peered networks."
  validation {
    condition     = can(cidrhost(var.service_cidr, 0))
    error_message = "service_cidr must be a valid CIDR block."
  }
}

variable "dns_service_ip" {
  type        = string
  default     = "10.245.0.10"
  description = "Must be inside service_cidr."
  validation {
    condition     = can(regex("^(\\d{1,3}\\.){3}\\d{1,3}$", var.dns_service_ip))
    error_message = "dns_service_ip must be an IPv4 address."
  }
}

variable "private_cluster_enabled" {
  type        = bool
  default     = false
  description = "Private API server. Default false so a machine outside the VNet can reach it; api_server_authorized_ip_ranges is then required. Production: true (run kubectl/helm/Flux from inside the VNet or via a runner)."
}

variable "api_server_authorized_ip_ranges" {
  type        = list(string)
  default     = []
  description = "CIDRs allowed to reach a public API server. REQUIRED (non-empty) when private_cluster_enabled = false, unless allow_unrestricted_public_api_server = true. Ignored for private clusters."
  validation {
    condition     = alltrue([for c in var.api_server_authorized_ip_ranges : can(cidrhost(c, 0))])
    error_message = "Every api_server_authorized_ip_ranges entry must be a valid CIDR block."
  }
}

variable "allow_unrestricted_public_api_server" {
  type        = bool
  default     = false
  description = "Escape hatch: allow a public API server with no authorized IP ranges (reachable from the internet; AAD auth still required). Leave false."
}

# --- identity / RBAC --------------------------------------------------------

variable "admin_group_object_ids" {
  type        = list(string)
  description = "Entra groups granted cluster-admin. Local accounts are disabled, so an empty list would lock everyone out."
  validation {
    condition     = length(var.admin_group_object_ids) > 0
    error_message = "Provide at least one Entra group object id (local accounts are disabled)."
  }
}

variable "workload_identities" {
  nullable = false # env passes null => module default
  type = map(object({
    namespace            = string
    service_account_name = string
    key_vault_read       = optional(bool, true)
  }))
  description = "One user-assigned identity + federated credential per app ServiceAccount (key = short name). key_vault_read grants Key Vault Secrets User (vault-wide; narrow to per-secret scope before prod)."
  default = {
    core             = { namespace = "vegaduta", service_account_name = "core" }
    core-jobs        = { namespace = "vegaduta", service_account_name = "core-jobs" }
    web              = { namespace = "vegaduta", service_account_name = "web", key_vault_read = false }
    sidecar          = { namespace = "vegaduta", service_account_name = "sidecar" }
    code-intel       = { namespace = "vegaduta", service_account_name = "code-intel" }
    app-builder      = { namespace = "vegaduta", service_account_name = "app-builder" }
    academy          = { namespace = "vegaduta", service_account_name = "academy" }
    mcp-stdio-runner = { namespace = "vegaduta", service_account_name = "mcp-stdio-runner" }
    tool-sandbox     = { namespace = "vegaduta", service_account_name = "tool-sandbox", key_vault_read = false }
    voice-agent      = { namespace = "vegaduta", service_account_name = "voice-agent" }
    rl-trainer       = { namespace = "vegaduta", service_account_name = "rl-trainer" }
    # Stateful data plane (deploy/helm/stateful) lives in vegaduta-data.
    redpanda = { namespace = "vegaduta-data", service_account_name = "redpanda" }
    temporal = { namespace = "vegaduta-data", service_account_name = "temporal" }
  }
}

# --- policy / upgrades ------------------------------------------------------

variable "azure_policy_enabled" {
  type        = bool
  default     = false
  description = "Azure Policy add-on (Gatekeeper). Optional; enable once a policy initiative is chosen."
}

variable "automatic_upgrade_channel" {
  type        = string
  default     = "patch"
  description = "none | patch | stable | rapid | node-image. patch keeps the minor pinned and applies patches in the maintenance window."
  validation {
    condition     = contains(["none", "patch", "stable", "rapid", "node-image"], var.automatic_upgrade_channel)
    error_message = "automatic_upgrade_channel must be one of none, patch, stable, rapid, node-image."
  }
}

variable "node_os_upgrade_channel" {
  type        = string
  default     = "NodeImage"
  description = "None | Unmanaged | SecurityPatch | NodeImage."
  validation {
    condition     = contains(["None", "Unmanaged", "SecurityPatch", "NodeImage"], var.node_os_upgrade_channel)
    error_message = "node_os_upgrade_channel must be one of None, Unmanaged, SecurityPatch, NodeImage."
  }
}

variable "maintenance_window" {
  type = object({
    day_of_week = string
    start_time  = string # HH:mm
    duration    = number # hours, >= 4
    utc_offset  = string
  })
  default = {
    day_of_week = "Sunday"
    start_time  = "01:00"
    duration    = 4
    utc_offset  = "+00:00"
  }
  description = "Weekly window used for both cluster auto-upgrade and node-OS upgrade. Set utc_offset to the region's local offset."
  validation {
    condition     = var.maintenance_window.duration >= 4 && can(regex("^([01]\\d|2[0-3]):[0-5]\\d$", var.maintenance_window.start_time))
    error_message = "maintenance_window.duration must be >= 4 hours and start_time must be HH:mm."
  }
}

# --- node pools -------------------------------------------------------------
# Cost-safe defaults (small SKUs, min counts). Production changes are listed
# in the README ("What to change for production").

variable "zones" {
  type        = list(string)
  default     = ["1", "2", "3"]
  description = "Zones for the system and general pools. Confirm the region/SKU supports them. Use [] for a non-zonal cluster."
}

variable "system_pool" {
  nullable = false # env passes null => module default
  type = object({
    vm_size   = string
    min_count = number
    max_count = number
  })
  default = {
    vm_size   = "Standard_D2ads_v5"
    min_count = 1 # production: 3 (one per zone)
    max_count = 3
  }
  description = "Tainted CriticalAddonsOnly (only_critical_addons_enabled)."
  validation {
    condition     = var.system_pool.min_count >= 1 && var.system_pool.max_count >= var.system_pool.min_count
    error_message = "system_pool needs min_count >= 1 and max_count >= min_count."
  }
}

variable "general_pool" {
  nullable = false # env passes null => module default
  type = object({
    vm_size   = string
    min_count = number
    max_count = number
  })
  default = {
    vm_size   = "Standard_D4ads_v5"
    min_count = 1 # production: 2-3
    max_count = 4
  }
  description = "Autoscaling pool for stateless app workloads (core, web, ...)."
  validation {
    condition     = var.general_pool.min_count >= 0 && var.general_pool.max_count >= var.general_pool.min_count && var.general_pool.max_count > 0
    error_message = "general_pool needs 0 <= min_count <= max_count and max_count > 0."
  }
}

variable "stateful_pool" {
  nullable = false # env passes null => module default
  type = object({
    enabled    = bool
    vm_size    = string
    node_count = number
    zone       = string
  })
  default = {
    enabled    = true
    vm_size    = "Standard_D4ads_v5"
    node_count = 1 # production: 3, one pool per zone (see README)
    zone       = "1"
  }
  description = "Zone-pinned (managed disks are zonal), fixed size, tainted workload=stateful. For Redpanda/Temporal. Redpanda needs O_DIRECT block storage (Premium SSD via CSI), NOT Azure Files."
}

variable "sandbox_pool" {
  nullable = false # env passes null => module default
  type = object({
    enabled   = bool
    vm_size   = string
    min_count = number
    max_count = number
    # null = standard runc. "KataVmIsolation" = Pod Sandboxing (Kata VM-based
    # isolation): requires os_sku AzureLinux and a nested-virtualisation
    # capable SKU, and may be preview in your subscription/region. Verify
    # against current Microsoft docs before enabling.
    workload_runtime = optional(string)
    os_sku           = optional(string)
  })
  default = {
    enabled          = true
    vm_size          = "Standard_D4ads_v5"
    min_count        = 0
    max_count        = 3
    workload_runtime = null
    os_sku           = null
  }
  description = "Tainted workload=sandbox pool for tool-sandbox / mcp-stdio-runner (untrusted code). gVisor is not an AKS-native runtime; the supported hardened option is Kata VM isolation (workload_runtime), selected per-pod via a RuntimeClass."
}

variable "tags" {
  type    = map(string)
  default = {}
}
