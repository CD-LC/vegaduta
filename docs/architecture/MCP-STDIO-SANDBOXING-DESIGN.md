# MCP STDIO Sandboxing — Design Options for Pentest Finding C1

**Date:** 2026-07-27
**Input:** an internal MCP security review (not published) — Critical finding **C1**, its "Remediation
applied" section, and Residual-risk register entries **R1** and **R6**.
**Scope of this document:** design analysis only, scoped to the one open Critical. It does not
re-litigate H2/M2 (SSRF/DNS rebinding) or the Medium/Low backlog — those have their own fixes
tracked separately.

---

## 1. The problem, restated precisely

`McpStdioCommandGuard` (same-day fix, 2026-07-25) closed the crudest RCE: `node -e`, `python -c`,
`--eval`, `--command`, `--print` (with the `--flag=code` form) are now rejected on every allowed
launcher (`npx`, `node`, `uvx`, `uv`, `python`, `python3`). What it deliberately does **not** and
*cannot* block, by the security review, is the case that is also the entire point of the
feature: `npx <any-published-npm-package>` and `uvx --from <any-published-pypi-package>` run that
package's own code, in full, as a real subprocess. A tenant-admin (a customer role — see
`SecurityConfig`'s `ROLE_tenant-admin` gate on `POST /api/mcp/registry`) can register a STDIO MCP
server pointing at any package on the public npm/PyPI registries and get arbitrary code execution
**inside the shared `core` container**.

That container, confirmed by reading `deploy/docker-compose.yml`'s `core` service block, is on all
three internal Docker networks — `data-tier` (postgres/pgbouncer/redis/keycloak/minio),
`backend` (redpanda/temporal/sidecar/langfuse/livekit/code-intel/rl-trainer), and `whatsapp-net` —
and holds `APP_ENCRYPTION_KEY`, the datasource password, the Keycloak client secret, and every
tenant's decrypted provider keys/channel secrets in JVM memory during request handling. The spawned
subprocess is a **child of the same Java process**, in the **same container**, on the **same
network namespaces**, running as the **same Linux user** (`agentic`, confirmed non-root in
`core/Dockerfile:85,95` — real, but only a defense-in-depth label; it does not create a new network
or PID namespace, and same-uid processes can typically `ptrace`/read `/proc/<pid>/environ` of each
other unless the container additionally drops `CAP_SYS_PTRACE` or sets `no_new_privs` with a
restrictive seccomp profile, neither of which the `core` service currently does). Env isolation
(`EnvIsolatedStdioClientTransport`) stops the child *inheriting* secrets via its environment, but it
does nothing about the child reading the filesystem, connecting to `postgres`/`redis`/`keycloak`
over the container's existing network stack, or introspecting its own parent process.

This is a **container/process isolation gap**, not a string-matching gap. No further allowlist or
arg-blocking closes it — that's the security review's own conclusion, and this document doesn't
re-argue it, only scopes what it would actually take to fix it.

---

## 2. Constraint-gathering: what this host actually has

Checked directly on the deployment host, not assumed:

```
docker --version               → Docker version 25.0.3, build 4debf41
docker compose version         → Docker Compose version v2.24.6-desktop.1
docker info (OSType/Kernel)    → linux, Docker Desktop, 6.18.33.2-microsoft-standard-WSL2
docker info (Runtimes)         → io.containerd.runc.v2, runc   (no gvisor/runsc, no kata, no firecracker-containerd)
docker info (DefaultRuntime)   → runc
docker info (SecurityOptions)  → [name=seccomp,profile=unconfined name=cgroupns]   (no "name=apparmor" entry)
```

Inside a throwaway container on this daemon:

```
/proc/self/status  → Seccomp: 2, Seccomp_filters: 1   (Docker's default seccomp profile IS active/enforced)
/sys/module/apparmor/parameters/enabled → N            (AppArmor LSM present in the kernel but disabled)
/sys/kernel/security                    → empty        (no securityfs entries — consistent with AppArmor off)
```

`where`/`which` checks for `runsc`, `firecracker`, `bwrap`, `nsjail`, `apparmor_parser`,
`seccomp-tools` on the host: **none found**.

**What this means concretely:**

- This is **plain Docker Compose on Docker Desktop (Windows host, WSL2 backend)**. Not Kubernetes,
  not a managed container platform, no k8s primitives (PodSecurityPolicy/gVisor RuntimeClass,
  NetworkPolicy CRDs) available anywhere in this stack.
- **Custom seccomp profiles work.** `--security-opt seccomp=<profile.json>` / Compose's
  `security_opt: [seccomp:./profile.json]` is a live, usable primitive on this exact host today —
  Docker's default profile is already being enforced, so replacing it with a stricter custom one is
  a supported operation, no extra install required.
- **AppArmor profiles will not be enforced on this host.** The kernel module exists but is
  disabled in Docker Desktop's WSL2 VM, and there is no `apparmor_parser` to load a profile even if
  one were written. `security_opt: [apparmor:<profile>]` on this host is a no-op at best, or a
  container-start failure at worst (Docker refuses `apparmor=` options when AppArmor isn't
  available on the daemon, depending on version). Any design that leans on AppArmor for this
  deployment is not currently viable — seccomp is the one confinement layer this host actually
  gives you natively.
