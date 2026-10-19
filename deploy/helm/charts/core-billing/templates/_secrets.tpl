{{/* Guards for core-billing. Secrets are resolved by vegaduta-lib (same Secrets as core). */}}
{{- define "core-billing.guards" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.leaderElection) -}}
{{- fail (printf "core-billing replicaCount=%v: core-billing is a singleton. Set leaderElection=true to run a second replica for failover (every job here holds PgAdvisoryJobLock; it adds no parallelism)." .Values.replicaCount) -}}
{{- end -}}
{{- if (.Values.hpa | default dict).enabled -}}
{{- fail "core-billing must not be autoscaled (its advisory locks serialise the work)" -}}
{{- end -}}
{{- end -}}
