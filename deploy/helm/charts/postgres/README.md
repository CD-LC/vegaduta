# postgres

Bundled PostgreSQL for VegaDuta, rendered as a CloudNativePG `Cluster` (plus a `Database` and a managed
role for Keycloak when the bundled Keycloak is used, and an optional `ScheduledBackup`).
Requires the CloudNativePG operator (Apache-2.0), installed separately.

The image must be CloudNativePG-compatible (based on `ghcr.io/cloudnative-pg/postgresql`) and include
pgvector and Apache AGE; the default is `ghcr.io/cd-lc/postgres:16-pgvector-age`. Both extensions are
created in the application database right after initdb.

For production you may prefer a managed PostgreSQL: disable this chart (`postgres.enabled=false`) and set
`global.postgresql.host/port/database/username/existingSecret`. The server must allow the `vector`
extension; `age` (graph memory) is optional: on servers that do not offer it (AWS RDS/Aurora, Cloud SQL,
AlloyDB) set `global.postgresql.age=false`. Any pooler in front of it must use **session** pooling.
