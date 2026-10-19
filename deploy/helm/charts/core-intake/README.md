# core-intake

The intake **pool** of the jobs shape. It is the `core-jobs` image (`core/Dockerfile` with `MODULE=app-jobs`)
started with the `shape-jobs-pool` profile and `APP_SCHEDULING_POOL=intake`, so it runs only inbound email and X (Twitter) DM polling
(`EmailInboundPollingJob` and `XDmPollingJob`). With this pool deployed, a slow mailbox or provider API never competes with agent jobs, and intake can be restarted or sized on its own, and it can be sized and restarted on its own.

Optional. Enable with `global.components.coreIntake=true` (it needs `coreJobs`). The main `core-jobs` Deployment
then runs with `APP_SCHEDULING_OFFLOADED_POOLS` naming this pool, which leaves exactly these jobs to it. Pools
split which pod runs a job, never what runs: `JobsPoolsCoverEveryJobTest` fails the build if any combination of
pools would drop one. With the switch off (the default) the main jobs pod runs them as before.

Limits worth knowing: every job here holds a Postgres advisory lock, so a second replica gives failover
(`leaderElection=true`), not parallelism; it is not autoscaled; and the resource defaults are estimates, not
measurements. Same `global` wiring and the same `APP_ENCRYPTION_KEY` Secret as `core`; its image tag must match
the API's (the umbrella fails the render otherwise). Design: `docs/architecture/ADR-0014-horizontal-scaling.md`.

Because these pollers run whole agent turns (MCP, browser and sandbox state in this pod's JVM), the pod also runs the per-JVM sweeps listed under `app.scheduling.pool-lifecycle.intake`; the other pools run none of them. Boot listeners not in `skip-listener-classes` (for example the MCP reconnect on startup) run here too.

This pod does not inherit `core-jobs.env`, `extraEnv` or `secretEnv`, only `global.core.*` and this chart's own values; a setting put on `core-jobs` is absent here for the jobs that move.
