# mcp-http-gateway

Serves this repo's first-party **stdio** MCP connectors over **Streamable HTTP**, so they
can be registered as remote MCP servers instead of as STDIO subprocesses. Built for the
three-vertical client demo (`demo/three-verticals/`). Zero npm dependencies, same house
rule as the connectors themselves.

## Why this exists — the STDIO path does not work in production

This is not a preference. Three separate facts close that door, all verified in source
rather than assumed:

1. **There is no `/opt/mcp-servers` in production.** `deploy/docker-compose.yml` bind-mounts
   `../mcp-servers:/opt/mcp-servers:ro` into `mcp-stdio-runner`, which is why
   `node /opt/mcp-servers/office-ally/index.js` works locally. The production
   `mcp-stdio-runner` Container App has **no volume mounts at all** — and core's
   `MCP_STDIO_SIDECAR_URL` actually points at a Docker container on a separate VM,
   not at that Container App. Registering a STDIO connector in prod today yields
   `Cannot find module`.
2. **`node -e` is not a workaround.** `McpStdioCommandGuard` blocks `-e` and `-c` outright
   (along with `--inspect*`, `-p`, `-r`, and a set of env prefixes). Inlining a connector's
   source as an argument is rejected before the process builder is ever reached.
3. **A custom STDIO name sits behind a staff-review gate.** `McpRegistryService.register`
   gives any STDIO registration whose `name` is not a curated catalog entry
   `reviewStatus: PENDING`, and it will not connect until a platform admin approves it —
   recomputed on *every* save, so re-POSTing an approved server resets it.

The remote branch has none of those problems.
`DynamicMcpConnectionManager.buildRemoteTransport` builds a real
`HttpClientStreamableHttpTransport` (MCP Java SDK 0.18.2) for a `STREAMABLE_HTTP`
registration, and `McpRegistryService.register` calls `markReviewNotRequired()` on that
branch. So a remote connector connects immediately, with no VM access and no change to any
running production service.

## What it does *not* do

**It does not reimplement any connector.** Each connector is spawned as the exact same
child process `DynamicMcpConnectionManager` would spawn, speaking the exact same
newline-delimited JSON-RPC 2.0 over stdio. This file is a transport adapter and nothing
more. That matters for one specific reason: a connector's guardrails — Office Ally's
sandbox default, marketing-ops having no tool that can mutate a live ad account — cannot
drift between the stdio path and the HTTP path, because there is only one implementation of
each connector, not two.

## Transport shape

| Route | Behaviour |
|---|---|
| `GET /health`, `GET /` | Liveness + which connectors are installed and running. No token needed, starts no child process. |
| `POST /mcp/<server>` | JSON-RPC 2.0 request or batch. Responds `application/json`. Notification-only bodies answer `202` with no body, per spec. |
| `GET /mcp/<server>` | `405`. These connectors are strictly request/response — there are no server-initiated messages, so there is nothing to stream and the spec permits refusing. |
| `DELETE /mcp/<server>` | `200`. Session teardown is accepted and is a no-op. |

**One shared child per connector, with id remapping.** The connectors are stateless — every
answer comes from a fixed in-process fixture — so a single long-lived child is correct, and
JSON-RPC ids are remapped so two concurrent HTTP callers cannot collide. There is a test
that proves answers do not cross. This would *not* be correct for a connector holding
per-session state; if one is ever added here, it needs a child per session instead.

The child is handshaked **once, by the gateway**. A client's own `initialize` is answered
from that cached result rather than re-handshaking a shared child, which would be a protocol
error for whichever client arrived second.

