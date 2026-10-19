{{- define "sidecar.guard" -}}
{{- include "vegaduta-lib.serviceGuard" . -}}
{{- if eq (toString (.Values.env | default dict).SIDECAR_AUTH_REQUIRED) "false" -}}
{{- fail "sidecar: SIDECAR_AUTH_REQUIRED=false is a bare-local-dev escape hatch and must never be deployed" -}}
{{- end -}}
{{- end -}}
