{{/* ------------------------------------------------------------------
  vegaduta-lib: naming, labels, image, env, security and scheduling helpers.
  Every helper takes the consuming chart's root context (`.`) unless its
  comment says it takes a dict.

  Naming: fullname = fullnameOverride | "<release>-<chart name>". Sibling
  components are addressed with vegaduta-lib.componentName so that e.g. web
  can find "<release>-core" without seeing core's values. Consequence:
  fullnameOverride on a chart that others call (core, keycloak, postgres,
  valkey, seaweedfs, sidecar, ...) breaks those references - do not use it
  there inside the umbrella.
------------------------------------------------------------------ */}}

{{- define "vegaduta-lib.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* "<release>-<component>". Takes dict {root, name}. */}}
{{- define "vegaduta-lib.componentName" -}}
{{- printf "%s-%s" .root.Release.Name .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "vegaduta-lib.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "vegaduta-lib.componentName" (dict "root" . "name" (include "vegaduta-lib.name" .)) -}}
{{- end -}}
{{- end -}}

{{- define "vegaduta-lib.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "vegaduta-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ include "vegaduta-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Standard labels. `component` (values) defaults to the chart name. */}}
{{- define "vegaduta-lib.labels" -}}
helm.sh/chart: {{ include "vegaduta-lib.chart" . }}
{{ include "vegaduta-lib.selectorLabels" . }}
app.kubernetes.io/version: {{ include "vegaduta-lib.appVersion" . | quote }}
app.kubernetes.io/component: {{ .Values.component | default .Chart.Name }}
app.kubernetes.io/part-of: vegaduta
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* Standard labels with overrides (no duplicate keys). Takes dict {root, labels}. */}}
{{- define "vegaduta-lib.labelsWith" -}}
{{- $l := include "vegaduta-lib.labels" .root | fromYaml -}}
{{- toYaml (mergeOverwrite $l (.labels | default dict)) -}}
{{- end -}}

{{/* The version label follows the image tag actually deployed. */}}
{{- define "vegaduta-lib.appVersion" -}}
{{- $g := .Values.global | default dict -}}
{{- (.Values.image | default dict).tag | default $g.imageTag | default .Chart.AppVersion | toString | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "vegaduta-lib.serviceAccountName" -}}
{{- $sa := .Values.serviceAccount | default dict -}}
{{- if include "vegaduta-lib.on" (dict "obj" $sa "key" "create") -}}
{{- $sa.name | default (include "vegaduta-lib.fullname" .) -}}
{{- else -}}
{{- $sa.name | default "default" -}}
{{- end -}}
{{- end -}}

{{/* "true" when obj[key] is absent (default ON) or truthy, else "". */}}
{{- define "vegaduta-lib.on" -}}
{{- if or (not (hasKey .obj .key)) (get .obj .key) -}}true{{- end -}}
{{- end -}}

{{/* Image reference: <registry>/<repository>@<digest> or :<tag>.
  registry: image.registry | global.imageRegistry | ghcr.io/cd-lc
  tag:      image.tag | global.imageTag | Chart.appVersion
  Takes the root context, or dict {root, image} to render another image block. */}}
{{- define "vegaduta-lib.image" -}}
{{- $root := . -}}
{{- $img := dict -}}
{{- if hasKey . "root" -}}
{{- $root = .root -}}
{{- $img = .image -}}
{{- else -}}
{{- $img = .Values.image -}}
{{- end -}}
{{- $g := $root.Values.global | default dict -}}
{{- $reg := $img.registry | default $g.imageRegistry | default "ghcr.io/cd-lc" -}}
{{- $repo := required "image.repository is required" $img.repository -}}
{{- $ref := printf "%s/%s" (trimSuffix "/" $reg) $repo -}}
{{- if $img.digest -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" $img.digest) -}}
{{- fail (printf "image.digest for %s must look like sha256:<64 hex>, got %q" $repo $img.digest) -}}
{{- end -}}
{{- printf "%s@%s" $ref $img.digest -}}
{{- else -}}
{{- $tag := $img.tag | default $g.imageTag | default $root.Chart.AppVersion | toString -}}
{{- if eq $tag "latest" -}}
{{- fail (printf "image %s: the tag 'latest' is not allowed; pin a version or a digest" $repo) -}}
{{- end -}}
{{- printf "%s:%s" $ref $tag -}}
{{- end -}}
{{- end -}}

{{- define "vegaduta-lib.imagePullPolicy" -}}
{{- $g := .Values.global | default dict -}}
{{- (.Values.image | default dict).pullPolicy | default $g.imagePullPolicy | default "IfNotPresent" -}}
{{- end -}}

{{/* imagePullSecrets from global.imagePullSecrets + imagePullSecrets (names or {name}). */}}
{{- define "vegaduta-lib.imagePullSecrets" -}}
{{- $g := .Values.global | default dict -}}
{{- $names := list -}}
{{- range (concat ($g.imagePullSecrets | default list) (.Values.imagePullSecrets | default list)) -}}
{{- if kindIs "string" . }}{{ $names = append $names . }}{{ else }}{{ $names = append $names .name }}{{ end -}}
{{- end -}}
{{- if $names }}
imagePullSecrets:
{{- range ($names | uniq) }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Container env list. Takes dict {root, env, secretEnv?}.
  1. env map (plain values, already merged by the chart)
  2. secretEnv list ({name, secretName, key, optional}); secretName is tpl-rendered
  3. extraEnv (raw k8s env entries) */}}
{{- define "vegaduta-lib.env" -}}
{{- $root := .root -}}
{{- range $k, $v := .env }}
- name: {{ $k }}
  value: {{ $v | toString | quote }}
{{- end }}
{{- range (concat (.secretEnv | default list) ($root.Values.secretEnv | default list)) }}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ tpl (required (printf "secretEnv %s: secretName is required" .name) .secretName) $root }}
      key: {{ .key | default .name }}
      {{- if .optional }}
      optional: true
      {{- end }}
{{- end }}
{{- with $root.Values.extraEnv }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* Resources: requests AND limits are mandatory (no BestEffort pods). */}}
{{- define "vegaduta-lib.resources" -}}
{{- $r := required (printf "%s: resources is required" .Chart.Name) .Values.resources -}}
{{- if not $r.requests }}{{ fail (printf "%s: resources.requests is required (no BestEffort pods)" .Chart.Name) }}{{ end -}}
{{- if not $r.limits }}{{ fail (printf "%s: resources.limits is required (at least a memory limit)" .Chart.Name) }}{{ end -}}
{{- toYaml $r -}}
{{- end -}}

{{/* Pod securityContext with secure defaults filled in where unset. */}}
{{- define "vegaduta-lib.podSecurityContext" -}}
{{- $sc := deepCopy (.Values.podSecurityContext | default dict) -}}
{{- if not (hasKey $sc "runAsNonRoot") }}{{ $_ := set $sc "runAsNonRoot" true }}{{ end -}}
{{- if not (hasKey $sc "seccompProfile") }}{{ $_ := set $sc "seccompProfile" (dict "type" "RuntimeDefault") }}{{ end -}}
{{- if and (hasKey $sc "fsGroup") (not (hasKey $sc "fsGroupChangePolicy")) }}{{ $_ := set $sc "fsGroupChangePolicy" "OnRootMismatch" }}{{ end -}}
{{- toYaml $sc -}}
{{- end -}}

{{/* Container securityContext: drop ALL caps, no privilege escalation,
  read-only root FS and RuntimeDefault seccomp unless explicitly overridden. */}}
{{- define "vegaduta-lib.securityContext" -}}
{{- $sc := deepCopy (.Values.securityContext | default dict) -}}
{{- if not (hasKey $sc "allowPrivilegeEscalation") }}{{ $_ := set $sc "allowPrivilegeEscalation" false }}{{ end -}}
{{- if not (hasKey $sc "readOnlyRootFilesystem") }}{{ $_ := set $sc "readOnlyRootFilesystem" true }}{{ end -}}
{{- if not (hasKey $sc "runAsNonRoot") }}{{ $_ := set $sc "runAsNonRoot" true }}{{ end -}}
{{- if not (hasKey $sc "capabilities") }}{{ $_ := set $sc "capabilities" (dict "drop" (list "ALL")) }}{{ end -}}
{{- if not (hasKey $sc "seccompProfile") }}{{ $_ := set $sc "seccompProfile" (dict "type" "RuntimeDefault") }}{{ end -}}
{{- toYaml $sc -}}
{{- end -}}

{{/* Probes: values.probes.{startup,liveness,readiness} are raw k8s probe objects. */}}
{{- define "vegaduta-lib.probes" -}}
{{- $p := .Values.probes | default dict -}}
{{- with $p.startup }}
startupProbe:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $p.liveness }}
livenessProbe:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $p.readiness }}
readinessProbe:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/* Soft zone + node spread (ScheduleAnyway) so single-node clusters still
  schedule. Override with topologySpread.constraints; disable with enabled=false. */}}
{{- define "vegaduta-lib.topologySpread" -}}
{{- $t := .Values.topologySpread | default dict -}}
{{- if and (hasKey $t "enabled") (not $t.enabled) -}}
{{- else if $t.constraints -}}
{{- toYaml $t.constraints -}}
{{- else -}}
{{- $sel := include "vegaduta-lib.selectorLabels" . | fromYaml -}}
- maxSkew: 1
  topologyKey: topology.kubernetes.io/zone
  whenUnsatisfiable: ScheduleAnyway
  labelSelector:
    matchLabels:
      {{- toYaml $sel | nindent 6 }}
