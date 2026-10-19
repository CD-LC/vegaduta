# modules/aws-irsa-core (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

One IAM role that the core-family ServiceAccounts assume through the cluster's OIDC provider (IRSA).

Trust: `sts:AssumeRoleWithWebIdentity` from the provider in `oidc_provider_arn`, with `sub` restricted to the
listed ServiceAccounts in one namespace (exact names, no wildcards) and `aud = sts.amazonaws.com`.

Permissions (inline policy), nothing else:

- `s3:GetObject/PutObject/DeleteObject` and multipart actions on `arn:aws:s3:::<bucket>/*`; `s3:ListBucket` on the
  bucket.
- No `s3:PutLifecycleConfiguration` or `s3:CreateBucket`: core's startup attempts to create the bucket and to
  install a demo-image expiry rule are best-effort and log a WARN here. The bucket and its lifecycle are owned by
  `aws-s3`; see its README for the demo-image rule.
- `rds-db:connect` for one DB user, only with `enable_rds_iam_auth = true`. Core does not use IAM database tokens
  yet; this prepares the permission.
- `kms:Decrypt/GenerateDataKey/DescribeKey` on `kms_key_arns` (needed when the bucket uses SSE-KMS).

## Wiring into the chart

ServiceAccount names are `<release>-core`, `<release>-core-jobs`, `<release>-core-worker`; the defaults assume
the release name `vegaduta` and namespace `vegaduta`. Confirm with `kubectl get sa -n <namespace>`.

```yaml
core:
  serviceAccount:
    annotations:
      eks.amazonaws.com/role-arn: <output role_arn>
core-jobs:
  serviceAccount:
    annotations:
      eks.amazonaws.com/role-arn: <output role_arn>
core-worker:
  serviceAccount:
    annotations:
      eks.amazonaws.com/role-arn: <output role_arn>
```

(`helm_values` outputs this map.) Together with `global.objectStorage.credentials: default` the AWS SDK picks up
the web identity token; no access keys exist anywhere. The SDK credential chain itself has not been run against
real AWS from this repository.
