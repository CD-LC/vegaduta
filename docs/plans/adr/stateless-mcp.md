# ADR: Stateless MCP (spec 2026-07-28) and unpinning stdio MCP from one replica

Status: proposed (2026-10-01); the transport-and-call-hook slice of section 4 is in the code behind `app.mcp.registry-route-tool-calls` (default `false`, checked 2026-10-21, v0.1.9); section 5 is not done.

## 1. Verified facts (primary sources, checked 2026-10-01)

- The MCP spec **2026-07-28 is final** (RC locked 2026-05-21, released 2026-07-28; blog.modelcontextprotocol.io/posts/2026-07-28/).
  Headline change: the `initialize`/`initialized` handshake and the `Mcp-Session-Id` header are removed. Every request carries
  `_meta.io.modelcontextprotocol/{protocolVersion,clientInfo,clientCapabilities}`; an optional `server/discover` RPC replaces upfront
  capability discovery; state must be modelled as tool-returned handles. Extensions: MCP Apps, Tasks.
- Tier 1 SDKs at release: TypeScript, Python, Go, C#; Rust beta. **The Java SDK is not listed**, and no Java release note was found.
- **Installed SDK does not support it.** Spring AI 1.1.7 (`spring-ai-starter-mcp-client`) resolves `io.modelcontextprotocol.sdk:mcp-core`
  0.18.x. `ProtocolVersions` in mcp-core 0.18.3 and in the 2.0.0 jar in the local Maven cache both top out at `2025-11-25` (read from the
  jars). The `McpStatelessAsyncServer` classes in 2.0.0 are the older stateless Streamable HTTP *server* mode of the 2025 protocol, not the
  2026-07-28 wire format. Moving to 2.0.0 also needs Spring AI 2.x / Boot 4, so it is not a drop-in.
- Rule honoured: **no hand-rolled transport.**

## 2. Premise check: what pins stdio MCP to one replica

Partly already built (flagged, default off, unit-tested only): `app.mcp.registry-store=redis` (`RedisMcpOwnershipRegistry` leases,
`McpCommandBus`, `RedisMcpOwnershipGate`, `McpOrphanAdopter`). In default *replicated* mode every replica spawns its own subprocess, so chat
is not pinned (cost: N subprocesses, per-JVM stdio caps multiply by N). With `registry-exclusive-clients=true` only the owner spawns, but
non-owners then have **no client and no tools**, so MCP traffic would have to be pinned. The missing piece is a call path from a non-owner
to the owner.

Stdio servers are subprocesses and therefore per-host whatever the HTTP protocol does; stateless MCP helps remote servers (no sticky
sessions) once the SDK supports it, and does nothing for stdio.

## 3. Options

| Option | Trade-off |
|---|---|
| A. Wait for Java SDK 2026-07-28 support, then upgrade Spring AI | No custom code; blocked upstream and on Boot 4; remote servers only. |
| B. Hand-roll the 2026-07-28 client transport | Rejected: security-sensitive, diverges from the SDK, breaks the rule. |
| C. Route tool calls to the stdio owner over the existing Redis bus | Works today for stdio, protocol-independent; adds a Redis hop and an at-most-once story. |
| D. Dedicated single-replica stdio worker service | Clean isolation, fits L6 sandbox; bigger infra change. |

Decision: C as the first slice, A when the SDK lands, revisit D with L6.

## 4. Slice shipped (behind `app.mcp.registry-route-tool-calls`, default false)

- `McpCommand.Op.CALL` with optional `payload` (JSON `{tool,args}`); the 4-arg constructor is kept; legacy queue entries still parse.
- `McpOwnershipGate.routeToolCall(...)` (default: empty = not routed) and `attachToolCaller(...)`. `RedisMcpOwnershipGate` routes only when the
  flag is on, this JVM has no live client, and a different replica owns the registration.
- Owner side: `DynamicMcpConnectionManager.callLiveTool` uses the already sanitized and namespaced callbacks, so the output-sanitizer defence is
  unchanged. CALL runs on the test pool (now 4 threads, shared with TEST) so a slow tool never stalls connect/disconnect lanes; a tool running up to 60 s can still occupy a thread, so a dedicated CALL pool is a stage-1 follow-up if the flag is ever enabled in production.
- **At-most-once:** owner silent or erroring makes the gate throw `IllegalStateException`; callers must not retry locally (the tool may have
  run). Redis lookup failures fail open to local behaviour like the rest of the gate.
- Sender side: `RoutedMcpToolCallback` is the outermost wrapper of every dynamic registration's callbacks (connect and tools/list_changed refresh). It
  asks the gate first and falls through to the local call when the gate answers empty, so with the flag off it is a pass-through.
- Keys: `app.mcp.registry-route-tool-calls`, `app.mcp.registry-tool-call-timeout` (PT60S), declared under `app.mcp.*` in `application.yml`
  (env `APP_MCP_REGISTRY_ROUTE_TOOL_CALLS`, `APP_MCP_REGISTRY_TOOL_CALL_TIMEOUT`).

## 5. Staged plan (not done)

1. Non-owner discovery: owner publishes sanitized tool definitions to a TTL'd Redis key; non-owner builds proxy `ToolCallback`s that call
   `routeToolCall`, wired into `resolveDynamicToolCallbacks` behind the same flag. Until then the sender hook exists but a non-owner has no callback to invoke, so
   enabling the flag does not yet make a non-owner replica expose the tools: **this slice is the transport and call hook, not full stateless
   MCP.** A callback only reaches `routeToolCall` when it outlives its client on a replica that is not the owner.
2. Real-Redis two-JVM test (owner killed mid-call, lease takeover).
3. Cluster-wide per-tenant stdio caps and `inFlightStdioTests` (currently per JVM).
4. Attribution: owner runs the call as `system:mcp-command`; carry the caller subject/run id in the payload for audit and budget.
5. When the Java SDK supports 2026-07-28: upgrade, drop session-affinity assumptions for remote servers, adopt `server/discover`.

## 6. Risks

- Tool arguments and results transit Redis (reply TTL 2 min, queue TTL 5 min). Require Redis auth/TLS before enabling.
- Added latency up to `registry-poll-ms` (250 ms) plus the 100 ms reply poll; unsuitable for streaming output.
- Tenancy: the owner re-applies the tenant carried in the command and the registry key is trusted; the payload does not carry the caller's
  subject, so owner-side audit shows `system:mcp-command` (stage 4).
- Verification state: not compiled by this agent (lead builds serially), never run against a real Redis.
