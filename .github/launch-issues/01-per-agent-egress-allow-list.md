---
title: "Per-agent egress allow-list for HTTP, OpenAPI, web-fetch and MCP tools"
labels: [security, owasp, high]
status: draft
owasp: ASI02, LLM06, MCP09
---

# Per-agent egress allow-list for HTTP, OpenAPI, web-fetch and MCP tools

OWASP: ASI02, LLM06, MCP09. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

All outbound tool traffic is checked by the global `SsrfGuard` (private ranges, metadata hosts, DNS pinning, redirect re-validation). Since the operator list `app.security.outbound.egress-allow-list` shipped, an install can restrict tenant URLs to named hosts, but the list is **platform-wide**. An injected agent that has `http_request` can still reach every host any other agent on the install may reach, and a tenant cannot narrow one agent.
`A2aEgressGuard` (`app.a2a.client.allowed-hosts`) and `TenantSecret.allowedHosts` (`SecretHostPolicy`) are the only narrower lists; the second limits where a credential may be sent, not where an agent may call.

## Proposed fix

- Add a nullable `egress_allow_list` (JSON array of `host` / `*.suffix`) on the agent (Flyway migration; one namespace across `controlplane/` and `tenant/`, run `scripts/ci/flyway-lint.sh`).
- Resolve it per resolved tool set in `AgentToolProvider` and pass it to `HttpRequestExecutor`, `WebPageFetcher`, the OpenAPI executor and `McpRemoteUrlGuard` as an additional `SsrfGuard.egressPermits` check (intersect with the operator list; an agent can never widen it).
- Empty/null = current behaviour. Surface in the agent editor (Tools tab) and API.
- Audit-log a denied call with agent id and host.

## Tests

`SsrfGuardEgressAllowListTest` pattern: unit tests for intersection with the operator list, wildcard edge cases, redirect to an unlisted host, and an agent with no list. One `AgentToolProvider` test that two agents with different lists get different outcomes.

## Status

Operator list is shipped (`SsrfGuard.egressPermits`, `SsrfGuardEgressAllowListTest`).
