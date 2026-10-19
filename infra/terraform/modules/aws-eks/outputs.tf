output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority_data" {
  value = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "EKS-managed security group attached to the nodes (and control plane ENIs). Pass it as allowed_security_group_ids to aws-rds-postgres and aws-elasticache."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "oidc_provider_arn" {
  description = "Pass to aws-irsa-core.oidc_provider_arn."
  value       = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_url" {
  description = "Issuer URL without the https:// prefix; pass to aws-irsa-core.oidc_provider_url."
  value       = local.oidc_host
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}
