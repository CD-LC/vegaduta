output "id" {
  value = azurerm_container_app_environment.this.id
}

output "default_domain" {
  value = azurerm_container_app_environment.this.default_domain
}

output "static_ip" {
  description = "This environment's shared outbound/static IP — every app in it egresses from this same address. Used to IP-restrict an externally-TCP-ingressed app (see azure-container-app's ip_security_restrictions) down to effectively-internal-only reachability."
  value       = azurerm_container_app_environment.this.static_ip_address
}

output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.this.id
}
