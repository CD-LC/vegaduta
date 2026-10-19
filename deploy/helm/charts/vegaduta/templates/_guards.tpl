{{/* The optional pools of the jobs shape: switch under global.components, subchart name, default Hikari max.
  Mirrors vegaduta-lib's coreEnv. Rendered as JSON so the guards below share one list. */}}
{{- define "vegaduta.corePools" -}}
{{- toJson (list
  (dict "flag" "coreIngest" "chart" "core-ingest" "hikari" 6)
  (dict "flag" "coreWebhooks" "chart" "core-webhooks" "hikari" 6)
  (dict "flag" "coreIntake" "chart" "core-intake" "hikari" 8)
  (dict "flag" "coreBilling" "chart" "core-billing" "hikari" 8)) -}}
{{- end -}}

{{/* Umbrella-level guards: things only the parent can see (every subchart's values). */}}
{{- define "vegaduta.guards" -}}
{{- $g := .Values.global -}}
{{- if not $g.domain -}}
{{- fail "global.domain is required (e.g. --set global.domain=example.com): every public URL, the OIDC issuer and the CORS origins derive from it" -}}
{{- end -}}

{{- /* Every pool (ingest, webhooks, intake, billing) is a pool OF the jobs shape: the API's shape-api profile
  hands the jobs family everything, and the main jobs pod hands each enabled pool's jobs on to that pool.
  Without core-jobs the agent jobs, workflow schedules and the other pools' work would have no owner at all. */ -}}
{{- $pools := include "vegaduta.corePools" . | fromJsonArray -}}
{{- $enabledPools := list -}}
{{- range $pool := $pools -}}
{{- if get $g.components $pool.flag -}}
{{- $enabledPools = append $enabledPools $pool -}}
{{- if not $g.components.coreJobs -}}
{{- fail (printf "global.components.%s=true needs global.components.coreJobs=true: %s is a pool of the jobs shape, and without core-jobs nothing would run the other background jobs" $pool.flag $pool.chart) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- /* bundled XOR external, per dependency */ -}}
{{- $deps := list
  (list "postgres" .Values.postgres.enabled "global.postgresql.host" $g.postgresql.host)
  (list "valkey" .Values.valkey.enabled "global.valkey.host" $g.valkey.host)
  (list "keycloak" .Values.keycloak.enabled "global.oidc.issuerUrl" $g.oidc.issuerUrl) -}}
{{- if eq ($g.objectStorage.type | default "s3") "local" -}}
{{- if or .Values.seaweedfs.enabled $g.objectStorage.endpoint -}}
{{- fail "global.objectStorage.type=local stores files on a PVC: set seaweedfs.enabled=false and leave global.objectStorage.endpoint empty" -}}
{{- end -}}
{{- else if eq ($g.objectStorage.type | default "s3") "azure-blob" -}}
{{- if or .Values.seaweedfs.enabled $g.objectStorage.endpoint -}}
{{- fail "global.objectStorage.type=azure-blob uses Azure Blob Storage: set seaweedfs.enabled=false and leave global.objectStorage.endpoint (the S3 endpoint) empty; the account endpoint is global.objectStorage.azure.endpoint" -}}
{{- end -}}
{{- if and (eq ($g.objectStorage.credentials | default "static") "default") (not (($g.objectStorage.azure | default dict).endpoint)) -}}
{{- fail "global.objectStorage.type=azure-blob with workload identity (credentials=default) needs global.objectStorage.azure.endpoint (https://<account>.blob.core.windows.net)" -}}
{{- end -}}
{{- else if ne ($g.objectStorage.type | default "s3") "s3" -}}
{{- fail (printf "global.objectStorage.type must be s3, azure-blob or local, got %q" $g.objectStorage.type) -}}
{{- else -}}
{{- $deps = append $deps (list "seaweedfs" .Values.seaweedfs.enabled "global.objectStorage.endpoint" $g.objectStorage.endpoint) -}}
{{- end -}}
{{- range $deps -}}
{{- $name := index . 0 -}}{{- $on := index . 1 -}}{{- $key := index . 2 -}}{{- $ext := index . 3 -}}
{{- if and $on $ext -}}
{{- fail (printf "%s.enabled=true but %s=%q is set: choose ONE - the bundled %s or your own (then set %s.enabled=false)" $name $key $ext $name $name) -}}
{{- end -}}
{{- if and (not $on) (not $ext) -}}
{{- fail (printf "%s.enabled=false and %s is empty: set %s to your own service, or re-enable the bundled %s" $name $key $key $name) -}}
{{- end -}}
{{- end -}}

