# Registering a custom MCP server

This is the sanctioned path for wiring any MCP server into VegaDūta — including a bespoke,
in-house one (e.g. the Home Assistant reference connector, see `core/mcp` roadmap §9.4) —
without hand-writing a new integration module. Every fact below was verified against live
source in `core/mcp` and `core/agents` (`DynamicMcpConnectionManager`, `AgentToolProvider`,
`GuardedToolCallback`) as of 2026-07-19, not copied from a spec doc.

## Two ways a server gets wired in, and when to use which

### 1. Static — `application-mcp.yml`, deploy-time, every tenant

Add a connection under `spring.ai.mcp.client.<transport>.connections.<name>` in
`core/app-config/src/main/resources/application-mcp.yml`. Spring AI's MCP client starter connects
at JVM startup; the connection is shared by every tenant in the deployment. Use this for a
server every tenant should always have (no per-tenant credential, or a credential the
platform operator manages centrally).

A matching row in `mcp_server_registration` (same `name`, `command`/`args`/`encryptedEnv`
all `null`, `connectionStatus = STATIC`) is just the on/off switch and audit record over an
already-running connection — registering/toggling it does **not** start or stop the
transport itself.

### 2. Dynamic — `POST /api/mcp/registry`, runtime, per-tenant

This is almost certainly what you want for a custom server, and what the rest of this guide
covers. A tenant admin (or an automated setup script hitting the same endpoint) registers a
server with its launch command and credentials; `DynamicMcpConnectionManager`
(`core/agents-tools/src/main/java/com/agenticai/core/agents/tools/DynamicMcpConnectionManager.java`)
connects a real MCP client to it immediately — no YAML edit, no restart, scoped to that one
tenant's schema.

**Important limitation, confirmed by reading `DynamicMcpConnectionManager.connect()`
directly: the dynamic path only ever builds a STDIO transport today**, regardless of the
`type` field you submit. It always does
`ServerParameters.builder(config.command()).args(...).env(...)` →
`buildStdioTransport(...)`. `McpServerType.SSE` and `STREAMABLE_HTTP` registrations are
connected dynamically too (`DynamicMcpConnectionManager` builds an SSE or streamable-HTTP
transport from the stored `url`, after the SSRF guard has approved it), so a remote server
needs no YAML entry. A `stdio` server — the overwhelmingly common shape for reference MCP
servers, including a Home Assistant one built as a small Node/Python CLI — runs as a
subprocess (in the `mcp-stdio-runner` sandbox when that profile is on). The static YAML path
remains for servers every tenant must always have (not for a transport limitation, which no
longer exists; extending `connect()` yourself would be a real code change, not a
registration).

## The request

```
POST /api/mcp/registry
Content-Type: application/json

{
  "name": "home-assistant",
  "type": "STDIO",
  "description": "Controls and queries Home Assistant entities for this tenant's smart-home setup.",
  "enabled": true,
  "command": "npx",
  "args": ["-y", "@your-org/mcp-server-home-assistant"],
  "env": {
    "HASS_URL": "http://homeassistant.local:8123",
    "HASS_TOKEN": "..."
  }
}
```

Field-for-field (`McpServerRegistrationRequest`, `core/mcp/.../dto/McpServerRegistrationRequest.java`):

- `name` (required) — must be unique per tenant (`mcp_server_registration` has a unique
  constraint on `name`); this is what shows up in the Tools UI and in tool-call logs.
- `type` (required) — `STDIO` for a subprocess launched via `command`/`args`, `SSE` or
  `STREAMABLE_HTTP` for a remote server reached at `url`; all three connect dynamically.
- `description` — free text, shown in the UI.
- `enabled` — if `true` and `command` is set, `McpRegistryController.register()` calls
  `DynamicMcpConnector.connect(id)` synchronously right after saving. The HTTP response
  still reflects the pre-connect state (`connectionStatus` will read `NOT_CONFIGURED` or
  `CONNECTING` in the response body) — poll `GET /api/mcp/registry` or call
  `POST /api/mcp/registry/{id}/connect` to observe `CONNECTED`/`ERROR` land.
- `command` — the executable, e.g. `"npx"`, `"uvx"`, `"node"`, or an absolute path to your
  own binary. Never leave this null if you want a live dynamic connection.
- `args` — list of CLI args passed to `command`.
- `env` — map of environment variables the subprocess needs (API tokens, URLs, etc.).
  **Encrypted at rest** (`McpRegistryService.encryptEnv`, AES-256-GCM via the shared
  `EncryptionService`, same boundary every other credential entity in this schema uses —
  the entity itself never touches the encryption call). Decrypted only transiently, inside
  `McpRegistryService.dynamicConfigFor(...)`, for `DynamicMcpConnectionManager` to actually
  connect with — never logged, never serialized back into an HTTP response.

## The response

`McpServerRegistrationResponse` deliberately omits `env` entirely — there is no code path
in this codebase where a registration's decrypted secret can be echoed back to a browser.
`command`/`args` are returned as-is (never secret). `connectionStatus` is one of
`NOT_CONFIGURED` / `STATIC` / `CONNECTING` / `CONNECTED` / `ERROR` / `DISABLED`
(`McpConnectionStatus`) — poll `GET /api/mcp/registry` after registering to watch it settle,
or read `connectionError` if it lands on `ERROR`.

If a connection attempt fails — bad credential, `npx`/Node missing on the server host,
target unreachable, your server process crashing on startup — `connectionError` carries a
human-readable reason. This message has any of *this same registration's own* configured
secret values stripped out first (`DynamicMcpConnectionManager.redactSecrets`) before being
persisted or returned, specifically because a misbehaving CLI can legitimately echo a bad
credential value back in its own stderr (`"invalid API key: sk-abc123..."` is a completely
normal shape for a third-party tool to print) — this repo treats that as a real leak vector,
not a hypothetical one, so don't rely on `connectionError` being safe to skip this check for
future connectors; it already handles it, but if you ever touch this code path again,
preserve the redaction.

