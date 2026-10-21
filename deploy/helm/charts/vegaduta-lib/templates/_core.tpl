{{/* ------------------------------------------------------------------
  Core family (core api, core-jobs, core-worker, migrate Job).

  All four boot the same Spring Boot configuration (core/app-config) and MUST
  agree on the database, Valkey, object storage, OIDC issuer and - above all -
  APP_ENCRYPTION_KEY. They therefore take every shared setting from `global`
  and ONE app Secret (global.core.appSecret.existingSecret, or the generated
  "<release>-core-app"). A different encryption key makes every stored
  provider key and channel secret undecryptable.

  Dependency resolution (empty external host => the bundled component):
    postgres    global.postgresql.host      | <release>-postgres-rw   (CloudNativePG)
    valkey      global.valkey.host          | <release>-valkey
    object S3   global.objectStorage.endpoint | http://<release>-seaweedfs:8333
    OIDC        global.oidc.issuerUrl       | https://<hosts.auth>.<domain>/realms/<realm> (bundled Keycloak)
------------------------------------------------------------------ */}}

{{- define "vegaduta-lib.g" -}}{{- toJson (.Values.global | default dict) -}}{{- end -}}

{{/* ---------- PostgreSQL ---------- */}}
{{- define "vegaduta-lib.pg.bundled" -}}
{{- if not ((.Values.global | default dict).postgresql | default dict).host }}true{{ end -}}
{{- end -}}
{{- define "vegaduta-lib.pg.host" -}}
{{- $p := (.Values.global | default dict).postgresql | default dict -}}
{{- $p.host | default (printf "%s-rw" (include "vegaduta-lib.componentName" (dict "root" . "name" "postgres"))) -}}
{{- end -}}
{{- define "vegaduta-lib.pg.port" -}}{{- ((.Values.global | default dict).postgresql | default dict).port | default 5432 -}}{{- end -}}
{{- define "vegaduta-lib.pg.database" -}}{{- ((.Values.global | default dict).postgresql | default dict).database | default "vegaduta" -}}{{- end -}}
{{- define "vegaduta-lib.pg.username" -}}{{- ((.Values.global | default dict).postgresql | default dict).username | default "vegaduta" -}}{{- end -}}
{{- define "vegaduta-lib.pg.secretName" -}}
{{- $p := (.Values.global | default dict).postgresql | default dict -}}
{{- if $p.existingSecret -}}{{- $p.existingSecret -}}
{{- else if include "vegaduta-lib.pg.bundled" . -}}{{- printf "%s-app" (include "vegaduta-lib.componentName" (dict "root" . "name" "postgres")) -}}
{{- else -}}{{- fail "global.postgresql.existingSecret is required when global.postgresql.host points at an external database (Secret with the password under global.postgresql.passwordKey)" -}}
{{- end -}}
{{- end -}}
{{- /* Database login: password (default) | aws-iam | azure-entra | gcp-iam. Token modes send no password. */ -}}
{{- define "vegaduta-lib.pg.auth" -}}{{- ((.Values.global | default dict).postgresql | default dict).auth | default "password" -}}{{- end -}}
{{- define "vegaduta-lib.pg.passwordKey" -}}{{- ((.Values.global | default dict).postgresql | default dict).passwordKey | default "password" -}}{{- end -}}

{{/* ---------- Valkey (Redis protocol) ---------- */}}
{{- define "vegaduta-lib.valkey.host" -}}
{{- $v := (.Values.global | default dict).valkey | default dict -}}
{{- $v.host | default (include "vegaduta-lib.componentName" (dict "root" . "name" "valkey")) -}}
{{- end -}}
{{- define "vegaduta-lib.valkey.port" -}}{{- ((.Values.global | default dict).valkey | default dict).port | default 6379 -}}{{- end -}}
{{/* Secret name, or "" when an external Valkey/Redis has no password. */}}
{{- define "vegaduta-lib.valkey.secretName" -}}
{{- $v := (.Values.global | default dict).valkey | default dict -}}
{{- if $v.existingSecret -}}{{- $v.existingSecret -}}
{{- else if not $v.host -}}{{- include "vegaduta-lib.componentName" (dict "root" . "name" "valkey") -}}
{{- end -}}
{{- end -}}
{{- define "vegaduta-lib.valkey.passwordKey" -}}{{- ((.Values.global | default dict).valkey | default dict).passwordKey | default "password" -}}{{- end -}}

{{/* ---------- S3-compatible object storage ---------- */}}
{{- define "vegaduta-lib.s3.endpoint" -}}
{{- $o := (.Values.global | default dict).objectStorage | default dict -}}
{{- $o.endpoint | default (printf "http://%s:8333" (include "vegaduta-lib.componentName" (dict "root" . "name" "seaweedfs"))) -}}
{{- end -}}
{{- define "vegaduta-lib.s3.secretName" -}}
{{- $o := (.Values.global | default dict).objectStorage | default dict -}}
{{- if $o.existingSecret -}}{{- $o.existingSecret -}}
{{- else if not $o.endpoint -}}{{- include "vegaduta-lib.componentName" (dict "root" . "name" "seaweedfs") -}}
{{- else -}}{{- fail "global.objectStorage.existingSecret is required with an external S3 endpoint (keys: global.objectStorage.accessKeyIdKey / secretAccessKeyKey)" -}}
{{- end -}}
{{- end -}}

