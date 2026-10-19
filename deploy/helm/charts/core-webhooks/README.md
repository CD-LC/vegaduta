# core-webhooks

The webhooks **pool** of the jobs shape. It is the `core-jobs` image (`core/Dockerfile` with `MODULE=app-jobs`)
started with the `shape-jobs-pool` profile and `APP_SCHEDULING_POOL=webhooks`, so it runs only outbound webhook delivery and the workflow event outbox
(`WebhookDeliveryRelay` and `WorkflowEventOutboxRelay`). With this pool deployed, a slow or dead customer endpoint, with its retries and backoff, never competes with agent jobs or workflow schedules, and it can be sized and restarted on its own.

Optional. Enable with `global.components.coreWebhooks=true` (it needs `coreJobs`). The main `core-jobs` Deployment
then runs with `APP_SCHEDULING_OFFLOADED_POOLS` naming this pool, which leaves exactly these jobs to it. Pools
split which pod runs a job, never what runs: `JobsPoolsCoverEveryJobTest` fails the build if any combination of
pools would drop one. With the switch off (the default) the main jobs pod runs them as before.

Limits worth knowing: every job here holds a Postgres advisory lock, so a second replica gives failover
(`leaderElection=true`), not parallelism; it is not autoscaled; and the resource defaults are estimates, not
measurements. Same `global` wiring and the same `APP_ENCRYPTION_KEY` Secret as `core`; its image tag must match
the API's (the umbrella fails the render otherwise). Design: `docs/architecture/ADR-0014-horizontal-scaling.md`.

This pod does not inherit `core-jobs.env`, `extraEnv` or `secretEnv`, only `global.core.*` and this chart's own values; a setting put on `core-jobs` is absent here for the jobs that move.

**Network egress (NetworkPolicy).** This pool runs no agent turn, so it is allowed to reach `postgres`, `valkey`, `keycloak`, `seaweedfs`, `core` and `mcp-stdio-runner`, DNS, the configured integrations (Kafka for the outbox relay, via `integrationEgress`) and the internet minus private ranges on `externalEgressPorts` (the customer webhook endpoints), and nothing else. It cannot reach `sidecar`, `code-intel` or `tool-sandbox`: a compromised or SSRF-steered delivery thread should not be able to call the tool executors. `core-intake` is the pool that runs agent turns and keeps the full list. `mcp-stdio-runner` stays because every core-family pod reconnects the tenants' enabled MCP servers at boot and writes their connection status; blocking it would mark those servers as errored for everyone. Skipping that boot reconnect in the pools that run no agent turn would allow removing it too. Not changed: the pod still mounts the whole core Secret (`envFrom`), because splitting it per pool needs a per-pool Secret contract; it only uses the encryption key, database, Valkey, Keycloak and storage credentials.
