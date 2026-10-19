output "vpc_id" {
  value = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "private_subnet_ids" {
  description = "Use for EKS nodes, RDS and ElastiCache subnet groups."
  value       = aws_subnet.private[*].id
}

output "public_subnet_ids" {
  description = "Load balancers and NAT gateways."
  value       = aws_subnet.public[*].id
}

output "availability_zones" {
  value = local.azs
}

output "nat_public_ips" {
  description = "Egress IPs, for allow-listing at third parties."
  value       = aws_eip.nat[*].public_ip
}
