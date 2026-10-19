# vegaduta-lib (Helm library chart)

Shared templates for every VegaDuta chart. Nothing here renders on its own.

| File | Provides |
|---|---|
| `templates/_helpers.tpl` | names (`<release>-<chart>`, sibling lookup via `componentName`), standard labels, image reference (registry/tag/digest, no `:latest`), env list, security contexts with secure defaults, probes, topology spread, public host/URL derivation, stable generated secrets |
| `templates/_workload.tpl` | pod spec, Deployment, Service, ServiceAccount (token automount off), HPA, PDB, PVC, ServiceMonitor, `helm test` pod, service/sandbox guards |
| `templates/_secretanchor.tpl` | Ingress, Gateway API HTTPRoute, NetworkPolicy (file name kept from the pre-OSS layout) |
| `templates/_core.tpl` | dependency resolution (bundled vs external PostgreSQL, Valkey, S3/local storage, OIDC), the core-family env and Secret references, core Deployment, the migrate hook Job |

## Values contract of a component chart

`image`, `replicaCount`, `strategy`, `containerPort`, `extraPorts`, `service`, `serviceAccount`,
`podSecurityContext`, `securityContext`, `resources` (requests and limits required), `probes`, `lifecycle`,
`emptyDirs`, `persistence`, `extraVolumes`, `extraVolumeMounts`, `env`, `secretEnv`, `extraEnv`, `envFrom`,
`initContainers`, `hpa`, `pdb`, `topologySpread`, `ingress` (`enabled`, `hostKey`, `extraHosts`,
`annotations`, `paths`), `networkPolicy` (`enabled`, `allowFrom`, `allowEgressTo`, `allowDns`,
`allowExternalEgress`, `externalEgressPorts`, `extraIngress`, `extraEgress`), `serviceMonitor`, `tests`,
`nodeSelector`, `tolerations`, `affinity`, `priorityClassName`, `runtimeClassName`, `podAnnotations`,
`podLabels`, `imagePullSecrets`, plus the shared `global` block documented in `charts/vegaduta/values.yaml`.

Security defaults are filled in only where a chart does not set the key, so an exception is always
explicit in that chart's `values.yaml` (for example `readOnlyRootFilesystem: false` for Keycloak).
