output "identity_ids" {
  description = "map of service_name => user-assigned identity resource id"
  value       = { for k, v in azurerm_user_assigned_identity.this : k => v.id }
}

output "principal_ids" {
  description = "map of service_name => principal (object) id, for RBAC role assignments"
  value       = { for k, v in azurerm_user_assigned_identity.this : k => v.principal_id }
}

output "client_ids" {
  value = { for k, v in azurerm_user_assigned_identity.this : k => v.client_id }
}
