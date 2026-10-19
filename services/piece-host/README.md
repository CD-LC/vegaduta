# piece-host

A small stdio **MCP server** that exposes allow-listed
[Activepieces](https://www.activepieces.com) community pieces as MCP tools, so
an agent can use hundreds of third-party integrations without VegaDuta writing
(or hand-maintaining) a connector per service.

Status (read this first): **preview. The flag defaults to off; the unit tests
and the image build pass, but no piece has been called against its real
service yet.** See "What is verified" at the end.

- zero runtime npm dependencies in this directory (`package.json` has none);
  the MCP protocol subset and everything else is hand-written, like
  `../../examples/mcp-servers/home-assistant/`
- pieces are **installed at image build time from npm by exact version**
  (`pieces.txt`), never at runtime
- one MCP tool per piece action, named `<action>` when one piece is served (core adds `mcp__piece-<key>__`), `<piece>__<action>` otherwise
- credentials come only from the process environment, are never logged and are
  redacted from results and errors

## Licensing note (MIT pieces only)

Activepieces is MIT-licensed **except** `packages/ee/` and
`packages/server/api/src/app/ee/`, which are under a separate commercial
license (<https://github.com/activepieces/activepieces/blob/main/LICENSE>,
checked 2026-10-04). Community pieces live in `packages/pieces/community/<name>`
and are covered by the MIT license, which requires keeping the copyright and
license notice with copies/substantial portions of the software.

Rules for this directory:

1. **No Activepieces source is copied into this repository.** The host only
   depends on the *runtime shape* of a piece (its public framework API) and
   installs the published npm packages unmodified.
2. **Only `@activepieces/piece-*` community pieces** may be listed in
   `pieces.txt`. The loader rejects any other scope, any version range and any
   key that is not listed. Nothing from `packages/ee` / enterprise is, or may
   be, installed.
3. The published npm manifests of the pieces we checked (`piece-slack`,
   `piece-google-sheets`) carry **no `license` field**, so npm metadata is not
   license evidence. Evidence is the source location: the piece must exist under
   `packages/pieces/community/<name>` in the Activepieces repository at (or
   near) the version being pinned.
4. n8n (Sustainable Use License), Dify (modified Apache-2.0), Pipedream and
   Windmill/Skyvern (AGPL) code must not be copied or installed here. Ideas
   only.
5. The image is a redistribution of the pinned piece packages. Keep each
   package's own `LICENSE`/notice files in the image (they stay in
   `/opt/piece-host/pieces/node_modules/@activepieces/piece-*`; do not strip
   them). Activepieces' MIT notice is in the repository `NOTICE`.

### How to verify a piece's license before adding it

```bash
# 1. The package exists and which version you are pinning
npm view @activepieces/piece-<name> version dist.tarball

# 2. The source lives in the MIT part of the repo (a 200 here, NOT a 404)
curl -sI https://github.com/activepieces/activepieces/tree/main/packages/pieces/community/<name> | head -1

# 3. Look at what the tarball carries (license field / LICENSE file, bundled deps)
npm pack @activepieces/piece-<name>@<version> --dry-run
npm view @activepieces/piece-<name>@<version> license dependencies
```

Write the verification date and what you checked next to the entry in
`pieces.txt`.

### How to add a piece

1. Verify the license as above.
2. Add `@activepieces/piece-<name>@<exact version>` to `pieces.txt`.
3. Rebuild the image. The build runs `node server.js --describe --strict` and
   fails if the piece does not load.
4. Run `node server.js --pieces <name> --describe` (or read the build output) to
   see the **exact** credential variables and tools. Put those names (not guesses)
   in the catalog entry in `core/mcp/.../McpCatalogService.java`
   (`PieceHostCatalog`) and in its unit test.
5. Decide whether any of its actions must be denied (`PIECE_HOST_DENY`, or the
   built-in default list in `src/policy.js`).

There is deliberately **no auto-update**: a piece's actions, props and required
credentials change between releases.

## Tools

