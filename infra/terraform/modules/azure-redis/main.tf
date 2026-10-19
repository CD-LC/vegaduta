# Azure Managed Redis — the current Azure PaaS Redis offering (built on
# Redis Enterprise). The classic `azurerm_redis_cache` (Azure Cache for
# Redis) is being retired, and the older
# `azurerm_redis_enterprise_cluster`/`_database` pair is deprecated in favor
# of this single unified resource.
#
# sku_name: exact string format matters, e.g. "Balanced_B0" (0.5 GB, the
# smallest SKU), not "B0" or "Basic".
# client_protocol: "Encrypted" (TLS) by default. Set "Plaintext" only if the
# client cannot speak TLS; traffic then still stays inside the VNet via the
# private endpoint below.

resource "azurerm_managed_redis" "this" {
  name                = "${var.name_prefix}-redis"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku_name            = var.sku_name

  public_network_access = "Disabled" # reachable only via the private endpoint below, same posture as every other stateful service in this stack

  default_database {
    client_protocol   = var.client_protocol
    clustering_policy = "NoCluster" # matches a single-node, non-cluster-aware Spring Data Redis client
    # Defaults to false, in which case Azure Managed Redis issues NO access
    # key (Entra ID token auth only) and `primary_access_key` is empty. A Key
    # Vault secret built from it is then an empty string, which Container
    # Apps' key-vault-reference resolution rejects with a generic "unable to
    # fetch secret" error. `core` authenticates with a password
    # (REDIS_PASSWORD), so access-key auth has to be explicitly on.
    access_keys_authentication_enabled = true
  }
}

resource "azurerm_private_dns_zone" "redis" {
  name                = "privatelink.redis.azure.net" # exact zone name for "Azure Managed Redis" (Microsoft.Cache/RedisEnterprise) per learn.microsoft.com/azure/private-link/private-endpoint-dns — do NOT reuse privatelink.redis.cache.windows.net, that's the classic/retired Azure Cache for Redis zone
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "redis" {
  name                  = "${var.name_prefix}-redis-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.redis.name
  virtual_network_id    = var.vnet_id
}

resource "azurerm_private_endpoint" "redis" {
  name                = "${var.name_prefix}-redis-pe"
  resource_group_name = var.resource_group_name
  location            = var.location
  subnet_id           = var.private_endpoints_subnet_id # NOT the container-apps subnet — that one's service-delegated and can't also host a private endpoint

  private_service_connection {
    name                           = "${var.name_prefix}-redis-psc"
    private_connection_resource_id = azurerm_managed_redis.this.id
    subresource_names              = ["redisEnterprise"] # confirmed exact subresource/group-id name for Microsoft.Cache/RedisEnterprise (Azure Managed Redis) per the same private-endpoint-dns reference
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "redis-dns"
    private_dns_zone_ids = [azurerm_private_dns_zone.redis.id]
  }
}
