{{- define "mcp-stdio-runner.guard" -}}
{{- include "vegaduta-lib.sandboxGuard" . -}}
{{- $n := .Values.networkPolicy | default dict -}}
{{- if or (not $n.enabled) $n.allowEgressTo $n.globalExtraEgress -}}
{{- fail "mcp-stdio-runner: its NetworkPolicy must stay enabled and must not allow egress to cluster workloads (internet 80/443 + DNS only)" -}}
{{- end -}}
{{- end -}}
