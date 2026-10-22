# mcp-stdio-runner

Spawns the real subprocess for a dynamic MCP **STDIO** server registration
(`npx <pkg>`, `uvx <pkg>`, plain `node`/`python`) on `core`'s behalf, inside
its own isolated container, instead of inside `core`.

See `../../docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md` for the full threat model this
exists to close (pentest finding C1): a tenant-admin can register a custom
STDIO MCP server pointing at any package on the public npm/PyPI registries.
That package's code then runs, in full, as a real subprocess. Before this
service existed, that subprocess ran as a child of the `core` JVM, in the
same container, on the same network namespaces as Postgres/Redis/Keycloak,
holding `APP_ENCRYPTION_KEY` in memory. With this service (and
`MCP_STDIO_SIDECAR_ENABLED=true`), the real package only ever executes here:
a container with no route to `data-tier`/`app`/`agent-io`/`observability`/
`whatsapp-net`, and no platform secret in its environment at all.

---

## How `core` reaches it

`core` never spawns the real interpreter directly in sidecar mode. It spawns
a tiny local relay (`core/mcp-stdio-proxy/proxy.js`, baked into `core`'s own
image) as its subprocess instead — the relay does nothing but forward bytes,
so it's safe to run inside `core`. The relay opens one plain TCP connection
to this service and speaks the frame protocol below; this service spawns the
*real* command and relays its stdin/stdout/stderr back over that same
connection.

## Where it runs

- **Full stack** (`deploy/compose/docker-compose.yml`): profiles `tools`, `sandbox` or `browser`.
- **Personal mode** (`deploy/compose/personal.yml`): profile `tools`, **on by default for a fresh
  install** (`scripts/vegaduta personal --no-tools` or `VEGADUTA_TOOLS=0` in `.env` opts out). Sized for
  a laptop: 768 MB, at most 8 concurrent servers (`MCP_STDIO_RUNNER_MEM_LIMIT`,
  `MCP_STDIO_RUNNER_MAX_CHILDREN`), on its own `mcp-stdio-net` network with no published port and no
  mounted volume, so the person's projects folder is not visible here. It starts the starter connectors
  (`fetch`, `time`, `sequential-thinking`: exact-version `uvx` / `npx` specs registered by core's
  `McpStarterService`) and anything the owner adds later. A host JVM (`--native-core`) does not use it.

## Wire protocol

One TCP connection per MCP registration's live subprocess. Frames:
`[1-byte type][4-byte big-endian length][payload]` (see `framing.js`).

| type | direction | payload |
|---|---|---|
| 1 (control) | relay → runner | first frame only: `{command, args, env, secret}` |
| 1 (control) | runner → relay | `{type:"spawned",pid}` / `{type:"error",message}` / `{type:"stderr",line}` / `{type:"exit",code,signal}` |
| 1 (control) | relay → runner | `{type:"kill"}` (optional; closing the socket has the same effect) |
| 2 (stdout) | runner → relay | raw bytes read from the child's stdout, byte-for-byte |
| 3 (stdin) | relay → runner | raw bytes to write to the child's stdin |

**Lifecycle: the connection IS the process's lease.** There is no
heartbeat/TTL. When the socket closes — for any reason, including the relay
process (and therefore the `core` JVM that owns it) dying — the child is
SIGTERM'd immediately, then SIGKILL'd after a short grace period
(`MCP_STDIO_RUNNER_KILL_GRACE_MS`, default 3000ms). This mirrors, unchanged,
how a directly-spawned child was already correctly killed by JVM/container
shutdown before this service existed.

## Threat model / what's actually enforced

- **No inbound `ports:`** in `deploy/docker-compose.yml` — only `core`, over
  the isolated `mcp-stdio-net` network, can ever reach this service.
