# ADR: OS-level sandbox for custom tools and stdio MCP (L6)

Status: accepted for first slice, 2026-10-01. Owner: core-3.

## Re-verified premise (the gap report was partly wrong)

The report graded this "absent" from negative greps. Reading the code:

| Surface | Today | Isolation |
|---|---|---|
| `UserToolScript` | SpEL in `SimpleEvaluationContext` (no method/constructor/type/bean resolvers, no loops) | In-process but has no OS reach by construction. Routing it to E2B adds nothing and costs a microVM boot per call. **Out of scope.** |
| `CustomCodeTool` (Python/Java) + `CodeToolGenerationService` | Already executes in the separate `tool-sandbox` service (AST allow-list, seccomp profile, time/memory caps). Generation only calls `validate`, never runs code. | Process-level, separate container. No second layer. |
| Dynamic stdio MCP | `McpStdioCommandGuard` blocklist/allowlist in core, plus optional `mcp-stdio-runner` sidecar (`mcp-stdio-sidecar.enabled`, see `docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md`). | Sidecar is a separate container, not a microVM. |
| E2B | `SdlcSandboxService.runCode` (tenant BYOK, Firecracker microVM), exposed to agents as `run_code`. | Hardware-virtualised. |

So the real gap is narrower: no microVM-grade option for tenant-authored custom-tool code, and
stdio MCP packages still run on infrastructure we operate.

## Options

1. **E2B as an alternative execution backend for Python custom tools (chosen, slice 1).**
   Reuses `runCode`; strongest isolation; cost is E2B boot latency (seconds) and tenant BYOK spend.
   Validation stays in tool-sandbox, so the AST allow-list remains defence in depth.
2. Replace tool-sandbox with E2B. Rejected: no Java runner in the E2B template, no secret
   hand-off model, latency regression for every call.
3. Run stdio MCP servers inside E2B. Deferred (stage 3): needs a long-lived sandbox plus a
   stdio-over-network bridge; E2B `runCode` is request/response and cannot host a persistent
   JSON-RPC pipe today.
4. gVisor/Kata on the platform's own container runtime. Right long-term for the platform pool,
   but is an infra change outside this repo slice (terraform/Container Apps).

## Decision and staged plan

- **Stage 1 (this change):** `tool-sandbox.e2b-execution` (env `TOOL_SANDBOX_E2B_EXECUTION`,
  default false). When on, `CustomCodeToolService.executeNode` sends Python tools without vault
  secrets to `E2bCustomToolExecutor`; everything else keeps using tool-sandbox. The program and its
  input travel base64-encoded in a generated harness; the result is returned behind a per-call
  random marker so tenant stdout cannot forge it. Fails closed (`ToolSandboxUnavailableException`)
  if E2B is not configured. Blocklist/validator unchanged.
- **Stage 2:** secrets via E2B env injection (needs a decision on shipping vault values to a
  third-party VM; per-secret opt-in), Java runner in the E2B template, per-tenant concurrency cap
  and a warm-sandbox pool to cut latency.
- **Stage 3:** stdio MCP in E2B using a long-lived sandbox and a bridge; until then keep
  `McpStdioCommandGuard` plus the `mcp-stdio-runner` sidecar (enable the sidecar in prod).
- **Stage 4:** gVisor/Kata for the sidecars on the platform runtime.

## Trade-offs and risks

- Latency: E2B boot adds seconds per call; the flag is opt-in per deployment, not per tool yet.
- Cost lands on the tenant's E2B key; a missing key makes flagged tools fail rather than
  silently fall back (deliberate: the flag asks for stronger isolation).
- Output size is bounded by the E2B template, not tool-sandbox's `OUTPUT_TOO_LARGE`; `truncated`
  is always false on this path.
- Memory limit (`memoryMb`) is not enforced per call on E2B (template-level); only wall clock is.
- Not exercised against a real E2B account in this pass (unit tests only).
