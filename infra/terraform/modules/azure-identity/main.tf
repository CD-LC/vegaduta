# Azure equivalent of gcp-iam's per-service-account piece. The Binary
# Authorization/cosign-signing half of gcp-iam has no direct Azure
# equivalent and is not implemented here (known gap).

resource "azurerm_user_assigned_identity" "this" {
  for_each            = toset(var.service_names)
  name                = "${var.name_prefix}-${each.value}"
  resource_group_name = var.resource_group_name
  location            = var.location
}
