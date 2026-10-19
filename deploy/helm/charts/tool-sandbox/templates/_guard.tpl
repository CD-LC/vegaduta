{{- define "tool-sandbox.guard" -}}
{{- include "vegaduta-lib.sandboxGuard" . -}}
{{- if eq (toString (.Values.env | default dict).TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP) "false" -}}
{{- fail "tool-sandbox: TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP must stay true" -}}
{{- end -}}
{{- $n := .Values.networkPolicy | default dict -}}
{{- if or (not $n.enabled) $n.allowExternalEgress $n.allowDns $n.allowEgressTo $n.extraEgress -}}
{{- fail "tool-sandbox: its NetworkPolicy must stay enabled with NO egress (no DNS, no external, no sibling egress); CoreDNS forwarding alone is an exfiltration path" -}}
{{- end -}}
{{- end -}}
