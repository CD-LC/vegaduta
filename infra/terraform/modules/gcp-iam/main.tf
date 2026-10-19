resource "google_service_account" "service" {
  for_each     = toset(var.service_names)
  project      = var.project_id
  account_id   = "${var.name_prefix}-${each.value}"
  display_name = "${var.name_prefix} - ${each.value} runtime SA"
}

# Baseline every runtime SA needs - logging/metrics. Nothing here grants
# Editor/Owner; each SA otherwise starts with zero permissions.
resource "google_project_iam_member" "log_writer" {
  for_each = google_service_account.service
  project  = var.project_id
  role     = "roles/logging.logWriter"
  member   = "serviceAccount:${each.value.email}"
}

resource "google_project_iam_member" "metric_writer" {
  for_each = google_service_account.service
  project  = var.project_id
  role     = "roles/monitoring.metricWriter"
  member   = "serviceAccount:${each.value.email}"
}

# Only core actually talks to Cloud SQL.
resource "google_project_iam_member" "core_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.service["core"].email}"
}

# --- Supply chain: cosign signing key + Binary Authorization ---
# The signing key lives in KMS (not a file anywhere) - a release pipeline
# calls `cosign sign --key gcpkms://...` using this key via its own workload
# identity, which needs roles/cloudkms.signerVerifier on the key (granted
# outside this module).

resource "google_kms_key_ring" "signing" {
  project  = var.project_id
  name     = "${var.name_prefix}-signing"
  location = var.region
}

resource "google_kms_crypto_key" "cosign" {
  key_ring = google_kms_key_ring.signing.id
  name     = "cosign-image-signing"
  purpose  = "ASYMMETRIC_SIGN"
  version_template {
    algorithm = "EC_SIGN_P256_SHA256"
  }
  # Signing keys are not something you want auto-rotated out from under a
  # deployed attestor.
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_container_analysis_note" "cosign" {
  project = var.project_id
  name    = "${var.name_prefix}-cosign-attestor-note"
  attestation_authority {
    hint {
      human_readable_name = "${var.name_prefix} cosign image signer"
    }
  }
}

resource "google_binary_authorization_attestor" "cosign" {
  project = var.project_id
  name    = "${var.name_prefix}-cosign-attestor"
  attestation_authority_note {
    note_reference = google_container_analysis_note.cosign.name
  }
}

# Only images carrying a valid attestation from the attestor above may
# deploy - an unsigned image push should be rejected at deploy time. Verify
# with a negative test (deploy an unsigned image and expect a rejection)
# before relying on it.
resource "google_binary_authorization_policy" "this" {
  project = var.project_id

  default_admission_rule {
    evaluation_mode  = "REQUIRE_ATTESTATION"
    enforcement_mode = "ENFORCED_BLOCK_AND_AUDIT_LOG"
    require_attestations_by = [
      google_binary_authorization_attestor.cosign.name
    ]
  }

  global_policy_evaluation_mode = "ENABLE"
}