- Name: `<action>` when the host serves a single piece (the catalog case; core
  then exposes it as `mcp__piece-<key>__<action>`), `<piece>__<action>`
  (`google-sheets__insert_row`) when it serves several. Sanitised to
  `[A-Za-z0-9_-]` and capped so the FULL core name stays within the providers'
  64-character limit: `64 - len("mcp__piece-<key>__")` (floor 24, override
  `PIECE_HOST_TOOL_NAME_MAX`; hash suffix when longer, `_2`, `_3` on collision).
  `PIECE_HOST_BARE_NAMES=on|off` forces either form. Deny/allow globs always
  match `<piece>__<action>` regardless of the exposed name.
- `inputSchema` is derived from the action's `props`
  (`src/schema.js`). `required` = `required: true` **and** no default value
  (defaults are applied by the host). Static dropdowns become `enum`; dynamic
  dropdowns (options fetched from the service) are left as free values with a
  note; `DYNAMIC` props are an open object; `FILE` is
  `{filename, base64}`; `MARKDOWN`/`CUSTOM` props are display-only and skipped.
  Unsupported prop types are listed under `skippedProps` in `--describe`. The
  published schema gives every node a `type`, never an empty `items`, and omits
  `additionalProperties` (Gemini/Vertex-safe); the host validates against the
  stricter original.
- Arguments are coerced (numeric strings, `"true"`/`"false"`, JSON-in-a-string,
  `null` = omitted) and validated before the action runs; unknown fields are
  rejected so the model can self-correct.
- Results: strings as-is, anything else pretty JSON; Buffers become a
  placeholder; circular structures and BigInt are handled; the text is redacted
  and then capped at `PIECE_HOST_MAX_RESULT_BYTES` (default 64 KiB) with a
  truncation marker.
- A tool's own failure (bad args, missing credentials, the piece throws,
  timeout) is an MCP result with `isError: true`. Protocol problems are JSON-RPC
  errors.
- **Triggers are not exposed** (no webhooks/polling in a tool-call setting).

### Context shim

Actions run in-process with a shim of `ActionContext`
(`src/context.js`): real `auth` and `propsValue`, an in-memory `store`
(per process, not persisted), an opt-in `files` sink, `project`/`step`
identity. Flow-engine facilities (waitpoints/pause, `agent.tools`, stored
`connections`, `flows`, tags, webhooks, resume URLs) are no-ops or throw a
clear error. Pieces that depend on them will not work.

## Credentials

Passed **only** as environment variables of the spawned process (the
registration's `env`, which `mcp-stdio-runner` gives to the child and nothing
else). They are not accepted as tool arguments (arguments flow through the
model and the tool-call journal). With `<P>` = upper-cased piece key
(`google-sheets` -> `GOOGLE_SHEETS`):

| Piece auth type | Variables |
|---|---|
| `SECRET_TEXT` (API key / token) | `PIECE_AUTH_<P>` |
| `OAUTH2` | `PIECE_AUTH_<P>` = an **access token you obtained yourself**; optional `PIECE_AUTH_<P>_REFRESH_TOKEN`; one `PIECE_AUTH_<P>_<PROP>` per extra auth prop the piece declares. This host does **not** run the OAuth flow and does **not** refresh tokens. |
| `BASIC_AUTH` | `PIECE_AUTH_<P>_USERNAME`, `PIECE_AUTH_<P>_PASSWORD` |
| `CUSTOM_AUTH` | one `PIECE_AUTH_<P>_<PROP>` per declared prop (`instanceUrl` -> `INSTANCE_URL`) |
| any | `PIECE_AUTH_<P>_JSON`: a JSON object with the same fields; individual variables override it. A JSON object pasted into `PIECE_AUTH_<P>` itself is read the same way (only when `_JSON` is unset and the value parses as an object), so a connect form that shows one variable still works for multi-field auth |

`node server.js --describe` prints the exact variables for every installed
piece. `OIDC` auth is unsupported. A missing credential fails the call with an
error naming the variables (never values).

