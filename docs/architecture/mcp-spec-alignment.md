# MCP specification alignment

Where VegaDūta's MCP client (`core/mcp`, `core/agents-tools/.../tools`), MCP server (`core/mcpserver`,
`/mcp`) and stdio relay (`core/mcp-stdio-proxy`, `services/mcp-stdio-runner`) stand against the
Model Context Protocol specification, requirement by requirement, and what changed in this tranche.

## Revisions (verified against modelcontextprotocol.io on 2026-10-14)

Only the official specification site was used. Nothing below comes from a blog or a secondary summary.

| Revision | Status | Source |
|---|---|---|
| **2026-07-28** | **Current.** The versioning page says "The **current** protocol version is 2026-07-28" and defines *Current* as ready for use. It is not a release candidate and not a draft. | <https://modelcontextprotocol.io/specification/versioning> |
| Draft | Empty. The draft changelog reads "Changes since the most recent release will accumulate here" and lists nothing. | <https://modelcontextprotocol.io/specification/draft/changelog> |
| 2025-11-25 | **Final** (a past, complete revision; the versioning page defines *Final* as a past specification that will not change). It is the "previous revision" in the 2026-07-28 changelog, so no revision exists between the two. | <https://modelcontextprotocol.io/specification/versioning>, <https://modelcontextprotocol.io/specification/2026-07-28/changelog> |

The versioning page does not publish a dated list of every older revision, so earlier ones
(2025-06-18, 2025-03-26, 2024-11-05) are mentioned only where a 2026-07-28 page names them.

### What 2025-11-25 → 2026-07-28 changed (summary of the official changelog)

Major: no protocol-level sessions or `Mcp-Session-Id`; no `initialize` handshake (every request carries
`protocolVersion` and client capabilities in `_meta`); mandatory `server/discover`; `subscriptions/listen`
replaces the GET stream and `resources/subscribe`; `ping`, `logging/setLevel` and
`notifications/roots/list_changed` removed; tasks moved to an extension; Multi Round-Trip Requests replace
server-initiated requests; every result carries `resultType`; SSE resumability (`Last-Event-ID`) removed.
Minor: `Mcp-Method` and `Mcp-Name` request headers (plus `x-mcp-header`), `ttlMs`/`cacheScope` on list
results, deterministic `tools/list` order, `iss` validation (RFC 9207), `application_type` in dynamic client
registration, client credentials bound to the issuing authorization server, error-code allocation.
Deprecated: Roots, Sampling, Logging, `includeContext` values, **HTTP+SSE transport**, and **Dynamic Client
Registration** (in favour of Client ID Metadata Documents). Full text:
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>. Deprecated-features registry:
<https://modelcontextprotocol.io/specification/2026-07-28/deprecated>.

## The constraint that shapes everything: the SDK speaks the previous era

