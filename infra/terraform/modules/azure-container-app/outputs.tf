output "fqdn" {
  value = azurerm_container_app.this.ingress[0].fqdn
}

output "name" {
  value = azurerm_container_app.this.name
}

output "id" {
  value = azurerm_container_app.this.id
}

# null unless var.sticky_sessions — the manual step azurerm can't codify yet
# (see the TODO on that variable). Needs `az extension add -n containerapp`.
output "sticky_sessions_command" {
  value = var.sticky_sessions ? "az containerapp ingress sticky-sessions set --name ${azurerm_container_app.this.name} --resource-group ${var.resource_group_name} --affinity sticky" : null
}
