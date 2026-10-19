# modules/aws-rds-postgres (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

RDS for PostgreSQL 16.

| Area | Choice |
|---|---|
| Network | Private subnets, `publicly_accessible = false`, a security group with ingress only from `allowed_security_group_ids` / `allowed_cidr_blocks` (`0.0.0.0/0` rejected) and no egress rules. |
| Encryption | Storage always encrypted (`kms_key_arn` or the AWS-managed key). Parameter group sets `rds.force_ssl = 1`, so clients must use TLS. |
| Credentials | Default: Terraform generates the master password (`random_password`) into a Secrets Manager secret and **never rotates it**. It is in Terraform state, so use an encrypted remote backend. `rds_managed_master_password = true` switches to an RDS-managed secret (not in state) that RDS **rotates every 7 days**; see below. |
| IAM auth | `iam_database_authentication_enabled` (default off) switches on the server side only. Core does not mint IAM auth tokens yet ([ADR](../../../../docs/architecture/ADR-cloud-portability.md), gap 2), so the app still uses the password. |
| Safety | `deletion_protection = true`, final snapshot on destroy, 7 days of backups, Multi-AZ, auto minor upgrades, Performance Insights, log exports. |

## Extensions: read this

- **pgvector: available.** The `vector` extension ships with RDS PostgreSQL 16; core's migrations run
  `CREATE EXTENSION vector`, which the master user may do.
- **Apache AGE: NOT available on RDS or Aurora.** Install the chart with **`global.postgresql.age=false`**
  (the output `helm_values` already sets it). Graph memory is then disabled; everything else works.

## Wiring into the chart

```yaml
global:
  postgresql:
    host: <output address>
    port: 5432
    database: vegaduta
    username: vegaduta
    sslMode: require            # verify-full needs the RDS CA bundle in the pod; not set up here
    age: false                  # no AGE on RDS
    existingSecret: vegaduta-postgres   # a Secret you create with key "password"
    poolMode: session
```

`master_user_secret_arn` is a Secrets Manager JSON secret (`username`, `password`). Sync the `password`
property into the Kubernetes Secret, for example with External Secrets Operator (an example in this repo is
still open, see the ADR).

**Rotation.** The chart passes the password to core as an environment variable (`secretKeyRef`), and an
environment variable is fixed when the pod starts: updating the Kubernetes Secret does not change running pods.
That is why the default does not rotate. With `rds_managed_master_password = true` RDS rotates the password
(every 7 days by default) and nothing in this repository restarts the pods, so new database connections start
failing after each rotation until you restart them (a Secret-watching restarter such as Reloader works). To
rotate by hand in the default mode: `tofu taint 'module.<name>.random_password.master[0]'`, apply, re-sync the
Kubernetes Secret, restart the core pods.

**Replacement.** The final snapshot is named `<name_prefix>-postgres-final` unless you set
`final_snapshot_identifier`; a second destroy collides with the first snapshot until you delete it or rename.

If you put RDS Proxy or PgBouncer in front, it must use **session** pooling (tenants are schemas selected with
`SET search_path`).
