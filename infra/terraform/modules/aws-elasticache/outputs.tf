output "primary_endpoint_address" {
  description = "global.valkey.host"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "global.valkey.port"
  value       = aws_elasticache_replication_group.this.port
}

output "auth_secret_arn" {
  description = "Secrets Manager secret (JSON {\"password\": ...}). Sync the value into the Kubernetes Secret named by global.valkey.existingSecret (key: password)."
  value       = aws_secretsmanager_secret.auth.arn
}

output "security_group_id" {
  value = aws_security_group.this.id
}

output "helm_values" {
  description = "Values to merge into the vegaduta chart (the Secret named existingSecret must be created separately)."
  value = {
    global = {
      valkey = {
        host = aws_elasticache_replication_group.this.primary_endpoint_address
        port = aws_elasticache_replication_group.this.port
        tls  = true
      }
    }
  }
}
