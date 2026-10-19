# core-ingest

The knowledge-ingestion **pool** of the jobs shape. It is the `core-jobs` image
(`core/Dockerfile` with `MODULE=app-jobs`) started with the `shape-jobs-ingest` profile, so it runs only
the two heavy pollers: document ingestion (`KnowledgeIngestionJobRunner`) and re-embedding
(`KnowledgeReembedJobRunner`). A large upload then never competes with agent jobs, workflow schedules or
webhook delivery for heap, CPU or database connections, and this pool can be sized and restarted on its own.

Optional. Enable with `global.components.coreIngest=true` (it needs `coreJobs`). The main `core-jobs`
Deployment then runs with `shape-jobs-core`, which leaves exactly these two pollers to this pool; the
API (`shape-api`) skips them too. Pools split which pod runs a job, never what runs:
`JobsPoolsCoverEveryJobTest` fails the build if a split would drop one.

Limits worth knowing: both pollers hold a Postgres advisory lock for their whole queue walk, so a second
replica gives failover (`leaderElection=true`), not parallel ingestion; and it is not autoscaled. Same
`global` wiring and the same `APP_ENCRYPTION_KEY` Secret as `core`; its image tag must match the API's
(the umbrella fails the render otherwise). Design: `docs/architecture/ADR-0014-horizontal-scaling.md`.
