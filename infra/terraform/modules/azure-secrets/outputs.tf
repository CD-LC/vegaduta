output "vault_id" {
  value = azurerm_key_vault.this.id
}

output "vault_uri" {
  value = azurerm_key_vault.this.vault_uri
}

output "secret_ids" {
  value = { for k, v in azurerm_key_vault_secret.this : k => v.id }
}

output "secret_versionless_ids" {
  # `resource_versionless_id` is the ARM management-plane
  # resource ID (.../resourceGroups/.../providers/Microsoft.KeyVault/vaults/
  # .../secrets/<name>) — correct for a `scope` on an azurerm_role_assignment,
  # but azurerm_container_app's `secret.key_vault_secret_id` wants the actual
  # Key Vault DATA-PLANE URI (https://<vault>.vault.azure.net/secrets/<name>),
  # which is `versionless_id`. Using the wrong one fails apply with "expected
  # 2 or 3 path segments, found 10 segment(s)".
  value = { for k, v in azurerm_key_vault_secret.this : k => v.versionless_id }
}
