{{/* Guards for core-ingest. Secrets are resolved by vegaduta-lib (same Secrets as core). */}}
{{- define "core-ingest.guards" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.leaderElection) -}}
{{- fail (printf "core-ingest replicaCount=%v: core-ingest is a singleton. Set leaderElection=true to run a second replica for failover (both pollers hold PgAdvisoryJobLock; it adds no parallelism)." .Values.replicaCount) -}}
{{- end -}}
{{- if (.Values.hpa | default dict).enabled -}}
{{- fail "core-ingest must not be autoscaled (one advisory lock serialises the work)" -}}
{{- end -}}
{{- end -}}
