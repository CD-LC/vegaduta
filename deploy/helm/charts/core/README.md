# core

VegaDuta core API (Spring Boot, `core/Dockerfile` with `MODULE=app`) plus its schema-migration hook Job.
Normally installed through the umbrella chart `charts/vegaduta`; the settings it shares with
core-jobs and core-worker (database, Valkey, S3, OIDC, Temporal, the `APP_ENCRYPTION_KEY` Secret)
come from `global` and are documented there.

- Migrations: `templates/migrate-job.yaml` runs the API image with `APP_TENANCY_MIGRATE_ONLY=true`
  as a `post-install,pre-upgrade` hook (exit 0 ok, 1 fails the release, 75 = another migrator holds
  the advisory lock and is retried). API pods then run with `APP_TENANCY_MIGRATE_ON_BOOT=false`.
- Replicas: pinned to 1 until every `replicaReadiness` flag is true (render fails otherwise). With
  `replicaCount > 1` or `hpa.enabled` the chart also sets `APP_REPLICA_MODE=multi` (and
  `APP_REPLICA_MODE_STRICT` with `replicaStrict`), puts every per-pod store in Valkey and lets
  `ReplicaModeSafetyCheck` report the rest at boot. `a2aSigningKeysPinned` is your attestation: supply
  `APP_A2A_SIGNING_*` in the app Secret.
- Probes: readiness group `/actuator/health/readiness`, startup probe allows 5 minutes.

See `deploy/helm/README.md` for install, upgrade and the values reference.