- **gVisor (`runsc`) is not installed** and would need active installation as an alternate
  containerd/Docker runtime. This is achievable without Kubernetes (`--runtime=runsc` on a plain
  `docker run`, or Compose's `runtime:` key on a service), but it means modifying Docker Desktop's
  own managed WSL2 VM (`docker-desktop`/`docker-desktop-data` distros) to install a Linux binary
  inside a VM that Docker Desktop owns and can reset/rebuild on update — every Docker Desktop
  upgrade is a real risk of silently reverting that install. Compatibility is also a live concern:
  `runsc`'s syscall emulation has known gaps with some workloads, and Node.js/npm's/uv's actual
  syscall surface under gVisor for arbitrary published packages has not been validated here.
- **Firecracker is not viable on this host at all.** Firecracker needs `/dev/kvm` (hardware
  virtualization exposed to the guest running it). Docker Desktop on Windows runs its Linux
  environment inside WSL2, which itself runs on Hyper-V; nested virtualization (KVM inside that
  WSL2 VM) is not something this stack currently has, is not guaranteed to be enableable depending
  on the physical host's virtualization support, and is not something a Compose-level change can
  turn on. This option needs a different host entirely (bare-metal Linux with KVM, or a cloud VM
  with nested-virt enabled) — out of scope for "make this Compose stack safer."
- **bubblewrap/nsjail are not installed**, and more importantly: to use either one *inside* the
  `core` container (the only place they could run without a new service), that container would
  need `--cap-add SYS_ADMIN` (or equivalent) to create its own user/mount namespaces. That is a
  direct trade against the very container holding `APP_ENCRYPTION_KEY` — handing `core` a broader
  capability set specifically so it can *build a smaller sandbox inside itself* weakens the outer
  boundary to build an inner one. This tension is real and, on this host, not obviously worth it:
  the outer container already has network-namespace access to `data-tier`/`backend` regardless of
  what nsjail restricts *inside* it, so the highest-value isolation (no route to Postgres/Redis/
  Keycloak) is a network-topology property, not something a namespace tool run from inside the
  same container+network stack can grant.

**Bottom line on constraints:** the only isolation primitive this exact host supports today,
without new host-level installation or infra risk, is **Docker's own container/network boundary**
— separate services, separate networks, seccomp profiles, dropped capabilities, non-root. That
maps directly to option (a) below and rules out (b)/(c) as this-host-today options.

---

## 3. Options considered

### (a) Separate unprivileged sidecar container (`mcp-stdio-runner`)

`core` stops calling `ProcessBuilder`/`StdioClientTransport` directly. Instead, a new Compose
service spawns and owns the STDIO subprocess; `core` talks to it over a narrow protocol (gRPC,
plain HTTP, or a Unix domain socket if co-located) to say "run `npx <pkg>` with this env/args,
proxy MCP JSON-RPC frames over stdin/stdout, give me results/tool list back."

**Isolation gained:**
- New container = new network namespace. Put it on a **new, isolated Compose network** with *no*
  membership in `data-tier`/`backend`/`whatsapp-net` (this repo already has the precedent for this
  kind of segmentation — see the top-level `networks:` block: `data-tier`/`backend`/`whatsapp-net`
  are already separate bridges, and most services only join the one or two tiers they need). A
  compromised `npx`-run package in this sidecar has no route to `postgres`, `pgbouncer`, `redis`,
  `keycloak`, or `core` itself except the one narrow port the IPC protocol needs — and `core` can
  reach the sidecar without the sidecar needing to reach back arbitrarily (a one-way-initiated
  connection, or a response-only pattern over the same TCP connection).
- Runs as a dedicated non-root user with its own minimal image (e.g. `node:20-slim` + `uv`
  installed, no JDK, no app source, no `APP_ENCRYPTION_KEY` in its environment at all — it never
  needs it).
- `security_opt: [no-new-privileges:true]`, `cap_drop: [ALL]` plus only what the launchers
  genuinely need (none of `npx`/`uvx`/`node`/`python` need any Linux capability beyond default
  process creation), and — since seccomp *is* live on this host — a custom seccomp profile that
  denies the syscall families a legitimate MCP server has no reason to use (`mount`, `ptrace`,
  `reboot`, `kexec_load`, raw socket creation, etc.) while allowing the default Docker profile's
  baseline. This is a real, usable hardening layer on this exact host, unlike AppArmor.
- Resource limits (`mem_limit`, `pids_limit`, `cpus`) become a per-server-class ceiling instead of
  competing with `core`'s own JVM heap — closes part of H3's resource-exhaustion concern for free.

**Real engineering cost (this is the honest part):**
1. **New service + Dockerfile.** Minimal but real: Node 20 + uv, a small runner process (could be a
   thin Node or Python HTTP/gRPC server) that accepts "spawn(command, args, env)" and pipes
   stdin/stdout/stderr back over the wire framed as whatever the IPC protocol is.
2. **New IPC protocol.** MCP's STDIO transport is a raw JSON-RPC-over-stdin/stdout stream, and the
   SDK (`io.modelcontextprotocol...StdioClientTransport`) is built assuming it owns a local
   `Process`. To route through a sidecar, either:
   - (i) tunnel raw bytes — the sidecar exposes something like a WebSocket/TCP stream that mirrors
     the child's stdin/stdout verbatim, and a new transport implementation on the `core` side reads
     from that socket instead of a local `Process`'s streams. This preserves the existing MCP
     JSON-RPC framing untouched but means writing a custom `McpClientTransport` (the SDK's
     transport interface is pluggable — `HttpClientSseClientTransport`/`StdioClientTransport`/
     `HttpClientStreamableHttpTransport` are all implementations of it) that speaks "socket that
     happens to carry stdio bytes" instead of "local process." This is the *cleaner* shape because
     it does not touch the JSON-RPC semantics MCP already runs, but is real, novel code — not a
     config change.
   - (ii) build a request/response wrapper (gRPc `SpawnAndCall(command, args, env, requestFrames)
     returns (responseFrames)`) that reimplements MCP's initialize/listTools/callTool handshake at
     the sidecar boundary instead of tunneling raw bytes. More total code, but easier to reason
     about failure modes (timeouts, partial writes) than a raw byte tunnel.
   Either shape is a multi-day build, not a one-file patch.
3. **Lifecycle/cleanup.** Today, `DynamicMcpConnectionManager` owns a `McpSyncClient` per live
   registration and calls `client.closeGracefully()` on disconnect (kills the child through the
   SDK's own process handle). With a sidecar, "close" has to be a real RPC (`Kill(processId)`),
   the sidecar has to track its own spawned-process table per tenant/registration id (mirroring
   `liveStdioIds` today), and orphan cleanup needs its own story — what happens if `core` restarts
   mid-connection and the sidecar still has a live child with no owner? (Today this is trivially
   correct: the child is a subprocess of the JVM, so the JVM dying kills it via normal Unix process
   semantics or Docker's container-stop signal propagation. Split across two containers, that
   free correctness disappears — needs an explicit heartbeat/TTL/reap policy in the sidecar.)
4. **Tool-call round-trip cost.** Every `client.listTools()`/tool invocation now crosses two
   container boundaries (core → sidecar → child process → sidecar → core) instead of one
   (core → child process). Added latency is probably small (local Docker network, no external
   hop) but is a real, measurable regression versus today and needs a load-check, not just an
   assumption.
5. **The `maxStdioConnectionsPerTenant` cap, the per-tenant STDIO-test slot limiter (H3 fix), the
   stderr-tail capture for the Connection Doctor (`StderrTailBuffer`/`McpStdioSelfHealer`), and the
   Windows `npx`→`npx.cmd` normalization** all currently live inside `DynamicMcpConnectionManager`
   assuming direct process ownership. Every one of these needs to be re-homed to (or re-plumbed
   through) the sidecar boundary, or the fixes those pentest findings already closed silently stop
   working the day this ships. This is the single biggest reason "just spawn it in a different
   container" is not a small patch: half of C1's *existing, shipped* remediation is wired into
   assumptions this option breaks.

**Rough size estimate:** new service + Dockerfile + compose wiring (~0.5–1 day), IPC protocol
definition + server-side implementation (~2–4 days depending on shape (i) vs (ii)), core-side
transport/client rewire including re-homing the cap/self-heal/stderr-tail logic (~3–5 days), test
rewrite for `DynamicMcpConnectionManagerTest` plus new sidecar-protocol tests (~2–3 days),
hardening pass (seccomp profile authoring + verification, resource limits, orphan-cleanup) (~2
days). **Call it 2–3 weeks of focused work for a defensible v1**, not counting whatever surfaces
during integration. That is explicitly the size of change the task instructions asked to be honest
about — this is not a same-session patch.

### (b) MicroVM-per-connection (gVisor / Firecracker)

Covered in §2's constraint-gathering. Concretely:

- **Firecracker: not feasible on this host at all.** No KVM exposure through Windows → Hyper-V →
  WSL2 → Docker Desktop's Linux VM. Would require a different deployment target entirely (bare-
  metal Linux, or a cloud VM with nested virtualization explicitly enabled) — a platform-migration
  decision, not something this design can scope as a Compose change.
- **gVisor: theoretically installable, practically fragile here.** `runsc` as an alternate
  `--runtime=` is a genuine Docker-level feature that doesn't need Kubernetes. But installing it
  means modifying Docker Desktop's own internally-managed WSL2 distro — a part of the stack this
  repo doesn't own or control the lifecycle of (a Docker Desktop version bump can rebuild that VM
  and silently drop the install). Even if installed, gVisor's syscall-interception model has real,
  documented compatibility and performance costs for workloads it wasn't tuned for; whether
  `npx`/`uvx`'s actual syscall surface (native npm postinstall scripts sometimes do surprising
  things — compiling native addons, etc.) runs cleanly under `runsc` has not been validated in this
  environment and would need its own spike before being trusted for production traffic.
