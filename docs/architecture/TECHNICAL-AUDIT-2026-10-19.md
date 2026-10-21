# Technical audit (2026-10-19)

A whole-repository read: architecture, security, the data layer, the web
console, the sidecar services, the SDKs and the personal install path. CI and
release mechanics were out of scope by request. Everything below is
**read** evidence (files and config read, greps counted) except where marked
**run** (`scripts/ci/flyway-lint.sh` was executed and passed: 380 migration
files, no duplicates). No Maven, npm or pytest run was made; nothing was
compiled or booted in this pass.

This audit stands on top of three existing audits rather than repeating them:
[STRUCTURAL-AUDIT-2026-10-14](STRUCTURAL-AUDIT-2026-10-14.md) (Java structure),
[PERSONAL-INSTALL-AUDIT-2026-10-19](PERSONAL-INSTALL-AUDIT-2026-10-19.md) (the
live personal stack) and [STANDARDS-AUDIT](../engineering/STANDARDS-AUDIT.md)
(2026-09-29, tooling). Where those already recorded a finding, this document
says whether it still stands instead of scoring it again.

**Working-tree caveat.** The tree was mid-flight when this ran: 136 modified or
untracked paths, including an unfinished passkey/unlock feature under
`core/admin/.../localauth/` and fixes documented in the personal-install audit.
Findings are about the code as it sits on disk, not a commit boundary.

## 1. Verdict

**This codebase is unusually well-hardened for an open-source platform.** The
things a brutal audit normally finds — missing encryption-at-rest, SSRF gaps,
permissive filter chains, hardcoded secrets, eval-based expression engines,
unauthenticated sidecars, `localhost` leaks — are all already closed, and the
closures carry `SEC-*`/`FE-*`/`INF-*` finding codes from previous passes. Every
sidecar shares one auth shape (bearer or `X-Internal-Token`, timing-safe
compare, fail-closed when the secret is unset). The personal compose stack
publishes nothing but loopback, runs containers read-only with all capabilities
dropped, and refuses to start without generated secrets.

The residual findings are concentrated in three places:

1. **The installer's browser wizard** — the one new piece that has not been
   through a security pass (F1, F2).
2. **Frozen, not fixed, architecture violations** — the ArchUnit baseline
   records ~350 live violations that are enforced against *worsening* but not
   yet repaired (F4).
3. **Deliberate personal-mode relaxations** whose residual risk should be
   written down honestly (F5), plus a tail of known-open items carried from the
   personal-install audit (section 5).

None of the new findings blocks the personal install. F1 is worth fixing before
the setup app ships to people who do not read terminals, because it is the one
place where "drive a package manager from a web page" is actually reachable.

## 2. What was verified strong (so the next audit does not re-check it)

