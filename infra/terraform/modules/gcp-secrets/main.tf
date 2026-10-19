resource "google_secret_manager_secret" "this" {
  for_each  = var.secrets
  project   = var.project_id
  secret_id = each.key

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "this" {
  for_each    = { for k, v in var.secrets : k => v if v.manage_version }
  secret      = google_secret_manager_secret.this[each.key].id
  secret_data = var.secret_values[each.key] # sensitive lookup, never a for_each driver
}

resource "google_secret_manager_secret_iam_member" "accessors" {
  for_each = {
    for pair in flatten([
      for secret_id, cfg in var.secrets : [
        for sa in cfg.accessor_service_accounts : {
          key       = "${secret_id}::${sa}"
          secret_id = secret_id
          sa        = sa
        }
      ]
    ]) : pair.key => pair
  }
  project   = var.project_id
  secret_id = google_secret_manager_secret.this[each.value.secret_id].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${each.value.sa}"
}
