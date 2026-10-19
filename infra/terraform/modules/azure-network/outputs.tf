output "vnet_id" {
  value = azurerm_virtual_network.this.id
}

output "container_apps_subnet_id" {
  value = azurerm_subnet.container_apps.id
}

output "database_subnet_id" {
  value = azurerm_subnet.database.id
}

output "private_endpoints_subnet_id" {
  value = azurerm_subnet.private_endpoints.id
}

output "postgres_private_dns_zone_id" {
  value = azurerm_private_dns_zone.postgres.id
}
