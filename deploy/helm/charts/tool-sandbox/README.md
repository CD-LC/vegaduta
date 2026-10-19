# tool-sandbox

Executes tenant-authored code tools (`services/tool-sandbox`, port 8095). Optional
(`global.components.toolSandbox`). Because the code is untrusted the chart refuses to render unless:
a VM-isolating `runtimeClassName` is set (or `requireRuntimeClass=false` for local evaluation), the pod
receives no secret other than `TOOL_SANDBOX_INTERNAL_TOKEN`, carries no cloud identity or SA token, and
its NetworkPolicy allows ingress from core only and no egress at all. Schedule it on a dedicated, tainted
node pool via `nodeSelector` / `tolerations`.