| Area | Evidence (read) |
|---|---|
| Secrets at rest | `core/crypto/.../EncryptionService.java`: AES-256-GCM, fresh random 96-bit IV per call, fail-fast boot on missing/malformed `APP_ENCRYPTION_KEY`, dual-key rotation window (`APP_ENCRYPTION_KEY_PREVIOUS`). No weaker cipher anywhere in main code. |
| JWT validation | `core/security/.../SecurityConfig.java`: issuer pinned, `azp` allowlist, optional audience check, algorithm pinned to RS256 in local mode (`LocalJwtService` rejects `none`/HS256 confusion). No localhost issuer fallback — boot fails loud without config, deliberately. |
| Public surface | `PublicSurfaceRules.java`: every `permitAll` entry carries a written justification; `SecurityRuleSnapshotTest`, `PublicSurfaceSnapshotTest`, `AuthorizationCoverageTest`, `SensitiveRouteAuthorizationTest` freeze the whole chain and fail on new mutating endpoints without rules. |
| SSRF | `SsrfGuard.java` + `SafeHttpClients`: a single guard covering loopback, RFC 1918, link-local, metadata names, IPv6-embedded IPv4 (mapped/compatible/6to4), Teredo, NAT64, single-label hostnames, embedded credentials — and a custom DNS resolver so the *same* validated answer reaches the socket (rebinding closed). Operator allow-list only. An ArchUnit rule (`853d44fa`) bans raw HTTP clients. |
| Tenant isolation | `SchemaMultiTenantConnectionProvider`: `SET search_path` per checkout, schema reset before pool return, connection aborted if reset fails. `TenantSchemaGuard`: server-generated `t_<26 base32>` names, one `quoteIdentifier` choke point, `pg_`/system schemas refused to destructive DDL. PgBouncer documented and configured session-only. |
| Sidecar auth | `mcp-stdio-runner` (spawn policy mirrors `McpStdioCommandGuard`, per-connection private HOME, launcher allowlist, seccomp profile), `workspace-runner` (bearer + `timingSafeEqual`, realpath folder confinement, process-group kill), `desktop-agent` (loopback + bearer), `whatsapp-qr` (timing-safe bearer, hashed session dir names), `sidecar` (`X-Internal-Token`, `hmac.compare_digest`, fails closed). |
| Install path | `install.sh`: truncation-safe `main`-at-bottom, HTTPS-only fetch, mandatory SHA256SUMS check, optional cosign keyless verification pinned to the release workflow identity. `scripts/vegaduta`: `umask 077`, `.env` chmod 600, secrets never overwritten, openssl/urandom only. |
| Expression evaluation | No SpEL `StandardEvaluationContext` anywhere; user scripts run on `SimpleEvaluationContext` (no method/type/bean resolvers); workflow expressions use a purpose-built engine with step/time budgets. |
| Data layer | `flyway-lint.sh` (**run**): 380 migrations in the shared controlplane+tenant namespace, zero version collisions. |
| Containers | All first-party images non-root (tool-sandbox's root is documented as deliberate for `setpriv`); Node 22.23.3 **digest-pinned**; compose `:?` mandatory secrets; `BIND_ADDR=127.0.0.1` default; bounded json-file logging. |
| Web console | Enforced per-request nonce CSP built from runtime config (`web/proxy.js` + `app/lib/csp.ts`), `frame-ancestors 'self'`, no `dangerouslySetInnerHTML`, tokens never in `localStorage` (cookie session via same-origin proxy), scanner-probe short-circuit, dev-only routes 404 in production. |
| RNG discipline | No `Math.random` on any credential path. `randomUuid` prefers `crypto.randomUUID`/`getRandomValues`; `Math.random` is last resort for non-secret UI ids on plain-HTTP LAN installs where secure-context APIs are absent — a documented trade-off, correct as written. |

## 3. New findings

> **Post-audit remediation (same day).** F1, F2, F3 and F5 are fixed and tested;
> F6 is fixed; F4's `SafeHttpClients` subset burned down from 48 sites to 14
> (all remaining sites deliberately raw — they target operator-configured
> private hosts like the sidecar, SearXNG, Langfuse, internal health probes and
> websocket realtime endpoints that a socket-level SSRF guard would refuse).
> Per-finding status is noted below.

### F1. The setup wizard accepts unauthenticated state-changing POSTs on a fixed loopback port (medium) — FIXED

`installer/server.go` serves on `127.0.0.1:17890` by default (`main.go:71`
`flag.IntVar(&c.port, "port", 17890, ...)`) — a **fixed, well-known port**, not
a random one. `POST /api/install`, `POST /api/docker/install` and
`POST /api/docker/start` need no credential and check no `Origin`/`Host`
header (`server.go:106-139`). The handler decodes the body with
`json.NewDecoder` without a content-type check, so a `fetch` with
`Content-Type: text/plain` — a CORS "simple request" that browsers send
**without** a preflight — reaches it from any web page the user has open while
the wizard runs.

Reachable actions: `dockerInstall` runs `brew install --cask docker-desktop` /
`winget install Docker.DockerDesktop` (`dockerbootstrap.go:24-41`), and
`api/install` downloads a release and runs the full stack install. The release
itself is checksum-verified, so this cannot plant arbitrary binaries — the
impact is a drive-by "make this machine install Docker and a large application
stack", plus `openBrowser` opening tabs. Annoyance-class, not code-execution —
but the wizard's whole audience is non-technical users who will leave it open
in a browser tab while browsing elsewhere.

Smallest fix: at startup mint `crypto/rand` token, put it in the wizard URL
(`http://127.0.0.1:<port>/?k=<token>`), and require it on every `/api/*` POST;
optionally also reject POSTs whose `Origin` is not the loopback origin.
(`--port 0` already exists as an escape, but the default is what ships.)

**Fixed:** the server mints a per-process setup token, carries it in the wizard
URL (`?k=`), requires `Authorization: Bearer <token>` on every `/api/*` call, and
rejects non-loopback `Host` headers. Installer tests pass.

### F2. Release untar allows symlink escape (low — defence in depth) — FIXED

`installer/release.go:290-315` checks that each tar member's *path* stays under
the install dir, but for `tar.TypeSymlink` it creates `os.Symlink(hdr.Linkname,
target)` without constraining `Linkname`. A member `a` = symlink to `/etc`
followed by member `a/x` = regular file writes through the link: the clean-path
check passes on the name while `os.OpenFile` follows the symlink. `install.sh`'s
`tar` extraction does not have this gap (it does not synthesize links).

This is only reachable behind the checksum/cosign verification — i.e. a
compromised release pipeline, or a `--release-url`/`file://` payload that the
checksum still covers. Low severity, but it is a one-line class of bug worth
closing: reject linknames that resolve outside `dir` (or drop symlink support
entirely — release tarballs are known content).

**Fixed:** `unpackRelease` now rejects absolute symlink targets, resolves each
member's parent through `EvalSymlinks` and compares against the resolved install
root, and confinement-checks the link's target — a `a -> /etc` + `a/x` archive
can no longer write outside `dir`. A first cut compared resolved paths against
the lexical root and broke on macOS (`/var` -> `/private/var` tempdirs); the
final version keeps a lexical root for name checks and a resolved root for
symlink checks. Installer tests pass.

### F3. `desktop-agent` compares its bearer secret with `===` (low) — FIXED

`services/desktop-agent/server.js:443`:
`header === \`Bearer ${SECRET}\``. Every sibling sidecar uses
`crypto.timingSafeEqual` (workspace-runner:69, whatsapp-qr:344-349, mcp-stdio
runner's secret check). On a loopback-only service this is a theoretical
side-channel rather than an exploitable one, but it is a one-line divergence
from the codebase's own convention — align it.

**Fixed:** `server.js` now uses `crypto.timingSafeEqual` (with a length guard)
like its siblings; `node --test server.test.js` passes.

### F4. The frozen architecture baselines are large and still fully populated (medium — debt, not bug) — partial burn-down done

The ArchUnit freeze store (`core/app/src/test/resources/archunit_store/`)
currently records, per `stored.rules` and the store files:

| Rule | Recorded violations |
|---|---:|
| Controller depends on a repository or JPA entity (`3b0a0201`) | ~180 pairs |
| Package inside a dependency cycle, any depth (`9900f9ce`) | ~147 packages |
| Cross-area repository use (`a6a2058b`) | ~82 pairs |
| Outbound HTTP client not via `SafeHttpClients` (`853d44fa`) | ~48 sites |
| Constructor over 12 collaborator parameters (`e802d0a3`) | ~17 classes |

Rule 13 says baselines only shrink — these are *recorded* violations, not
absolved ones. The cross-area-repository and controller→repository entries are
the ones that matter most for the stated goal (hybrid tenancy / module
extraction per ADR-0015/0017): each is a place where an area's tables are its
public API. The 48 non-`SafeHttpClients` call sites are the security-relevant
subset — each is a place where the SSRF guard's resolver is bypassed (some are
legitimate: internal `http://core`/`keycloak` wiring, test fixtures; the store
does not distinguish). Recommend: annotate the store entries with the
justification so the residual set is honest, then schedule burn-down.

**Burned down (48 → 14).** A new `GuardedJdkHttpClient`
(`core/security/.../net/GuardedJdkHttpClient.java`) is a drop-in
`java.net.http.HttpClient` whose sockets resolve through `SsrfGuard`
(Apache-transported, streaming-aware, honours per-request timeouts and the
redirect policy; `defaults()` resolves the guard lazily so the operator's
allow-list installed at start-up is always in effect). Migrated: MCP OAuth
and Atlassian OAuth, the official MCP registry client, A2A, all Google and
Meta API clients, Slack/Teams/Discord/X/Telegram channel clients, the skill
registry and Brave search clients, x402 facilitator calls, image/video
generation clients, the OpenAI-compatible embedding client (guard injected as
a bean so ordering is explicit), the LLM blocking AND streaming paths (closing
the DNS-rebinding residual `LlmHttpClientFactory` itself documented),
`DocumentImageFetcher`, `OutboundHttpTimeouts`, GCP Cloud KMS, and Twilio's
network client (now `NetworkHttpClient(RequestConfig)` — no raw builder call).
The 14 deliberately-raw remainders target operator-configured *private* hosts
the guard would refuse: the sidecar, whisper provider, Langfuse, LiveKit
egress, internal health/version probes, SearXNG (`WebSearchHttp`), the
operator-configured `web_task` backend, eval's local-Ollama transport, and the
two websocket realtime providers (websocket transport is `SafeWebSockets`' job,
not this client's — `newWebSocketBuilder` on the guarded client fails closed
on purpose).

### F5. Personal mode removes the approval gate on the machine's hands — residual risk is real, mitigations hold but should be stated (medium, by design) — DOCUMENTED

`application-personal.yml:128` sets
`app.assistant.local-shell.approval-required: false` and `:144` does the same
for desktop GUI actions. Deliberate — a one-person install shouldn't approve
every `ls` — but worth writing down what the residual actually is:

- `local_shell` commands run via `workspace-runner` (no platform secrets,
  confined to the mounted projects folder — good). But the runner container is
  on the default compose network with **unrestricted egress**: a prompt-injected
  command (a poisoned web page the agent fetched, an email it read) can
  exfiltrate workspace contents or reach `core:8080`/`postgres:5432` —
  credentials are not in the runner's env, so the damage is bounded to the
  mounted folder plus whatever the network accepts without auth.
- `desktop` tools talk to a loopback agent that can click and type as the
  logged-in user — outside every container boundary. `taint.mode: enforce`
  (line 45) is the real mitigation: untrusted content can't drive the tools
  without an approval card. That makes the taint gate the load-bearing control
  for the entire personal posture; `BuiltInToolClassifications` decisions
  (e.g. the `search_knowledge_base`/`local_shell` interaction already noted in
  the personal audit) deserve a dedicated test pass because they are now the
  only thing standing between "page says click OK" and a real click.

Not a bug — a posture statement that belongs in `docs/personal-mode.md` next to
the flags, so an owner opting out of approvals does it knowingly.

**Done:** `docs/personal-mode.md` now carries the residual-risk paragraph in the
Approvals section — `local_shell` runs sandboxed in the workspace-runner (no
secrets, mounted-folder confinement, but unrestricted egress), `local_desktop_*`
acts on the real screen outside every container boundary, and with
`approval-required: false` the taint gate (`app.agents.taint.mode=enforce`) is
the load-bearing control: it gates actions but does not stop reads or
exfiltration through already-allowed outbound paths.

### F6. `git`-level hygiene observations (low) — FIXED

- 136 dirty/untracked paths at audit time from concurrent sessions, including
  security-adjacent new code (`localauth` passkey/unlock) not yet committed.
  The personal audit already flags a known failing test
  (`HookGuardChainTest`) on this tree. State of HEAD ≠ state of disk.
- `.dockerignore` still exists only at repo root, `core/`, `web/` — the
  September audit's list (app-builder, rl-trainer, voice-agent,
  mcp-stdio-runner, …) is unchanged, so those builds still send the whole
  service directory (including any local `.venv` — `services/voice-agent/.venv`
  exists in this checkout) as build context.
