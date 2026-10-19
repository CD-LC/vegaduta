output "budget_id" {
  value = google_billing_budget.this.id
}

output "soft_kill_function_name" {
  value = google_cloudfunctions2_function.soft_kill.name
}
