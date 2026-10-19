# Operations

Runbooks for running VegaDūta. Deployment-specific detail stays with the
deployment: [Compose](../../deploy/README.md) and [Helm](../../deploy/helm/README.md).

| Runbook | Where |
|---|---|
| Backup and restore | [Compose](../../deploy/README.md#backup-and-restore), [Helm](../../deploy/helm/README.md#backup-and-restore), [rollback and recovery (handbook)](../platform-documentation/deployment/rollback-recovery.html) |
| Upgrade | [Compose](../../deploy/README.md#upgrade), [Helm](../../deploy/helm/README.md#upgrade) |
| Run locally, ports, `kubectl`/`k9s` | [local-stack-guide.md](local-stack-guide.md) |
| Host behind a Cloudflare Tunnel (no open ports) | [tunnel-hosting.md](tunnel-hosting.md) |
| Rotate `APP_ENCRYPTION_KEY` | [key-rotation.md](key-rotation.md) |
| Observability, alerts | [handbook](../platform-documentation/operations/observability.html); alert rules in `deploy/prometheus/` |
| Secrets model | [handbook](../platform-documentation/security/secrets-management.html) |
| CLI: doctor, logs, secrets, status, backup, restore, upgrade | `scripts/vegaduta doctor`, `logs`, `secrets`, `status`, `backup`, `restore`, `upgrade` ([deploy/README.md](../../deploy/README.md)) |
| First platform admin | `scripts/vegaduta make-admin USERNAME_OR_EMAIL` |
| Local model catalog (Ollama) | `scripts/vegaduta models list`, `models pull TAG`, `models rm TAG`; catalog in `deploy/models/catalog.txt`. `models` and `seed-demo` exist in the bash script only; `scripts/vegaduta.ps1` has no such command (checked 2026-10-15) |
| Fictional demo data for a pilot | `scripts/vegaduta seed-demo [--tenant NAME] [--remove] [--yes]`; see [pilots](../pilots/README.md) |
| Using the console (screens, permissions) | [Console guide](../console/README.md) |

Compatibility policy: a release may add Flyway migrations and never edits
released ones (`docs/engineering/FLYWAY-VERSIONING.md`); upgrade one minor at a
time unless the release notes say otherwise, and read `CHANGELOG.md` for
`### Changed` entries that need an operator action.