- The stray empty `package-lock.json` at repo root that `web/next.config.js:195`
  works around is still there; delete it rather than papering over it.

**Done:** the stray lockfile was already gone (the stale comment in
`web/next.config.js` referring to it is removed), and the missing
`.dockerignore`s were added for the listed service dirs.

### F7. Carried-forward open items, still standing (for the record)

From the personal-install audit (its own ranking, unchanged here): no published
release; embeddings `auto` ignoring a wizard-registered Ollama; STDIO MCP
unable to start in the container stack; browser automation never run against
real Chromium; local-model 500s on malformed tool calls; no identical-call
guard. From the standards audit: TypeScript `strict: false` remains (the ladder
documents 681 errors at flip time; ~35 files still read `process.env.NEXT_PUBLIC_*`
directly, down from 95); ~519 web files use `any` (down from ~1352 instances);
`any`/`@ts-*` suppression is thin (16) but the `any` surface is wide.
`server.shutdown`/`spring.lifecycle.timeout-per-shutdown-phase` remain unset.

**Same-day status of the tractable subset.** Done by concurrent commits and
this pass: `RegisteredOllamaEmbeddingResolver` (auto + wizard-registered
Ollama), `RepeatToolCallGuard` (identical-call guard), `OllamaToolCallParseRetry`
(local-model 500 on a malformed tool call), `server.shutdown`/`timeout-per-shutdown-phase`
(already set in `application.yml` — the earlier note was stale), wizard
preflight no longer fails on ports this stack itself publishes, `HOST_RAM_GB`/
`HOST_ACCELERATOR` are persisted to `.env` (documented in `.env.example` and
`docs/configuration.md`), server-side share pages and the agent-card proxy
prefer `CORE_INTERNAL_URL` over a localhost fallback that dialled the web
container itself, `/connect` badges Slack/SMS as "needs a public address" and
routes WhatsApp to the QR pairing (the personal-install route), and the doc
drift items (`getting-started.md` personal pointer, `core.env.example` personal
block) are in. Still open: a published release, STDIO MCP inside the container
stack, real-Chromium verification of browser automation, and the TypeScript
strict ladder.

