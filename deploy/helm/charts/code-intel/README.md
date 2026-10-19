# code-intel

VegaDuta code-intelligence service (`services/code-intel`, port 8090). Optional
(`global.components.codeIntel`). Validates user tokens against the configured OIDC issuer; core calls it with
`CODE_INTEL_INTERNAL_TOKEN`. Keeps its index on a ReadWriteOnce PVC (`Recreate` strategy, single replica,
PVC kept on uninstall). `CODE_INTEL_RUN_MODE=docker` fails closed when no container runtime is reachable,
which is the expected state on Kubernetes until execution moves to Kubernetes Jobs.
