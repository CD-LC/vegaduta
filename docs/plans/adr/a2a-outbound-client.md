# ADR: Outbound A2A client (agents calling external A2A agents)

Status: first slice implemented behind `app.a2a.client.enabled` (default OFF). Date: 2026-10-01.
Audience: developers, freelancers, scientists, researchers, general users (no enterprise/B2B scope).

## Context
The platform already serves A2A inbound (`core/agents/a2a`: signed Agent Cards, JSON-RPC 0.3 and 1.0,
task store). Agents could not call out to third-party A2A agents. The only agent-calls-agent paths were
MCP or `http_request`, neither of which verifies identity or understands the task lifecycle.

## Decision
Expose one tool, `a2a_call_agent(agent_card_url, message)`, backed by `A2aClientService`.

Per call: allow-list + SSRF check -> fetch card (no redirects, size cap) -> verify detached JWS with the
existing `A2aCardVerifier.verifyCard` over the exact fetched bytes -> take endpoint from the SIGNED card
(never from the model) -> re-guard endpoint -> `message/send` | `SendMessage` per `protocolVersion` ->
poll `tasks/get` | `GetTask` to terminal or `max-wait` -> best-effort cancel on timeout, or when the
remote asks for input/auth (a one-shot tool cannot answer) -> text-only result.

## Options considered
1. Trust any URL the model supplies, rely on an SSRF denylist only. Rejected: the model (prompt-injectable)
   chooses the URL; a denylist is the wrong default.
2. Operator allow-list + SSRF resolution check + signed card (chosen). Empty allow-list reaches nothing.
3. Full A2A client with auth schemes, streaming, push notifications, multi-turn input-required. Deferred:
   needs a credential model and a place to park an interrupted task (see staged plan).
4. MCP-over-A2A bridges. Rejected: adds a hop and loses card verification.

## Trade-offs
- Card verification is origin-bound integrity (JWKS from the same origin or a guarded `jwks_uri`), not
  independent attestation. The allow-list is what makes an origin trusted; the signature stops tampering
  and card/endpoint swaps between fetch and use.
- DNS rebinding between check and connect is an accepted limitation (same as `fetch_web_page`); redirects
  are never followed, which removes the cheap variant.
- Result text is third-party data: classified `UNTRUSTED_EXTERNAL` (and egress) in
  `BuiltInToolClassifications`, framed as untrusted in the tool output, text parts only.
- No credentials are sent. Remotes needing auth are out of scope for this slice.

## Task lifecycle and namespace
Each call is mirrored as an `A2aTask` in the shared `A2aTaskStore` (tenant/subject scoped) for retention
and Redis machinery. Outbound ids carry `A2aTask.OUTBOUND_ID_PREFIX` (`a2a-out-`) and the INBOUND
`A2aTaskService.get/cancel` refuses that prefix, so a remote caller can never read or cancel outbound
bookkeeping (it holds the prompt we sent and the third-party reply). Store failures are swallowed;
bookkeeping never breaks a call. The remote's state remains authoritative.

## Config (`app.a2a.client` in `application.yml`)
`enabled` (false), `allowed-hosts` (empty), `require-signed-card` (true), `allow-insecure-loopback`
(false, dev only), `connect-timeout` 5s, `request-timeout` 30s, `max-wait` 120s, `poll-interval` 2s,
`max-response-bytes` 1 MiB, `max-result-chars` 20000.

## Staged plan
1. (done) Unauthenticated blocking call, both dialects, polling, cancel, guard, classification, tests with
   a loopback fake A2A server.
2. Per-tenant allow-list and per-agent opt-in (today operator-global), plus an audit-log row per call.
3. Outbound auth (bearer/API key from the tenant secret store, scheme taken from the signed card).
4. Resumable `input-required`: park the remote task id on the run and let the user answer instead of
   canceling. Needs the step journal (V376) from the durability item.
5. Streaming (`message/stream`) and push notifications.

## Risks
- Operator misconfiguration: a wildcard allow-list (`*.example.com`) trusts every subdomain.
- A malicious but allow-listed remote can return prompt-injection text; mitigated by classification and
  framing, not eliminated.
- A remote task that never terminates holds a poll loop up to `max-wait` on the calling thread.
- Per-call card + JWKS fetch adds latency; no caching yet (deliberate: avoids stale-key trust).