- **`cap_drop: ALL` + `no-new-privileges`** on the container (see that
  service's compose block). By default every MCP server runs as the one
  fixed, unprivileged `mcprunner` user, so it needs no capabilities back at
  all. Per-child uids are opt-in — see below.
- **Fails closed**: every spawn request is rejected unless the shared secret
  is configured on this container AND the request's `secret` matches it
  (constant-time compare). Prefer `MCP_STDIO_SIDECAR_SHARED_SECRET_FILE`
  (path to a mounted secret file; trailing newline stripped) over the plain
  `MCP_STDIO_SIDECAR_SHARED_SECRET` env var. If `_FILE` is set but
  unreadable or empty, the runner refuses every spawn — it never falls back
  to the env var. Either way the env var is deleted from the runner's
  `process.env` right after it is read.
- **Private sandbox per connection** (SEC-012). Each spawned child gets its
  own fresh 0700 directory under `MCP_RUNNER_SESSION_ROOT` (default
  `/tmp/mcp-runner-sessions`) holding its `HOME`, `TMPDIR`, npm cache
  (`npm_config_cache`), `UV_CACHE_DIR` and `XDG_*` dirs. It is deleted when
  the child exits or the connection closes, and leftovers from a crashed run
  are swept at startup. The caller cannot redirect any of these: `PATH`,
  `HOME`, `TMPDIR`/`TMP`/`TEMP`, `NPM_CONFIG_CACHE`/`USERCONFIG`/
  `GLOBALCONFIG`/`PREFIX`, `UV_CACHE_DIR`/`UV_TOOL_*`/`UV_PYTHON_*`,
  `PIP_CACHE_DIR` and `XDG_*` in a request's env are ignored in any letter
  case. So one tenant's package can no longer plant or read files in the
  shared `~/.npm` / `~/.cache/uv` that the next tenant's `npx`/`uvx` runs.
  The child's working directory is still the image `WORKDIR` (`/app`).
  **Cost:** nothing is cached between connections, so every spawn downloads
  its package again, and the browser image's build-time npx pre-warm is no
  longer used (the image deletes that cache, so its frozen server-puppeteer
  tree no longer ships; watch the 60s MCP init timeout for heavy packages such as
  `server-puppeteer`). All sandboxes share the `/tmp` volume (512Mi emptyDir
  in the helm chart), so size it for concurrent connections × package size.
- **Per-child uid (optional, `MCP_RUNNER_UID_RANGE`, e.g.
  `200000-200999`).** Each connection's child runs as its own uid (and gid)
  from the range, so children cannot read each other's files, signal each
  other, or read another process's `/proc/<pid>/environ`. It turns on only
  when the runner really holds `CAP_SETUID`, `CAP_SETGID`, `CAP_CHOWN` and
  `CAP_KILL` (in practice: `user: root` plus `cap_add` of exactly those
  four; keep `no-new-privileges` and the seccomp profile). Otherwise it stays
  off and the reason is logged at startup. A uid is reused least-recently
  first; if a process of that uid survives teardown, the uid is quarantined
  until restart. When the range runs out, spawns are refused
  (`runner busy: no free sandbox uid`), never shared. **Before enabling:**
  paths children write outside their sandbox (`/app/mcp-data`, the
  `outreach-data` volume) are owned by `mcprunner` and are not writable by
  range uids, and the secret file must be readable only by the runner.
- **What env scrubbing does and doesn't do.** Deleting the secret from
  `process.env` stops it being copied onward, but Linux keeps the runner's
  original environment block readable at `/proc/<runner-pid>/environ` for
  any process of the SAME uid. With the default single uid, a child can
  therefore still read a secret that came from the env var. Use `_FILE`,
  per-child uids, or both.
- **`hidepid`.** Mounting `/proc` with `hidepid=2` hides other uids'
  processes (and their command lines) from each other. It only adds anything
  with per-child uids, since a process always sees its own uid's entries.
  Docker and Kubernetes give each container a fresh `/proc` with no
  `hidepid` option, so it is not set today. If you add it, give the runner
  the `gid=` exemption, or its uid-release check (which scans `/proc`) can't
  see child processes. Even without `hidepid`, `/proc/<pid>/environ` is
  already private to each uid.
- **Defense-in-depth spawn policy** (`validateSpawnRequest` in
  `server.js`) — mirrors
  `core/mcp/src/main/java/com/agenticai/core/mcp/McpStdioCommandGuard.java`:
  launcher allowlist, inline-eval/module-loading flags (`-e`, `-c`,
  `--eval`, `--import`, `--require`, `--inspect*`, node `-p`/`-r`, ...
  including `--flag=value`, attached and clustered forms), shell
  metacharacters in args, and the env denylist (`NODE_OPTIONS`,
  `PYTHONPATH`, `LD_*`, `DYLD_*`, ...; case-insensitive). A violating
  request is rejected with an `error` control frame, never silently
  stripped. `core`'s guard still runs first; this is a second gate so a bug
  in that guard is not the only thing between a request and a spawn. Keep
  both in sync (see the divergence notes in `server.js`).
- **Spawned children get only their own configured env** plus `PATH`, the
  sandbox dirs above and the image passthroughs (`PLAYWRIGHT_BROWSERS_PATH`,
  `PUPPETEER_CACHE_DIR`, `DOCKER_CONTAINER`) — never this container's own
  process env. The runner's env holds no platform secret, and no longer the
  shared secret either.
- **`mcp-stdio-net` is intentionally NOT `internal: true`** (unlike
  `sandbox-net`) — MCP servers need real DNS/egress to fetch their own
  package from `registry.npmjs.org`/`pypi.org`. This is the one residual
  risk explicitly in scope: a compromised package can still reach the public
  internet from this container. It cannot reach anything else in the stack.
- **Custom seccomp deny-list profile** (`seccomp-profile.json`, wired via
  `security_opt` in `deploy/docker-compose.yml`). Derived mechanically from
  the moby v28 default baseline, with the dangerous set made
  capability-independent and explicit: `ptrace`/`process_vm_*`/`kcmp`/
  `pidfd_getfd`/`process_madvise` (cross-process memory reads — protects
  this container's own `MCP_STDIO_SIDECAR_SHARED_SECRET` from a spawned
  same-uid child; upstream *allows* ptrace on kernel ≥ 4.8), `unshare`/
  `setns` + unconditional `clone`/`clone3` namespace masking (blocks the
  unprivileged-userns escape primer, which upstream leaves reachable via
  `unshare` for non-cap-holders), the mount family incl.
  `name_to_handle_at`/`open_by_handle_at` (file-handle escape — the former
  was in upstream's unconditional allowlist), `AF_PACKET` added to the
  `socket()` domain denial (raw link-layer on `mcp-stdio-net`), and the
  kernel-attack surface (`bpf`, `perf_event_open`, `io_uring_*`,
  `userfaultfd`, `kexec_*`, `*_module`, `keyctl`, `fanotify_*`, time/host
  control). **Verified live on this host (2026-09-23):**
  `unshare(CLONE_NEWUSER)` and `ptrace(TRACEME)` return `0` unconfined but
  `-1/EPERM` under the profile; `server.test.js` 9/9 passes inside the
  container under it; and real `npx --version` / `uvx --version` spawns
  round-trip over the wire protocol through the compose-deployed service.
- **Residual**: `clone3`'s flags live in a struct pointer and cannot be
  arg-filtered by seccomp — the profile returns ENOSYS for `clone3`
  instead (upstream does the same without `CAP_SYS_ADMIN`), so callers
  fall back to the arg-filterable `clone`. If a future kernel lets
  `clone3` callers create namespaces through the plain-`clone` mask
  there is no regression, but a `clone3`-only namespace path is not
  byte-identically covered.

## Resource limits (all per-process, all env-tunable)

Counters are in-memory **per runner process**: with N replicas the effective
ceiling is N x the cap, and a restart resets them. They bound runaway growth;
they are not an exact fleet-wide quota. `0` disables a limit.

| Env var | Default | Meaning |
|---|---|---|
| `MCP_STDIO_RUNNER_MAX_CHILDREN` | `100` | Global concurrent children. Over the cap, a spawn request is **queued** (not refused) for up to `..._QUEUE_WAIT_MS`. Sized for `reconnectEnabledOnStartup` bursts and rolling-deploy overlap (old + new core replicas both hold children). |
| `MCP_STDIO_RUNNER_QUEUE_WAIT_MS` | `60000` | Max wait for a slot before an `error` frame is returned (`0` = refuse immediately). |
| `MCP_STDIO_RUNNER_MAX_CHILDREN_PER_TENANT` | `0` (off) | Per-tenant cap; keyed by `MCP_SIDECAR_TENANT_ID` sent by core. Requests without a tenant id are exempt. |
| `MCP_STDIO_RUNNER_IDLE_TTL_MS` | `0` (off) | Kill a child with no stdin/stdout traffic for this long. Opt-in: stderr does not count as activity. |
| `MCP_STDIO_RUNNER_MAX_LIFETIME_MS` | `0` (off) | Kill a child older than this. |
| `MCP_STDIO_RUNNER_NODE_MAX_OLD_SPACE_MB` | `0` (off) | Appends `--max-old-space-size=N` to a child's `NODE_OPTIONS` unless the caller already set one. Opt-in: can OOM-kill heavy servers. |

`GET /health` returns `200 ok` normally; `?format=json` (or `Accept:
application/json`) adds `children`, `queued`, `perTenant`, caps and `draining`.
After SIGTERM it returns **503 `draining`** until children have exited, so a
load balancer can stop routing new spawns.