- maxSkew: 1
  topologyKey: kubernetes.io/hostname
  whenUnsatisfiable: ScheduleAnyway
  labelSelector:
    matchLabels:
      {{- toYaml $sel | nindent 6 }}
{{- end -}}
{{- end -}}

{{/* Public host for a logical host key (app/admin/api/auth/...). Takes dict {root, key}.
  <global.hosts.<key>>.<global.domain>; a value containing a dot is used verbatim
  (so global.hosts.app: "example.com" puts the web app on the apex). */}}
{{- define "vegaduta-lib.publicHost" -}}
{{- $g := .root.Values.global | default dict -}}
{{- $sub := get ($g.hosts | default dict) .key | default .key -}}
{{- if contains "." $sub -}}
{{- $sub -}}
{{- else -}}
{{- printf "%s.%s" $sub (required "global.domain is required: every public hostname derives from it (e.g. --set global.domain=example.com)" $g.domain) -}}
{{- end -}}
{{- end -}}

{{/* Public base URL for a host key. Takes dict {root, key}. */}}
{{- define "vegaduta-lib.publicUrl" -}}
{{- $g := .root.Values.global | default dict -}}
{{- printf "%s://%s" ($g.scheme | default "https") (include "vegaduta-lib.publicHost" .) -}}
{{- end -}}

