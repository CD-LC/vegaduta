# IAM role for the core-family Kubernetes ServiceAccounts (IRSA).
# Least privilege: object access on one bucket, optionally rds-db:connect for
# one database user, optionally use of named KMS keys. Nothing else.

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = [for sa in var.service_account_names : "system:serviceaccount:${var.namespace}:${sa}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = var.role_name
  description        = "VegaDuta core pods (IRSA)"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags

  lifecycle {
    precondition {
      condition     = !var.enable_rds_iam_auth || var.db_resource_id != null
      error_message = "enable_rds_iam_auth = true requires db_resource_id."
    }
  }
}

data "aws_partition" "current" {}

data "aws_iam_policy_document" "this" {
  statement {
    sid = "ObjectAccess"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}/*"]
  }

  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket", "s3:ListBucketMultipartUploads", "s3:GetBucketLocation"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}"]
  }

  dynamic "statement" {
    for_each = var.enable_rds_iam_auth ? [1] : []
    content {
      sid       = "RdsIamConnect"
      actions   = ["rds-db:connect"]
      resources = ["arn:${data.aws_partition.current.partition}:rds-db:*:*:dbuser:${var.db_resource_id}/${var.db_username}"]
    }
  }

  dynamic "statement" {
    for_each = length(var.kms_key_arns) > 0 ? [1] : []
    content {
      sid       = "UseKmsKeys"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
      resources = var.kms_key_arns
    }
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "vegaduta-core"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.this.json
}
