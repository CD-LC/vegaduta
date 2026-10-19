# Core security hardening (pre-release review, 2026-09-30)

This page records what the core fixes for the pre-release security review
changed, the settings they added, the behaviour operators will notice, and the
risk that remains. Finding IDs (`SEC-0xx`) refer to that review.

## Settings added or changed

| Property | Default | What it does |
|---|---|---|
| `app.security.outbound.allowed-private-hosts` | empty | Comma-separated `host` or `host:port` entries that tenant-configured outbound requests may reach even though they resolve to a private address. Needed for a self-hosted Ollama, hosted-pool row, OpenAPI source, A2A peer or webhook on a Compose/cluster host (for example `ollama:11434`). Not applied to `fetch_web_page`, browser-use or the Magic Studio fetcher. Every entry is logged at startup. |
| `app.tenancy.fail-on-missing-context` | `true` (was `false`) | A thread with no tenant context resolves to the never-created schema `tenant_context_missing`, so tenant-table access fails instead of reading the shared `public` pool. A tenant JWT without context throws. Platform work is marked with `PlatformScope.run/call`. `false` restores the old fallback and logs a warning. |
| `app.sharing.default-ttl` | `P30D` | Lifetime of new agent and workflow share links. Zero or negative: new links never expire. Links created before this change (`expires_at` NULL) keep working. Expired links answer 410. |
| `app.agent.share.visitor-token-secret` | empty | HMAC key for share visitor session tokens. Empty: derived from `app.encryption.key`; if that is also absent, a random per-boot key (visitors lose continuity on restart). |
| `app.storage.local.legacy-unprefixed-fallback` | `false` | Local-disk storage no longer serves unprefixed legacy keys to tenants unless enabled. |
| `app.oauth.state.require-browser-cookie` | `false` | When `true`, the Google/X/Atlassian OAuth callback also requires the HttpOnly binding cookie set when the flow started. Turn on only after the web app sends `credentials: 'include'` on the authorize-url call and CORS allows credentials for the console origin. |
| `app.security.jwt.required-audience` | empty (off) | When set, access tokens must carry one of these `aud` values. Stock Keycloak public-client tokens only carry `aud=account`; add an Audience mapper first. |
| `app.sso.tenant-idp.first-broker-login-flow` | `first broker login` | First-broker-login flow pinned on every tenant IdP. |
| `app.x402.allowed-payees` | empty | x402 auto-pay payee allow-list. Empty refuses every payment. |
| `app.x402.tenant-daily-budget-usdc` | `5` | Rolling 24 h x402 budget per tenant (Redis when present, otherwise per node). |
| `app.guardrails.tenant-rules.max-regex-steps` | `5000000` | Step budget for one tenant guardrail regex evaluation. |
| `app.guardrails.tenant-rules.regex-timeout-ms` | `250` | Wall-clock budget for one tenant guardrail regex evaluation. |
| `app.teams.service-url-allowed-hosts` | Bot Framework hosts | Hosts a Teams activity `serviceUrl` may name before the bot's AAD token is sent there. |

`app.keycloak.allowed-azp` now also rejects tokens that carry no `azp`. Leaving
it empty still disables the check (emergency lever only).

## Behaviour operators will notice

- **New tenant schemas** are named `t_<26 random characters>`, never after the
  organisation name. Existing tenants keep their schema names.
- **Vault secrets in `http_request`, workflow HTTP nodes and OpenAPI sources**
  resolve only for hosts listed on the secret (`allowedHosts` on
  `POST /api/secrets`, or `PUT /api/secrets/{id}/allowed-hosts`). A secret with
  no allowed hosts is never sent to a URL. Existing secrets used this way stop
  resolving until an admin sets their hosts. MCP server env and other non-URL
  uses are unchanged.
- **BYOK / hosted-pool / OpenAPI / A2A / webhook URLs** that point at a
  single-label or private host (for example `http://ollama:11434`) are refused
  unless listed in `app.security.outbound.allowed-private-hosts`.
- **MCP STDIO servers** skip staff review only when command, args (package,
  version, order) and env keys exactly match the catalog entry. Env names in the
  `NODE_*`, `PYTHON*`, `npm_config_*`, `UV_*`, `PIP_*`, `OPENSSL_*`, `LD_*` and
  `*_PROXY` families are refused (a few benign ones such as `NODE_ENV` and
  `PYTHONUNBUFFERED` stay allowed). Stored servers using a now-blocked name must
  be re-saved.
