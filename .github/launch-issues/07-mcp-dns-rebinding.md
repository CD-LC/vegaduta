---
title: "Close the DNS-rebinding window on outbound MCP connections"
labels: [security, owasp, high]
status: partly-done
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

The pinning half is done. The SDK transports now take a `GuardedHttpClientBuilder` (`core/security/.../net`) through their `clientBuilder(..)` hook, so the HTTP client they use is `GuardedJdkHttpClient`, backed by `SafeHttpClients`: every connect, and every reconnect of the long-lived SSE stream, resolves through `SsrfGuard` and goes to the address it validated (a smaller change than an Apache transport of our own, and the SDK's code is untouched). `McpGuardedSocketTest` proves a loopback server is refused with the default guard and that both transports still complete the handshake over the guarded socket.

Still open: the pre-buffer response-size cap. Redirect hops need no re-validation because redirects are off (`Redirect.NEVER`) on both transports.