**Status 2026-10-21 (v0.1.9).** Published release: done (signed release artifacts and pinned GHCR images; see
`CHANGELOG.md` 0.1.5). STDIO MCP in the container stack: the `mcp-stdio-runner` profile landed in 0.1.4
(`--profile tools`; the personal Tools/Browser add-on, also on Windows from 0.1.7). Browser automation was run against
a real Chromium in the personal-install audit (`BrowserRemoteRealTest`). The TypeScript strict ladder was not re-checked.

## 4. Process observations

- **Gates that nothing runs still rot.** Flyway lint passed today, but the
  September structural audit found the boot check had rotted within days of
  becoming stale; the same risk applies to every advisory gate. Not a code
  finding — a reminder that `scripts/ci/` is full of good gates whose value is
  zero when advisory.
- **Audit cadence is working.** Three prior audits, and each documented
  finding is measurably reduced (AgentService 2,375 → 664 lines; largest Java
  file now 808; direct `process.env` reads 95 → 35). The ratchet model —
  freeze, forbid growth, shrink opportunistically — is visibly doing its job.
  The cost is the frozen sets in F4 look permanent without scheduled burn-down.

## 5. Not verified

- Nothing was compiled, booted or tested in this pass. Findings are static reads
  plus one `flyway-lint.sh` run.
