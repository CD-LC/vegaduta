{{- define "code-intel.guard" -}}
{{- include "vegaduta-lib.serviceGuard" . -}}
{{- if eq (toString (.Values.env | default dict).CODE_INTEL_RUN_MODE) "local" -}}
{{- fail "code-intel: CODE_INTEL_RUN_MODE=local executes tenant code inside the shared pod; not allowed on a deployment" -}}
{{- end -}}
{{- end -}}