Secret values (tokens, passwords, props that look like keys) of 6+ characters
are redacted from results and error messages, and are never written to the
log. The log (stderr, JSON lines) records tool name, outcome and duration only.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PIECE_HOST_PIECES` / `--pieces a,b` | all in `pieces.txt` | pieces to serve (must be on the allow-list) |
| `PIECE_HOST_DENY` | empty | extra deny globs on `<piece>__<action>` (`*` wildcard, case-insensitive) |
| `PIECE_HOST_DENY_DEFAULTS` | on | `off` removes the built-in deny list (`*__custom_api_call`) |
| `PIECE_HOST_ALLOW` | empty (= all) | if set, only matching actions are exposed; deny still wins |
| `PIECE_HOST_BARE_NAMES` | `auto` | `auto` = tool names are `<action>` when one piece is served, `<piece>__<action>` otherwise; `on` / `off` force it |
| `PIECE_HOST_TOOL_NAME_MAX` | `64 - len("mcp__piece-<key>__")` | host-side tool-name cap (24..64) |
| `PIECE_HOST_MAX_RESULT_BYTES` | `65536` | result size cap |
| `PIECE_HOST_CALL_TIMEOUT_MS` | `60000` | per-call timeout (the action is abandoned, not killed) |
| `PIECE_HOST_MAX_CONCURRENCY` | `8` | concurrent calls; overflow is rejected, not queued |
| `PIECE_HOST_ALLOW_PRIVATE_EGRESS` | `false` | `true` disables the private-address egress guard |
| `PIECE_HOST_FILES_DIR` | unset | enables file-writing actions into this directory |
| `PIECE_HOST_LOG` | `info` | `error` / `warn` / `info` / `debug` |
| `PIECE_HOST_ALLOWLIST`, `PIECE_HOST_PIECES_DIR` | `<dir>/pieces.txt`, `<dir>/pieces` | overrides, for tests/dev |

`custom_api_call` ("Custom API Call", present in nearly every piece) is denied
by default: it sends an arbitrary HTTP request to an arbitrary URL with the
connection's credential attached.

## Registration (through the sandboxed stdio runner)

The runner (`../mcp-stdio-runner`) only spawns `npx`, `node`, `uvx`, `uv`,
`python`, `python3`, so the host is launched as `node` with the script path as
the first argument (same shape as the first-party connectors under
`/opt/mcp-servers/...`). Everything the child needs must be inside the **runner**
container, which is why the Dockerfile layers `/opt/piece-host` onto the runner
image:

```bash
docker build -f piece-host/Dockerfile --target runner \
  --build-arg RUNNER_IMAGE=<the mcp-stdio-runner image you deploy> \
  -t <registry>/mcp-stdio-runner:pieces piece-host/
```

The stock runner image is node:20, so build the base first with
`docker build --build-arg NODE_IMAGE=node:24.20.0-bookworm-slim mcp-stdio-runner`
(the `NODE_IMAGE` arg defaults to node:20, so a plain build is unchanged).
With Docker Compose, `../../deploy/compose/docker-compose.piece-host.yml` does
both builds as an opt-in overlay (commands in its header).

then deploy that image **as** the `mcp-stdio-runner` service (compose:
`image:` override; Kubernetes or another platform: the runner's image). Pre-requisites,
none of which are done by this change: `MCP_STDIO_SIDECAR_ENABLED=true` (the
piece host cannot run in core's own container, where `/opt/piece-host` does not
exist), `app.mcp.piece-host.enabled=true` in core to show the catalog entries,
and a runner base on a supported Node (the runner image is `node:20`, which
reached end of life on 2026-04-30; the `runner` stage fails fast when the base
is older than Node 22 - `--build-arg MIN_NODE_MAJOR=0` skips that check - and the
`--describe --strict` build step will tell you if pinned pieces need a newer one).

A registration is a normal dynamic STDIO registration
(`POST /api/mcp/registry`, `McpServerRegistrationRequest`):

```json
{
  "name": "piece-slack",
  "type": "STDIO",
  "description": "Slack via Activepieces piece",
  "enabled": true,
  "command": "node",
  "args": ["/opt/piece-host/server.js", "--pieces", "slack"],
  "env": { "PIECE_AUTH_SLACK": "<xoxb-... token you created>" }
}
```

`McpStdioCommandGuard` accepts this (bare launcher `node`, no code-loading
flags, env names are plain). The catalog entries added by `McpCatalogService`
(when `app.mcp.piece-host.enabled=true`) produce exactly this shape, one per
piece, so each connection loads only its own piece and sees only that
piece's credentials.

Local smoke test without any of that:

```bash
cd piece-host && mkdir -p pieces && (cd pieces && npm init -y && \
  npm install --ignore-scripts @activepieces/piece-slack@0.21.0)