{{- /* managed-service flags that only make sense in combination */ -}}
{{- if and $g.valkey.tls (not $g.valkey.host) -}}
{{- fail "global.valkey.tls=true needs an external global.valkey.host: the bundled Valkey speaks plain TCP, so core would fail every connection" -}}
{{- end -}}
{{- $kf := $g.kafka | default dict -}}
{{- if and $kf.saslMechanism (not (hasPrefix "SASL_" ($kf.securityProtocol | default ""))) -}}
{{- fail "global.kafka.saslMechanism needs global.kafka.securityProtocol SASL_PLAINTEXT or SASL_SSL; it is ignored otherwise" -}}
{{- end -}}
{{- if and (hasPrefix "SASL_" ($kf.securityProtocol | default "")) (not $kf.existingSecret) (not ($g.core.extraEnvFromSecrets)) -}}
{{- fail "global.kafka.securityProtocol=SASL_* needs the JAAS line: set global.kafka.existingSecret (key global.kafka.saslJaasConfigKey), or provide SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG through global.core.extraEnvFromSecrets" -}}
{{- end -}}

{{- /* core, core-jobs, every pool, core-worker and the migrate Job: same encryption-key Secret */ -}}
{{- $globalSecret := ($g.core.appSecret | default dict).existingSecret | default (printf "%s-core-app" .Release.Name) -}}
{{- $effective := dict -}}
{{- $secretMembers := list "core" "core-jobs" "core-worker" -}}
{{- range $pool := $pools }}{{ $secretMembers = append $secretMembers $pool.chart }}{{ end -}}
{{- range $c := $secretMembers -}}
{{- $cv := index $.Values $c | default dict -}}
{{- $_ := set $effective $c ((($cv.appSecret) | default dict).existingSecret | default $globalSecret) -}}
{{- end -}}
{{- if gt (len (values $effective | uniq)) 1 -}}
{{- fail (printf "core, core-jobs and core-worker must read APP_ENCRYPTION_KEY from ONE Secret, got %v. Set only global.core.appSecret.existingSecret. A different key makes every stored provider key and channel secret undecryptable." $effective) -}}
{{- end -}}
{{- if and $g.postgresql.host (not $g.postgresql.existingSecret) (eq ($g.postgresql.auth | default "password") "password") -}}
{{- fail "global.postgresql.existingSecret is required when global.postgresql.host points at an external database (Secret with the password under global.postgresql.passwordKey)" -}}
{{- end -}}
{{- if and $g.postgresql.host .Values.keycloak.enabled (not .Values.keycloak.database.existingSecret) -}}
{{- fail "keycloak.database.existingSecret is required with an external PostgreSQL: the bundled Keycloak needs its own database (keycloak.database.name) and a Secret with that user's password" -}}
{{- end -}}
{{- if and $g.objectStorage.endpoint (not $g.objectStorage.existingSecret) (ne ($g.objectStorage.credentials | default "static") "default") -}}
{{- fail "global.objectStorage.existingSecret is required with an external S3 endpoint (keys global.objectStorage.accessKeyIdKey / secretAccessKeyKey)" -}}
{{- end -}}
{{- if and $g.oidc.issuerUrl (not $g.oidc.existingSecret) -}}
{{- fail "global.oidc.existingSecret is required with an external issuer (Secret with the confidential core client secret under global.oidc.clientSecretKey)" -}}
{{- end -}}
{{- if and $g.gateway.enabled (not $g.gateway.parentRefs) -}}
{{- fail "global.gateway.parentRefs is required when global.gateway.enabled (e.g. [{name: public, namespace: gateway-system}])" -}}
{{- end -}}

