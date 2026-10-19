{{/* Guards for core-intake. Secrets are resolved by vegaduta-lib (same Secrets as core). */}}
{{- define "core-intake.guards" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.leaderElection) -}}
{{- fail (printf "core-intake replicaCount=%v: core-intake is a singleton. Set leaderElection=true to run a second replica for failover (every job here holds PgAdvisoryJobLock; it adds no parallelism)." .Values.replicaCount) -}}
{{- end -}}
{{- if (.Values.hpa | default dict).enabled -}}
{{- fail "core-intake must not be autoscaled (its advisory locks serialise the work)" -}}
{{- end -}}
{{- end -}}
