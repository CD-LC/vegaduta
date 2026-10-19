# deploy/helm/scripts

- `gen-docs.sh` - regenerate `charts/vegaduta/VALUES.md` and `charts/core/VALUES.md` with helm-docs (Docker).

Validation lives one level up: `../validate.sh` (lint, template, kubeconform, rule checks) and
`../test-guards.sh` (render-time guard tests).

`check-rules.sh`, `check-rules.allow`, `render-all.sh` and `test-keycloak-gate.sh` belong to the pre-OSS
Azure/AKS layout (they reference `values/{staging,prod}` and `stateful/`, which no longer apply) and are
scheduled for deletion; their checks are covered by `validate.sh` and `test-guards.sh`.