{{- /* core, core-jobs, core-worker: same build (tag / digest) */ -}}
{{- if not .Values.allowCoreImageSkew -}}
{{- $ref := dict -}}
{{- $members := list "core" -}}
{{- if $g.components.coreJobs }}{{ $members = append $members "core-jobs" }}{{ end -}}
{{- range $pool := $enabledPools }}{{ $members = append $members $pool.chart }}{{ end -}}
{{- if $g.temporal.enabled }}{{ $members = append $members "core-worker" }}{{ end -}}
{{- range $c := $members -}}
{{- $img := (index $.Values $c | default dict).image | default dict -}}
{{- $_ := set $ref $c (printf "tag=%s digest-set=%v" ($img.tag | default $g.imageTag | default $.Chart.AppVersion | toString) (not (empty $img.digest))) -}}
{{- end -}}
{{- if gt (len (values $ref | uniq)) 1 -}}
{{- fail (printf "core-family images differ %v: core, core-jobs and core-worker must be built from the same commit (same tag). Use global.imageTag, or set allowCoreImageSkew=true only with a reviewed reason." $ref) -}}
{{- end -}}
{{- end -}}

{{- /* Worst-case database connections vs the server limit. Every core pod holds up to its Hikari pool
  (api 12, or 15 without core-jobs; jobs 10; worker 8; the migrate hook Job 10), so autoscaling the API
  multiplies the demand. Checked only when the limit is known: the bundled cluster
  (postgres.parameters.max_connections, chart default 200) or global.postgresql.maxConnections for an
  external server or pooler. Defaults below mirror the subcharts' (a parent cannot always see them). */ -}}
{{- $pgMax := 0 -}}
{{- if $g.postgresql.maxConnections }}{{ $pgMax = int $g.postgresql.maxConnections }}
{{- else if .Values.postgres.enabled }}{{ $pgMax = int (dig "parameters" "max_connections" 200 (.Values.postgres | default dict)) }}{{ end -}}
{{- if gt $pgMax 0 -}}
{{- $core := .Values.core | default dict -}}
{{- $apiPods := int ($core.replicaCount | default 1) -}}
{{- if ($core.hpa | default dict).enabled }}{{ $apiPods = max $apiPods (int (($core.hpa).maxReplicas | default 4)) }}{{ end -}}
{{- if ($core.keda | default dict).enabled }}{{ $apiPods = max $apiPods (int (($core.keda).maxReplicas | default 4)) }}{{ end -}}
{{- $apiPool := int (($core.hikari | default dict).maxPoolSize | default (ternary 12 15 (not (empty $g.components.coreJobs)))) -}}
{{- $demand := mul $apiPods $apiPool -}}
{{- $jobs := index .Values "core-jobs" | default dict -}}
{{- if $g.components.coreJobs }}{{ $demand = add $demand (mul (int ($jobs.replicaCount | default 1)) (int (($jobs.hikari | default dict).maxPoolSize | default 10))) }}{{ end -}}
{{- range $pool := $enabledPools -}}
{{- $pv := index $.Values $pool.chart | default dict -}}
{{- $demand = add $demand (mul (int ($pv.replicaCount | default 1)) (int (($pv.hikari | default dict).maxPoolSize | default $pool.hikari))) -}}
{{- end -}}
{{- $worker := index .Values "core-worker" | default dict -}}
{{- if $g.temporal.enabled }}{{ $demand = add $demand (mul (int ($worker.replicaCount | default 1)) (int (($worker.hikari | default dict).maxPoolSize | default 8))) }}{{ end -}}
{{- /* the migrate Job runs while the old pods still hold theirs; the reserve covers Keycloak, monitoring and superuser access */ -}}
{{- $demand = add $demand 10 -}}
{{- $reserve := 20 -}}
{{- if gt $demand (sub $pgMax $reserve) -}}
{{- fail (printf "worst-case database connections %d (API up to %d pods x %d, plus jobs, any pools, worker and the migrate Job) exceed the server limit %d minus a reserve of %d. Raise postgres.parameters.max_connections (bundled) or global.postgresql.maxConnections (external; put a SESSION-mode pooler in front, never transaction mode), lower core.hpa.maxReplicas, or lower core.hikari.maxPoolSize" (int $demand) $apiPods $apiPool $pgMax $reserve) -}}
{{- end -}}
{{- end -}}
{{- end -}}