## Retrying and disabling

- `PATCH /api/mcp/registry/{id}` with `{"enabled": false}` disconnects the live client
  (`disconnect()` closes it gracefully) and marks the row `DISABLED`. Re-enabling calls
  `connect()` again from a clean slate.
- `POST /api/mcp/registry/{id}/connect` retries a connection without resubmitting the whole
  form — use this after fixing a bad credential rather than re-registering.

## Health monitoring — automatic, no extra work needed

Once connected, `DynamicMcpConnectionManager.healthCheckLiveConnections()` (a
`@Scheduled(fixedRate = 5 minutes)` job, iterating every tenant schema via
`TenantSchemaIterator`) pings every live connection with a real `listTools()` round-trip. A
connection that's gone dead (server crashed, network dropped) is detected within 5 minutes,
evicted from the live-connection/tool-callback maps, and marked `ERROR` with the same
redacted failure message described above — you don't need to build your own liveness check
for a custom connector; registering it through this path gets you this for free.

## Governance — every registered tool is already guarded, by construction

This is the part worth understanding before building a "reference connector" from scratch:
**you do not need to wire your own approval-gate/audit logic into a custom MCP server for it
to be governed.** `AgentToolProvider.resolveToolsFor()` merges tool callbacks from every
source — static MCP, dynamic MCP (yours), the native knowledge-search tool, everything — and
wraps every single one, uniformly, regardless of origin, in:

1. **`GuardedToolCallback`** (`core/guardrails`) — a shared per-turn step ceiling; a
   per-tool human-in-the-loop check via `ApprovalPolicyService.requiresApproval("mcp_tool."
   + toolName)` (a tenant admin can require approval for one of your tool's specific
   actions — e.g. `mcp_tool.home_assistant_unlock_door` — through the *existing*
   `PUT /api/approvals/policy/**` endpoint, no new endpoint needed for this); deterministic
   a length cap on the result (truncated with a marker past `app.tools.output.max-chars`, zero LLM calls); output sanitization
   (`GuardrailService.sanitizeToolOutput`); and, for agents running with
   `reasoningDepth = RIGOROUS`, an independent LLM-based intent-match check before the tool
   ever runs.
2. **`LoggingToolCallback`**, wrapping the above — every call (including a rejected/gated
   one) is persisted via `ToolCallLogRepository`, so it shows up in the same audit trail
   every other tool call does.

Practical consequence for the Home Assistant connector (§9.4) specifically: as long as it's
registered as a normal dynamic MCP server through this same path — not hand-built as a
bespoke Java tool bypassing `AgentToolProvider` — the roadmap's §10.3 requirement ("route
every tool call through `GuardrailService`/approval-gate/audit from day one") is satisfied
automatically, with zero extra plumbing. The only per-tool-name governance choice left for
whoever builds that connector is picking sensible `mcp_tool.<name>` action names for
anything actually destructive (unlocking doors, disarming alarms) and documenting which of
those a tenant admin should gate behind approval — the mechanism itself already exists.

## Optional: adding a one-click catalog entry

`McpCatalogService` (`core/mcp/src/main/java/com/agenticai/core/mcp/McpCatalogService.java`)
is a fixed, hand-maintained list backing the "MCP Tools" gallery in the UI — not a live fetch
from the public MCP registry, deliberately (see that class's own javadoc for why). To let a
tenant add your server without typing raw JSON, add an `McpCatalogEntry`:

```java
new McpCatalogEntry(
    "home-assistant",                          // key: frontend React key, not persisted
    "home-assistant",                           // name: sent as McpServerRegistrationRequest.name
    McpServerType.STDIO,
    "Controls and queries Home Assistant entities for this tenant's smart-home setup.",
    "home-assistant",                           // iconKey: frontend maps to a brand icon
    "https://github.com/your-org/mcp-server-home-assistant",
    false,                                       // ready: false, since it needs a credential
    List.of("HASS_URL", "HASS_TOKEN"),           // requiredEnvVars
    null,                                        // yamlSnippet: only needed if you also want a static option
    "npx",                                       // dynamicCommand
    List.of("-y", "@your-org/mcp-server-home-assistant")  // dynamicArgs
)
```

If your server takes a value as a positional CLI argument instead of an environment
variable (like `postgres`/`sqlite`'s connection-string entries), write it into
`dynamicArgs` as `"{{HASS_URL}}"` — the frontend substitutes the value the person typed for
the matching `requiredEnvVars` entry directly into the arg list instead of sending it as
`env`.

## Honest caveats

- **No Maven/Java toolchain was available to compile-check any of this** in the sandbox
  this guide was written from — every fact here comes from reading the live `.java` source
  directly (constructors, method bodies, field types), not from running it. Confirm with a
  real `mvn -pl mcp,agents -am compile` before depending on any of it in production,
  especially the STDIO-only limitation above and the reflection-based
  `StdioClientTransport` constructor resolution in `DynamicMcpConnectionManager` (see that
  class's own javadoc — the bundled MCP SDK version's exact constructor arity was never
  confirmed against a real dependency resolution).
- This guide describes today's dynamic-connection behavior (all three transports connect
  dynamically; SSE and streamable HTTP were added after the first version of this page).
  If that changes, update the transport section above rather than leaving it stale — this exact kind of doc/code drift
  is what caused an earlier `WorkflowTriggerRateLimiter` fail-open confusion.
