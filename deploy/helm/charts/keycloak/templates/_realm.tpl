{{/* The realm import, built as data and serialised with toJson (no hand-written JSON).
  The core client secret is the Keycloak import placeholder ${env.KC_CORE_CLIENT_SECRET},
  resolved from the pod env at import time - the ConfigMap holds no secret. */}}
{{- define "keycloak.realmJson" -}}
{{- $r := .Values.realm -}}
{{- $realm := include "vegaduta-lib.oidc.realm" . -}}
{{- $o := (.Values.global | default dict).oidc | default dict -}}
{{- $app := include "vegaduta-lib.publicUrl" (dict "root" . "key" "app") -}}
{{- $admin := include "vegaduta-lib.publicUrl" (dict "root" . "key" "admin") -}}
{{- $tenantMapper := dict "name" "tenant_id" "protocol" "openid-connect" "protocolMapper" "oidc-usermodel-attribute-mapper" "consentRequired" false "config" (dict "user.attribute" "tenant_id" "claim.name" "tenant_id" "jsonType.label" "String" "id.token.claim" "true" "access.token.claim" "true" "userinfo.token.claim" "true" "multivalued" "false") -}}
{{- $core := dict "clientId" ($o.clientId | default "agentic-ai-core") "name" "VegaDuta core (resource server + admin API)" "protocol" "openid-connect" "publicClient" false "standardFlowEnabled" false "serviceAccountsEnabled" true "directAccessGrantsEnabled" false "secret" "${env.KC_CORE_CLIENT_SECRET}" "protocolMappers" (list $tenantMapper) -}}
{{- /* The admin host is one of web's redirect URIs because the merged
  platform-admin console (was web-admin) is served by the same web app on
  admin.<domain> via ingress.extraHostKeys. The separate $adm client is
  kept for installs that still run the legacy console. */ -}}
{{- $web := dict "clientId" ($o.webClientId | default "agentic-ai-web") "name" "VegaDuta web console" "protocol" "openid-connect" "publicClient" true "standardFlowEnabled" true "directAccessGrantsEnabled" false "redirectUris" (concat (list (printf "%s/*" $app) (printf "%s/*" $admin)) ($r.extraRedirectUris | default list)) "webOrigins" (concat (list $app $admin) ($r.extraWebOrigins | default list)) "attributes" (dict "pkce.code.challenge.method" "S256" "post.logout.redirect.uris" (printf "%s/* %s/*" $app $admin)) "protocolMappers" (list $tenantMapper) -}}
{{- $adm := dict "clientId" ($o.adminClientId | default "agentic-ai-platform-admin") "name" "VegaDuta platform-admin console" "protocol" "openid-connect" "publicClient" true "standardFlowEnabled" true "directAccessGrantsEnabled" false "redirectUris" (list (printf "%s/*" $admin)) "webOrigins" (list $admin) "attributes" (dict "pkce.code.challenge.method" "S256" "post.logout.redirect.uris" (printf "%s/*" $admin)) -}}
{{- $roles := list
  (dict "name" "tenant-admin" "description" "Full admin over their own tenant.")
  (dict "name" "platform-admin" "description" "Cross-tenant platform operator. Assign manually to operators only.")
  (dict "name" "agent-builder" "description" "Creates and edits agents; no billing or user management.")
  (dict "name" "delegated-user" "description" "Configures an agent on behalf of an end user.")
  (dict "name" "end-customer" "description" "Scoped role for the customer-facing widget.") -}}
{{- $sa := dict "username" (printf "service-account-%s" $core.clientId) "enabled" true "serviceAccountClientId" $core.clientId "clientRoles" (dict "realm-management" (list "view-users" "query-users" "manage-users" "query-groups" "view-realm" "view-identity-providers" "manage-identity-providers")) -}}
{{- $csp := printf "frame-src 'self'; frame-ancestors 'self' %s %s; object-src 'none';" $app $admin -}}
{{- $doc := dict
  "realm" $realm
  "enabled" true
  "displayName" ($r.displayName | default "VegaDuta")
  "sslRequired" "external"
  "registrationAllowed" $r.registrationAllowed
  "registrationEmailAsUsername" false
  "verifyEmail" $r.verifyEmail
  "loginWithEmailAllowed" true
  "rememberMe" true
  "resetPasswordAllowed" true
  "bruteForceProtected" true
  "permanentLockout" false
  "failureFactor" 10
  "waitIncrementSeconds" 60
  "maxFailureWaitSeconds" 900
  "passwordPolicy" "length(12) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)"
  "eventsEnabled" true
  "eventsExpiration" 7776000
  "adminEventsEnabled" true
  "accessTokenLifespan" 900
  "ssoSessionIdleTimeout" 14400
  "browserSecurityHeaders" (dict "contentSecurityPolicy" $csp)
  "roles" (dict "realm" $roles)
  "clients" (concat (list $core $web $adm) ($r.extraClients | default list))
  "users" (list $sa) -}}
{{- toPrettyJson $doc -}}
{{- end -}}

{{- define "keycloak.adminSecretName" -}}
{{- .Values.admin.existingSecret | default (printf "%s-admin" (include "vegaduta-lib.fullname" .)) -}}
{{- end -}}
