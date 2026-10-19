output "fqdn" {
  value = azurerm_postgresql_flexible_server.this.fqdn
}

output "jdbc_url" {
  description = "JDBC URL (no credentials) for the application database; sslmode=require."
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.this.fqdn}:5432/${azurerm_postgresql_flexible_server_database.app.name}?sslmode=require"
}

output "database_name" {
  value = azurerm_postgresql_flexible_server_database.app.name
}

output "admin_username" {
  value = var.admin_username
}

output "admin_password" {
  value     = random_password.admin.result
  sensitive = true
}

output "server_id" {
  value = azurerm_postgresql_flexible_server.this.id
}

output "server_name" {
  value = azurerm_postgresql_flexible_server.this.name
}

# null unless var.pitr_restore_enabled
output "pitr_restore_fqdn" {
  value = one(azurerm_postgresql_flexible_server.pitr_restore[*].fqdn)
}
