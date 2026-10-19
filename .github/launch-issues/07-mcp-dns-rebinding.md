---
title: "Close the DNS-rebinding window on outbound MCP connections"
labels: [security, owasp, high]
status: draft
owasp: MCP07, ASI02
---

# Close the DNS-rebinding window on outbound MCP connections

OWASP: MCP07, ASI02. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

`McpRemoteUrlGuard` / `RemoteMcpPreProbe` validate the URL and probe it, but the MCP SDK transports resolve the host again when connecting, so a name that answered with a public address at validation time can resolve to an internal one at connect time. `McpOriginPinning` documents that the SDK offers no resolver hook; the threat model lists "MCP DNS-rebinding pinning, redirect re-guarding" as not fully closed. `SsrfGuard.dnsResolver()` / `SafeHttpClients` provide connect-time pinning for Apache HttpClient 5 callers; I did not verify which tools use them today.

## Proposed fix

- Build the MCP HTTP transports on an Apache HttpClient 5 built by `SafeHttpClients`, or put a local pinning proxy in front of them, so connection and validation share one lookup.
- Re-validate every redirect hop with `SsrfGuard.resolveRedirect`.
- Add a pre-buffer response-size cap (listed as open in the threat model).

## Tests

Fake resolver that answers public then private: connect must fail. Redirect to a private host must fail. Response larger than the cap must abort before buffering.

## Status

None yet.
