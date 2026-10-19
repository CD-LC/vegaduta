# modules/aks (reference module)

Status: **`terraform validate`d only; not exercised by any shipped
environment.** Community-supported reference module, not used by the
Compose/Helm install paths. Run a real `terraform plan` before relying on it.

Creates one AKS cluster plus node pools, ACR pull, and per-ServiceAccount
workload identities. It does not create the RG, VNet, ACR, Key Vault, Log
Analytics workspace, Azure Monitor workspace, Postgres or Redis; those are
passed in by id from your own root module.

## What it builds

| Area | Choice |
|---|---|
| Networking | Azure CNI Overlay, Cilium dataplane + Cilium network policy (default-deny NetworkPolicy in the charts is enforced). `pod_cidr` 10.244.0.0/16 and `service_cidr` 10.245.0.0/16 must not overlap the VNet or peered networks. |
| Identity | OIDC issuer + workload identity. AAD-integrated RBAC (`azure_rbac_enabled`), `local_account_disabled = true`, `run_command_enabled = false`. |
| Secrets | Key Vault CSI add-on with secret rotation (2m). Apps use workload identity + `SecretProviderClass`, not the add-on identity. |
| Pools | `system` (tainted `CriticalAddonsOnly`), `general` (autoscale), `stateful` (zone-pinned, fixed size, taint `workload=stateful`), `sandbox` (taint `workload=sandbox`, scales to 0). Pool labels: `vegaduta.ai/pool=<name>`. |
| Registry | `AcrPull` for the kubelet identity on the existing ACR. |
| Workload identities | `workload_identities` map: one user-assigned identity + federated credential (`system:serviceaccount:<ns>:<sa>`) each; `Key Vault Secrets User` on the vault unless `key_vault_read = false`. Default set covers every chart in `deploy/helm/charts` plus `redpanda`/`temporal` in `vegaduta-data`. |
| Observability | Container Insights into the existing workspace (when `log_analytics_workspace_id` set); optional Managed Prometheus DCE/DCR/DCRA into the existing Azure Monitor workspace. |
| Upgrades | `automatic_upgrade_channel = patch`, `node_os_upgrade_channel = NodeImage`, weekly Sunday 01:00 window (both; set `maintenance_window.utc_offset`). `kubernetes_version` and `node_count` are `ignore_changes`. |
| API server | Public clusters must set `api_server_authorized_ip_ranges` (enforced by a precondition) unless `private_cluster_enabled = true` or `allow_unrestricted_public_api_server = true`. |
| Optional | `azure_policy_enabled`, `private_cluster_enabled`. |

## Rules encoded / not encoded here

* No secrets or literals: only ids and names. `APP_ENCRYPTION_KEY` and every other
  secret stay in Key Vault; the charts read them through the CSI driver.
* Nothing here stops/deletes/resets anything existing. Destroying this stack
  would only remove the cluster and its identities.
* Redpanda cannot use Azure Files (needs O_DIRECT): the stateful pool is
  meant for Premium SSD managed-disk PVs (zonal, hence zone-pinned).

## Sandbox pool: gVisor / Kata

gVisor is not an AKS-native runtime. The supported hardened option is Pod
Sandboxing (Kata VM isolation): set `sandbox_pool.workload_runtime =
"KataVmIsolation"` and `os_sku = "AzureLinux"` on a nested-virtualisation
capable SKU, then select it per pod with the Kata `RuntimeClass`. This may be
preview or SKU/region-limited: **verify against current Microsoft docs before
enabling.** Default is plain runc with taint + NetworkPolicy (block IMDS
169.254.169.254 and in-cluster ranges) as the boundary, which is weaker.

## What to change for production

| Variable | Default | Production |
|---|---|---|
| `sku_tier` | Free | Standard (SLA) |
| `private_cluster_enabled` | false | true (run kubectl/helm/Flux from inside the VNet or a runner) |
| `api_server_authorized_ip_ranges` | [] (required when public) | your admin/runner egress CIDRs if the API server stays public |
| `system_pool` | D2ads_v5, 1-3 | 3 minimum (one per zone) |
| `general_pool` | D4ads_v5, 1-4 | 2-3 min, size from load test |
| `stateful_pool` | 1 node, zone 1 | one pool per zone (copy the resource) for a 3-broker Redpanda |
| `sandbox_pool` | runc, 0-3 | Kata isolation if verified |
| `azure_policy_enabled` | false | true with a chosen initiative |
| `enable_managed_prometheus` | false | true |
| Container Insights | on | add a data-collection filter (ingestion cost) |
| Key Vault role scope | vault-wide | per-secret scope |
| `kubernetes_version` | AKS default | pin the minor deliberately |

Also verify VM SKU/zone availability and quota in your region, and that
`zones` matches the VNet's region.

## Known unverified points

* `terraform validate` cannot check that a VM SKU, `workload_runtime`, or zone is
  available in the subscription/region.
* Azure Managed Prometheus needs a matching `monitor_metrics` and a DCR in the
  same region as the Azure Monitor workspace.
* `outbound_type = loadBalancer` uses a cluster-managed public IP for egress;
  decide on NAT Gateway / firewall before production if a fixed egress IP is needed
  (Postgres, Redis and third-party allow-lists).
