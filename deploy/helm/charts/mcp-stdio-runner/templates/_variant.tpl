{{/* browser=true selects the mcp-stdio-runner-browser image (and at least 2Gi of memory when the
limit is still the 1Gi default). Idempotent: included by every template that renders the image. */}}
{{- define "mcp-stdio-runner.variant" -}}
{{- if .Values.browser -}}
{{- if not (hasSuffix "-browser" .Values.image.repository) -}}
{{- $_ := set .Values.image "repository" (printf "%s-browser" .Values.image.repository) -}}
{{- end -}}
{{- $lim := (.Values.resources | default dict).limits | default dict -}}
{{- if eq (toString $lim.memory) "1Gi" -}}
{{- $_ := set $lim "memory" "2Gi" -}}
{{- end -}}
{{- end -}}
{{- end -}}