{{/* ---------- Local-filesystem storage (global.objectStorage.type=local) ----------
  Files live on ONE PersistentVolumeClaim "<release>-core-storage" (created by the core
  chart) mounted by every core-family pod. With more than one such pod the claim must be
  ReadWriteMany. */}}
{{- define "vegaduta-lib.storage.local" -}}
{{- if eq ((((.Values.global | default dict).objectStorage) | default dict).type | default "s3") "local" }}true{{ end -}}
{{- end -}}
{{- define "vegaduta-lib.storage.azure" -}}
{{- if eq ((((.Values.global | default dict).objectStorage) | default dict).type | default "s3") "azure-blob" }}true{{ end -}}
{{- end -}}
{{- define "vegaduta-lib.storage.localPath" -}}/data/storage{{- end -}}
{{- define "vegaduta-lib.storage.claimName" -}}
{{- $l := (((.Values.global | default dict).objectStorage) | default dict).local | default dict -}}
{{- $l.existingClaim | default (include "vegaduta-lib.componentName" (dict "root" . "name" "core-storage")) -}}
{{- end -}}
{{/* Values copy with the storage volume added (no-op unless local storage). */}}
{{- define "vegaduta-lib.core.withStorage" -}}
{{- $v := deepCopy .Values -}}
{{- if include "vegaduta-lib.storage.local" . -}}
{{- $_ := set $v "extraVolumes" (append ($v.extraVolumes | default list) (dict "name" "core-storage" "persistentVolumeClaim" (dict "claimName" (include "vegaduta-lib.storage.claimName" .)))) -}}
{{- $_ := set $v "extraVolumeMounts" (append ($v.extraVolumeMounts | default list) (dict "name" "core-storage" "mountPath" (include "vegaduta-lib.storage.localPath" .))) -}}
{{- end -}}
{{- toYaml $v -}}
{{- end -}}

{{/* ---------- OIDC (Keycloak or compatible) ---------- */}}
{{- define "vegaduta-lib.oidc.realm" -}}{{- ((.Values.global | default dict).oidc | default dict).realm | default "agentic-ai" -}}{{- end -}}
{{- define "vegaduta-lib.oidc.bundled" -}}
{{- if not ((.Values.global | default dict).oidc | default dict).issuerUrl }}true{{ end -}}
{{- end -}}
{{/* Public issuer: must equal the `iss` claim the IdP puts in tokens. */}}
{{- define "vegaduta-lib.oidc.issuer" -}}
{{- $o := (.Values.global | default dict).oidc | default dict -}}
{{- if $o.issuerUrl -}}{{- trimSuffix "/" $o.issuerUrl -}}
{{- else -}}{{- printf "%s/realms/%s" (include "vegaduta-lib.publicUrl" (dict "root" . "key" "auth")) (include "vegaduta-lib.oidc.realm" .) -}}
{{- end -}}
{{- end -}}
{{/* Public (browser-facing) base URL of the IdP: the issuer without /realms/<realm>. */}}
{{- define "vegaduta-lib.oidc.publicBase" -}}
{{- regexReplaceAll "/realms/[^/]+$" (include "vegaduta-lib.oidc.issuer" .) "" -}}
{{- end -}}

{{/* Public env shared by web and web-admin (read at RUNTIME by both apps, see web/docs/RUNTIME-CONFIG.md). Takes dict {root, clientId}. */}}
{{- define "vegaduta-lib.webPublicEnv" -}}
NEXT_PUBLIC_API_BASE_URL: {{ include "vegaduta-lib.publicUrl" (dict "root" .root "key" "api") | quote }}
NEXT_PUBLIC_KEYCLOAK_URL: {{ include "vegaduta-lib.oidc.publicBase" .root | quote }}
NEXT_PUBLIC_KEYCLOAK_REALM: {{ include "vegaduta-lib.oidc.realm" .root | quote }}
NEXT_PUBLIC_KEYCLOAK_CLIENT_ID: {{ .clientId | quote }}
API_BASE_URL: {{ printf "http://%s:8080" (include "vegaduta-lib.componentName" (dict "root" .root "name" "core")) | quote }}
HOSTNAME: "0.0.0.0"
{{- end -}}

{{/* In-cluster base URL of the IdP (JWKS fetch + Keycloak admin API). The ONE
  place an internal, non-public URL is correct. */}}
{{- define "vegaduta-lib.oidc.internalBase" -}}
{{- $o := (.Values.global | default dict).oidc | default dict -}}
{{- if $o.internalBaseUrl -}}{{- trimSuffix "/" $o.internalBaseUrl -}}
{{- else if include "vegaduta-lib.oidc.bundled" . -}}{{- printf "http://%s:8080" (include "vegaduta-lib.componentName" (dict "root" . "name" "keycloak")) -}}
{{- else -}}{{- regexReplaceAll "/realms/[^/]+$" (include "vegaduta-lib.oidc.issuer" .) "" -}}
{{- end -}}
{{- end -}}
{{- define "vegaduta-lib.oidc.jwkSetUri" -}}
{{- $o := (.Values.global | default dict).oidc | default dict -}}
{{- $o.jwkSetUri | default (printf "%s/realms/%s/protocol/openid-connect/certs" (include "vegaduta-lib.oidc.internalBase" .) (include "vegaduta-lib.oidc.realm" .)) -}}
{{- end -}}
{{- define "vegaduta-lib.oidc.secretName" -}}
{{- $o := (.Values.global | default dict).oidc | default dict -}}
{{- if $o.existingSecret -}}{{- $o.existingSecret -}}
{{- else if include "vegaduta-lib.oidc.bundled" . -}}{{- printf "%s-core-client" (include "vegaduta-lib.componentName" (dict "root" . "name" "keycloak")) -}}
{{- else -}}{{- fail "global.oidc.existingSecret is required with an external issuer (Secret holding the confidential core client secret under global.oidc.clientSecretKey)" -}}
{{- end -}}
{{- end -}}

{{/* ---------- Shared secrets ---------- */}}
{{/* The ONE app Secret (APP_ENCRYPTION_KEY + optional extra core env). A chart-local
  appSecret.existingSecret is allowed for standalone installs but must not
  disagree with the global one. */}}
{{- define "vegaduta-lib.core.appSecretName" -}}
{{- $g := .Values.global | default dict -}}
{{- $gs := (($g.core | default dict).appSecret | default dict).existingSecret | default "" -}}
{{- $ls := (.Values.appSecret | default dict).existingSecret | default "" -}}
{{- if and $gs $ls (ne $gs $ls) -}}
{{- fail (printf "%s: appSecret.existingSecret=%q differs from global.core.appSecret.existingSecret=%q. core, core-jobs, core-worker and the migrate Job MUST read APP_ENCRYPTION_KEY from the same Secret, or stored credentials become undecryptable." .Chart.Name $ls $gs) -}}
{{- end -}}
{{- $ls | default $gs | default (include "vegaduta-lib.componentName" (dict "root" . "name" "core-app")) -}}
{{- end -}}

