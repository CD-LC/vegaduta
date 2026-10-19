# ElastiCache replication group (Valkey or Redis), cluster mode disabled,
# TLS in transit (the app connects with REDIS_SSL / global.valkey.tls=true),
# encrypted at rest, password (AUTH token) required.

locals {
  name       = "${var.name_prefix}-cache"
  family     = "${var.engine}${split(".", var.engine_version)[0]}"
  auth_token = coalesce(var.auth_token, try(random_password.auth[0].result, null))
  multi_az   = var.num_cache_clusters > 1
}

# URL-safe characters only: ElastiCache restricts the token alphabet.
resource "random_password" "auth" {
  count            = var.auth_token == null ? 1 : 0
  length           = 40
  special          = true
  override_special = "-"
}

resource "aws_secretsmanager_secret" "auth" {
  name                    = "${var.name_prefix}/elasticache/auth-token"
  description             = "ElastiCache AUTH token for ${local.name}"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 7
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "auth" {
  secret_id = aws_secretsmanager_secret.auth.id
  secret_string = jsonencode({
    password = local.auth_token
  })
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  # name_prefix, not name: with create_before_destroy a replacement is created
  # while the old group still exists, so a fixed name would collide.
  name_prefix = "${local.name}-"
  description = "ElastiCache for VegaDuta; ingress only from listed security groups"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = local.name })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_sg" {
  count                        = length(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = var.allowed_security_group_ids[count.index]
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "Redis protocol from workload security group"
}

resource "aws_elasticache_parameter_group" "this" {
  # This resource has no name_prefix. The name carries the family, so a family
  # change (the only replacement that matters) gets a new name: the new group
  # is created first, the replication group moves to it, then the old one is
  # deleted. Do not edit the description of a live group: that also forces a
  # replacement, under the same name.
  name        = "${local.name}-${local.family}"
  family      = local.family
  description = "VegaDuta cache parameters"
  tags        = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = local.name
  description          = "VegaDuta cache and queues"

  engine               = var.engine
  engine_version       = var.engine_version
  node_type            = var.node_type
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name

  # Cluster mode disabled: one shard, N-1 replicas.
  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = local.multi_az
  multi_az_enabled           = local.multi_az

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn
  transit_encryption_enabled = true
  auth_token                 = local.auth_token

  snapshot_retention_limit   = var.snapshot_retention_days
  maintenance_window         = var.maintenance_window
  auto_minor_version_upgrade = true
  apply_immediately          = false

  tags = var.tags
}
