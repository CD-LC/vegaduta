output "instance_connection_name" {
  value = google_sql_database_instance.this.connection_name
}

output "private_ip_address" {
  value = google_sql_database_instance.this.private_ip_address
}

output "database_name" {
  value = google_sql_database.this.name
}

output "db_user_name" {
  value = google_sql_user.app.name
}

output "db_user_password" {
  value     = random_password.db_user.result
  sensitive = true
}