- Even where gVisor is genuinely the strongest per-connection isolation available short of a real
  microVM, it does not remove the need for (a)'s network-topology work (a gVisor sandbox that's
  still plumbed onto `data-tier` is still a gVisor sandbox that can reach Postgres) — it would be
  additive to (a), not a replacement for it, and only worth the operational risk once (a)'s service
  boundary already exists.

**Verdict:** not achievable as a scoped, testable change on this host. Worth revisiting only if the
deployment target changes to bare-metal Linux or a cloud host with nested virtualization, and even
then only as a hardening layer on top of (a)'s network segmentation, not instead of it.

### (c) Lightweight namespace/resource wrapper (bubblewrap / nsjail)

Neither tool is installed on this host. The only place either could run without inventing a new
service is *inside* the existing `core` container — which requires granting that container
`CAP_SYS_ADMIN` (or equivalent) to create nested user/mount namespaces, directly weakening the
boundary around the one container that holds `APP_ENCRYPTION_KEY` and every tenant's decrypted
secrets, specifically in order to build a smaller sandbox inside it. Even granting that trade, it
does not solve the network-segmentation half of the problem: a subprocess wrapped in bubblewrap
but still running inside `core`'s own network namespace still has a route to `postgres`/`redis`/
`keycloak` unless bubblewrap is *also* asked to strip network namespace access (`--unshare-net`),
which is achievable but then needs its own explicit allow-listed egress (how does the child still
reach `registry.npmjs.org`/`pypi.org` to fetch the package it was told to run?) — solving that
well ends up re-deriving most of (a)'s network topology anyway, just built with a namespace tool
instead of a second container, on a host that doesn't have the tool installed and would need a new
capability grant to use it.

