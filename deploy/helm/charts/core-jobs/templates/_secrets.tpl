{{/* Guards for core-jobs. Secrets are resolved by vegaduta-lib (same Secrets as core). */}}
{{- define "core-jobs.guards" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.leaderElection) -}}
{{- fail (printf "core-jobs replicaCount=%v: core-jobs is a singleton. Set leaderElection=true only after verifying every scheduled job holds PgAdvisoryJobLock." .Values.replicaCount) -}}
{{- end -}}
{{- if (.Values.hpa | default dict).enabled -}}
{{- fail "core-jobs must not be autoscaled (singleton)" -}}
{{- end -}}
{{- end -}}
