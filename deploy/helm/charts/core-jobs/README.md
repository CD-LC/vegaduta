# core-jobs

Background-jobs deployable of VegaDuta core (`core/Dockerfile` with `MODULE=app-jobs`, image `core-jobs`).
Owns every `@Scheduled` sweep and job poller; the API runs with the `shape-api` profile while this
component is enabled (`global.components.coreJobs`). Singleton: `replicaCount` > 1 fails to render
unless `leaderElection=true`. Uses the same `global` wiring and the same `APP_ENCRYPTION_KEY` Secret as
`core`; its image tag must match the API's (the umbrella fails the render otherwise).
