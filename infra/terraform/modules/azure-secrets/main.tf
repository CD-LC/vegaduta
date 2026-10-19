# Azure equivalent of gcp-secrets. One Key Vault, RBAC authorization mode
# (not the older access-policy model — matches "grants are ordinary Azure
# role assignments" the same way GCP grants are ordinary IAM bindings).

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                       = "${var.name_prefix}-kv-${var.name_suffix}" # Key Vault names are globally unique
  resource_group_name        = var.resource_group_name
  location                   = var.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = var.purge_protection_enabled
  soft_delete_retention_days = var.soft_delete_retention_days

  # Container Apps' Key Vault secret references resolve through the vault's
  # public endpoint even for VNet-integrated apps, so public network access
  # stays configurable (default true) and is locked down by the firewall
  # below instead: default Deny, trusted Azure services bypass, plus only the
  # CIDRs / subnets you list. The machine running `terraform apply` must be
  # in allowed_ip_cidrs (or in an allowed subnet) to write secrets.
  public_network_access_enabled = var.public_network_access_enabled
  network_acls {
    default_action             = var.network_default_action
    bypass                     = "AzureServices"
    ip_rules                   = var.allowed_ip_cidrs
    virtual_network_subnet_ids = var.allowed_subnet_ids
  }
}

# RBAC-mode Key Vault grants NOTHING implicitly, not even to whoever creates
# it — without this, reading/writing every secret fails with 403 ("Caller is
# not authorized..."). The deploying principal (whoever runs `terraform
# apply`, or a CI deployer identity) needs an explicit role here just like
# the managed identities below get per-secret grants.
resource "azurerm_role_assignment" "deployer_secrets_officer" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# Azure RBAC role assignments are not instantly consistent (Azure's 403 says
# "If role assignments ... were changed recently, please observe
# propagation time"). depends_on alone only
# sequences the API calls, not real-world propagation; this buffer is the
# standard workaround for that specific class of flake.
resource "time_sleep" "rbac_propagation" {
  depends_on      = [azurerm_role_assignment.deployer_secrets_officer]
  create_duration = "30s"
}

resource "azurerm_key_vault_secret" "this" {
  for_each        = var.secrets
  name            = each.key
  value           = var.secret_values[each.key] # sensitive lookup, not a for_each driver — see variables.tf
  key_vault_id    = azurerm_key_vault.this.id
  content_type    = "text/plain"
  expiration_date = var.secret_expiration_date
  depends_on      = [time_sleep.rbac_propagation]

  # Terraform seeds each value once; after that the vault is the source of
  # truth. If a value is later changed in Key Vault directly (e.g.
  # APP_ENCRYPTION_KEY carried over from an existing install), an apply
  # would otherwise write the tfvars value back — and a changed
  # APP_ENCRYPTION_KEY leaves every stored provider key and channel secret
  # undecryptable. Rotate secrets in Key Vault, not by editing tfvars.
  lifecycle {
    ignore_changes = [value]
  }
}

# Flattened per-secret × accessor-principal grants, same shape as the GCP
# module's per-secret IAM bindings.
locals {
  # secret_id and service_name are both static strings known at plan time —
  # the map key below is built from these two, never from a principal_id.
  secret_accessor_pairs = flatten([
    for secret_id, cfg in var.secrets : [
      for service_name in cfg.accessor_service_names : {
        secret_id    = secret_id
        service_name = service_name
      }
    ]
  ])
}

resource "azurerm_role_assignment" "secret_access" {
  for_each = {
    for pair in local.secret_accessor_pairs :
    "${pair.secret_id}-${pair.service_name}" => pair
  }
  scope                = azurerm_key_vault_secret.this[each.value.secret_id].resource_versionless_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = var.identity_principal_ids[each.value.service_name] # apply-time value, fine as a plain attribute
}
