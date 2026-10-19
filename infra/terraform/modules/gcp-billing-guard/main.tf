# NOTE ON SCHEMA CONFIDENCE: google_billing_budget's threshold_rules /
# all_updates_rule shape passes `terraform validate`, but the Cloud Run v2
# REST PATCH body in function-src/main.py is based on Google's documented
# API surface, not exercised against a live project. Run a real plan (and a
# test budget notification) before relying on it.

resource "google_pubsub_topic" "budget_alerts" {
  project = var.project_id
  name    = "vegaduta-ai-budget-alerts"
}

resource "google_monitoring_notification_channel" "email" {
  for_each     = toset(var.alert_emails)
  project      = var.project_id
  display_name = "Budget alert - ${each.value}"
  type         = "email"
  labels = {
    email_address = each.value
  }
}

# Fires at 50%, 90%, 100% (email, human reacts) and again past the
# soft-kill ratio (email AND the Cloud Function below). Google recalculates
# and republishes to the Pub/Sub topic periodically as spend changes, not
# just once per threshold crossing - the function below is idempotent
# (re-zeroing an already-zeroed service is a no-op), so that's fine.
resource "google_billing_budget" "this" {
  billing_account = var.billing_account_id
  display_name    = var.budget_display_name

  budget_filter {
    projects = ["projects/${var.project_number}"]
  }

  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.budget_amount_usd)
    }
  }

  threshold_rules { threshold_percent = 0.5 }
  threshold_rules { threshold_percent = 0.9 }
  threshold_rules { threshold_percent = 1.0 }
  threshold_rules { threshold_percent = var.soft_kill_threshold_ratio }

  all_updates_rule {
    monitoring_notification_channels = [for c in google_monitoring_notification_channel.email : c.id]
    pubsub_topic                     = google_pubsub_topic.budget_alerts.id
  }
}

resource "google_service_account" "soft_kill_fn" {
  project      = var.project_id
  account_id   = "vegaduta-ai-billing-guard"
  display_name = "Billing-guard soft-kill function"
}

# Scoped to exactly what the function does - update Cloud Run service specs.
# Not roles/run.admin's full surface (delete, IAM changes, etc.) - if a
# narrower predefined role exists at apply time, prefer it over this.
resource "google_project_iam_member" "soft_kill_fn_run_developer" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.soft_kill_fn.email}"
}

resource "google_storage_bucket" "function_source" {
  project                     = var.project_id
  name                        = "${var.project_id}-billing-guard-fn"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = true # holds only the generated function zip

  versioning {
    enabled = true
  }
}

data "archive_file" "function_zip" {
  type        = "zip"
  source_dir  = "${path.module}/function-src"
  output_path = "${path.module}/function-src.zip"
}

resource "google_storage_bucket_object" "function_zip" {
  name   = "soft-kill-${data.archive_file.function_zip.output_md5}.zip"
  bucket = google_storage_bucket.function_source.name
  source = data.archive_file.function_zip.output_path
}

resource "google_cloudfunctions2_function" "soft_kill" {
  project  = var.project_id
  name     = "vegaduta-ai-billing-soft-kill"
  location = var.region

  build_config {
    runtime     = "python312"
    entry_point = "soft_kill_on_budget_breach"
    source {
      storage_source {
        bucket = google_storage_bucket.function_source.name
        object = google_storage_bucket_object.function_zip.name
      }
    }
  }

  service_config {
    ingress_settings      = "ALLOW_INTERNAL_ONLY" # Pub/Sub-triggered only; no public HTTP entry point
    max_instance_count    = 1
    available_memory      = "256M"
    timeout_seconds       = 60
    service_account_email = google_service_account.soft_kill_fn.email
    environment_variables = {
      PROJECT_ID       = var.project_id
      REGION           = var.region
      GUARDED_SERVICES = join(",", var.guarded_cloud_run_services)
      KILL_RATIO       = tostring(var.soft_kill_threshold_ratio)
    }
  }

  event_trigger {
    trigger_region = var.region
    event_type     = "google.cloud.pubsub.topic.v1.messagePublished"
    pubsub_topic   = google_pubsub_topic.budget_alerts.id
    retry_policy   = "RETRY_POLICY_RETRY"
  }
}
