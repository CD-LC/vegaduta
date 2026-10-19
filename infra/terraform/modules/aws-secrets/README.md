# modules/aws-secrets (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

AWS counterpart of `azure-secrets` / `gcp-secrets`: one Secrets Manager secret per id, named
`<name_prefix>/<id>`, 30-day recovery window, optional customer-managed KMS key.

- `secrets` (ids) and `secret_values` (sensitive map) are separate because Terraform cannot `for_each` over a map
  that holds sensitive values.
- Values written through Terraform are in its state. For the most sensitive ones (`APP_ENCRYPTION_KEY`) set
  `create_values = false` and put the value yourself (`aws secretsmanager put-secret-value`), so it never enters
  state. `APP_ENCRYPTION_KEY` must be identical for every core shape: base64 of 32 bytes.
- `reader_role_arns` adds a resource policy allowing those roles to read, and `reader_policy_json` is an IAM
  policy document for the role that syncs the secrets (External Secrets Operator or the Secrets Store CSI
  driver). The module attaches nothing.

The charts only need Kubernetes Secret names (`existingSecret`); getting the values from Secrets Manager into
those Secrets is done by the sync tool you choose.

## Wiring into the chart

Each secret feeds one chart `existingSecret` (for example `global.postgresql.existingSecret`,
`global.valkey.existingSecret`, the `APP_ENCRYPTION_KEY` secret). The sync tool (External Secrets Operator or the
Secrets Store CSI driver) creates the Kubernetes Secret with the name the chart expects. Environment variables
read from a Secret are fixed at pod start: restart the pods after you change a value.
