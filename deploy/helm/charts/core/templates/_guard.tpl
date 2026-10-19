{{/* Render-time guards for the core API chart. */}}
{{- define "core.guards" -}}
{{- /* Zero-downtime: maxUnavailable must stay 0 on the API rollout. */ -}}
{{- $ru := (.Values.strategy | default dict).rollingUpdate | default dict -}}
{{- if and (hasKey $ru "maxUnavailable") (not (has (toString $ru.maxUnavailable) (list "0" "0%"))) (not .Values.allowNonZeroUnavailable) -}}
{{- fail (printf "core strategy.rollingUpdate.maxUnavailable must be 0 (zero-downtime rollout), got %v; set allowNonZeroUnavailable=true only with a reviewed reason" $ru.maxUnavailable) -}}
{{- end -}}
{{- /* More than one API replica is only safe once every in-memory store is shared. */ -}}
{{- $multi := include "vegaduta-lib.multiReplica" . -}}
{{- if $multi -}}
{{- $missing := list -}}
{{- range $k, $val := (.Values.replicaReadiness | default dict) -}}
{{- if not $val }}{{ $missing = append $missing $k }}{{ end -}}
{{- end -}}
{{- if $missing -}}
{{- fail (printf "core replicaCount=%v / hpa.enabled=%v / keda.enabled=%v needs every replicaReadiness flag true (shared stores in Valkey, pinned A2A signing keys; see docs/architecture/ADR-0014-horizontal-scaling.md). Still false: %s" .Values.replicaCount (.Values.hpa | default dict).enabled (.Values.keda | default dict).enabled (join ", " (sortAlpha $missing))) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* global.core.mcpToolchain=full: every pod of this chart (API, migrate Job, test pod) uses the
core-full image (core/Dockerfile MCP_TOOLCHAIN=full). Idempotent; included by each template that
renders the image so the result never depends on template order. core-jobs/core-worker stay slim. */}}
{{- define "core.imageVariant" -}}
{{- $gc := (.Values.global | default dict).core | default dict -}}
{{- if and (eq ($gc.mcpToolchain | default "none") "full") (eq .Values.image.repository "core") -}}
{{- $_ := set .Values.image "repository" "core-full" -}}
{{- end -}}
{{- end -}}
