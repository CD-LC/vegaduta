output "cluster_id" {
  value = azurerm_kubernetes_cluster.this.id
}

output "cluster_name" {
  value = azurerm_kubernetes_cluster.this.name
}

output "oidc_issuer_url" {
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
  description = "Issuer used by the federated credentials."
}

output "kubelet_identity_object_id" {
  value = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

output "key_vault_secrets_provider_identity" {
  value       = azurerm_kubernetes_cluster.this.key_vault_secrets_provider[0].secret_identity[0].client_id
  description = "Client id of the CSI add-on's own identity. Charts use workload identity instead; this is informational."
}

output "workload_identity_client_ids" {
  value       = { for k, v in azurerm_user_assigned_identity.workload : k => v.client_id }
  description = "Map short-name => client id. Set as the azure.workload.identity/client-id annotation on each ServiceAccount (deploy/helm values) and as clientID in SecretProviderClass."
}

output "workload_identity_principal_ids" {
  value = { for k, v in azurerm_user_assigned_identity.workload : k => v.principal_id }
}