PIECE_AUTH_SLACK=xoxb-... node server.js --describe --pieces slack
```

## Security notes

- Trust model: the **tenant admin chooses the registration**, and the runner
  already runs tenant-chosen npm/PyPI packages (see
  `../../docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md`). The container (no route to the
  platform networks, `cap_drop: ALL`, seccomp profile, no platform secrets in the
  child env) is the real sandbox. The host's own controls (allow-list, deny
  list, egress guard, redaction, caps) defend against a *prompt-injected model*
  misusing a legitimately configured connection - they are not a defence against
  a malicious tenant admin, who controls this host's environment variables.
- Egress guard (`src/egress.js`): connections made through `http`/`https`
  agents and `fetch` to loopback, RFC1918, link-local (cloud metadata), CGNAT
  and reserved ranges are refused. Known gaps: DNS rebinding between the
  `fetch` pre-check and the connect, redirects followed by `fetch`, pieces
  that open raw sockets. Network policy on the container remains the boundary.
- The runner's network is intentionally not `internal`: pieces must reach their
  SaaS APIs.
- Tool output is third-party data. Core's existing untrusted-MCP-output
  handling (`mcpUntrustedDataDirective`, the output sanitizer) applies to
  `mcp__piece-*__*` tools like any other MCP tool.
- MCP tools are not classified in code: classify these in the
  `tool_classification` table / per-tenant override (writes such as
  `send_message`, `delete_*`, refunds are irreversible).

## Tests

```bash
cd piece-host && node --test "test/*.test.js"
```

No network and no third-party packages: `test/fixtures/fake-piece-pkg` is a
hand-written stand-in with the same runtime shape as a `createPiece()` result;
`test/helpers.js` copies it into a temp `node_modules/@activepieces/piece-fake`
so the real loader/resolution path is exercised, and `test/e2e.test.js` spawns
`node server.js` and speaks MCP to it.

## What is verified

Run 2026-10-05 on a Windows host (Node v24.18.0, Docker engine 29.8.1), nothing
deployed:

- `node --test "test/*.test.js"`: 87 tests, 87 pass, 0 fail (about 0.8 s). No
  host bug was found by them.
- `docker build --target standalone`: succeeds. `npm install` of the exact
  `pieces.txt` versions with `--ignore-scripts` added 30 packages (34 MB of
  `node_modules`) with no extra `@activepieces/*` support packages needed, and
  the `--describe --strict` build step passed: all 30 pieces load, 0 errors,
  1390 tools, 28 `*__custom_api_call` tools denied. Image 372 MB.
- `docker build --target runner` on a Node 24 build of `mcp-stdio-runner`
  (`--build-arg NODE_IMAGE=node:24.20.0-bookworm-slim`): succeeds, 657 MB, runs as
  uid `mcprunner`, `/opt/piece-host` not writable. On the shipped node:20 base it
  fails fast with the intended Node-version message; with `MIN_NODE_MAJOR=0` the
  `--describe --strict` load test also passes under Node 20.
- A real stdio MCP session against the built image (`initialize`, `tools/list`,
  an unknown-tool call) behaves as the unit tests say.
- The real per-piece auth types are now read from `--describe` (not guessed);
  some differ from the auth types the core catalog entries assume.

Against published documentation / typings (2026-10-04): the framework API, the
MIT/ee license split, that all 30 pinned packages exist on npm.

**Still not verified:** any piece executing an action against its real service
(no credentials were used), OAuth token behaviour, the egress guard inside a
deployed container, the runner's TCP-frame spawn path with this image, Gemini /
OpenAI acceptance of the published schemas, anything in core (`mvn` was not run),
and any deployed environment.