{{/* Service-to-service tokens (sidecar, mcp runner, code-intel, tool-sandbox, ...). */}}
{{- define "vegaduta-lib.internalSecretName" -}}
{{- $g := .Values.global | default dict -}}
{{- ($g.internalSecret | default dict).existingSecret | default (include "vegaduta-lib.componentName" (dict "root" . "name" "internal")) -}}
{{- end -}}

{{/* ---------- Component switches ---------- */}}
{{/* "true" when global.components.<key> is on. Takes dict {root, key}. */}}
{{- define "vegaduta-lib.component" -}}
{{- $c := ((.root.Values.global | default dict).components | default dict) -}}
{{- if get $c .key }}true{{ end -}}
{{- end -}}
{{- define "vegaduta-lib.temporal.enabled" -}}
{{- if ((.Values.global | default dict).temporal | default dict).enabled }}true{{ end -}}
{{- end -}}

{{/* ---------- Core env ---------- */}}
{{/* Plain env shared by every core-family process. Takes dict {root, role};
  role = api | jobs | worker | migrate. The jobs role has pools (values `pool`: "", ingest, webhooks, intake or billing). Renders a YAML map. */}}
{{- define "vegaduta-lib.coreEnv" -}}
{{- $root := .root -}}
{{- $role := .role -}}
{{- $v := $root.Values -}}
{{- $g := $v.global | default dict -}}
{{- $gc := $g.core | default dict -}}
{{- $o := $g.oidc | default dict -}}
{{- $s3 := $g.objectStorage | default dict -}}
{{- $pg := $g.postgresql | default dict -}}
{{- $tmp := $g.temporal | default dict -}}
{{- $temporal := include "vegaduta-lib.temporal.enabled" $root -}}
{{- $jobs := include "vegaduta-lib.component" (dict "root" $root "key" "coreJobs") -}}
{{- $profiles := $gc.springProfiles | default "security,knowledge,workflow,graph-memory,mcpserver" -}}
{{- /* Apache AGE is optional: on a server without it (AWS RDS/Aurora, Cloud SQL, AlloyDB) set
     global.postgresql.age=false. graph-memory then leaves the profile list (core also records the AGE
     migration V44 as applied without running it, see TenantSchemaMigrator). */ -}}
{{- if eq (toString (dig "age" true $pg)) "false" }}{{ $profiles = without (splitList "," $profiles) "graph-memory" | join "," }}{{ end -}}
{{- /* `mcp` (application-mcp.yml's static STDIO built-ins) only where something can spawn them:
     the mcp-stdio-runner (every core role, relayed) or, without it, the API on the core-full
     image (global.core.mcpToolchain=full). The slim image refuses `mcp` with nothing to spawn
     it (McpStdioLauncherPreflight). An explicit `mcp` in springProfiles is left alone. */ -}}
{{- $mcpRunner := include "vegaduta-lib.component" (dict "root" $root "key" "mcpStdioRunner") -}}
{{- $mcpInCore := and (eq $role "api") (eq ($gc.mcpToolchain | default "none") "full") -}}
{{- if and (or $mcpRunner $mcpInCore) (not (has "mcp" (splitList "," $profiles))) }}{{ $profiles = printf "%s,mcp" $profiles }}{{ end -}}
{{- if $temporal }}{{ $profiles = printf "%s,temporal" $profiles }}{{ end -}}
{{- if eq $role "api" }}{{ if $jobs }}{{ $profiles = printf "%s,shape-api" $profiles }}{{ end }}
{{- else if eq $role "jobs" }}
{{- /* Pools of the jobs shape (docs/architecture/ADR-0014-horizontal-scaling.md). A pool pod runs only its own
     jobs: `ingest` has its own profile; the others share shape-jobs-pool and are named by APP_SCHEDULING_POOL.
     The main jobs pod lists the pools that run elsewhere in APP_SCHEDULING_OFFLOADED_POOLS (env below), so any
     combination of pools composes. With no pool deployed nothing is added. Later profiles win. */}}
{{- if eq ($v.pool | default "") "ingest" }}{{ $profiles = printf "%s,shape-jobs,shape-jobs-ingest,jobs" $profiles }}
{{- else if $v.pool }}{{ $profiles = printf "%s,shape-jobs,shape-jobs-pool,jobs" $profiles }}
{{- else }}{{ $profiles = printf "%s,shape-jobs,jobs" $profiles }}{{ end }}
{{- else if eq $role "worker" }}{{ $profiles = printf "%s,worker" $profiles }}
{{- end -}}
{{- $hikari := dict "api" (ternary 12 15 (not (empty $jobs))) "jobs" 10 "worker" 8 "migrate" 10 -}}
{{- $h := $v.hikari | default dict -}}
{{- /* Default Hikari max of each pool of the jobs shape; the umbrella's connection-budget guard mirrors these. */ -}}
{{- $poolHikari := dict "ingest" 6 "webhooks" 6 "intake" 8 "billing" 8 -}}
{{- $pool := $v.pool | default "" -}}
{{- $offloaded := list -}}
{{- if and (eq $role "jobs") (not $pool) -}}
{{- range $p := list (list "coreIngest" "ingest") (list "coreWebhooks" "webhooks") (list "coreIntake" "intake") (list "coreBilling" "billing") -}}
{{- if include "vegaduta-lib.component" (dict "root" $root "key" (index $p 0)) }}{{ $offloaded = append $offloaded (index $p 1) }}{{ end -}}
{{- end -}}
{{- end -}}
JAVA_TOOL_OPTIONS: {{ $v.javaToolOptions | default "-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" | quote }}
SPRING_PROFILES_ACTIVE: {{ $profiles | quote }}
DB_HOST: {{ include "vegaduta-lib.pg.host" $root | quote }}
DB_PORT: {{ include "vegaduta-lib.pg.port" $root | quote }}
POSTGRES_DB: {{ include "vegaduta-lib.pg.database" $root | quote }}
POSTGRES_USER: {{ include "vegaduta-lib.pg.username" $root | quote }}
{{- if ne (include "vegaduta-lib.pg.auth" $root) "password" }}
DB_AUTH: {{ include "vegaduta-lib.pg.auth" $root | quote }}
{{- end }}
DB_HIKARI_MAX_POOL_SIZE: {{ $h.maxPoolSize | default (ternary (get $poolHikari $pool) (get $hikari $role) (hasKey $poolHikari $pool)) | quote }}
{{- if and $pool (ne $pool "ingest") }}
APP_SCHEDULING_POOL: {{ $pool | quote }}
{{- end }}
{{- if $offloaded }}
APP_SCHEDULING_OFFLOADED_POOLS: {{ join "," $offloaded | quote }}
{{- end }}
DB_HIKARI_MIN_IDLE: {{ $h.minIdle | default (ternary 1 3 (eq $role "migrate")) | quote }}
DB_HIKARI_CONNECTION_TIMEOUT_MS: {{ $h.connectionTimeoutMs | default (ternary 60000 (ternary 30000 20000 (eq $role "jobs")) (eq $role "migrate")) | quote }}
REDIS_HOST: {{ include "vegaduta-lib.valkey.host" $root | quote }}
REDIS_PORT: {{ include "vegaduta-lib.valkey.port" $root | quote }}
{{- with ($g.valkey | default dict) }}
{{- if .tls }}
REDIS_SSL: "true"
{{- end }}
{{- with .username }}
REDIS_USERNAME: {{ . | quote }}
{{- end }}
{{- end }}
KEYCLOAK_ISSUER_URI: {{ include "vegaduta-lib.oidc.issuer" $root | quote }}
KEYCLOAK_ADDITIONAL_ISSUERS: {{ join "," ($o.additionalIssuers | default list) | quote }}
KEYCLOAK_JWK_SET_URI: {{ include "vegaduta-lib.oidc.jwkSetUri" $root | quote }}
KEYCLOAK_ADMIN_BASE_URL: {{ $o.adminBaseUrl | default (include "vegaduta-lib.oidc.internalBase" $root) | quote }}
KEYCLOAK_REALM: {{ include "vegaduta-lib.oidc.realm" $root | quote }}
KEYCLOAK_CLIENT_ID: {{ $o.clientId | default "agentic-ai-core" | quote }}
APP_WEB_ALLOWED_ORIGINS: {{ join "," (concat (list (include "vegaduta-lib.publicUrl" (dict "root" $root "key" "app")) (include "vegaduta-lib.publicUrl" (dict "root" $root "key" "admin"))) ($gc.extraAllowedOrigins | default list)) | quote }}
{{- if include "vegaduta-lib.storage.local" $root }}
APP_STORAGE_TYPE: "local"
APP_STORAGE_LOCAL_PATH: {{ include "vegaduta-lib.storage.localPath" $root | quote }}
{{- else if include "vegaduta-lib.storage.azure" $root }}
{{- /* Azure Blob Storage: the container is global.objectStorage.bucket; workload identity (credentials=default)
     needs the account endpoint and no secret. */}}
