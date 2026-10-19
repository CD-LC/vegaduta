# ADR: Generic MCP OAuth (RFC 9728 / 8414 / 7591, PKCE)

Status: accepted for the flag-off slice (`app.mcp.oauth.enabled=false`). Not wired into the connect path.

## Decisions and trade-offs

1. **Per-tenant dynamic client registration (RFC 7591) vs one shared platform client.**
   Chosen: register one client per (tenant, MCP resource). Gains: tenant isolation (a leaked
   client secret affects one tenant), consent screens name the tenant's own connection, revocation is
   per tenant. Costs: one extra registration call on first connect and many registered clients on
   authorization servers; servers without a registration endpoint are unsupported until a
   pre-registered-client option exists. A shared client was rejected: one secret for all tenants and
   no per-tenant revocation.
2. **In-memory connection store.** Chosen for this slice so no Flyway number is consumed. Costs:
   connections are lost on restart (user reconnects) and are not shared across replicas (core is
   single-replica today, see the open backlog). The store is an always-on `@Component`; the durable
   JPA store (needs a Flyway number; the namespace is shared with controlplane) MUST remove
   `InMemoryMcpOAuthConnectionStore` in the same change or Spring fails with duplicate beans.
   The consumed-state set (item 5) has the same single-process limit.
3. **Probing with an unauthenticated POST.** Discovery POSTs the MCP URL without credentials to read
   the `WWW-Authenticate` `resource_metadata` hint (RFC 9728 s5.1), then falls back to well-known
   paths. Cost: one request that a non-compliant server may log as a failed call. A probe failure
   that is not an SSRF refusal falls through to the fallbacks; a refusal
   (`McpOAuthBlockedException`, a type, not a message match) is fatal and surfaced.
4. **Only the first authorization server is used.** The resource metadata may list several; we take
   the first that yields valid metadata. Simplicity over choice; a picker can come with the UI.
5. **State is not bound to the initiating user/session.** It is an AES-GCM blob (tenant, PKCE
   verifier, issue time, resource) with a 10 minute lifetime, and is now single-use per process (a
   SHA-256 of the blob is remembered until expiry), so replay inside the window is rejected here as
   well as at the authorization server (codes are single-use). Binding to a user/session needs the
   controller slice (cookie or session nonce) and is deferred to it.
6. **Flag-off footprint.** All beans instantiate when the flag is off (they hold no connections or
   threads); every `McpOAuthService` entry point calls `requireEnabled()`. The only change to
   existing code is Atlassian PKCE delegating to `McpOAuthPkce`.

## SSRF
All egress goes through `JdkMcpOAuthHttp`: https-only, `McpRemoteUrlGuard.checkResolved` on every URL,
redirects never followed, response size cap, bounded body-read time. Covered by `JdkMcpOAuthHttpTest`.
The residual DNS-rebinding window is the one the guard itself documents.