## Environment

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8099` | Listen port. |
| `GATEWAY_TOKEN` | *(unset)* | If set, every `/mcp/*` request must carry `Authorization: Bearer <token>`. **Unset means the endpoint is open** — the startup log says so explicitly. |
| `SERVERS` | `home-assistant` | Which connectors to expose. |
| `CONNECTOR_ROOT` | `..` | Directory holding the connector folders. The Docker image sets `/app/connectors`. |
| `CALL_TIMEOUT_MS` | `20000` | Per-call ceiling for a normal connector. |
| `SLOW_CALL_TIMEOUT_MS` | `900000` | Ceiling for connectors in `SLOW_CONNECTORS`. |
| `SLOW_CONNECTORS` | *(empty)* | Connectors whose tools legitimately take minutes. |
| `MAX_CHILDREN_PER_CONNECTOR` | `8` | Cap on distinct credential sets held live per connector. LRU-evicted. |
| `CHILD_IDLE_MS` | `600000` | Evict a credentialed child after this long unused. |

The gateway deliberately **does not** pass its own environment to the connector children —
they get `PATH`, `HOME`, and only the credentials that arrived on *this request*. Its own
`GATEWAY_TOKEN` is never visible to a connector. That is enforced by what the child is
given, not by configuration someone could forget.

## Per-tenant credentials

A connector child is keyed by **connector name *and* credential set**, not by name alone.
Credentials arrive as `X-Connector-Env-<VAR>` request headers and become the child's process
environment:

```json
{
  "name": "broll-generate",
  "type": "STREAMABLE_HTTP",
  "enabled": true,
  "url": "https://<gateway>/mcp/broll-generate",
  "env": {
    "Authorization":           "Bearer <derived-token>",
    "X-Connector-Env-FAL_KEY": "<this tenant's fal key>"
  }
}
```

This reuses a mechanism that already exists rather than inventing one:
`DynamicMcpConnectionManager.remoteHeaders` copies a remote registration's `env` map verbatim
into request headers, and `core/MCP-SECURITY-PENTEST-2026-07-25.md` records that those values
are encrypted at rest, absent from `McpServerRegistrationResponse`, and never logged.

Consequences worth being precise about:

- **Two tenants with different credentials never share a child.** That is the isolation.
- **A caller sending no credential headers gets the credential-less shared child**, exactly as
  before — which is why the three sandbox connectors are unaffected.
- **Runtime-control variables are refused**, not sanitised: `NODE_OPTIONS`, `PATH`, `LD_PRELOAD`,
  `PYTHONPATH` and friends are denylisted, because they reconfigure the *runtime* rather than
  the connector (`NODE_OPTIONS` alone would be remote code execution). A rejection is logged
  **by name**; a credential value is never logged.
- **Header names are upper-cased.** HTTP header names are case-insensitive and Node lower-cases
  them, so `X-Connector-Env-FAL_KEY` → `FAL_KEY`. A connector needing a lower-case env var
  cannot be fed this way. Every credential in this repo's connectors is already upper-case.
- **This is not a boundary against the gateway itself.** Whoever holds a connector's bearer
  token can send any env and reach a child running with it. The isolation is *between tenants*,
  not between a tenant and this process.

## Registering it

`env` entries on a **remote** registration become HTTP request headers verbatim
(`DynamicMcpConnectionManager.remoteHeaders`), which is how the token is passed:

```json
POST /api/mcp/registry
{
  "name": "office-ally",
  "type": "STREAMABLE_HTTP",
  "description": "Office Ally eligibility + claim status - sandbox mode, synthetic data only.",
  "enabled": true,
  "url": "https://<gateway-host>/mcp/office-ally",
  "env": { "Authorization": "Bearer <GATEWAY_TOKEN>" }
}
```

Needs `ROLE_tenant-admin`. The POST returns the **pre-connect** state — re-read
`GET /api/mcp/registry` to watch `connectionStatus` settle to `CONNECTED`. Custom
connectors are also gated off the FREE tier by plan limits.

`demo/three-verticals/bootstrap.sh` does all of this idempotently.

## Running it

```bash
node server.js
```

```bash
docker build -f mcp-servers/http-gateway/Dockerfile -t mcp-http-gateway:latest .
```

Build **from the repo root** — the Dockerfile copies the connector sources out of
`mcp-servers/`, so they must be in build context.

## Testing

```bash
node test/gateway.test.js
```

Real integration test, no framework: boots `server.js` as a child process, points it at the
real `office-ally` connector, and speaks real HTTP + JSON-RPC to it — the same code path a
production `STREAMABLE_HTTP` registration takes. 40 checks covering the handshake, auth,
notifications, id fidelity, concurrent-request isolation, routing and malformed input.

## What the real client actually does — three findings that cost a cycle each

Verified against production core on 2026-09-17. The earlier version of this README warned
that no real MCP client had ever connected; it has now, and it behaved in three ways the
spec does not require you to expect.

**1. It discards the path of the URL you register.** Core's own pre-probe is a plain HTTP
client and honours the full URL, so `POST /mcp/office-ally` arrives and succeeds. The SDK's
`HttpClientStreamableHttpTransport` then requests **origin + its own default endpoint**,
i.e. `POST /mcp` and `GET /mcp`. So *every* remote connector arrives on the same path and
the path cannot identify which one is wanted. That is why this gateway routes a bare `/mcp`
by a **per-connector derived token** (`sha256("<master>:<name>")[:40]`) — the `env` map is
the only per-registration signal, and `DynamicMcpConnectionManager.remoteHeaders` copies it
verbatim into request headers. The failure before this fix presented only as
`MCP initialization timed out`, with a 404 invisible on both sides.

**2. It rejects an SSE response.** Streamable HTTP lets a server answer a POST with either
`application/json` or an event stream. Answering with SSE produces
`Connection failed: The server answered with a server-sent-events stream.` Always answer
JSON, even though the client's `Accept` header advertises `text/event-stream`.

**3. It asks for `protocolVersion` 2025-06-18.** The connectors speak 2024-11-05 over stdio,
which predates Streamable HTTP. The gateway answers for the transport and echoes back a
supported modern version rather than the child's.

All three have regression tests in `test/gateway.test.js`.

## Honest caveats
- **No SSE.** If a future connector needs server-initiated messages, `GET /mcp/<server>`
  must stop returning 405 and start returning a real event stream. Nothing here does today.
- **Per-tenant isolation is by credential set, not by identity.** Two tenants presenting the
  *same* credentials share a child — correct, since they are the same principal as far as the
  upstream API is concerned, but it does mean the gateway has no notion of *who* is calling
  beyond what they present. It also does not isolate a tenant from this gateway process; see
  the per-tenant credentials section.
- **One child per credential set, shared by concurrent callers of that set.** Correct for every
  connector here because none holds per-SESSION state (`youtube-publish` is explicitly
  stateless — no ledger, no volume). A connector that did would need a child per session.
- **`/health` no longer claims a blanket sandbox mode**, because that stopped being true once
  credentialed connectors became servable. It reports `credentialed` per connector instead. The
  sandbox data behind the three demo connectors is still **fiction written for a
  demonstration** — not a sample of anyone's real data.