APP_STORAGE_TYPE: "azure-blob"
STORAGE_BUCKET: {{ $s3.bucket | default "vegaduta" | quote }}
STORAGE_CREDENTIALS: {{ $s3.credentials | default "static" | quote }}
{{- with ($s3.azure | default dict).endpoint }}
STORAGE_AZURE_ENDPOINT: {{ . | quote }}
{{- end }}
{{- else }}
APP_STORAGE_TYPE: "s3"
STORAGE_ENDPOINT: {{ include "vegaduta-lib.s3.endpoint" $root | quote }}
STORAGE_REGION: {{ $s3.region | default "us-east-1" | quote }}
STORAGE_BUCKET: {{ $s3.bucket | default "vegaduta" | quote }}
STORAGE_PATH_STYLE_ACCESS: {{ ternary "true" "false" (not (empty (include "vegaduta-lib.on" (dict "obj" $s3 "key" "pathStyleAccess")))) | quote }}
{{- if eq ($s3.credentials | default "static") "default" }}
{{- if not $s3.endpoint }}{{ fail "global.objectStorage.credentials=default (AWS SDK credential chain) needs an external endpoint: set global.objectStorage.endpoint (e.g. https://s3.<region>.amazonaws.com); the bundled SeaweedFS needs its key pair" }}{{ end }}
STORAGE_CREDENTIALS: "default"
{{- end }}
{{- end }}
APP_WEB_PUBLIC_URL: {{ include "vegaduta-lib.publicUrl" (dict "root" $root "key" "app") | quote }}
{{- with $pg.sslMode }}
{{- /* core builds its JDBC URL from DB_HOST/DB_PORT/POSTGRES_DB with no SSL knob; override the whole URL to add sslmode. */}}
SPRING_DATASOURCE_URL: {{ printf "jdbc:postgresql://%s:%v/%s?options=-c%%20TimeZone%%3DUTC&sslmode=%s" (include "vegaduta-lib.pg.host" $root) (include "vegaduta-lib.pg.port" $root) (include "vegaduta-lib.pg.database" $root) . | quote }}
{{- end }}
WORKFLOW_EXECUTION_ENGINE: {{ ternary "temporal" "legacy" (not (empty $temporal)) | quote }}
{{- if $temporal }}
TEMPORAL_TARGET: {{ required "global.temporal.target (host:port of the Temporal frontend) is required when global.temporal.enabled" $tmp.target | quote }}
TEMPORAL_NAMESPACE: {{ $tmp.namespace | default "default" | quote }}
{{- /* Only the core-worker deployable polls the task queue. */}}
APP_WORKFLOW_TEMPORAL_WORKER_ENABLED: {{ ternary "true" "false" (eq $role "worker") | quote }}
{{- end }}
{{- with ($g.kafka | default dict).bootstrapServers }}
WORKFLOW_KAFKA_BOOTSTRAP_SERVERS: {{ . | quote }}
{{- /* No global.kafka.bootstrapServers: the chart emits NO Kafka switch. A bootstrap can also arrive from
     a Secret the chart cannot read (global.core.appSecret.existingSecret, extraEnvFromSecrets, envFrom, ...),
     so core decides at start-up: no bootstrap resolved -> no Kafka client and one log line (see
     MigrateOnlyEnvironmentPostProcessor). */}}
{{- end }}
{{- /* Managed Kafka (MSK with SCRAM, Event Hubs' Kafka endpoint, Confluent Cloud): Spring Boot binds these. */}}
{{- with ($g.kafka | default dict).securityProtocol }}
SPRING_KAFKA_SECURITY_PROTOCOL: {{ . | quote }}
{{- end }}
{{- with ($g.kafka | default dict).saslMechanism }}
SPRING_KAFKA_PROPERTIES_SASL_MECHANISM: {{ . | quote }}
{{- end }}
{{- $otel := $g.otel | default dict }}
{{- if $otel.tracesEndpoint }}
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: {{ $otel.tracesEndpoint | quote }}
OTLP_TRACING_EXPORT_ENABLED: "true"
{{- else }}
OTLP_TRACING_EXPORT_ENABLED: "false"
{{- end }}
TRACING_SAMPLING_PROBABILITY: {{ $otel.samplingProbability | default "0.0" | toString | quote }}
MANAGEMENT_HEALTH_MAIL_ENABLED: "false"
LOGGING_STRUCTURED_FORMAT_CONSOLE: "ecs"
MCP_FILESYSTEM_ROOT: "/app/mcp-data"
{{- /* Integrations: derived from global.components so they can never disagree with what is deployed. */}}
{{- if include "vegaduta-lib.component" (dict "root" $root "key" "sidecar") }}
SIDECAR_BASE_URL: {{ printf "http://%s:8001" (include "vegaduta-lib.componentName" (dict "root" $root "name" "sidecar")) | quote }}
{{- end }}
{{- $ci := include "vegaduta-lib.component" (dict "root" $root "key" "codeIntel") }}
CODE_INTEL_ENABLED: {{ ternary "true" "false" (not (empty $ci)) | quote }}
{{- if $ci }}
CODE_INTEL_BASE_URL: {{ printf "http://%s:8090" (include "vegaduta-lib.componentName" (dict "root" $root "name" "code-intel")) | quote }}
{{- end }}
{{- $ts := include "vegaduta-lib.component" (dict "root" $root "key" "toolSandbox") }}
TOOL_SANDBOX_ENABLED: {{ ternary "true" "false" (not (empty $ts)) | quote }}
{{- if $ts }}
TOOL_SANDBOX_BASE_URL: {{ printf "http://%s:8095" (include "vegaduta-lib.componentName" (dict "root" $root "name" "tool-sandbox")) | quote }}
{{- end }}
{{- $mcp := include "vegaduta-lib.component" (dict "root" $root "key" "mcpStdioRunner") }}
MCP_STDIO_SIDECAR_ENABLED: {{ ternary "true" "false" (not (empty $mcp)) | quote }}
{{- if $mcp }}
MCP_STDIO_SIDECAR_URL: {{ printf "%s:8097" (include "vegaduta-lib.componentName" (dict "root" $root "name" "mcp-stdio-runner")) | quote }}
{{- end }}
{{- with ($g.livekit | default dict).url }}
LIVEKIT_URL: {{ . | quote }}
{{- end }}
{{- if eq $role "jobs" }}
{{- /* core/Dockerfile HEALTHCHECK and the probes use 8080. */}}
JOBS_HTTP_PORT: "8080"
{{- else if eq $role "worker" }}
WORKER_HTTP_PORT: "8080"
{{- else if eq $role "migrate" }}
APP_TENANCY_MIGRATE_ONLY: "true"
{{- else if eq $role "api" }}
{{- if (($v.migrate | default dict).enabled) }}
{{- /* Tenant-schema migrations run in the migrate Job, never on pod boot. */}}
APP_TENANCY_MIGRATE_ON_BOOT: "false"
{{- end }}
{{- if $v.resumableStreamsEnabled }}
APP_CHAT_RESUMABLE_STREAMS_ENABLED: "true"
{{- end }}
{{- if $v.branchingEnabled }}
APP_CHAT_BRANCHING_ENABLED: "true"
{{- end }}
{{- $rr := $v.replicaReadiness | default dict }}
{{- $multi := include "vegaduta-lib.multiReplica" $root }}
{{- if $multi }}
{{- /* Turns on core's boot-time ReplicaModeSafetyCheck (ReplicaModeSafetyCheck.java): it lists any
     per-JVM setting that is still unsafe with 2+ replicas. replicaStrict makes the fatal ones refuse to boot. */}}
