# RDS for PostgreSQL 16: private, encrypted, TLS-only, deletion-protected.
#
# Extensions: `vector` (pgvector) is available on RDS; run
# `CREATE EXTENSION IF NOT EXISTS vector;` (core's migrations do). Apache AGE
# is NOT available on RDS or Aurora, so the Helm chart must be installed with
# global.postgresql.age=false (graph memory is then off).

locals {
  name = "${var.name_prefix}-postgres"
  # Which secret holds the master password (a JSON secret with username and password).
  master_secret_arn = (
    var.rds_managed_master_password
    ? aws_db_instance.this.master_user_secret[0].secret_arn
    : aws_secretsmanager_secret.master[0].arn
  )
}

# Default: the password is generated here, stored in Secrets Manager and NEVER
# rotated. The chart injects it as an environment variable (secretKeyRef),
# which is fixed when a pod starts, so a rotation would leave running pods with
# a stale password. The cost: the value is in Terraform state (use an encrypted
# remote backend). Rotate on your own schedule: taint random_password.master,
# apply, sync the Kubernetes Secret, then restart the core pods.
# RDS rejects '/', '@', '"' and spaces, so the alphabet is alphanumeric.
resource "random_password" "master" {
  count   = var.rds_managed_master_password ? 0 : 1
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "master" {
  count                   = var.rds_managed_master_password ? 0 : 1
  name                    = "${var.name_prefix}/rds/master-password"
  description             = "PostgreSQL master credentials for ${local.name} (not auto-rotated)"
  kms_key_id              = var.master_password_kms_key_arn
  recovery_window_in_days = 7
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "master" {
  count     = var.rds_managed_master_password ? 0 : 1
  secret_id = aws_secretsmanager_secret.master[0].id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master[0].result
  })
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  # name_prefix, not name: with create_before_destroy a replacement is created
  # while the old group still exists, so a fixed name would collide.
  name_prefix = "${local.name}-"
  description = "PostgreSQL for VegaDuta; ingress only from listed security groups/CIDRs"
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
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from workload security group"
}

resource "aws_vpc_security_group_ingress_rule" "from_cidr" {
  count             = length(var.allowed_cidr_blocks)
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = var.allowed_cidr_blocks[count.index]
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  description       = "PostgreSQL from private CIDR"
}

# No egress rules: the database initiates no connections.

resource "aws_db_parameter_group" "this" {
  name_prefix = "${local.name}-"
  family      = "postgres16"
  description = "VegaDuta: TLS required"

  # Refuse unencrypted connections. Clients need sslmode=require or stronger
  # (Helm: global.postgresql.sslMode).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  dynamic "parameter" {
    for_each = var.max_connections == null ? [] : [var.max_connections]
    content {
      name         = "max_connections"
      value        = parameter.value
      apply_method = "pending-reboot"
    }
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = var.database_name
  username = var.master_username
  # Default: the password comes from random_password.master (no rotation, see
  # above). With rds_managed_master_password = true RDS generates it into
  # Secrets Manager (not in state) but ROTATES it every 7 days.
  password                      = var.rds_managed_master_password ? null : random_password.master[0].result
  manage_master_user_password   = var.rds_managed_master_password ? true : null
  master_user_secret_kms_key_id = var.rds_managed_master_password ? var.master_password_kms_key_arn : null

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb == 0 ? null : var.max_allocated_storage_gb
  storage_type          = var.storage_type
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.multi_az

  iam_database_authentication_enabled = var.iam_database_authentication_enabled

  backup_retention_period   = var.backup_retention_days
  backup_window             = var.backup_window
  maintenance_window        = var.maintenance_window
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : coalesce(var.final_snapshot_identifier, "${local.name}-final")

  auto_minor_version_upgrade      = true
  performance_insights_enabled    = var.performance_insights_enabled
  performance_insights_kms_key_id = var.performance_insights_enabled ? var.kms_key_arn : null
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = var.tags
}
