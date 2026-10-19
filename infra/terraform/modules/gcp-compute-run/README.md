# gcp-compute-run

Parametrized Cloud Run v2 module, invoked once per service from your root
module. Images must carry an explicit tag or digest; `:latest` is rejected
by variable validation (the PgBouncer and Alloy sidecar images are pinned).

## Why PgBouncer must stay in session-pooling mode

Only `core` sets `pgbouncer_sidecar = true`. `POOL_MODE=session` is hardcoded
in `main.tf`, not exposed as a variable, on purpose: transaction-pooling mode
can silently route schema-per-tenant queries to the wrong tenant's schema
(Hibernate's schema-switching assumes a stable connection for the duration
of a session, which transaction pooling breaks). Generic advice to switch to
transaction pooling once there are multiple services is wrong for this
schema-per-tenant design specifically. If Postgres connection limits
become a real constraint under load, the fix is more/bigger PgBouncer sidecars
(one per Cloud Run instance already isolates this reasonably well), not a
pooling-mode change.

## Monitoring sidecar

`monitoring_sidecar = true` (typically on `core`) adds a Grafana Alloy
container in the same revision, scraping the main container's own
`/actuator/prometheus` over `127.0.0.1` and remote-writing to a
Prometheus-compatible endpoint such as Grafana Cloud. Supply the rendered
Alloy config via `alloy_config_secret_id` (mounted at
`/etc/alloy/config.alloy`; it can read `SCRAPE_ADDRESS`, `METRICS_PATH` and
the `GRAFANA_REMOTE_WRITE_*` env vars) and the remote-write credentials via
the `grafana_remote_write_*_secret_id` inputs.
