output "service_account_emails" {
  value = { for k, v in google_service_account.service : k => v.email }
}

output "cosign_kms_key_id" {
  value = google_kms_crypto_key.cosign.id
}

output "binary_authorization_attestor_name" {
  value = google_binary_authorization_attestor.cosign.name
}
