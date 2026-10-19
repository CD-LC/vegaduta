{{/* ------------------------------------------------------------------
  Networking: Ingress, Gateway API HTTPRoute and NetworkPolicy.
  (File name kept from the pre-OSS layout; it no longer holds the Azure
  Key Vault "secret anchor", which the OSS charts do not need.)

  Exposure model: a chart is PUBLIC when `ingress.enabled` is true. How it is
  published is global: global.ingress.enabled -> networking.k8s.io Ingress,
  global.gateway.enabled -> gateway.networking.k8s.io/v1 HTTPRoute (both may be on).
  Host = <global.hosts.<ingress.hostKey>>.<global.domain> plus ingress.extraHosts.
------------------------------------------------------------------ */}}

{{- define "vegaduta-lib.hosts" -}}
{{- $i := .Values.ingress | default dict -}}
{{- $hosts := list -}}
{{- if $i.hostKey }}{{ $hosts = append $hosts (include "vegaduta-lib.publicHost" (dict "root" . "key" $i.hostKey)) }}{{ end -}}
{{- range ($i.extraHostKeys | default list) }}{{ $hosts = append $hosts (include "vegaduta-lib.publicHost" (dict "root" $ "key" .)) }}{{ end -}}
{{- $hosts = concat $hosts ($i.extraHosts | default list) -}}
{{- range $hosts }}
{{- if regexMatch "^(localhost|127\\.0\\.0\\.1)$" . }}{{ fail (printf "%s: public host %q must not be localhost - browsers and token issuers need a real hostname" $.Chart.Name .) }}{{ end }}
{{- end -}}
{{- toJson ($hosts | uniq) -}}
{{- end -}}

