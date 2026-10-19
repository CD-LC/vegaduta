output "secret_arns" {
  description = "id => secret ARN."
  value       = { for k, v in aws_secretsmanager_secret.this : k => v.arn }
}

output "secret_names" {
  description = "id => Secrets Manager name (<name_prefix>/<id>), what External Secrets references as remoteRef.key."
  value       = { for k, v in aws_secretsmanager_secret.this : k => v.name }
}

output "reader_policy_json" {
  description = "IAM policy document granting read access to all these secrets (null when reader_role_arns is empty). Attach it to the role that syncs them into the cluster."
  value       = try(data.aws_iam_policy_document.read[0].json, null)
}
