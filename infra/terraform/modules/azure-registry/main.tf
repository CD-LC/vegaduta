# Azure equivalent of gcp-artifact-registry.

resource "azurerm_container_registry" "this" {
  name                = "${replace(var.name_prefix, "-", "")}acr" # ACR names: alnum only, globally unique
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = var.sku
  admin_enabled       = false # pull via managed identity + RBAC only, matches gcp-artifact-registry's IAM-only reader model
}

resource "azurerm_role_assignment" "reader" {
  for_each             = var.reader_principal_ids # map(service_name => principal_id) — see variables.tf
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPull"
  principal_id         = each.value
}