APP_REPLICA_MODE: "multi"
{{- if $v.replicaStrict }}
APP_REPLICA_MODE_STRICT: "true"
{{- end }}
{{- end }}
{{- if $rr.abuseThrottleRedis }}
APP_ABUSE_THROTTLE_STORE: "redis"
{{- end }}
{{- if $rr.a2aTaskStoreRedis }}
APP_A2A_TASK_STORE: "redis"
{{- end }}
{{- if $rr.mcpRegistryRedis }}
APP_MCP_REGISTRY_STORE: "redis"
{{- end }}
{{- /* The remaining shared stores only matter with 2+ replicas, so they switch only then. Each has
     a Valkey implementation that fails open to the in-memory copy (see the property docs in
     application.yml). ReplicaStoreCoverageTest (core/app) keeps this list equal to what
     ReplicaModeSafetyCheck inspects. */}}
{{- if $multi }}
{{- if $rr.sandboxFilesRedis }}
APP_SANDBOX_DATA_ANALYSIS_STORE: "redis"
{{- end }}
{{- if $rr.e2bQuotaRedis }}
APP_SANDBOX_PLATFORM_E2B_QUOTA_STORE: "redis"
{{- end }}
{{- if $rr.resumableStreamsRedis }}
APP_CHAT_RESUMABLE_STREAMS_STORE: "redis"
{{- end }}
{{- if $rr.guardrailSessionCallsRedis }}
APP_GUARDRAILS_PROCEDURES_SESSION_CALLS_STORE: "redis"
{{- end }}
{{- if $rr.demoStudioApprovalRedis }}
APP_DEMO_STUDIO_APPROVAL_STORE: "redis"
{{- end }}
{{- if $rr.effectJournalRedis }}
TOOL_EFFECT_JOURNAL_STORE: "redis"
{{- end }}
{{- if $rr.mcpOAuthStoreRedis }}
{{- /* Only unsafe while MCP OAuth is on; core's safety check covers that gate (the key is
     allowlisted out of ReplicaStoreCoverageTest's unconditional isRedis list for that reason). */}}