{{/* Render-time guards common to every chart. */}}
{{- define "vegaduta-lib.validate" -}}
{{- range (.Values.extraVolumes | default list) -}}
{{- if and (hasKey . "hostPath") (not $.Values.allowHostPath) -}}
{{- fail (printf "%s: hostPath volumes are not allowed (docker.sock, host dirs); set allowHostPath=true only with a reviewed reason" $.Chart.Name) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* "true" when this render can read the cluster: helm install/upgrade and
  --dry-run=server. Empty for helm template, helm lint, --dry-run=client, Argo CD and
  Flux (they render offline, and `lookup` returns nothing there). */}}
{{- define "vegaduta-lib.clusterReachable" -}}
{{- if lookup "v1" "Namespace" "" "kube-system" }}true{{ end -}}
{{- end -}}

{{/* Refuse to GENERATE a secret in a render that cannot persist it (INF-006): offline,
  `lookup` cannot see the value already in the cluster, so every render (every Argo CD
  sync) would mint a new one - for APP_ENCRYPTION_KEY that destroys all encrypted data.
  global.allowGeneratedSecrets=true opts out (CI renders, throwaway clusters).
  Takes dict {root, secret, hint}. */}}
{{- define "vegaduta-lib.requirePersistableSecret" -}}
{{- $g := .root.Values.global | default dict -}}
{{- if and (not $g.allowGeneratedSecrets) (not (include "vegaduta-lib.clusterReachable" .root)) -}}
{{- fail (printf "Secret %q would be generated in a render without cluster access (helm template / helm lint / Argo CD / Flux / --dry-run=client): lookup cannot see its current value, so every render would create a NEW one. %s Or set global.allowGeneratedSecrets=true for throwaway renders only." .secret (.hint | default "Pre-create it and point the chart's existingSecret at it (ExternalSecrets / SealedSecrets / SOPS).")) -}}
{{- end -}}
{{- end -}}

{{/* Stable random value that survives upgrades: reuse the key from an existing
  Secret (lookup) or generate a new one. Takes dict {root, secret, key, length}.
  Generating is refused in offline renders (see requirePersistableSecret): use
  existingSecret for GitOps / template-based installs. */}}
{{- define "vegaduta-lib.stableRandom" -}}
{{- $existing := lookup "v1" "Secret" .root.Release.Namespace .secret -}}
{{- if and $existing $existing.data (hasKey $existing.data .key) -}}
{{- index $existing.data .key | b64dec -}}
{{- else -}}
{{- include "vegaduta-lib.requirePersistableSecret" (dict "root" .root "secret" .secret) -}}
{{- randAlphaNum (int (.length | default 32)) -}}
{{- end -}}
{{- end -}}

{{/* Annotations every generated Secret carries: never deleted by helm uninstall
  (losing APP_ENCRYPTION_KEY makes every stored credential undecryptable). */}}
{{- define "vegaduta-lib.keepAnnotations" -}}
helm.sh/resource-policy: keep
{{- end -}}