- **Community template moderation** (approve, reject, verify, pending list)
  needs `ROLE_platform-admin`; tenant admins see approved entries and their own
  submissions only.
- **Share chat** issues a signed visitor token (`sessionToken` in the response
  body, `X-Session-Id` header) instead of hashing the caller's IP. The web client
  must send it back (`sessionToken` in the body or `X-Share-Session` header) to
  continue a conversation; until it does, each turn starts a new conversation.
- **Chat history** in Redis is keyed by tenant, agent, principal and client
  session id. Conversations in progress at upgrade start with empty history.
- **Graph memory** no longer needs a superuser: core skips `LOAD` when `age` is
  in `shared_preload_libraries`, otherwise loads `$libdir/plugins/age`.

## Tenant single sign-on (bring-your-own IdP)

Each tenant's identity provider is created in the shared Keycloak realm under
the alias `tenant-<tenantId>`. Core hardens every tenant IdP on each save:

- Email addresses asserted by the tenant IdP are never trusted as verified
  (`trustEmail=false`).
- The IdP is hidden from the shared login page (`hideOnLogin=true`, and
  `hideOnLoginPage=true` for older Keycloak). Tenant users reach it with
  `kc_idp_hint=tenant-<tenantId>` on the authorization request; the tenant's
  login link or the console's "Sign in with SSO" entry must carry that hint.
- A hardcoded-attribute mapper (`vegaduta-tenant-id`, sync mode FORCE) sets
  `tenant_id` to the owning tenant on every login, so a tenant IdP cannot place
  a user in another tenant.
- The first-broker-login flow is pinned to
  `app.sso.tenant-idp.first-broker-login-flow`. Because email is not trusted,
  Keycloak's built-in flow does not link an IdP identity to an existing account
  until the user proves ownership. For stricter isolation, import a dedicated
  flow that never links existing accounts and point the property at it.
- The discovery, token, userinfo and JWKS URLs must be https and resolve only to
  public addresses. Keycloak re-resolves these hosts itself, so Keycloak's own
  egress should also be filtered.
- Keycloak Organizations are not used yet, so there is no verified-domain
  discovery or email-domain restriction.

## Outbound request guard

`core/security/.../net/SsrfGuard` is the one classification and check for
tenant-, model- or internet-supplied URLs. `SafeHttpClients` builds Apache
HttpClient 5 clients whose DNS resolver is the guard: the host is resolved
once, every address is checked (loopback, RFC 1918, link-local incl. cloud
metadata, CGNAT, Azure WireServer `168.63.129.16`, reserved/benchmark/multicast
ranges, IPv6 unique-local, and IPv6 forms embedding IPv4), and the socket goes
to one of the checked addresses. No system proxy is used. Redirects are off or
re-validated per hop.

Pinned today: `http_request`, `fetch_web_page`, OpenAPI tools, the web-crawl and
Confluence connectors, BYOK Ollama/Azure validation and blocking chat calls,
A2A card fetch, outbound webhooks, and the Magic Studio fetcher.

## Residual risk

- **Not address-pinned** (validated immediately before use, redirects off):
  streaming LLM calls on the JDK client, the platform-admin hosted-pool chat
  call, and the MCP SDK's SSE/streamable-HTTP transports (the SSE `endpoint`
  event is held to the registered origin).
- **browser-use:** Chromium does its own DNS. Redirected subresources can be
  requested before the session is tainted and closed. Keep network egress
  controls (proxy or network policy blocking private ranges) in place before
  enabling browser-use.
- **OAuth state binding** is server-side, single-use and short-lived, but the
  browser cookie is optional until the web app sends credentials. The nonce
  store is per JVM (callbacks on another replica fail closed).
- **Workflow webhooks** still authenticate by path token (looked up by hash,
  masked in core logs, but visible in upstream proxy/CDN logs). HMAC-signed
  webhooks are not built.
- **SCIM and admin invite** can still pre-register any email address; the
  seat limit is enforced.
- **Existing MCP registrations** marked review-not-required under the old
  name-only rule are re-evaluated only when next saved.
- **Legacy tenant schemas** named after an organisation are protected from the
  drop paths but not renamed.
- **Dependencies:** `bcprov-jdk18on` stays at 1.80 and `poi-ooxml` at 5.3.0
  (no newer version was available to the offline build). Bump to
  bcprov >= 1.84 and poi-ooxml >= 5.4. Spring Boot 3.4 -> 3.5 is left to the Boot
  migration.
- **Core database role:** core still connects as the Postgres superuser in some
  deployments; run it as the non-superuser app role that owns only tenant
  schemas.