MCP_OAUTH_STORE: "redis"
{{- end }}
{{- if $rr.qualityBootSweepStale }}
APP_QUALITY_BOOT_ORPHAN_SWEEP: "stale"
{{- end }}
{{- end }}
{{- end }}
{{- range $k, $val := ($gc.extraEnv | default dict) }}
{{ $k }}: {{ $val | toString | quote }}
{{- end }}
{{- end -}}

{{/* Final plain env (computed + global.core.extraEnv + chart env; chart env wins)
  with the non-negotiable rules enforced. Takes dict {root, role}. */}}
{{- define "vegaduta-lib.coreEnvFinal" -}}
{{- $root := .root -}}
{{- $computed := include "vegaduta-lib.coreEnv" . | fromYaml -}}
{{- $env := mergeOverwrite $computed (deepCopy ($root.Values.env | default dict)) -}}
{{- $secretNames := list "APP_ENCRYPTION_KEY" "APP_ENCRYPTION_KEY_PREVIOUS" "POSTGRES_PASSWORD" "KEYCLOAK_CLIENT_SECRET" "STORAGE_SECRET_KEY" "REDIS_PASSWORD" "SPRING_DATA_REDIS_PASSWORD" "SPRING_DATASOURCE_PASSWORD" "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG" "STORAGE_AZURE_CONNECTION_STRING" -}}
{{- range $k := $secretNames -}}
{{- if hasKey $env $k -}}
{{- fail (printf "%s must not be set as a plain env value; it comes from a Secret (APP_ENCRYPTION_KEY: the ONE app Secret shared by core, core-jobs, core-worker and the migrate Job)" $k) -}}
{{- end -}}
{{- end -}}
{{- /* The chart's raw extraEnv list is appended verbatim to the container env: a literal `value` on a secret name is refused there too (valueFrom is fine). */ -}}
{{- range $e := ($root.Values.extraEnv | default list) -}}
{{- if and (has $e.name $secretNames) (hasKey $e "value") -}}
{{- fail (printf "extraEnv %s must not carry a literal value; reference a Secret with valueFrom.secretKeyRef (or secretEnv)" $e.name) -}}
{{- end -}}
{{- end -}}
{{- if not (get $env "KEYCLOAK_ISSUER_URI") -}}
{{- fail "KEYCLOAK_ISSUER_URI must not be empty: it is compared with the `iss` claim of every token" -}}
{{- end -}}
{{- $pgAuth := include "vegaduta-lib.pg.auth" $root -}}
{{- if ne $pgAuth "password" -}}
{{- $pgv := (($root.Values.global | default dict).postgresql | default dict) -}}
{{- if not $pgv.host -}}
{{- fail (printf "global.postgresql.auth=%s needs a managed database: set global.postgresql.host (the bundled CloudNativePG cluster only does password login)" $pgAuth) -}}
{{- end -}}
{{- if not (has ($pgv.sslMode | default "") (list "require" "verify-ca" "verify-full")) -}}
{{- fail (printf "global.postgresql.auth=%s sends a short-lived token as the database password: set global.postgresql.sslMode to require, verify-ca or verify-full" $pgAuth) -}}
{{- end -}}
{{- end -}}
{{- if ne (((($root.Values.global | default dict).postgresql) | default dict).poolMode | default "session") "session" -}}
{{- fail "global.postgresql.poolMode must be 'session': transaction pooling (PgBouncer/pooler) silently routes schema-per-tenant queries to the wrong tenant" -}}
{{- end -}}
{{- range $k, $val := $env -}}
{{- if regexMatch "^(https?|wss?)://(localhost|127\\.0\\.0\\.1)([:/]|$)" (toString $val) -}}
{{- fail (printf "env %s=%s points at localhost; inside a pod that is the pod itself. Use the in-cluster Service DNS (internal) or the public hostname." $k $val) -}}
{{- end -}}
{{- end -}}
{{- toYaml $env -}}
{{- end -}}