The MCP Java SDK on our classpath (`mcp-core` 0.18.x, used by the client and, through Spring AI's MCP server
starter, by `/mcp`) has protocol constants up to `2025-11-25` and none for `2026-07-28`
(checked in the jar: `ProtocolVersions` lists 2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25). Both our client and
our server are therefore **handshake-based ("legacy" in the spec's terminology)**. The 2026-07-28 versioning page
says a legacy client against a modern-only server **fails**, and a legacy server keeps working with legacy
clients; a dual-era server can serve both. So today we interoperate with every 2025-11-25-and-earlier peer and
with dual-era peers, and not with servers that dropped `initialize`. Adopting the stateless revision is an SDK
upgrade, not something to hand-roll; it is listed as **Missing** below and not attempted here.

## Requirements and status

Status: **Done** = implemented and covered by a test; **Partly** = implemented with the stated gap;
**Missing** = not implemented; **n/a** = requirement does not apply to our role. "Changed" marks rows moved by this
tranche. Source pages are under `https://modelcontextprotocol.io/specification/2026-07-28/`.

### Transport

| # | Requirement (role) | Source | Status | Where |
|---|---|---|---|---|
| T1 | Server provides one MCP endpoint path accepting POST | `basic/transports/streamable-http` | Done | `/mcp` via Spring AI starter, `application-mcpserver.yml` (`streamable-http.mcp-endpoint`), `McpServerStreamableHttpContractTest` |
| T2 | Server MUST validate `Origin`; present and invalid → 403 (body MAY be a JSON-RPC error without id) | `basic/transports/streamable-http` ("Security & Endpoint") | **Done (changed; was Missing)** | `McpOriginValidationFilter`, `McpServerToolConfig#mcpOriginValidationFilterRegistration`, `McpOriginValidationFilterTest` |
| T3 | Local servers SHOULD bind to 127.0.0.1, not 0.0.0.0 | same | **Partly** | Not changed. core runs in containers and must listen on all interfaces to be reachable; protection is T4 plus T2. For a laptop install, set `server.address=127.0.0.1`. See "Local binding" below |
| T4 | Servers SHOULD authenticate every connection | same | Done | `McpApiKeyAuthFilter` (grant keys), OAuth option (A1) |
| T5 | Client POSTs every message; `Accept` lists `application/json` and `text/event-stream`; handles JSON or SSE replies | `basic/transports/streamable-http` ("Sending Messages") | Done (by SDK) | `HttpClientStreamableHttpTransport` in `DynamicMcpConnectionManager#buildRemoteTransport`; not separately asserted by us |
| T6 | Client sends `MCP-Protocol-Version` on every POST; server rejects mismatch with 400 `HeaderMismatch` | same ("Protocol Version Header") | Partly | The SDK transport carries the header for the version it negotiated (string present in the jar). Server-side mismatch rejection is the SDK's for the legacy era; the new `-32020` error is not produced |
| T7 | **Mcp-Method** on all requests, **Mcp-Name** for `tools/call`, `resources/read`, `prompts/get`; Base64 sentinel for unsafe values | same ("Standard Request Headers", "Value Encoding") | **Partly (changed; was Missing)** | Client only, behind `app.mcp.client.routing-headers.enabled` (default **off**): `McpRoutingHeaders`, `McpRoutingHeadersTest`. Server-side validation (`HeaderMismatch` when header ≠ body) is not implemented. Off by default because we are not a modern-era client (see above); the headers are a courtesy to modern gateways, not compliance |
| T8 | `x-mcp-header` tool-parameter mirroring (`Mcp-Param-*`); clients MUST support it on Streamable HTTP | same ("Custom Headers from Tool Parameters") | Missing | Needs the modern request pipeline; SDK-blocked |
| T9 | No `Mcp-Session-Id`; list endpoints do not vary per connection; GET stream removed; `Last-Event-ID` ignored | `basic/transports/streamable-http` ("Earlier Streamable HTTP Revisions"), changelog major #1, #9 | Missing (intentionally legacy) | Our server still mints `Mcp-Session-Id` (the contract test follows it). Correct for the legacy era; changes only with the SDK |
| T10 | `server/discover`, per-request `_meta`, no `initialize`, `resultType`, MRTR, `subscriptions/listen`, `ttlMs`/`cacheScope` | changelog major #2–#8, minor #5 | Missing | SDK-blocked (no 2026-07-28 constants in `mcp-core` 0.18.x) |
| T11 | Client falls back to `initialize` against legacy servers; probes modern first | `basic/transports/streamable-http` ("Backward Compatibility"), `basic/versioning` | n/a today | We are a legacy client; we never attempt the modern request, so there is nothing to fall back from |
| T12 | HTTP+SSE is **Deprecated** (since 2025-03-26); new implementations SHOULD NOT adopt it | `basic/transports/streamable-http` ("HTTP+SSE Transport"), `deprecated` | **Done (changed)** | `McpServerType.SSE` still works. Marked deprecated in the registration form (`web/app/components/McpPanel.tsx`), logged per connect (`McpRoutingHeaders#warnSseDeprecated`) and documented here |
| T13 | stdio: newline-delimited messages, nothing but MCP messages on stdout, logging on stderr, shutdown by closing stdin then terminating | `basic/transports/stdio` | Done | The relay forwards child stdout byte-for-byte and only stderr lines separately (`core/mcp-stdio-proxy/proxy.js` header contract, `services/mcp-stdio-runner/server.js` SIGTERM then SIGKILL after a grace period). The relay's own TCP framing (`framing.js`) is internal and not the MCP wire format |
| T14 | stdio `server/discover` probe for modern servers | `basic/transports/stdio` ("Backward Compatibility") | n/a today | Same reason as T11 |

### Authorization (HTTP transports)

Authorization is OPTIONAL in the spec; for HTTP, implementations SHOULD conform. stdio SHOULD NOT use it
(`basic/authorization`). Our stdio registrations take credentials from the environment, as the spec says.

| # | Requirement (role) | Source | Status | Where |
|---|---|---|---|---|
| A1 | Server (if it offers auth) implements RFC 9728 Protected Resource Metadata with `authorization_servers`, and `WWW-Authenticate: Bearer resource_metadata=` on 401 | `basic/authorization`, `basic/authorization/authorization-server-discovery` | **Done (changed; was Missing)** | Option `app.mcpserver.oauth.enabled`. `McpProtectedResourceMetadataFilter`, `McpApiKeyAuthFilter#challenge`, `McpOAuthTest` |
| A2 | Server MUST validate the token's audience is itself (RFC 8707); MUST NOT accept tokens meant for other resources; invalid/expired → 401 | `basic/authorization` ("Token Handling"), `basic/authorization/security-considerations` | **Done (changed)** | `McpOAuthAuthenticator` (signature, exact issuer, expiry, audience == configured resource). Negative tests: wrong audience, no audience, audience prefix, wrong issuer, expired, forged signature |
| A3 | Server MUST NOT pass through the token it received to upstream APIs | `basic/authorization/security-considerations` ("Access Token Privilege Restriction") | **Done (changed)** | `McpApiKeyAuthFilter.WithoutAuthorizationRequest` hides `Authorization` from every downstream handler; tools use their own credentials. `McpOAuthTest#tokenIsNeverPassedDownstream` |
| A4 | Scopes: 403 `insufficient_scope` with `scope=`; least privilege; `scopes_supported` | `basic/authorization` ("Scope Challenge Handling") | **Done (changed)** | `McpOAuthScopes`; per-tool scope table, exact match, **no wildcards** (a token with `*`, `mcp:*` gets 403), `scopes_supported` is concrete scopes only; batches need every scope. Tests: `wildcardScopesGrantNothing`, `scopeIsRequiredPerTool`, `batchNeedsEveryScopeAndListingNeedsNone`, `advertisedScopesContainNoWildcard` |
| A5 | Existing simple option kept | task requirement | Done | `vmcp_` grant keys unchanged and still the default; with OAuth on, a `vmcp_` bearer still takes the key path (`grantKeysKeepWorkingWhenOAuthIsOn`) |
| A6 | Authorization-server endpoints and redirect URIs are HTTPS (loopback excepted) | `basic/authorization/security-considerations` ("Communication Security") | Done | Server: `McpOAuthConfig#requireHttpsOrLocal` fails startup on plain http. Client: `McpOAuthDiscovery` requires https for every endpoint |
| A7 | Client uses RFC 9728 discovery: `resource_metadata` from the 401, else the two well-known URIs in order | `basic/authorization/authorization-server-discovery` | Done | `McpOAuthDiscovery` (`RESOURCE_METADATA_PARAM`, `wellKnown(...)` sub-path then root) |
| A8 | Client validates AS metadata `issuer` equals the issuer used to build the well-known URL; tries OAuth and OIDC metadata endpoints in order | same ("Authorization Server Metadata Discovery") | Done | `McpOAuthDiscovery#discoverAuthServer` (issuer mismatch rejected, RFC 8414 §3.3) |
| A9 | Client MUST send `resource` in authorization, token (and refresh) requests, canonical server URI, regardless of AS support | `basic/authorization` ("Resource Parameter Implementation") | Done | `McpOAuthClient` (authorize, exchange, refresh); discovery rejects a `resource` that does not cover the typed server URL (`McpOAuthUrls.covers`) |
| A10 | Client MUST verify PKCE support from AS metadata and use S256 | `basic/authorization/security-considerations` ("Authorization Code Protection") | Done | `McpOAuthDiscovery` refuses without `code_challenge_methods_supported` ∋ S256; `McpOAuthClient` `code_challenge_method=S256` |
| A11 | Client MUST record the issuer before redirecting and validate `iss` (RFC 9207) before redeeming the code; absent `iss` rejected when the AS advertised support | `basic/authorization` ("Authorization Response Validation") | Done | `McpOAuthService#completeAuthorization` (`issRequired`, exact string compare); `McpOAuthFlowTest` |
| A12 | Client MUST NOT send tokens to a server other than the one whose AS issued them; separate registration state per AS | `basic/authorization` ("Token Handling"), `authorization-server-discovery` | **Done (changed: test added)** | Each registration's transport customizer is pinned to its own origin (`McpOriginPinning`) and the SSE `endpoint` event may not leave it; `McpTokenIsolationTest` proves the token supplier does not even run for a foreign origin. Connections are stored per tenant + resource URL and the callback re-checks issuer and client id (`JpaMcpOAuthConnectionStore`, `McpOAuthService`) |
| A13 | Client MUST specify `application_type` in dynamic client registration | changelog minor #8 | **Done (changed; was Missing)** | `McpOAuthClient#register` sends `"application_type":"web"` (our redirect is a public https callback). `McpOAuthClientTest` |
| A14 | Client SHOULD support Client ID Metadata Documents; DCR is **Deprecated** but allowed | `basic/authorization`, `deprecated` | Done | CIMD: `McpOAuthClientMetadata` (`app.mcp.oauth.cimd.*`); DCR remains the fallback |
| A15 | Client persisted credentials keyed by issuer; MUST re-register when the AS changes | changelog minor #9 | Partly | The callback requires the stored connection's issuer to equal the one in the signed state; a changed AS makes the connection fail rather than reuse the old client id. Credentials are keyed by tenant + resource URL, not by issuer identifier as the clause asks |
| A16 | Client SHOULD handle 403 `insufficient_scope` with step-up (union of scopes, bounded retries) | `basic/authorization` ("Step-Up Authorization Flow") | Missing | Not implemented; a 403 surfaces as a tool error and the admin reconnects with wider scope |
| A17 | Authorization server issues short-lived tokens, rotates public-client refresh tokens | `basic/authorization/security-considerations` | n/a | We are not the authorization server; Keycloak is, and its realm settings govern |

### Tool definitions (security guidance, not a protocol field)

The spec's overview says descriptions of tool behaviour, such as annotations, "should be considered untrusted,
unless obtained from a trusted server" and that hosts must obtain consent before invoking a tool
(<https://modelcontextprotocol.io/specification/2026-07-28>). It does not define pinning; this is our control.

| # | Concern | Status | Where |
|---|---|---|---|
| P1 | A server changes a tool's description or schema after an admin reviewed it | **Done (changed; was Missing)** | Tool pinning, below |
| P2 | Descriptions capped and output flooding limited; tool names namespaced so a server cannot shadow a built-in | Done (pre-existing) | `SanitizedMcpToolCallback`, `NamespacedMcpToolCallback` |
| P3 | Deterministic `tools/list` order (SHOULD) | n/a for the client; Partly for our server | Our `/mcp` tool list is a fixed curated list; per-caller exposed tools (`McpExposureRpcHandler`) are not explicitly sorted |

## What changed in this tranche

### 1. Server `/mcp`

- **Origin validation** (`McpOriginValidationFilter`, order 50, before the key filter). No `Origin` header (every
  non-browser MCP client) passes. A present `Origin` passes only when it is in `app.mcpserver.origin.allowed`
  (exact scheme + host + port, case-insensitive, no wildcards, `null` is not allowed). Otherwise 403 with
  `{"jsonrpc":"2.0","error":{...}}` and no `id`. The request's own `Host` is deliberately *not* trusted:
  under DNS rebinding the attacker's page and the rebound `Host` are the same name, which is the attack.
  `app.mcpserver.origin.mode`: `enforce` (default), `report` (log only), `off`.
  Applies to `/mcp/*` only; `/api/dev/*` is a plain REST surface the spec does not cover.
- **OAuth 2.1 option**, `app.mcpserver.oauth.enabled` (default **false**). Needs `resource` (canonical URI of `/mcp`),
  `issuer`, `jwk-set-uri`, all https; a bad or missing value fails startup. Serves
  `/.well-known/oauth-protected-resource[/mcp]` (and `/mcp/.well-known/oauth-protected-resource`), answers
  unauthenticated requests with `WWW-Authenticate: Bearer resource_metadata="…"`.
  Token → caller: the token must carry the tenant claim (`tenant_id`) and a grant claim (`mcp_grant_id`, a
  UUID of an existing, enabled grant). The grant keeps the agent/workflow/collection confinement identical
  to a `vmcp_` key; the scopes only narrow it further:

  | Scope | Allows |
  |---|---|
  | `mcp:agents:read` | `list_my_agents` |
  | `mcp:agents:call` | `call_agent`, `agent_*` |
  | `mcp:workflows:read` | `list_my_workflows` |
  | `mcp:workflows:call` | `call_workflow`, `workflow_*`, `get_run` |
  | `mcp:knowledge:search` | `search_knowledge` |
  | `mcp:tasks` | `start_agent_task`, `get_task`, `cancel_task` |
  | `mcp:tools:call` | any tool not in the table; not advertised, so a stock token lacks it |

  Methods that touch no tool (`initialize`, `tools/list`, notifications) need only a valid token.
- **Grant keys stay**, documented as the simple option: a `vmcp_` bearer is handled by the key path whether or
  not OAuth is on.

### 2. Client

Most of the client authorization requirements were already met (A7–A11, A14, see table). Added: `application_type`
(A13), and a regression test for token isolation between servers (A12). Step-up (A16) and keying by issuer (A15)
remain open.

### 3. Tool-definition pinning

- **What is hashed.** For each tool the model is offered: name, description, input schema (the wrapped,
  namespaced callbacks, i.e. what the model sees). SHA-256, length-prefixed fields, order-independent
  (`McpToolPin`). Per-tool hashes are stored so a change can name the tools (`added`, `removed`, `changed`).
  **Annotations are not part of the pin**: the Spring AI tool definition does not carry them.
- **When.** Every time the connection lists tools: at connect and on each `tools/list_changed` refresh
  (`DynamicMcpConnectionManager#routable` → `McpToolPinGate#observe`).
- **First listing is trusted** (trust on first use) and pinned; existing registrations are pinned on their next
  connect, so the migration changes nothing by itself. Migration `V491__mcp_tool_pinning.sql` adds seven nullable
  columns to `mcp_server_registration`.
- **On a difference:** the registration is marked changed, one audit event `mcp.tools.changed`
  (`AuditOutcome.PENDING_APPROVAL`, detail lists the diff) is written the first time that list is seen, and an
  admin review is required. A server that later returns to the approved list clears the flag.
- **Approval:** `GET /api/mcp/tool-pins` (changed registrations), `GET /api/mcp/tool-pins/{id}`,
  `POST /api/mcp/tool-pins/{id}/approve` (`PERM_MANAGE_INTEGRATIONS`). Approval writes audit event
  `mcp.tools.approved` with the approving subject and evicts the agent-side tool cache.
- **Mode** `app.mcp.tool-pinning.mode`: `off` | **`warn`** (default: record, audit, report; agents keep using
  the server) | `enforce` (a changed registration contributes **no tools** to agents or to edge sessions until
  approved; if the pin state cannot be read, no dynamic tools are offered). **The default is `warn`, so by
  default this does not yet "require re-approval before agents can call the changed tools"; set `enforce`
  for that.** That is a deliberate choice so shipping it cannot cut off a server whose tool list changes
  legitimately; decide when to flip the default.
- There is no UI for the review queue yet; the API above is the surface.

### 4. Transport

HTTP+SSE: deprecated in the UI, logged, documented; Streamable HTTP is the form's example URL. Routing headers: see T7.

## Pending decisions and open items

Left open on purpose; nothing here is decided until you say so.

| # | Item | Current state |
|---|---|---|
| D1 | Make tool pinning `enforce` the default | Default is `warn`: changes are recorded, audited and shown in the review card, agents keep using the server |
| D2 | Origin validation default | `enforce`. A browser-based MCP client on an unlisted origin gets 403; `mode=report` to observe first |
| D3 | HTTP+SSE removal timeline | Proposal only (see below); the spec publishes no date we could verify |
| D4 | Keycloak setup for OAuth mode | Not configured: needs an audience mapper for `/mcp` and an `mcp_grant_id` claim mapper |
| O1 | OAuth step-up on 403 `insufficient_scope` (client) | Missing |
| O2 | Client credentials keyed by issuer identifier | Partly |
| O3 | Server-side header-versus-body validation (`HeaderMismatch`) | Missing |
| O4 | The stateless 2026-07-28 protocol | Missing; SDK-blocked |
| O5 | Annotations in the tool pin | Not included; the Spring AI tool definition does not carry them |

## Where it shows in the console

| Feature | Where | Component |
|---|---|---|
| Tool-pin review queue (what changed, Approve) | Tools → Servers, and Tools → Enabled servers, above the list; shown only when a server changed | `web/app/components/McpPanel/ToolPinReview.tsx` |
| How callers authenticate to `/mcp` (grant keys, OAuth state, allowed browser origins) | MCP access page (`/admin/mcp-servers`), read-only because these are operator settings | `web/app/components/McpServerAuthOptionsCard.tsx`, endpoint `GET /api/mcpserver/grants/auth-options` |
| HTTP+SSE deprecation | Add-server form: option labelled "SSE (deprecated)" with a hint when chosen | `web/app/components/McpPanel.tsx` |

Routing headers and the OAuth `application_type` have no UI: they are wire-level behaviour.

## Compatibility note and deprecation timeline

Nothing a user depends on is removed or changed by default:

| Change | Default | Who is affected |
|---|---|---|
| Origin validation | **enforce** | Only a browser (or anything sending `Origin`) calling `/mcp` from an origin not in `app.mcpserver.origin.allowed`. Claude Desktop, Cursor, SDKs and curl send no `Origin`. If a browser-based MCP client of yours breaks, add its origin, or use `mode=report` while you find out |
| OAuth option | off | nobody |
| Tool pinning | warn | nobody loses tools; admins see a changed list |
| Routing headers | off | nobody |
| `application_type: web` in DCR | on | an authorization server that rejects the field; none known |
| SSE label/log | on | cosmetic |

HTTP+SSE timeline (proposal, needs a decision): the spec lists it as Deprecated with earliest removal "three
months after SEP-2596 reaches Final" and publishes no date we could verify. Until the spec removes it we keep
supporting it. Proposed: *now* deprecated in UI, log and docs (done); *next minor release after the spec removes
it* warn in the registry API response and block creating new SSE registrations; *no sooner than one release
after that* remove. Existing SSE registrations keep working until the last step and the CHANGELOG announces each.

Dynamic Client Registration is spec-deprecated (earliest removal: first revision on or after 2027-07-28). We keep
it as the fallback for authorization servers without Client ID Metadata Document support.

## Local binding

Left as is. The core container must listen on all interfaces for the compose network and Kubernetes to reach it,
and `/mcp` is protected by a grant key or OAuth token plus Origin validation. For a single-user laptop install
outside a container, set `server.address=127.0.0.1` (or `SERVER_ADDRESS`). A startup warning for "all
interfaces with the mcpserver profile and no OAuth" was considered and not added: it would fire on every
container deployment.

## How this was tested

Unit, wiring, and one integration test; nothing was run against a live stack, a real authorization server or a
real third-party MCP server. Run on 2026-10-14 with JDK 24 (`mvn -o -pl mcpserver -am test`, which builds and tests
every module those depend on): `security` 303, `mcp` 687 (1 skipped), `agents` 4,630, `mcpserver` 268, all passing,
plus the architecture rules (`StructureRulesTest`, `ModuleBoundaryRulesTest`, `AsyncHopTenantScopeRulesTest`) passing.
60 of those tests are new in this tranche. `flyway-lint`, `bean-name-collision`, `entity-name-lint` and the docs link
check pass. The repository-wide file-size ratchet currently fails on files this change does not touch (workflow,
chat and web files edited by other work in the same checkout); the files this change touches are within their baselines.

| Check | Test |
|---|---|
| Hash is order-independent; description / schema / annotation change changes it; field-boundary collisions | `McpToolPinTest` |
| First pin, unchanged, changed-once, warn vs enforce, off, approve, revert | `McpToolPinServiceTest`, `McpToolPinControllerTest` |
| **Integration:** real MCP client over Streamable HTTP against a server whose tool list changes: pinned, poisoned description flagged and hidden (enforce), one audit event not two, approve restores; warn mode never hides | `McpToolPinIntegrationTest` |
| Bad origin → 403 JSON-RPC error without id; allow-list; DNS-rebinding Host not trusted; `null` and `*` refused; modes | `McpOriginValidationFilterTest` |
| Wrong audience, no audience, audience prefix, wrong issuer, expired, forged key, no grant link | `McpOAuthTest` |
| Wildcard scopes grant nothing; per-tool scopes; batch; challenge headers; token never passed downstream; grant keys still work; metadata document | `McpOAuthTest` |
| Startup refuses http/missing OAuth config | `McpOAuthConfigTest` |
| Token for server A never sent to server B; SSE endpoint event cannot leave the origin | `McpTokenIsolationTest` |
| Routing header values and Base64 sentinel; off by default | `McpRoutingHeadersTest` |
| `application_type` | `McpOAuthClientTest` |

## What was not verified

- Behaviour against a real Keycloak or other authorization server issuing tokens with these claims. The
  Keycloak side (audience mapper for the `/mcp` resource, a `mcp_grant_id` claim mapper, the scopes) is not
  configured by this change; an operator must do that before enabling `app.mcpserver.oauth.enabled`.
- Whether any browser-based client of yours sends an `Origin` to `/mcp`; the `enforce` default assumes none.
- The SDK's exact `MCP-Protocol-Version` behaviour on the wire (T6): the header string is in the jar; we did not
  capture a request.
- The 2026-07-28 pages were read through a page-summarising fetch tool, not a browser. Quoted requirement
  wording was cross-checked across the transports, authorization and changelog pages, but a line-by-line diff
  against the published Markdown source was not done.
- The remaining three `StructureRulesTest` failures on this branch belong to the in-progress WhatsApp policy work
  in the same checkout (omnichannel package cycle, one controller→entity edge, one cross-area repository use),
  not to this change.
