{{/* ------------------------------------------------------------------
  Workload templates: pod spec, Deployment, Service, ServiceAccount, HPA,
  PDB, PVC, ServiceMonitor and the `helm test` pod.

  Values contract (per chart; see charts/vegaduta-lib/README.md):
    image, replicaCount, strategy, containerPort, extraPorts, service,
    serviceAccount, podSecurityContext, securityContext, resources, probes,
    lifecycle, emptyDirs, persistence, extraVolumes, extraVolumeMounts, env,
    secretEnv, extraEnv, envFrom, hpa, pdb, topologySpread, nodeSelector,
    tolerations, affinity, priorityClassName, runtimeClassName,
    podAnnotations, podLabels, terminationGracePeriodSeconds, tests
------------------------------------------------------------------ */}}

{{/* Pod spec shared by Deployments and Jobs. Takes dict:
    root, env (map), secretEnv (list), envFrom (list), job (bool),
    restartPolicy, serviceAccountName, containerName, command, args,
    initContainers (list), noProbes (bool) */}}
{{- define "vegaduta-lib.podSpec" -}}
{{- $root := .root -}}
{{- $v := $root.Values -}}
{{- $pers := $v.persistence | default dict -}}
serviceAccountName: {{ .serviceAccountName | default (include "vegaduta-lib.serviceAccountName" $root) }}
automountServiceAccountToken: {{ $v.automountServiceAccountToken | default false }}
{{- /* Service-link env (CORE_PORT=tcp://..., etc.) collides with Spring/Next config. */}}
enableServiceLinks: false
{{- with .restartPolicy }}
restartPolicy: {{ . }}
{{- end }}
terminationGracePeriodSeconds: {{ $v.terminationGracePeriodSeconds | default 30 }}
securityContext:
  {{- include "vegaduta-lib.podSecurityContext" $root | nindent 2 }}
{{- include "vegaduta-lib.imagePullSecrets" $root }}
{{- with $v.priorityClassName }}
priorityClassName: {{ . }}
{{- end }}
{{- with $v.runtimeClassName }}
runtimeClassName: {{ . }}
{{- end }}
{{- with $v.shareProcessNamespace }}
shareProcessNamespace: true
{{- end }}
{{- with $v.hostAliases }}
hostAliases:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- $inits := concat (.initContainers | default list) ($v.initContainers | default list) }}
{{- with $inits }}
initContainers:
  {{- toYaml . | nindent 2 }}
{{- end }}
containers:
  - name: {{ .containerName | default $root.Chart.Name }}
    image: {{ include "vegaduta-lib.image" $root | quote }}
    imagePullPolicy: {{ include "vegaduta-lib.imagePullPolicy" $root }}
    {{- with $v.workingDir }}
    workingDir: {{ . }}
    {{- end }}
    {{- with .command }}
    command:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    {{- with (.args | default $v.args) }}
    args:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    {{- if and (not .job) $v.containerPort }}
    ports:
      - name: http
        containerPort: {{ $v.containerPort }}
        protocol: TCP
      {{- range $v.extraPorts }}
      - name: {{ .name }}
        containerPort: {{ .containerPort }}
        protocol: {{ .protocol | default "TCP" }}
      {{- end }}
    {{- end }}
    {{- $envList := include "vegaduta-lib.env" (dict "root" $root "env" .env "secretEnv" .secretEnv) | trim }}
    {{- if $envList }}
    env:
      {{- $envList | nindent 6 }}
    {{- end }}
    {{- $envFrom := concat (.envFrom | default list) ($v.envFrom | default list) }}
    {{- if $envFrom }}
    envFrom:
      {{- tpl (toYaml $envFrom) $root | nindent 6 }}
    {{- end }}
    resources:
      {{- include "vegaduta-lib.resources" $root | nindent 6 }}
    securityContext:
      {{- include "vegaduta-lib.securityContext" $root | nindent 6 }}
    {{- if not (or .job .noProbes) }}
    {{- include "vegaduta-lib.probes" $root | nindent 4 }}
    {{- with $v.lifecycle }}
    lifecycle:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    {{- end }}
    {{- if or $v.emptyDirs $pers.enabled $v.extraVolumeMounts }}
    volumeMounts:
      {{- range $v.emptyDirs }}
      - name: {{ .name }}
        mountPath: {{ .mountPath }}
      {{- end }}
      {{- if and $pers.enabled (not .job) }}
      - name: data
        mountPath: {{ $pers.mountPath | default "/data" }}
      {{- end }}
      {{- with $v.extraVolumeMounts }}
      {{- toYaml . | nindent 6 }}
      {{- end }}
    {{- end }}
{{- if or $v.emptyDirs (and $pers.enabled (not .job)) $v.extraVolumes }}
volumes:
  {{- range $v.emptyDirs }}
  - name: {{ .name }}
    emptyDir:
      {{- if .medium }}
      medium: {{ .medium }}
      {{- end }}
      {{- if .sizeLimit }}
      sizeLimit: {{ .sizeLimit | quote }}
      {{- end }}
  {{- end }}
  {{- if and $pers.enabled (not .job) }}
  - name: data
    persistentVolumeClaim:
      claimName: {{ $pers.existingClaim | default (printf "%s-data" (include "vegaduta-lib.fullname" $root)) }}
  {{- end }}
  {{- with $v.extraVolumes }}
  {{- toYaml . | nindent 2 }}
  {{- end }}
{{- end }}
{{- with $v.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $v.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- if $v.affinity }}
affinity:
  {{- toYaml $v.affinity | nindent 2 }}
{{- else if not .job }}
{{- /* >1 replica (or an HPA): prefer different nodes, so one node failure never takes
  every replica (the topology spread below is only ScheduleAnyway). Soft, so a
  single-node cluster still schedules. Set `affinity` to override. */}}
{{- if include "vegaduta-lib.multiReplica" $root }}
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              {{- include "vegaduta-lib.selectorLabels" $root | nindent 14 }}
{{- end }}
{{- end }}
{{- if not .job }}
{{- $tsc := include "vegaduta-lib.topologySpread" $root | trim }}
{{- if $tsc }}
topologySpreadConstraints:
  {{- $tsc | nindent 2 }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Deployment. Takes dict {root, env, secretEnv, envFrom, initContainers, podAnnotations}.
  Default strategy: RollingUpdate maxUnavailable 0 / maxSurge 1 (zero downtime);
  a chart with a ReadWriteOnce PVC must use Recreate (guarded). */}}
{{- define "vegaduta-lib.deployment" -}}
{{- $root := .root -}}
{{- $v := $root.Values -}}
{{- include "vegaduta-lib.validate" $root -}}
{{- $pers := $v.persistence | default dict -}}
{{- $strategy := $v.strategy | default (dict "type" "RollingUpdate" "rollingUpdate" (dict "maxUnavailable" 0 "maxSurge" 1)) -}}
{{- if and $pers.enabled (not $pers.existingClaim) (has "ReadWriteOnce" ($pers.accessModes | default (list "ReadWriteOnce"))) (ne $strategy.type "Recreate") -}}
{{- fail (printf "%s has a ReadWriteOnce PVC: strategy.type must be Recreate (a surge pod cannot attach the volume)" $root.Chart.Name) -}}
{{- end -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "vegaduta-lib.fullname" $root }}
  namespace: {{ $root.Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" $root | nindent 4 }}
  {{- /* Argo CD ordering (argocd.syncWave): Secrets -2, database -1, migrate Job 0,
    core family 1. Helm ignores the annotation. */}}
  {{- with ($v.argocd | default dict).syncWave }}
  annotations:
    argocd.argoproj.io/sync-wave: {{ . | toString | quote }}
  {{- end }}
spec:
  {{- if not (include "vegaduta-lib.autoscaled" $root) }}
  replicas: {{ $v.replicaCount | default 1 }}
  {{- end }}
  revisionHistoryLimit: {{ $v.revisionHistoryLimit | default 5 }}
  strategy:
    {{- toYaml $strategy | nindent 4 }}
  selector:
    matchLabels:
      {{- include "vegaduta-lib.selectorLabels" $root | nindent 6 }}
  template:
    metadata:
      {{- $ann := merge (dict) (.podAnnotations | default dict) ($v.podAnnotations | default dict) }}
      {{- with $ann }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      labels:
        {{- include "vegaduta-lib.labels" $root | nindent 8 }}
        {{- with $v.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      {{- include "vegaduta-lib.podSpec" (dict "root" $root "env" .env "secretEnv" .secretEnv "envFrom" .envFrom "initContainers" .initContainers) | nindent 6 }}
{{- end -}}

{{- define "vegaduta-lib.service" -}}
{{- $v := .Values -}}
{{- $s := $v.service | default dict -}}
{{- if and $v.containerPort (include "vegaduta-lib.on" (dict "obj" $s "key" "enabled")) }}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
  {{- with $s.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  type: {{ $s.type | default "ClusterIP" }}
  {{- /* Opt-in, needs Kubernetes 1.31+ (1.33 GA): keep pod-to-pod calls (web to API, API to sidecar) in the
       caller's zone when a healthy endpoint exists there, which removes a cross-zone hop (about a millisecond
       and a transfer charge) from every call. Unset = cluster default routing. */}}
  {{- with $s.trafficDistribution }}
  trafficDistribution: {{ . }}
  {{- end }}
  ports:
    - name: http
      port: {{ $s.port | default $v.containerPort }}
      targetPort: http
      protocol: TCP
      {{- if and (eq ($s.type | default "ClusterIP") "NodePort") $s.nodePort }}
      nodePort: {{ $s.nodePort }}
      {{- end }}
    {{- range $v.extraPorts }}
    {{- if .service }}
    - name: {{ .name }}
      port: {{ .servicePort | default .containerPort }}
      targetPort: {{ .name }}
      protocol: {{ .protocol | default "TCP" }}
    {{- end }}
    {{- end }}
  selector:
    {{- include "vegaduta-lib.selectorLabels" . | nindent 4 }}
{{- end }}
{{- end -}}

{{/* ServiceAccount. Token automount is OFF; add cloud identity via serviceAccount.annotations. */}}
{{- define "vegaduta-lib.serviceAccount" -}}
{{- $sa := .Values.serviceAccount | default dict -}}
{{- if include "vegaduta-lib.on" (dict "obj" $sa "key" "create") }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "vegaduta-lib.serviceAccountName" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
  {{- with $sa.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: false
{{- end }}
{{- end -}}

{{/* "true" when something other than replicaCount owns the replica count: an HPA or a KEDA ScaledObject. */}}
{{- define "vegaduta-lib.autoscaled" -}}
{{- if or ((.Values.hpa | default dict).enabled) ((.Values.keda | default dict).enabled) }}true{{ end -}}
{{- end -}}

{{/* "true" when the workload can run more than one replica: replicaCount > 1 or an autoscaler. */}}
{{- define "vegaduta-lib.multiReplica" -}}
{{- if or (include "vegaduta-lib.autoscaled" .) (gt (int (.Values.replicaCount | default 1)) 1) }}true{{ end -}}
{{- end -}}

{{/* Lower bound of whichever autoscaler is on (default 2). */}}
{{- define "vegaduta-lib.autoscaleMin" -}}
{{- $k := .Values.keda | default dict -}}
{{- $h := .Values.hpa | default dict -}}
{{- if $k.enabled }}{{ $k.minReplicas | default 2 }}{{ else }}{{ $h.minReplicas | default 2 }}{{ end -}}
{{- end -}}

{{/* HPA (autoscaling/v2). Off unless hpa.enabled. hpa.metrics appends extra MetricSpecs to the CPU (and
  optional memory) target; the HPA scales to the highest replica count any of them asks for. */}}
{{- define "vegaduta-lib.hpa" -}}
{{- $h := .Values.hpa | default dict -}}
{{- if $h.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "vegaduta-lib.fullname" . }}
  minReplicas: {{ $h.minReplicas | default 2 }}
  maxReplicas: {{ $h.maxReplicas | default 4 }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ $h.targetCPUUtilizationPercentage | default 75 }}
    {{- with $h.targetMemoryUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ . }}
    {{- end }}
    {{- with $h.metrics }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- with $h.behavior }}
  behavior:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
{{- end -}}

{{/* KEDA ScaledObject (keda.sh/v1alpha1). Off unless keda.enabled. KEDA creates and owns the HPA, so it
  replaces hpa.* (setting both fails). keda.triggers is passed through untouched: see
  deploy/helm/examples/values-autoscaling.yaml for the concurrent-requests trigger. Needs KEDA installed. */}}
{{- define "vegaduta-lib.keda" -}}
{{- $k := .Values.keda | default dict -}}
{{- if $k.enabled }}
{{- if (.Values.hpa | default dict).enabled }}
{{- fail (printf "%s: hpa.enabled and keda.enabled are both true. KEDA creates the HorizontalPodAutoscaler itself; turn one off" .Chart.Name) }}
{{- end }}
{{- if not $k.triggers }}
{{- fail (printf "%s: keda.enabled needs keda.triggers (see deploy/helm/examples/values-autoscaling.yaml)" .Chart.Name) }}
{{- end }}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    name: {{ include "vegaduta-lib.fullname" . }}
  minReplicaCount: {{ $k.minReplicas | default 2 }}
  maxReplicaCount: {{ $k.maxReplicas | default 4 }}
  pollingInterval: {{ $k.pollingInterval | default 15 }}
  {{- with $k.behavior }}
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        {{- toYaml . | nindent 8 }}
  {{- end }}
  triggers:
    {{- toYaml $k.triggers | nindent 4 }}
{{- end }}
{{- end -}}

{{/* PDB. At 1 replica a minAvailable:1 budget blocks every node drain, so the
  default is maxUnavailable:1 at <=1 replica and minAvailable:1 above. */}}
{{- define "vegaduta-lib.pdb" -}}
{{- $p := .Values.pdb | default dict -}}
{{- if include "vegaduta-lib.on" (dict "obj" $p "key" "enabled") }}
{{- $replicas := .Values.replicaCount | default 1 | int -}}
{{- if include "vegaduta-lib.autoscaled" . }}{{ $replicas = include "vegaduta-lib.autoscaleMin" . | int }}{{ end }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
spec:
  {{- if hasKey $p "minAvailable" }}
  minAvailable: {{ $p.minAvailable }}
  {{- else if hasKey $p "maxUnavailable" }}
  maxUnavailable: {{ $p.maxUnavailable }}
  {{- else if gt $replicas 1 }}
  minAvailable: 1
  {{- else }}
  maxUnavailable: 1
  {{- end }}
  {{- /* A crash-looping (never Ready) pod may always be evicted: otherwise it holds
    the budget hostage and blocks every node drain (Kubernetes >= 1.27). */}}
  unhealthyPodEvictionPolicy: {{ $p.unhealthyPodEvictionPolicy | default "AlwaysAllow" }}
  selector:
    matchLabels:
      {{- include "vegaduta-lib.selectorLabels" . | nindent 6 }}
{{- end }}
{{- end -}}

{{/* PVC "<fullname>-data" when persistence.enabled and no existingClaim. */}}
{{- define "vegaduta-lib.pvc" -}}
{{- $p := .Values.persistence | default dict -}}
{{- if and $p.enabled (not $p.existingClaim) }}
{{- $g := .Values.global | default dict -}}
{{- $sc := $p.storageClass | default $g.storageClass -}}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
  annotations:
    helm.sh/resource-policy: keep
spec:
  accessModes:
    {{- toYaml ($p.accessModes | default (list "ReadWriteOnce")) | nindent 4 }}
  {{- if $sc }}
  storageClassName: {{ if eq $sc "-" }}""{{ else }}{{ $sc | quote }}{{ end }}
  {{- end }}
  resources:
    requests:
      storage: {{ required "persistence.size is required" $p.size | quote }}
{{- end }}
{{- end -}}

{{/* ServiceMonitor (Prometheus Operator CRD; optional). Enabled per chart or via global.serviceMonitor.enabled. */}}
{{- define "vegaduta-lib.serviceMonitor" -}}
{{- $m := .Values.serviceMonitor | default dict -}}
{{- $gm := (.Values.global | default dict).serviceMonitor | default dict -}}
{{- $on := $gm.enabled -}}
{{- if hasKey $m "enabled" }}{{ $on = $m.enabled }}{{ end -}}
{{- if and $on $m.path .Values.containerPort }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
    {{- with (merge (dict) ($m.labels | default dict) ($gm.labels | default dict)) }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  selector:
    matchLabels:
      {{- include "vegaduta-lib.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: {{ $m.path }}
      interval: {{ $m.interval | default $gm.interval | default "30s" }}
{{- end }}
{{- end -}}

{{/* `helm test` pod: GET http://<service>:<port><tests.path> must answer < 500.
  Values: tests.enabled (default true when a Service exists), tests.path,
  global.tests.image (default docker.io/curlimages/curl). */}}
{{- define "vegaduta-lib.testPod" -}}
{{- $t := .Values.tests | default dict -}}
{{- $s := .Values.service | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $gt := $g.tests | default dict -}}
{{- if and .Values.containerPort (include "vegaduta-lib.on" (dict "obj" $s "key" "enabled")) (include "vegaduta-lib.on" (dict "obj" $t "key" "enabled")) }}
{{- $img := $gt.image | default (dict "registry" "docker.io" "repository" "curlimages/curl" "tag" "8.10.1") -}}
{{- $url := printf "http://%s:%v%s" (include "vegaduta-lib.fullname" .) ($s.port | default .Values.containerPort) ($t.path | default "/") -}}
apiVersion: v1
kind: Pod
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}-test
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labelsWith" (dict "root" . "labels" (dict "app.kubernetes.io/name" (printf "%s-test" (include "vegaduta-lib.name" .)) "app.kubernetes.io/component" "test" "vegaduta.io/helm-test" "true")) | nindent 4 }}
  annotations:
    helm.sh/hook: test
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    runAsGroup: 101
    seccompProfile:
      type: RuntimeDefault
  {{- include "vegaduta-lib.imagePullSecrets" . | nindent 2 }}
  containers:
    - name: curl
      image: {{ include "vegaduta-lib.image" (dict "root" . "image" $img) | quote }}
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c"]
      args:
        - >-
          code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 --retry 5 --retry-delay 5 --retry-all-errors {{ $url }});
          echo "GET {{ $url }} -> $code";
          [ "$code" -ge 200 ] && [ "$code" -lt 500 ]
      resources:
        requests: { cpu: 10m, memory: 16Mi }
        limits: { memory: 64Mi }
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: [ALL]
{{- end }}
{{- end -}}

{{/* Render a list of documents, skipping empty ones. Takes a list of strings. */}}
{{- define "vegaduta-lib.docs" -}}
{{- range . }}
{{- if trim . }}
---
{{ trim . }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Deployment for a non-core service: values.env merged over the chart's
  computed env (values win), localhost refused. Takes dict {root, env, secretEnv, initContainers}. */}}
{{- define "vegaduta-lib.serviceDeployment" -}}
{{- $root := .root -}}
{{- $env := mergeOverwrite (deepCopy (.env | default dict)) (deepCopy ($root.Values.env | default dict)) -}}
{{- range $k, $val := $env -}}
{{- if regexMatch "^(https?|wss?)://(localhost|127\\.0\\.0\\.1)([:/]|$)" (toString $val) -}}
{{- fail (printf "%s env %s=%s points at localhost; inside a pod that is the pod itself. Use in-cluster Service DNS or the public hostname." $root.Chart.Name $k $val) -}}
{{- end -}}
{{- end -}}
{{- include "vegaduta-lib.deployment" (dict "root" $root "env" $env "secretEnv" .secretEnv "initContainers" .initContainers) -}}
{{- end -}}

{{/* Guards shared by the non-core service charts. */}}
{{- define "vegaduta-lib.serviceGuard" -}}
{{- $v := .Values -}}
{{- range ($v.secretEnv | default list) -}}
{{- if or (eq .name "APP_ENCRYPTION_KEY") (eq (.key | default "") "APP_ENCRYPTION_KEY") -}}
{{- fail (printf "%s must never receive APP_ENCRYPTION_KEY (core family only)" $.Chart.Name) -}}
{{- end -}}
{{- end -}}
{{- if hasKey ($v.env | default dict) "APP_ENCRYPTION_KEY" -}}
{{- fail (printf "%s must never receive APP_ENCRYPTION_KEY (core family only)" .Chart.Name) -}}
{{- end -}}
{{- if $v.singleReplica -}}
{{- if ne (int $v.replicaCount) 1 -}}
{{- fail (printf "%s replicaCount must be exactly 1 (got %v): %s" .Chart.Name $v.replicaCount ($v.singleReplicaReason | default "state is held in-process")) -}}
{{- end -}}
{{- if include "vegaduta-lib.autoscaled" . -}}
{{- fail (printf "%s must not be autoscaled (single replica)" .Chart.Name) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Guards for charts that run untrusted tenant code (tool-sandbox, mcp-stdio-runner). */}}
{{- define "vegaduta-lib.sandboxGuard" -}}
{{- include "vegaduta-lib.serviceGuard" . -}}
{{- $v := .Values -}}
{{- if and $v.requireRuntimeClass (not $v.runtimeClassName) -}}
{{- fail (printf "%s runs untrusted tenant code: set runtimeClassName to a VM-isolating RuntimeClass (Kata, gVisor). For local evaluation only, set requireRuntimeClass=false." .Chart.Name) -}}
{{- end -}}
{{- if $v.automountServiceAccountToken -}}{{- fail (printf "%s must not automount a ServiceAccount token" .Chart.Name) -}}{{- end -}}
{{- if ($v.serviceAccount | default dict).annotations -}}
{{- fail (printf "%s must carry no cloud identity (serviceAccount.annotations must be empty)" .Chart.Name) -}}
{{- end -}}
{{- if or $v.envFrom $v.shareProcessNamespace -}}
{{- fail (printf "%s: envFrom and shareProcessNamespace are not allowed on a sandbox pod" .Chart.Name) -}}
{{- end -}}
{{- range ($v.secretEnv | default list) -}}
{{- if not (has (.key | default .name) $v.sandboxAllowedSecretKeys) -}}
{{- fail (printf "%s: secret key %q is not allowed; no platform credential may reach a sandbox pod" $.Chart.Name (.key | default .name)) -}}
{{- end -}}
{{- end -}}
{{- range ($v.extraVolumes | default list) -}}
{{- if or (hasKey . "secret") (hasKey . "projected") (hasKey . "csi") (hasKey . "hostPath") -}}
{{- fail (printf "%s: secret/projected/csi/hostPath volumes are not allowed on a sandbox pod" $.Chart.Name) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* A secretEnv entry reading one key of the shared internal-token Secret. Takes dict {root, name, key?, optional?}. */}}
{{- define "vegaduta-lib.internalSecretEnv" -}}
- name: {{ .name }}
  secretName: {{ include "vegaduta-lib.internalSecretName" .root }}
  key: {{ .key | default .name }}
  {{- if .optional }}
  optional: true
  {{- end }}
{{- end -}}

{{/* Everything a typical HTTP service chart renders besides its Deployment. */}}
{{- define "vegaduta-lib.standardResources" -}}
{{- include "vegaduta-lib.docs" (list
  (include "vegaduta-lib.serviceAccount" .)
  (include "vegaduta-lib.service" .)
  (include "vegaduta-lib.pvc" .)
  (include "vegaduta-lib.hpa" .)
  (include "vegaduta-lib.keda" .)
  (include "vegaduta-lib.pdb" .)
  (include "vegaduta-lib.ingress" .)
  (include "vegaduta-lib.httpRoute" .)
  (include "vegaduta-lib.networkPolicy" .)
  (include "vegaduta-lib.serviceMonitor" .)
) -}}
{{- end -}}
