# modules/aws-s3 (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

The application bucket.

- All four public-access blocks on, ACLs disabled (`BucketOwnerEnforced`), SSE-S3 by default or SSE-KMS with
  `kms_key_arn`.
- Bucket policy: denies any request without TLS, and denies an explicit list of object-level actions (get, put,
  tag, ACL, retention, restore, replicate, delete, list, multipart; see `main.tf`) to every principal except
  `allowed_principal_arns`. Administrators can still edit the bucket configuration (the deny covers data
  actions only), so Terraform keeps working. This is a guard rail, not a hard boundary: an administrator can
  change the policy and then read objects, and S3 actions missing from the list stay allowed. The Terraform
  principal is denied object listing and deletion as well, so `force_destroy = true` only works if it is in
  `allowed_principal_arns`.
- Lifecycle: aborts incomplete multipart uploads after 7 days. Versioning is optional (`versioning_enabled`) and
  then expires noncurrent versions after 30 days. No automatic expiry of live objects unless you add
  `expiration_rules`.
- **Core's demo-image lifecycle rule is not installed.** At startup core calls `PutBucketLifecycleConfiguration`
  to expire `demo-images/` objects (public demo sandbox only). The IRSA role deliberately does not have
  `s3:PutLifecycleConfiguration`: that call replaces the whole lifecycle configuration and would wipe the rules
  managed here. Core logs one WARN per start and carries on. If you run the demo sandbox, add
  `expiration_rules = { demo_images = { prefix = "demo-images/", days = 5 } }` (match `app.demo.lifetime-days`).
- Break-the-cycle note: pass the IRSA role ARN as a string built from the role name
  (`arn:aws:iam::<account-id>:role/vegaduta-core`); `aws-irsa-core` takes the bucket name, so neither module
  depends on the other. The policy condition does not require the role to exist yet.

## Wiring into the chart

```yaml
global:
  objectStorage:
    type: s3
    endpoint: https://s3.<region>.amazonaws.com
    region: <region>
    bucket: <bucket_name>
    pathStyleAccess: false
    credentials: default     # AWS SDK default chain: IRSA, no keys in the cluster
```

The output `helm_values` contains exactly this. Also set `seaweedfs.enabled=false`.
The chart annotations for IRSA come from `aws-irsa-core`.