- `services/app-builder`, `services/code-intel`, `services/rl-trainer`,
  `services/piece-host` internals (beyond their auth posture), the Helm charts'
  runtime behaviour, `infra/terraform`, and the Java/Python SDKs' internals were
  skimmed, not audited line-by-line.
- The F1 CSRF claim is argued from the Go stdlib's behaviour (no content-type
  check in the handler, no Origin check, CORS simple-request rules) — it was not
  exercised against a running wizard.
- Whether the 48 non-`SafeHttpClients` sites in the freeze store include any
  tenant-controlled URL was not individually traced.

## 6. Smallest fixes first

| # | Change | Effort |
|---|---|---|
| 1 | Wizard: random token in URL + required on `/api/*` POSTs; check `Origin`/`Host` — **done** (F1, 0.1.3) | hours |
| 2 | `unpackRelease`: refuse symlink targets escaping the install dir (or refuse symlinks) — **done** (F2, 0.1.3) | hours |
| 3 | `desktop-agent`: `timingSafeEqual` on the bearer check — **done** (F3, 0.1.3) | minutes |
| 4 | Personal mode: write the F5 residual-risk paragraph into `docs/personal-mode.md` — **done** (F5) | hours |
| 5 | Annotate the F4 freeze-store entries with justification; burn down the `SafeHttpClients` subset — **partly done** (48 → 14, F4) | days |
| 6 | Delete the stray root `package-lock.json`; add the missing `.dockerignore`s — **done** (F6) | hours |