{{/* secretKeyRef env shared by every core-family process. Takes root. Renders a YAML list. */}}
{{- define "vegaduta-lib.coreSecretEnv" -}}
{{- $g := .Values.global | default dict -}}
{{- $s3 := $g.objectStorage | default dict -}}
{{- $o := $g.oidc | default dict -}}
{{- $app := include "vegaduta-lib.core.appSecretName" . -}}
{{- $int := include "vegaduta-lib.internalSecretName" . -}}
- name: APP_ENCRYPTION_KEY
  secretName: {{ $app }}
  key: APP_ENCRYPTION_KEY
{{- if eq (include "vegaduta-lib.pg.auth" .) "password" }}
- name: POSTGRES_PASSWORD
  secretName: {{ include "vegaduta-lib.pg.secretName" . }}
  key: {{ include "vegaduta-lib.pg.passwordKey" . }}
{{- end }}
{{- with include "vegaduta-lib.valkey.secretName" . }}
- name: REDIS_PASSWORD
  secretName: {{ . }}
  key: {{ include "vegaduta-lib.valkey.passwordKey" $ }}
{{- end }}
{{- with ($g.kafka | default dict).existingSecret }}
- name: SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG
  secretName: {{ . }}
  key: {{ ($g.kafka).saslJaasConfigKey | default "saslJaasConfig" }}
{{- end }}
{{- if and (include "vegaduta-lib.storage.azure" .) (eq ($s3.credentials | default "static") "static") }}
- name: STORAGE_AZURE_CONNECTION_STRING
  secretName: {{ required "global.objectStorage.azure.existingSecret is required with type=azure-blob and credentials=static (key: connectionString), or set global.objectStorage.credentials=default for workload identity" ($s3.azure | default dict).existingSecret }}
  key: {{ ($s3.azure | default dict).connectionStringKey | default "connectionString" }}
{{- end }}
{{- if and (not (include "vegaduta-lib.storage.local" .)) (not (include "vegaduta-lib.storage.azure" .)) (ne ($s3.credentials | default "static") "default") }}
- name: STORAGE_ACCESS_KEY
  secretName: {{ include "vegaduta-lib.s3.secretName" . }}
  key: {{ $s3.accessKeyIdKey | default "accessKeyId" }}
- name: STORAGE_SECRET_KEY
  secretName: {{ include "vegaduta-lib.s3.secretName" . }}
  key: {{ $s3.secretAccessKeyKey | default "secretAccessKey" }}
{{- end }}
- name: KEYCLOAK_CLIENT_SECRET
  secretName: {{ include "vegaduta-lib.oidc.secretName" . }}
  key: {{ $o.clientSecretKey | default "clientSecret" }}
{{- range (list "SIDECAR_SHARED_SECRET" "MCP_STDIO_SIDECAR_SHARED_SECRET" "CODE_INTEL_INTERNAL_TOKEN" "TOOL_SANDBOX_INTERNAL_TOKEN" "APP_BUILDER_CORE_SERVICE_TOKEN") }}
- name: {{ . }}
  secretName: {{ $int }}
  key: {{ . }}
  optional: true
{{- end }}
{{- with ($g.livekit | default dict).existingSecret }}
- name: LIVEKIT_API_KEY
  secretName: {{ . }}
  key: LIVEKIT_API_KEY
- name: LIVEKIT_API_SECRET
  secretName: {{ . }}
  key: LIVEKIT_API_SECRET
{{- end }}
{{- end -}}

{{/* envFrom for core: the whole app Secret, so operators can add optional core
  env (RAZORPAY_*, SMTP_*, GOOGLE_OAUTH_*, ...) without touching the chart. */}}
{{- define "vegaduta-lib.coreEnvFrom" -}}
- secretRef:
    name: {{ include "vegaduta-lib.core.appSecretName" . }}
{{- with (((.Values.global | default dict).core | default dict).extraEnvFromSecrets) }}
{{- range . }}
- secretRef:
    name: {{ . }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Checksum of everything that must roll core-family pods when it changes. */}}
{{- define "vegaduta-lib.coreChecksum" -}}
{{- toJson (pick (.Values.global | default dict) "core" "postgresql" "valkey" "objectStorage" "oidc" "temporal" "kafka" "otel" "components" "livekit" "internalSecret" "domain" "hosts") | sha256sum | trunc 16 -}}
{{- end -}}

{{/* Complete core-family Deployment. Takes dict {root, role}. */}}
{{- define "vegaduta-lib.coreDeployment" -}}
{{- $root := .root -}}
{{- if include "vegaduta-lib.storage.local" $root -}}
{{- $root = dict "Values" (include "vegaduta-lib.core.withStorage" .root | fromYaml) "Chart" .root.Chart "Release" .root.Release "Capabilities" .root.Capabilities "Template" .root.Template -}}
{{- end -}}
{{- $env := include "vegaduta-lib.coreEnvFinal" . | fromYaml -}}
{{- $secretEnv := include "vegaduta-lib.coreSecretEnv" $root | fromYamlArray -}}
{{- $envFrom := include "vegaduta-lib.coreEnvFrom" $root | fromYamlArray -}}
{{- include "vegaduta-lib.deployment" (dict "root" $root "env" $env "secretEnv" $secretEnv "envFrom" $envFrom "podAnnotations" (dict "checksum/core-config" (include "vegaduta-lib.coreChecksum" $root))) -}}
{{- end -}}

