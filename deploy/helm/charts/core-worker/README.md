# core-worker

Temporal worker deployable of VegaDuta core (`core/Dockerfile` with `MODULE=app-worker`, image `core-worker`).
Deployed by the umbrella only when `global.temporal.enabled=true`; the chart itself refuses to render
otherwise. While Temporal is on, the API and core-jobs run with
`APP_WORKFLOW_TEMPORAL_WORKER_ENABLED=false` and only this deployable polls the task queue.
Uses the same `global` wiring and `APP_ENCRYPTION_KEY` Secret as `core`; its image tag must match the API's.
Bring your own Temporal (Temporal Cloud, or the official `temporalio/helm-charts` with its optional
third-party subcharts disabled) and set `global.temporal.target`.
