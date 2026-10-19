# vegaduta

Umbrella chart for the VegaDuta agentic-AI platform.

```bash
helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta \
  --namespace vegaduta --create-namespace --set global.domain=example.com
```

Default install: core API (+ migration hook Job), core-jobs, web (tenant UI and the platform-admin console at /platform-admin), the ML sidecar, and the bundled
PostgreSQL (CloudNativePG, operator required), Valkey, Keycloak and SeaweedFS. Every bundled dependency can be
replaced by your own; optional components are switched on under `global.components`.

- Full guide (quickstart, production checklist, bring-your-own matrix, upgrade, backup, uninstall):
  [`deploy/helm/README.md`](../../README.md)
- Values reference: [`VALUES.md`](VALUES.md)
