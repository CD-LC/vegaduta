# AWS equivalent of azure-secrets / gcp-secrets: Secrets Manager secrets for
# the values the charts read from Kubernetes Secrets (APP_ENCRYPTION_KEY,
# client secrets, ...). Get them into the cluster with External Secrets
# Operator or the Secrets Store CSI driver; the charts only need
# existingSecret names.

resource "aws_secretsmanager_secret" "this" {
  for_each                = var.secrets
  name                    = "${var.name_prefix}/${each.key}"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = var.tags

  lifecycle {
    precondition {
      condition     = !var.create_values || contains(keys(var.secret_values), each.key)
      error_message = "secret_values is missing a value for one of var.secrets (or set create_values = false)."
    }
  }
}

resource "aws_secretsmanager_secret_version" "this" {
  for_each      = var.create_values ? var.secrets : toset([])
  secret_id     = aws_secretsmanager_secret.this[each.key].id
  secret_string = var.secret_values[each.key]
}

data "aws_iam_policy_document" "read" {
  count = length(var.reader_role_arns) > 0 ? 1 : 0

  statement {
    sid       = "ReadVegaDutaSecrets"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [for s in aws_secretsmanager_secret.this : s.arn]
  }

  dynamic "statement" {
    for_each = var.kms_key_arn == null ? [] : [1]
    content {
      sid       = "DecryptWithKey"
      actions   = ["kms:Decrypt"]
      resources = [var.kms_key_arn]
    }
  }
}

# Resource policy: the listed roles may read, nobody is otherwise opened up
# (IAM in the account still applies as usual).
data "aws_iam_policy_document" "resource" {
  for_each = length(var.reader_role_arns) > 0 ? var.secrets : toset([])

  statement {
    sid       = "AllowReaders"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = var.reader_role_arns
    }
  }
}

resource "aws_secretsmanager_secret_policy" "this" {
  for_each   = length(var.reader_role_arns) > 0 ? var.secrets : toset([])
  secret_arn = aws_secretsmanager_secret.this[each.key].arn
  policy     = data.aws_iam_policy_document.resource[each.key].json
}