{{- define "vegaduta-lib.ingress" -}}
{{- $i := .Values.ingress | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $gi := $g.ingress | default dict -}}
{{- if and $i.enabled (include "vegaduta-lib.on" (dict "obj" $gi "key" "enabled")) }}
{{- $hosts := include "vegaduta-lib.hosts" . | fromJsonArray -}}
{{- if not $hosts }}{{ fail (printf "%s: ingress.enabled but no host (set ingress.hostKey or ingress.extraHosts)" .Chart.Name) }}{{ end -}}
{{- $tls := $g.tls | default dict -}}
{{- $tlsOn := include "vegaduta-lib.on" (dict "obj" $tls "key" "enabled") -}}
{{- $className := $i.className | default $gi.className -}}
{{- $ann := merge (dict) ($i.annotations | default dict) ($gi.annotations | default dict) -}}
{{- if and $tlsOn $tls.clusterIssuer }}{{ $_ := set $ann "cert-manager.io/cluster-issuer" $tls.clusterIssuer }}{{ end -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
  {{- with $ann }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- with $className }}
  ingressClassName: {{ . }}
  {{- end }}
  {{- if $tlsOn }}
  tls:
    - secretName: {{ $tls.secretName | default (printf "%s-tls" (include "vegaduta-lib.fullname" .)) }}
      hosts:
        {{- range $hosts }}
        - {{ . | quote }}
        {{- end }}
  {{- end }}
  rules:
    {{- range $hosts }}
    - host: {{ . | quote }}
      http:
        paths:
          {{- range ($i.paths | default (list (dict "path" "/" "pathType" "Prefix"))) }}
          - path: {{ .path }}
            pathType: {{ .pathType | default "Prefix" }}
            backend:
              service:
                name: {{ include "vegaduta-lib.fullname" $ }}
                port:
                  name: http
          {{- end }}
    {{- end }}
{{- end }}
{{- end -}}

{{- define "vegaduta-lib.httpRoute" -}}
{{- $i := .Values.ingress | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $gw := $g.gateway | default dict -}}
{{- if and $i.enabled $gw.enabled }}
{{- $hosts := include "vegaduta-lib.hosts" . | fromJsonArray -}}
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
  {{- with $gw.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  parentRefs:
    {{- if not $gw.parentRefs }}{{ fail "global.gateway.parentRefs is required when global.gateway.enabled (e.g. [{name: public, namespace: gateway-system}])" }}{{ end }}
    {{- toYaml $gw.parentRefs | nindent 4 }}
  hostnames:
    {{- range $hosts }}
    - {{ . | quote }}
    {{- end }}
  rules:
    {{- range ($i.paths | default (list (dict "path" "/" "pathType" "Prefix"))) }}
    - matches:
        - path:
            type: {{ if eq (.pathType | default "Prefix") "Exact" }}Exact{{ else }}PathPrefix{{ end }}
            value: {{ .path }}
      backendRefs:
        - name: {{ include "vegaduta-lib.fullname" $ }}
          port: {{ ($.Values.service | default dict).port | default $.Values.containerPort }}
      {{- with $i.gatewayTimeouts }}
      timeouts:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    {{- end }}
{{- end }}
{{- end -}}

{{/* podSelector peer for a sibling component of the same release. Takes dict {root, name}. */}}
{{- define "vegaduta-lib.siblingPeer" -}}
podSelector:
  matchLabels:
    app.kubernetes.io/name: {{ .name }}
    app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* NetworkPolicy for this chart's pods (both policy types => default deny for
  them). Allowed:
    ingress: networkPolicy.allowFrom sibling components, on this chart's SERVICE ports
             only (http + extraPorts with service: true; an entry may be
             {name, ports: [..]} to open other named ports to that one peer, or
             networkPolicy.allowFromAllPorts: true to open every port); the ingress controller
             namespace (Ingress) and/or global.networkPolicy.gatewayNamespace
             (Gateway API) when the chart is public; the monitoring namespace when a
             ServiceMonitor is on (chart-local OR global switch, same predicate as the
             ServiceMonitor template); `helm test` pods; networkPolicy.extraIngress
    egress : DNS; networkPolicy.allowEgressTo sibling components (any port);
             the internet minus private ranges on externalEgressPorts when
             networkPolicy.allowExternalEgress; for the core family
             (networkPolicy.integrationEgress=true) the ports derived from the
             configured integrations (vegaduta-lib.integrationEgress);
             global.networkPolicy.extraEgress rules (all components, or only those
             listed in a rule's `components:`) and networkPolicy.extraEgress.
  Enabled by networkPolicy.enabled, else global.networkPolicy.enabled. */}}
{{- define "vegaduta-lib.networkPolicy" -}}
{{- $n := .Values.networkPolicy | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $gn := $g.networkPolicy | default dict -}}
{{- $on := $gn.enabled -}}
{{- if hasKey $n "enabled" }}{{ $on = $n.enabled }}{{ end -}}
{{- if $on }}
{{- $root := . -}}
{{- $ing := list -}}
{{- /* Peers reach the service ports only: a pod that also listens on unauthenticated
  internal ports (SeaweedFS master/volume/filer, Keycloak management/JGroups) must not
  expose them to every sibling that is allowed to call its API. */ -}}
{{- $svcPorts := list -}}
{{- if .Values.containerPort }}{{ $svcPorts = append $svcPorts (dict "port" "http" "protocol" "TCP") }}{{ end -}}
{{- range (.Values.extraPorts | default list) }}{{ if .service }}{{ $svcPorts = append $svcPorts (dict "port" .name "protocol" (.protocol | default "TCP")) }}{{ end }}{{ end -}}
{{- range ($n.allowFrom | default list) -}}
{{- $peerName := . -}}
{{- $peerPorts := $svcPorts -}}
{{- if kindIs "map" . -}}
{{- $peerName = .name -}}
{{- if .ports }}{{ $peerPorts = list }}{{ range .ports }}{{ $peerPorts = append $peerPorts (dict "port" . "protocol" "TCP") }}{{ end }}{{ end -}}
{{- end -}}
{{- $rule := dict "from" (list (include "vegaduta-lib.siblingPeer" (dict "root" $root "name" $peerName) | fromYaml)) -}}
{{- if and $peerPorts (not $n.allowFromAllPorts) }}{{ $_ := set $rule "ports" $peerPorts }}{{ end -}}
{{- $ing = append $ing $rule -}}
{{- end -}}
{{- if and (.Values.ingress | default dict).enabled .Values.containerPort -}}
{{- $gi := $g.ingress | default dict -}}
{{- $gw := $g.gateway | default dict -}}
{{- if include "vegaduta-lib.on" (dict "obj" $gi "key" "enabled") -}}
{{- $peer := dict "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" ($gn.ingressControllerNamespace | default "ingress-nginx"))) -}}
{{- with $gn.ingressControllerPodLabels }}{{ $_ := set $peer "podSelector" (dict "matchLabels" .) }}{{ end -}}
{{- $ing = append $ing (dict "from" (list $peer) "ports" (list (dict "port" "http" "protocol" "TCP"))) -}}
{{- end -}}
{{- if $gw.enabled -}}
{{- /* Gateway API: the data-plane (proxy) pods usually live in the gateway
  controller's namespace (envoy-gateway-system, istio-ingress, ...), which is not
  necessarily the namespace of the Gateway object in parentRefs - so it is asked for. */ -}}
{{- if not $gn.gatewayNamespace -}}
{{- fail "global.networkPolicy.gatewayNamespace is required with global.gateway.enabled and NetworkPolicies on: the namespace of your Gateway's data-plane (proxy) pods, e.g. envoy-gateway-system" -}}
{{- end -}}
{{- $gpeer := dict "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" $gn.gatewayNamespace)) -}}
{{- with $gn.gatewayPodLabels }}{{ $_ := set $gpeer "podSelector" (dict "matchLabels" .) }}{{ end -}}
{{- $ing = append $ing (dict "from" (list $gpeer) "ports" (list (dict "port" "http" "protocol" "TCP"))) -}}
{{- end -}}
{{- end -}}
{{- /* Scrape ingress: the SAME local-or-global predicate as vegaduta-lib.serviceMonitor. */ -}}
{{- $sm := .Values.serviceMonitor | default dict -}}
{{- $smOn := ($g.serviceMonitor | default dict).enabled -}}
{{- if hasKey $sm "enabled" }}{{ $smOn = $sm.enabled }}{{ end -}}
{{- if and $smOn $sm.path .Values.containerPort -}}
{{- if not $gn.monitoringNamespace -}}
{{- fail (printf "%s: a ServiceMonitor is on but global.networkPolicy.monitoringNamespace is empty - set it to Prometheus' namespace, or the NetworkPolicy blocks every scrape" .Chart.Name) -}}
{{- end -}}
{{- $mpeer := dict "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" $gn.monitoringNamespace)) -}}
{{- with $gn.monitoringPodLabels }}{{ $_ := set $mpeer "podSelector" (dict "matchLabels" .) }}{{ end -}}
{{- $ing = append $ing (dict "from" (list $mpeer) "ports" (list (dict "port" "http" "protocol" "TCP"))) -}}
{{- end -}}
{{- if .Values.containerPort -}}
{{- $ing = append $ing (dict "from" (list (dict "podSelector" (dict "matchLabels" (dict "vegaduta.io/helm-test" "true" "app.kubernetes.io/instance" .Release.Name))))) -}}
{{- end -}}
{{- $ing = concat $ing ($n.extraIngress | default list) -}}
{{- $dnsPeer := dict "namespaceSelector" (dict "matchLabels" (dict "kubernetes.io/metadata.name" ($gn.dnsNamespace | default "kube-system"))) "podSelector" (dict "matchLabels" ($gn.dnsPodLabels | default (dict "k8s-app" "kube-dns"))) -}}
{{- $egr := list -}}
{{- if include "vegaduta-lib.on" (dict "obj" $n "key" "allowDns") -}}
{{- $egr = append $egr (dict "to" (list $dnsPeer) "ports" (list (dict "port" 53 "protocol" "UDP") (dict "port" 53 "protocol" "TCP"))) -}}
{{- end -}}
{{- range ($n.allowEgressTo | default list) -}}
{{- $egr = append $egr (dict "to" (list (include "vegaduta-lib.siblingPeer" (dict "root" $root "name" .) | fromYaml))) -}}
{{- end -}}
{{- if $n.allowExternalEgress -}}
{{- $ports := list -}}
{{- range ($n.externalEgressPorts | default (list 443)) }}{{ $ports = append $ports (dict "port" . "protocol" "TCP") }}{{ end -}}
{{- $except := $gn.privateCIDRs | default (list "10.0.0.0/8" "172.16.0.0/12" "192.168.0.0/16" "169.254.0.0/16" "100.64.0.0/10") -}}
{{- $egr = append $egr (dict "to" (list (dict "ipBlock" (dict "cidr" "0.0.0.0/0" "except" $except))) "ports" $ports) -}}
{{- end -}}
{{- if $n.integrationEgress -}}
{{- $egr = concat $egr (include "vegaduta-lib.integrationEgress" . | fromYamlArray) -}}
{{- end -}}
{{- /* LiveKit (voice-agent): signalling on global.livekit.internalUrl's port plus the
  RTC ports, to any address except link-local - an in-cluster LiveKit is on a private
  address, which allowExternalEgress deliberately excludes. */ -}}
{{- if $n.livekitEgress -}}
{{- $lk := $g.livekit | default dict -}}
{{- if $lk.internalUrl -}}
{{- $lkPorts := list (dict "port" (atoi (include "vegaduta-lib.portOf" (dict "s" $lk.internalUrl "default" "7880"))) "protocol" "TCP") -}}
{{- range ($n.livekitTcpPorts | default list) }}{{ $lkPorts = append $lkPorts (dict "port" (int .) "protocol" "TCP") }}{{ end -}}
{{- range ($n.livekitUdpPortRanges | default list) }}{{ $lkPorts = append $lkPorts (dict "port" (int .from) "endPort" (int .to) "protocol" "UDP") }}{{ end -}}
{{- $egr = append $egr (dict "to" (list (dict "ipBlock" (dict "cidr" "0.0.0.0/0" "except" (list "169.254.0.0/16")))) "ports" $lkPorts) -}}
{{- end -}}
{{- end -}}
{{- if include "vegaduta-lib.on" (dict "obj" $n "key" "globalExtraEgress") -}}
{{- $egr = concat $egr (include "vegaduta-lib.scopedExtraEgress" (dict "root" . "name" .Chart.Name) | fromYamlArray) -}}
{{- end -}}
{{- $egr = concat $egr ($n.extraEgress | default list) -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "vegaduta-lib.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "vegaduta-lib.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "vegaduta-lib.selectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
    - Egress
  {{- if $ing }}
  ingress:
    {{- toYaml $ing | nindent 4 }}
  {{- else }}
  ingress: []
  {{- end }}
  {{- if $egr }}
  egress:
    {{- toYaml $egr | nindent 4 }}
  {{- else }}
  egress: []
  {{- end }}
{{- end }}
{{- end -}}

{{/* global.networkPolicy.extraEgress filtered for one component. A rule may carry
  `components: [core, core-worker]`; without it the rule applies to every component
  (the pre-existing behaviour). The key itself is stripped from the rendered rule.
  Takes dict {root, name}. Returns a YAML list. */}}
{{- define "vegaduta-lib.scopedExtraEgress" -}}
{{- $gn := ((.root.Values.global | default dict).networkPolicy | default dict) -}}
{{- $out := list -}}
{{- range ($gn.extraEgress | default list) -}}
{{- if or (not (hasKey . "components")) (has $.name (.components | default list)) -}}
{{- $out = append $out (omit . "components") -}}
{{- end -}}
{{- end -}}
{{- toYaml $out -}}
{{- end -}}

{{/* Port of a URL or host:port string; "" when none can be derived.
  Takes dict {s, default}. */}}
{{- define "vegaduta-lib.portOf" -}}
{{- $s := .s | toString | trim -}}
{{- $p := "" -}}
{{- if contains "://" $s -}}
{{- $u := urlParse $s -}}
{{- $h := $u.host -}}
{{- if regexMatch ":[0-9]+$" $h }}{{ $p = regexFind "[0-9]+$" $h }}
{{- else if has $u.scheme (list "https" "wss") }}{{ $p = "443" }}
{{- else if has $u.scheme (list "http" "ws") }}{{ $p = "80" }}{{ end -}}
{{- else if regexMatch ":[0-9]+$" $s -}}
{{- $p = regexFind "[0-9]+$" $s -}}
{{- end -}}
{{- $p | default (.default | default "" | toString) -}}
{{- end -}}

{{/* Egress the core family (core, core-worker, core-jobs, the migrate Job) needs for
  the integrations that are CONFIGURED, so turning one on never silently breaks under
  NetworkPolicies (INF-008). Derived ports (any destination except link-local, so an
  in-cluster collector/broker on a pod IP works and the cloud metadata endpoint stays
  closed): external PostgreSQL / Valkey, OTLP traces endpoint, Kafka bootstrap servers,
  Temporal frontend, LiveKit internal URL, the OIDC internal base URL. Opt-in extras from
  global.networkPolicy.coreEgress: smtp (587/465, public addresses), otlp (4317/4318),
  ports (e.g. 11434 Ollama, 8000 vLLM, non-443 remote MCP servers) and cidrs
  ([{cidr, ports}]). Returns a YAML list of egress rules. */}}
{{- define "vegaduta-lib.integrationEgress" -}}
{{- $g := .Values.global | default dict -}}
{{- $gn := $g.networkPolicy | default dict -}}
{{- $ce := $gn.coreEgress | default dict -}}
{{- $ports := list -}}
{{- $pg := $g.postgresql | default dict -}}
{{- if $pg.host }}{{ $ports = append $ports (toString ($pg.port | default 5432)) }}{{ end -}}
{{- $vk := $g.valkey | default dict -}}
{{- if $vk.host }}{{ $ports = append $ports (toString ($vk.port | default 6379)) }}{{ end -}}
{{- $os := $g.objectStorage | default dict -}}
{{- if $os.endpoint }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $os.endpoint "default" "443")) }}{{ end -}}
{{- /* azure-blob keeps objectStorage.endpoint empty (the guard forces it); its account endpoint is objectStorage.azure.endpoint. Needed for a Private Endpoint, which the generic public-only 443 rule excludes. */ -}}
{{- if and (eq ($os.type | default "") "azure-blob") (($os.azure | default dict).endpoint) }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $os.azure.endpoint "default" "443")) }}{{ end -}}
{{- $otel := $g.otel | default dict -}}
{{- if $otel.tracesEndpoint }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $otel.tracesEndpoint "default" "4318")) }}{{ end -}}
{{- $kafka := $g.kafka | default dict -}}
{{- range (splitList "," ($kafka.bootstrapServers | default "")) -}}
{{- if trim . }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" (trim .) "default" "9092")) }}{{ end -}}
{{- end -}}
{{- $tp := $g.temporal | default dict -}}
{{- if and $tp.enabled $tp.target }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $tp.target "default" "7233")) }}{{ end -}}
{{- $lk := $g.livekit | default dict -}}
{{- if $lk.internalUrl }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $lk.internalUrl "default" "7880")) }}{{ end -}}
{{- $oidc := $g.oidc | default dict -}}
{{- if $oidc.internalBaseUrl }}{{ $ports = append $ports (include "vegaduta-lib.portOf" (dict "s" $oidc.internalBaseUrl "default" "8080")) }}{{ end -}}
{{- if $ce.otlp }}{{ $ports = concat $ports (list "4317" "4318") }}{{ end -}}
{{- range ($ce.ports | default list) }}{{ $ports = append $ports (toString .) }}{{ end -}}
{{- $rules := list -}}
{{- $anyButLinkLocal := list (dict "ipBlock" (dict "cidr" "0.0.0.0/0" "except" (list "169.254.0.0/16"))) -}}
{{- $pl := list -}}
{{- range ($ports | compact | uniq | sortAlpha) }}{{ $pl = append $pl (dict "port" (atoi .) "protocol" "TCP") }}{{ end -}}
{{- if $pl }}{{ $rules = append $rules (dict "to" $anyButLinkLocal "ports" $pl) }}{{ end -}}
{{- if $ce.smtp -}}
{{- $except := $gn.privateCIDRs | default (list "10.0.0.0/8" "172.16.0.0/12" "192.168.0.0/16" "169.254.0.0/16" "100.64.0.0/10") -}}
{{- $rules = append $rules (dict "to" (list (dict "ipBlock" (dict "cidr" "0.0.0.0/0" "except" $except))) "ports" (list (dict "port" 587 "protocol" "TCP") (dict "port" 465 "protocol" "TCP"))) -}}
{{- end -}}
{{- /* Database token login (global.postgresql.auth): the pod must reach the token issuer. Public HTTPS
  (AWS STS, login.microsoftonline.com); EKS Pod Identity's agent and GKE's metadata server are link-local,
  which every rule above excludes, so each gets ONE /32 rule. The node's own IMDS (169.254.169.254 on
  AWS/Azure) stays closed on purpose: it hands out the NODE role to any pod that can ask. */ -}}
{{- $dbAuth := include "vegaduta-lib.pg.auth" . -}}
{{- if ne $dbAuth "password" -}}
{{- $except := $gn.privateCIDRs | default (list "10.0.0.0/8" "172.16.0.0/12" "192.168.0.0/16" "169.254.0.0/16" "100.64.0.0/10") -}}
{{- $rules = append $rules (dict "to" (list (dict "ipBlock" (dict "cidr" "0.0.0.0/0" "except" $except))) "ports" (list (dict "port" 443 "protocol" "TCP"))) -}}
{{- if eq $dbAuth "aws-iam" -}}
{{- $rules = append $rules (dict "to" (list (dict "ipBlock" (dict "cidr" "169.254.170.23/32"))) "ports" (list (dict "port" 80 "protocol" "TCP"))) -}}
{{- else if eq $dbAuth "gcp-iam" -}}
{{- $rules = append $rules (dict "to" (list (dict "ipBlock" (dict "cidr" "169.254.169.254/32"))) "ports" (list (dict "port" 80 "protocol" "TCP"))) -}}
{{- end -}}
{{- end -}}
{{- range ($ce.cidrs | default list) -}}
{{- $cp := list -}}
{{- range (.ports | default list) }}{{ $cp = append $cp (dict "port" (int .) "protocol" "TCP") }}{{ end -}}
{{- $r := dict "to" (list (dict "ipBlock" (dict "cidr" .cidr))) -}}
{{- if $cp }}{{ $_ := set $r "ports" $cp }}{{ end -}}
{{- $rules = append $rules $r -}}
{{- end -}}
{{- toYaml $rules -}}
{{- end -}}