{{/* Migrate Job: the API image with APP_TENANCY_MIGRATE_ONLY=true. It runs
  control-plane Flyway + every tenant schema, then exits:
    0 ok | 1 failure (fails the Helm release) | 75 another migrator holds the
    advisory lock (ignored by podFailurePolicy, retried until activeDeadlineSeconds).
  Hook: post-install,pre-upgrade, always. Details at $hookAnn below.
  A wait-for-database init container absorbs the gap on first install. */}}
{{- define "vegaduta-lib.migrateJob" -}}
{{- $root := . -}}
{{- if include "vegaduta-lib.storage.local" . -}}
{{- $root = dict "Values" (include "vegaduta-lib.core.withStorage" . | fromYaml) "Chart" .Chart "Release" .Release "Capabilities" .Capabilities "Template" .Template -}}
{{- end -}}
{{- $v := $root.Values -}}
{{- $m := $v.migrate | default dict -}}
{{- $base := include "vegaduta-lib.fullname" $root -}}
{{- $env := include "vegaduta-lib.coreEnvFinal" (dict "root" $root "role" "migrate") | fromYaml -}}
{{- $secretEnv := include "vegaduta-lib.coreSecretEnv" $root | fromYamlArray -}}
{{- $envFrom := include "vegaduta-lib.coreEnvFrom" $root | fromYamlArray -}}
{{- $cname := printf "%s-migrate" $root.Chart.Name -}}
{{- /* Hook placement (INF-012): post-install,pre-upgrade - for every database.
  The Job runs as the release's own ServiceAccount and reads the release's Secrets
  (<release>-core-app, the Keycloak client secret). A pre-install hook runs before ANY
  release resource exists, so on a first install those are missing: the pod is refused
  by the ServiceAccount admission controller and the hook hangs until its deadline.
  That is exactly what happened with an external database, where this used to be
  pre-install. post-install does not deadlock `helm install --wait`: the API runs the
  control-plane Flyway migration itself on boot, and its readiness does not wait for
  tenant-schema migrations (APP_TENANCY_MIGRATE_ON_BOOT=false opens the gate; a fresh
  install has no tenants). On upgrades pre-upgrade still migrates before the new pods.
  Argo CD ignores Helm hook phases here: the Job is a Sync hook in wave 0, after the
  generated Secrets (-2) and the database (-1), before the core family (wave 1). */ -}}
{{- $helmHook := "post-install,pre-upgrade" -}}
{{- $hookAnn := dict "helm.sh/hook" $helmHook "helm.sh/hook-delete-policy" "before-hook-creation,hook-succeeded" "argocd.argoproj.io/hook" "Sync" "argocd.argoproj.io/hook-delete-policy" "BeforeHookCreation" -}}
{{- $wait := dict "name" "wait-for-database" "image" (include "vegaduta-lib.image" $root) "imagePullPolicy" (include "vegaduta-lib.imagePullPolicy" $root) "command" (list "bash" "-c" (printf "for i in $(seq 1 %d); do (exec 3<>/dev/tcp/$DB_HOST/$DB_PORT) 2>/dev/null && echo \"database reachable\" && exit 0; echo \"waiting for $DB_HOST:$DB_PORT\"; sleep 5; done; exit 1" (int ($m.waitForDatabaseAttempts | default 120)))) "env" (list (dict "name" "DB_HOST" "value" (get $env "DB_HOST")) (dict "name" "DB_PORT" "value" (get $env "DB_PORT"))) "resources" (dict "requests" (dict "cpu" "10m" "memory" "32Mi") "limits" (dict "memory" "128Mi")) "securityContext" (include "vegaduta-lib.securityContext" $root | fromYaml) -}}
{{- $n := $v.networkPolicy | default dict -}}
{{- $gn := ($v.global | default dict).networkPolicy | default dict -}}
{{- $npOn := $gn.enabled -}}
{{- if hasKey $n "enabled" }}{{ $npOn = $n.enabled }}{{ end -}}
{{- if $npOn }}
---
{{- /* The migrate pod is not selected by the API policy (different labels), so it gets its own egress. */}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $base }}-migrate
  namespace: {{ $root.Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" $root | nindent 4 }}
  annotations:
    {{- toYaml (merge (dict "helm.sh/hook-weight" "-5" "argocd.argoproj.io/sync-wave" "-1") $hookAnn) | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: {{ $cname }}
      app.kubernetes.io/instance: {{ $root.Release.Name }}
  policyTypes: [Ingress, Egress]
  ingress: []
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $gn.dnsNamespace | default "kube-system" }}
          podSelector:
            matchLabels:
              {{- toYaml ($gn.dnsPodLabels | default (dict "k8s-app" "kube-dns")) | nindent 14 }}
      ports:
        - { port: 53, protocol: UDP }
        - { port: 53, protocol: TCP }
    {{- range ($n.allowEgressTo | default list) }}
    - to:
        - {{- include "vegaduta-lib.siblingPeer" (dict "root" $root "name" .) | nindent 10 }}
    {{- end }}
    {{- /* External database/OTLP/... ports (the migrate pod carries the same env as core). */}}
    {{- with (include "vegaduta-lib.integrationEgress" $root | fromYamlArray) }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- with (include "vegaduta-lib.scopedExtraEgress" (dict "root" $root "name" $cname) | fromYamlArray) }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end }}
---
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ $base }}-migrate
  namespace: {{ $root.Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labelsWith" (dict "root" $root "labels" (dict "app.kubernetes.io/component" "migrate")) | nindent 4 }}
  annotations:
    {{- toYaml (merge (dict "helm.sh/hook-weight" "0" "argocd.argoproj.io/sync-wave" "0") $hookAnn) | nindent 4 }}
spec:
  backoffLimit: {{ $m.backoffLimit | default 6 }}
  activeDeadlineSeconds: {{ $m.activeDeadlineSeconds | default 1800 }}
  ttlSecondsAfterFinished: {{ $m.ttlSecondsAfterFinished | default 3600 }}
  podFailurePolicy:
    {{- /* onPodConditions: [] is deliberate: the 1.28 OpenAPI schema marks it required
      (fixed in 1.29) and the API server accepts an empty list beside onExitCodes. */}}
    rules:
      - action: Ignore
        onExitCodes:
          containerName: {{ $cname }}
          operator: In
          values: [75]
        onPodConditions: []
      - action: FailJob
        onExitCodes:
          containerName: {{ $cname }}
          operator: In
          values: [1]
        onPodConditions: []
  template:
    metadata:
      annotations:
        checksum/core-config: {{ include "vegaduta-lib.coreChecksum" $root }}
      labels:
        {{- /* NOT the API selector labels: the API Service/PDB/NetworkPolicy must never select this pod. */}}
        app.kubernetes.io/name: {{ $cname }}
        app.kubernetes.io/instance: {{ $root.Release.Name }}
        app.kubernetes.io/part-of: vegaduta
        app.kubernetes.io/component: migrate
    spec:
      {{- include "vegaduta-lib.podSpec" (dict "root" $root "env" $env "secretEnv" $secretEnv "envFrom" $envFrom "job" true "restartPolicy" "Never" "containerName" $cname "initContainers" (list $wait)) | nindent 6 }}
{{- end -}}
