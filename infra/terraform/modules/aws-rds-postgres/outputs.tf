output "address" {
  description = "global.postgresql.host"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "global.postgresql.port"
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "global.postgresql.database"
  value       = aws_db_instance.this.db_name
}

output "username" {
  description = "global.postgresql.username"
  value       = aws_db_instance.this.username
}

output "master_user_secret_arn" {
  description = "Secrets Manager secret (JSON with username and password) holding the master password. Sync the password into the Kubernetes Secret named by global.postgresql.existingSecret (key: password), for example with External Secrets. Rotates only when rds_managed_master_password = true."
  value       = local.master_secret_arn
}

output "resource_id" {
  description = "DbiResourceId, used in rds-db:connect ARNs (aws-irsa-core.db_resource_id)."
  value       = aws_db_instance.this.resource_id
}

output "security_group_id" {
  value = aws_security_group.this.id
}

output "helm_values" {
  description = "Values to merge into the vegaduta chart (the Secret named existingSecret must be created separately)."
  value = {
    global = {
      postgresql = {
        host    = aws_db_instance.this.address
        port    = aws_db_instance.this.port
        sslMode = "require"
        age     = false # Apache AGE is not available on RDS
      }
    }
  }
}