**Verdict:** not a good fit for this host. The capability trade-off is worse than (a)'s (weakens
the secret-holding container instead of building a boundary outside it), the tooling isn't present,
and a version that actually isolates network access converges on redoing (a)'s topology with less
mature primitives.

### (d) Do nothing structural yet — keep interpreter/arg-allowlist posture, change the *product* posture

Gate self-service STDIO MCP registration behind a staff-review queue instead of instant
tenant-admin self-service — i.e. make **R6's "accept under a trusted-operator model" the actual,
explicit, documented current product decision**, not an implicit gap. Concretely this could mean:
a new registration status (`PENDING_REVIEW`) for STDIO servers proposed by a tenant-admin, visible
to platform staff, that must be explicitly approved before `connect()`/`test()` will run it — the
existing `McpUsageStatus`/readiness-gate machinery (`usableDynamicRegistrationIds`,
`resolveDynamicToolCallbacks`'s fail-closed gate) already has the right shape to add one more gate
state to, since it already distinguishes "registered" from "usable."

This is explicitly what the security review's own "Remediation applied" section already states is the
**current actual safe posture** ("Current safe posture: trusted/first-party (staff-reviewed) MCP
registration only") — so (d) is less a new decision than making that already-true statement into an
enforced product behavior instead of an unenforced document footnote that a future engineer could
easily miss and re-enable instant self-service without realizing what it reopens.

**Cost:** small and well-scoped relative to (a) — one new status value, one new admin-facing
review queue/endpoint, one gate check in the connect path. Genuinely implementable as a scoped,
testable change without new infrastructure. Does **not** reduce the underlying risk if/when
self-service is ever turned back on — it is a policy gate around the existing gap, not a fix to it.

---

## 4. Recommendation

**Recommend (d) as the immediate, scoped, implementable decision; treat (a) as the real follow-up
project, explicitly not "a patch."**

Reasoning:

- (b) and (c) are ruled out on this exact host by fact-finding, not preference: no KVM path for
  Firecracker, no gVisor installed (and installing it fights Docker Desktop's own VM-lifecycle
  ownership), no bubblewrap/nsjail installed (and using either from inside `core` trades against
  the very container the fix is trying to protect).
- (a) is the *architecturally correct* fix and this host can support its core primitive (separate
  Docker network with no route to `data-tier`/`backend`, non-root, seccomp-hardened sidecar) with
  zero new host tooling. But it is honestly a 2–3 week project once the re-homing of the cap/
  self-heal/stderr-tail/Windows-normalization logic and the new IPC protocol are accounted for —
  not something to half-build in this session. The task's own instructions are explicit that a
  half-implemented (a) should be backed out rather than left partially wired, and a partial version
  (sidecar exists but `DynamicMcpConnectionManager` doesn't actually route through it, or routes
  through it but drops the STDIO cap/self-heal features) would be worse than the current state: it
  would look fixed without being fixed, and would silently regress the H3/self-heal remediations
  that already shipped.
- (d) does not reduce the *technical* severity of C1 — `npx <pkg>` is still full RCE the moment
  self-service STDIO registration is enabled for untrusted tenants — but it correctly matches the
  actual current product state (self-service is, per the security review, not something that has been
  signed off for untrusted tenants) to an enforced code path instead of an unenforced assumption.
  It is genuinely a same-session-sized, testable change.

**This document does not implement (d) either.** The task's brief was to scope and design; (d)
touches `McpRegistryService`/`McpUsageStatus`/`McpRegistryController` (product-behavior change,
not a security-library change) and should go through its own review of what "staff review" means
operationally (who are "staff" in Keycloak roles today? is there an existing `ROLE_platform-admin`
or equivalent to gate the approval endpoint on? — that's a real question this session did not
chase down, since it's a separate scoped task from "design the sandboxing options"). Flagging (d)
as the next concrete, small PR rather than building it speculatively here.

---

## 5. What was NOT changed

No code in `core/mcp/` or `core/agents-tools/.../tools/` was modified by this session. Specifically
preserved, unweakened:
- `McpStdioCommandGuard`'s arg-allowlist / inline-eval blocking (C1's shipped partial fix).
- `EnvIsolatedStdioClientTransport`'s env-clearing.
- `McpRemoteUrlGuard`'s SSRF literal/resolved-IP checks (H1/H2 fixes).
- `maxStdioConnectionsPerTenant` cap and the H3 in-flight-test-slot limiter.

This document is additive analysis on top of all of the above, not a replacement for any of it —
per the task's own explicit instruction.

---

## 6. Suggested next steps (for maintainer / product sign-off, not auto-actioned here)

1. Confirm (d)'s framing is actually wanted: is instant tenant-admin self-service STDIO
   registration a feature this product currently needs live, or was it always intended to be
   staff-reviewed and the review gate simply was never built? (The security review's residual-risk
   register treats R6 as "accept only under a trusted-operator model with explicit product
   sign-off" — that sign-off doesn't appear to exist yet as an enforced control.)
2. If (d) is confirmed, scope it as its own PR: new `McpUsageStatus` state, an admin review
   endpoint/UI surface (web-admin as well as core), and a gate in
   `DynamicMcpConnectionManager.rejectIfGuardBlocked` (or a new check alongside it) that refuses to
   connect a STDIO registration until it's been approved.
3. If/when (a) is greenlit as a real project: start from this document's engineering-cost
   breakdown in §3(a) as the scoping input, and re-run the same host constraint-check (docker/
   runtime/seccomp/apparmor availability) against whatever host actually runs it, since none of
   this analysis assumed anything the deployment host didn't already show.

## 7. Status update — 2026-08-05

Step 2 above is done: the maintainer confirmed self-service STDIO was never meant to be instant-live, and
option (d) shipped. `McpReviewStatus` (`NOT_REQUIRED`/`PENDING`/`APPROVED`/`REJECTED`) gates every
custom/registry-browse-sourced STDIO registration — catalog-sourced STDIO (platform-vetted) and all
remote/HTTP servers (different threat model, no code execution in this container) stay
`NOT_REQUIRED`. `DynamicMcpConnectionManager.connect()`/`.test()` refuse to spawn anything `PENDING`
or `REJECTED`, as the first check, before any other guard. Provenance is derived server-side
(`McpCatalogService.isCatalogEntry`) and re-evaluated on every save, never client-supplied. Platform
staff review at `POST /api/platform/mcp-review/**` (`ROLE_platform-admin`, the same role already
gating Smart-AI/hosted-pool admin), with a review-queue screen in web-admin. See `McpReviewStatus.java`,
`McpRegistryReviewService.java`, migration `V183__mcp_review_gate_retry_and_credential_flags.sql`.

**This closes the residual-risk register's R6 gap for the self-service path.** It does **not**
touch the underlying container/process isolation gap this whole document is about — an
*approved* custom STDIO server is still a full child process of the `core` JVM, same container,
same network namespaces, same Linux user, exactly as §1 describes. The review gate raises the bar
from "any tenant-admin, unreviewed, instantly" to "any tenant-admin, after one human looks at the
command/args" — a real improvement (catches obviously-malicious or obviously-wrong registrations
before they ever run), but it is a policy control, not a technical isolation boundary, and a
reviewer approving a legitimately-useful-but-compromised package (a supply-chain attack, not an
obviously-malicious one) gets the exact same unmitigated blast radius described in §1.

**Option (a) — the real fix — remains open and unscheduled.** This document's engineering-cost
breakdown in §3(a) is still the right scoping input whenever it's picked up; nothing about today's
interim gate changes that estimate or reduces its priority. Tracked here rather than as a silent
gap: full sidecar/process isolation for MCP STDIO children is the correct long-term posture and
should stay on the roadmap.

## 8. Status update — 2026-08-05, option (a) implemented, shipped with the flag OFF

Option (a) is now built: a new `mcp-stdio-runner` service (Node, `../mcp-stdio-runner/`) that
spawns the real interpreter/package for a STDIO registration inside its own container, on its own
network with no route to `data-tier`/`app`/`agent-io`/`observability`/`whatsapp-net`, holding no
platform secret. `core` routes to it when `mcp-stdio-sidecar.enabled=true`
(`McpStdioSidecarProperties`, `MCP_STDIO_SIDECAR_ENABLED` in `deploy/docker-compose.yml`) - default
**false**, so this is a complete, testable v1 that changes nothing about today's behavior until an
operator deliberately opts in per environment. Same "ship fully wired, off by default" posture
this codebase already uses for `tool-sandbox` (`TOOL_SANDBOX_ENABLED`).

### A simpler mechanism than §3(a) scoped

§3(a) assumed the hard part would be writing a brand-new `io.modelcontextprotocol.spec.McpClientTransport`
implementation in Java (shape (i)/(ii)) to tunnel JSON-RPC frames to the sidecar - multi-day,
novel reactive code, confirmed via `javap` against the actual bundled SDK
(`mcp-core-0.18.2.jar`): `McpClientTransport.connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>)`
plus inherited `sendMessage`/`closeGracefully`/`unmarshalFrom`, with zero existing test coverage to
build on (`DynamicMcpConnectionManagerTest`'s own class javadoc states the SDK spawn path "is not
something this sandbox can compile/run against a real SDK, so it has no coverage here").

The implementation instead keeps using the real, already-shipped
`EnvIsolatedStdioClientTransport extends StdioClientTransport` completely unchanged. `core` still
spawns a local child process exactly as it always has - it just spawns a tiny, zero-dependency
relay (`core/mcp-stdio-proxy/proxy.js`, baked into `core`'s image) instead of the real interpreter.
The relay opens one plain TCP connection to `mcp-stdio-runner`, which spawns the **real** target
inside itself and relays stdin/stdout/stderr back over that same connection. The wire protocol
(`framing.js`, duplicated deliberately in both `mcp-stdio-runner/` and `core/mcp-stdio-proxy/` -
they ship in separate Docker build contexts) is a plain length-prefixed frame codec: no third-party
dependency anywhere in either the sidecar or the relay, on principle - a component built
specifically to contain the blast radius of a compromised third-party package should not itself
carry one.

This has real consequences for §3(a)'s cost items:
- **Item 5 (cap/self-heal/stderr-tail/Windows-normalization re-homing) mostly disappears.**
  `constructStdioTransport`'s reflective-constructor logic, `buildStdioTransport`'s
  `instanceof StdioClientTransport` stderr wiring, `StderrTailBuffer`, `McpStdioSelfHealer`,
  `maxStdioConnectionsPerTenant`, and the H3 in-flight-test-slot limiter all keep working
  **completely unchanged** - from Java's perspective it is still spawning and owning a local
  `Process`, exactly as before this existed. The relay's stderr frames are written to its own real
  OS-level stderr, so `setStdErrorHandler` - the SDK's own public seam - picks them up with zero
  code changes on the Java side.
- **Item 3 (orphan cleanup / "what if `core` restarts mid-connection") is free, not a new
  heartbeat/TTL system.** The sidecar's live child is owned by a TCP connection, owned by the
  relay process, owned by the JVM via ordinary Unix child-process semantics - identical to how
  `closeGracefully()`/JVM death already correctly killed a directly-spawned child before this
  existed. When the connection drops for any reason, `mcp-stdio-runner` kills the child
  immediately (verified live - see below).
- **The Windows `npx.cmd` normalization item is sidestepped, not solved differently**: in sidecar
  mode `core` always spawns `node /opt/mcp-stdio-proxy/proxy.js`, never a literal `"npx"`, so
  `normalizeCommandForWindows` is simply never in the sidecar-mode code path at all (confirmed by
  the added `stdioParamsForRoutesThroughTheProxyWhenSidecarModeIsEnabled` test).
- **Item 4 (round-trip cost)** is real but unmeasured against production traffic - flagged as
  open below, not claimed solved.
- **Items 1/2 (new service + IPC protocol)** are what actually got built, just smaller than
  scoped: a length-prefixed TCP frame codec instead of a from-scratch MCP-transport
  implementation.

### What's verified, and how

Every claim above is backed by a real test, not assumed:
- `mcp-stdio-runner/server.test.js` (`node:test`) - real subprocess spawn/relay/kill, using
  `node -e <script>` as a deterministic stand-in child: auth rejection, disallowed-launcher
  rejection, stdout relayed byte-for-byte, stderr relayed line-delimited, exit code relayed, and -
  the one that matters most for the "orphan cleanup" claim above - **closing the socket kills the
  still-running child**, verified by checking the real OS pid is gone (`process.kill(pid, 0)`
  throwing `ESRCH`), not by inspecting internal state.
- `core/mcp-stdio-proxy/proxy.test.js` - end-to-end: spawns `proxy.js` as a REAL
  `child_process.spawn` (not a mock of either half), connects it to a live in-process
  `mcp-stdio-runner`, and round-trips stdin -> sidecar -> real child -> stdout -> proxy -> back,
  byte-for-byte. Also covers wrong-secret rejection, sidecar-unreachable handling, real exit-code
  propagation, and tolerance for an accidental `tcp://`/`http://` scheme prefix on the address.
- `DynamicMcpConnectionManagerTest` - `wrapForSidecar` (pure function: correct proxy
  command/args, control env vars present and correct, a colluding tenant-supplied env key named
  e.g. `MCP_SIDECAR_SECRET` cannot override the real one) and `stdioParamsFor`'s branch (direct
  spawn unchanged when disabled; routes through the proxy when enabled; the launcher guard still
  runs against the REAL command/args/env and still rejects a disallowed one even in sidecar mode -
  proving the wrap happens strictly after guard approval, never instead of it). 33/33 tests pass
  including every pre-existing test in this class, unchanged.
- `docker compose -f deploy/docker-compose.yml config` - the new service/network resolve
  correctly, `core` gets `mcp-stdio-net` added to its existing 6 networks, and every new env var
  defaults to the same "off/empty" values that keep today's behavior exactly as it was.
- **The image was actually built and booted, not just statically reviewed** (`docker compose
  build mcp-stdio-runner`, then a standalone `docker run` with the real Dockerfile's non-root
  `mcprunner` user confirmed live via `docker exec ... id`), and driven over the real TCP wire
  protocol from outside the container. This caught one real bug the unit-test suite above did
  NOT catch (both `node:test` suites run the runner in-process on the host, where `process.env`
  already carries a working `PATH`, masking the gap): `handleSpawnRequest` built the spawned
  child's env ONLY from the caller-supplied map, discarding this container's OWN `PATH`/`HOME`
  entirely - so any spawn request that didn't itself carry an explicit `PATH` (the realistic
  case; a registration's configured env is just that server's own API keys, never `PATH`) failed
  every time with `ENOENT`, even for `npx`/`uvx` genuinely present on this image's PATH. **This
  would have silently broken every real MCP server the moment the flag was enabled anywhere**,
  despite every automated test in this section passing. Fixed forward
  (`mcp-stdio-runner/server.js`: `childEnv` now starts from this container's own `PATH`/`HOME`,
  with the caller's env layered on top rather than replacing it), pinned by a new regression test
  (`server.test.js` - spawns with a deliberately empty `env: {}` and asserts the child observes
  this container's real `PATH`), and re-verified live against the rebuilt image: `npx --version`
  and `uvx --version` (the actual production launchers, not stand-ins) both now succeed with a
  clean exit 0 and correct version output over the real wire protocol. Left as the strongest
  argument in this whole document for why "verified live" and "unit tests pass" are not the same
  claim (the general form of
  this trap is: merged is not deployed).

### Re-run host constraint-check (design doc's own §6 step 3)

Re-run directly on this host (the staging deployment host at the time,
not assumed to still match §2's 2026-07-27 snapshot:

```
docker --version               → Docker version 29.5.3-rd, build 5d9ffe3
docker compose version         → Docker Compose version v5.1.4
docker info (SecurityOptions)  → [name=seccomp,profile=/etc/rancher-desktop/seccomp.json name=cgroupns]
```

**This host has moved from Docker Desktop to Rancher Desktop** since §2 was written (the `-rd`
build suffix and the `/etc/rancher-desktop/seccomp.json` profile path are both Rancher Desktop
signatures - consistent with project memory's independent finding of a Rancher Desktop
`docker_data.vhdx` on this same host). Re-verified inside a throwaway container:

```
Seccomp: 2, Seccomp_filters: 1                    (still enforced - profile identity changed, not enforcement)
/sys/module/apparmor/parameters/enabled → N       (still disabled at the kernel level)
/sys/kernel/security                    → empty   (still consistent with AppArmor off)
runsc/firecracker/bwrap/nsjail/apparmor_parser     (still none installed)
```

**§2's conclusions are unchanged in substance** - only the engine identity changed, not what it
supports. Docker's own container/network boundary (separate services, separate networks, dropped
capabilities, non-root) is still the one isolation primitive this host supports natively without
new host tooling or risking Docker/Rancher Desktop's own VM-lifecycle ownership - exactly what
`mcp-stdio-runner`'s compose block uses. One correction for whoever picks up seccomp hardening
next (see below): a custom profile now needs to be authored as an addition to
`/etc/rancher-desktop/seccomp.json`'s baseline, not Docker Desktop's old default - the baseline
itself changed with the engine.

### What's deliberately NOT done here

- ~~**No custom seccomp deny-list profile.**~~ **SHIPPED 2026-09-23** (see §9 below) -
  `mcp-stdio-runner/seccomp-profile.json`, a moby-v28-baseline profile with the dangerous
  syscall set made capability-independent, wired via `security_opt` in
  `deploy/docker-compose.yml` and verified live on this host (denial A/B + full test suite +
  real `npx`/`uvx` spawns under the profile).
- **No live end-to-end MCP registration test against a booted `mcp-stdio-runner` container in
  this stack.** Every claim above is backed by a real, passing automated test (see above) - but
  none of them boot the actual `deploy/docker-compose.yml` stack with `MCP_STDIO_SIDECAR_ENABLED=true`
  and register a real npm-published MCP server end to end. That is deliberately left as the next
  operator step once the flag is turned on in a chosen environment - matching how `tool-sandbox`
  itself was verified live in its own dedicated pass (see this repo's coordination log,
  2026-08-05 "tool-sandbox verified LIVE in a container") rather than in the same session that
  first wired it.
- **No round-trip latency measurement.** §3(a) item 4's concern (every tool call now crosses two
  container boundaries instead of one) is architecturally addressed by keeping the relay a thin,
  synchronous byte-forwarder, but "probably small on a local Docker network" is not the same claim
  as a measurement. Worth a load-check before this is the default posture for chat-latency-
  sensitive tenants.
- **`mcp-stdio-runner`'s command-allowlist re-check duplicates `McpStdioCommandGuard`'s base list
  by hand** (`ALLOWED_COMMANDS` in `server.js`) rather than sharing a single source of truth - a
  defense-in-depth check, not the real gate (core's guard still is, and still runs against the
  real command/args/env before anything is wrapped for the sidecar), but a manually-kept-in-sync
  duplicate is a real maintenance liability if that allowlist ever changes. Flagged rather than
  engineered around, since sharing config across a JVM and a Node process cleanly is its own small
  scoping question.

### Turning it on

A deploy/ops action, not a code change, in any given environment:
1. `openssl rand -hex 32` → `MCP_STDIO_SIDECAR_SHARED_SECRET` in that environment's `deploy/.env`
   (this repo's worktrees never carry the real `deploy/.env` - it's untracked; see
   `deploy/.env.example`'s new block for the exact variable names/comments).
2. `MCP_STDIO_SIDECAR_ENABLED=true` alongside it.
3. `docker compose up -d mcp-stdio-runner core` (or a full stack recreate) and verify a real
   dynamic STDIO MCP registration connects successfully, plus everything this section's "what's
   deliberately NOT done here" flags as unverified.

## 9. Status update — 2026-09-23, seccomp profile shipped and verified live

The one tracked hardening gap from §8 is now closed. `mcp-stdio-runner/seccomp-profile.json`
is generated by a mechanical transform of the moby v28.0.0 default baseline
(`profiles/seccomp/default.json`), not hand-authored:

- Every `includes.caps` allow block removed outright - the deny posture no longer depends on
  `cap_drop: ALL` staying configured; it is unconditional in the profile itself.
- The `minKernel >= 4.8` `ptrace`/`process_vm_readv`/`process_vm_writev` allow block removed
  (upstream grants it unconditionally on modern kernels; here it would let a spawned same-uid
  child read `server.js`'s env - i.e. `MCP_STDIO_SIDECAR_SHARED_SECRET` - under any
  `ptrace_scope` looser than 1).
- `name_to_handle_at` and `fanotify_mark` removed from the unconditional allowlist
  (file-handle escape path / filesystem-wide event monitors - no legit MCP server use).
- `socket()` arg rule extended: `domain != AF_VSOCK(40) && != AF_PACKET(17)` - upstream already
  denied vsock; raw link-layer sockets on `mcp-stdio-net` are now denied too.
- `clone`'s `CLONE_NEW*` masked-eq mask and `clone3`'s ENOSYS made unconditional (dropped the
  `excludes.caps` escape hatch); `unshare`/`setns` added to the explicit ERRNO set - upstream
  only cap-gates `unshare`, leaving the unprivileged-userns escape primer reachable.
- An explicit 63-name ERRNO block documents + enforces the dangerous set regardless of
  upstream drift: namespace/mount family, kernel-attack surface (`bpf`, `perf_event_open`,
  `io_uring_*`, `userfaultfd`, `kexec_*`, `*_module`, `keyctl`, `fanotify_*`), and host
  control (`reboot`, `swapon/off`, `sethostname`, `syslog`, `vhangup`, `acct`, `quotactl*`,
  `settimeofday`, `iopl`/`ioperm`, ...).

**Verified live on this host (Docker 29.8.0, seccomp enforced), not assumed:**

- `docker run --security-opt seccomp=...` A/B inside the built image: `unshare(CLONE_NEWUSER)`,
  `unshare(CLONE_NEWPID)`, `ptrace(PTRACE_TRACEME)` all return `0` unconfined, all `-1/EPERM`
  under the profile; `socket(AF_INET)` and `clone` (normal flags) unaffected.
- `server.test.js` 9/9 passes *inside the container under the profile* - the runner's real
  syscall surface (spawn/socket/relay/kill) is intact.
- Through the real compose path (`docker compose up -d mcp-stdio-runner`, profile inlined into
  `HostConfig.SecurityOpt` by compose): `npx --version` -> `10.8.2` and `uvx --version` ->
  `uvx 0.12.18` over the actual wire protocol; `bash` still refused by `ALLOWED_COMMANDS`;
  wrong/absent secret still refused (fail-closed verified both ways).

Known residual: `clone3`'s namespace flags live in a struct and cannot be arg-filtered; the
profile ENOSYS's `clone3` outright (as upstream does without `CAP_SYS_ADMIN`), so callers fall
back to the filterable `clone` - behavior-equivalent, noted in the README.

Still open from §8, unchanged: no live end-to-end *dynamic MCP registration* through the full
core->proxy->runner chain in one booted stack (component-level wire-protocol verification is
what exists), and no round-trip latency measurement under load.
