# load-test/ — k6 load-test harness

> ## WARNING — READ BEFORE RUNNING ANYTHING HERE
>
> **NEVER point `PEAK_VUS` / `READ_PEAK_VUS` / `MIXED_PEAK_VUS` (concurrency)
> or the stage `duration`s above the cap you have EXPLICITLY agreed with
> whoever owns the target environment, without their sign-off.** Every
> script in this directory defaults to a small, safe concurrency (20, or 50
> for the no-LLM-cost read scenario) precisely so an accidental
> unparameterized run cannot hurt anything. Raising those numbers is a
> deliberate `-e PEAK_VUS=200`-style choice the person running the command
> makes at run time, never a hardcoded default in these files.
>
> **Staging and production are separate environments.**
> `BASE_URL`/`KEYCLOAK_URL` below decide which one you hit — **check the
> printed startup banner every single run** before load ramps up, and abort
> (Ctrl+C) if it says a host you did not mean to test.
>
> **The chat scenarios (`chat-load.js`, and the `chat` portion of
> `mixed-realistic.js`) are only safe against zero real LLM cost when
> `LOAD_TEST_AGENT_ID` belongs to a tenant that a platform operator has
> explicitly added to the backend's tenant-ID allowlist for "load-test
> mode"** (a separate, in-flight effort — see "Load-test mode dependency"
> below). Never point these at a real tenant's agent id. `read-heavy.js` has
> no such constraint — it never calls an LLM.

## Platform overhead per turn (local kind stage)

`scripts/turn-overhead.js` measures the platform's own time per chat turn in milliseconds, with no model cost: the
agent talks to `mock-llm/server.js`, a tiny Ollama-compatible server with a fixed first-token delay, so platform
overhead is roughly time to first token minus that delay. `kind/` holds the harness for a local kind stage:

| File | Purpose |
|---|---|
| `mock-llm/server.js` | Mock model server (`FIRST_TOKEN_MS`, `TOKEN_MS`, `TOKENS`). Default replies are unchanged (benchmark-safe); opt-in scripting for the e2e suite: model `mock-slow:latest` (about 18 s per reply, `SLOW_*`), `MOCK_MARKER:<word>` in a system prompt is echoed, `mock:call_tool [name] [{json}]` in the last user message makes a tool call, judge prompts get a valid verdict. Header comment has the details. |
| `kind/mock-llm.yaml` | Deployment and Service for it (create the `mock-llm-code` ConfigMap from `server.js` first). |
| `kind/values-loadtest.yaml` | Helm overlay, stage only, applied to every core shape (`global.core.extraEnv`): allows `mock-llm:11434` (`app.security.outbound.allowed-private-hosts`; without it jobs and scheduled runs fail `EXECUTION_FAILED`), resumable chat streams on, device-claim limiter raised to 1000 per IP (`APP_DEVICES_CLAIM_RATE_LIMIT_MAX_ATTEMPTS_PER_IP`; default 10 per 10 min), and the ship-dark features the e2e suite needs (judge calibration, scheduled tasks, data tables, form trigger, coding harness, deep research and research runs; see [docs/configuration.md](../../docs/configuration.md)). Also web at 384Mi/1Gi. Always layer `~/vegaduta-values-kind-memory.yaml` too. |
| `kind/ropc.sh`, `kind/fix-user.sh` | Local-only: enable the password grant on the web client (and later disable it); mark the test user's email verified. Refuse non-kind contexts. |
| `kind/provision.sh` | Activates the mock as the tenant's Ollama provider, creates the test agent, seeds a `MODEL_ROUTE` arm and creates a slow-model agent. |
| `kind/provision-stage-users.sh` | Local-only: relaxes Keycloak brute-force detection of the stage realm, creates a platform-admin user and (with `LT_TOKEN`) a second tenant member; writes `E2E_ADMIN_EMAIL/PASSWORD` and `E2E_MEMBER_EMAIL/PASSWORD` to the gitignored `kind/stage.env`. |
| `kind/bench.sh` | The matrix: replicas x caches on/off x concurrency; one JSON line per cell. |
| `kind/cell.sh` | Benchmarks the CURRENTLY deployed config (no helm changes), for testing a values overlay. |
| `kind/step.sh`, `kind/pinned-threads.py` | The scale-out step (2 warm pods, then 40 long streams while the HPA adds pods) with a per-upstream-pod report from the ingress log; `PIN_TRACE=1` adds `-Djdk.tracePinnedThreads=full`, and `pinned-threads.py` summarises the traces from a pod log. See [section 4d](../../docs/architecture/HOT-PATH-PERFORMANCE.md#4d-the-scale-out-stall-not-cold-pods-a-pinned-thread-herd-main-at-af3597f54). |
| `kind/values-all-features.yaml` | Overlay: job pools, idle-skip and ingestion claiming on (stream-end executor and plan cache deliberately off). |
| `kind/keycloak-admin-console.sh` | Makes the Keycloak admin console work on kind through a port-forward by setting the master realm's Frontend URL (`enable`/`disable`); `keycloak.admin.consoleUrl` alone does not fix the console's login iframe. |
| `kind/values-autoscaling-kind.yaml`, `kind/prometheus*.yaml` | Request-based autoscaling on kind: a minimal Prometheus plus prometheus-adapter, and the HPA target. |
| `kind/gate.sh`, `kind/gate-stage.sh`, `kind/gate-provision.sh`, `kind/values-gate.yaml` | The load-test gate (`make load-gate`, `.github/workflows/load-nightly.yml`); see [Load-test gate](#load-test-gate). |

Order: sign up a test tenant (`POST /api/signup`), `kind/fix-user.sh`, `kind/ropc.sh enable`, `kind/provision.sh`,
then `kind/bench.sh` (see its header for the environment). The run raises the per-tenant gateway rate limit
(600 calls/minute on the top plan) for its duration, otherwise it measures only 429 responses. Afterwards run
`kind/ropc.sh disable` and a plain `helm upgrade` without the benchmark overrides. Results and method:
[HOT-PATH-PERFORMANCE.md](../../docs/architecture/HOT-PATH-PERFORMANCE.md#4-measured-latency-of-a-whole-turn).

## Load-test gate

`.github/workflows/load-nightly.yml` runs every night (and on demand) and fails when chat latency regresses. It builds
core, core-jobs, web, sidecar and the CNPG Postgres image from the commit, brings up a throwaway kind cluster
(`kind/gate-stage.sh`: chart with `deploy/kind/values-kind.yaml`, `kind/values-loadtest.yaml`, `kind/values-gate.yaml`),
deploys the mock model, provisions a load-test tenant (`kind/gate-provision.sh`: sign-up, verified email, password grant,
plan UNLIMITED, mock provider and agent), and runs `kind/gate.sh`: a 20 s warmup, then 32 users for 60 s with
`scripts/turn-overhead.js`. No provider keys are used and no model is called. The sidecar stays on: every turn embeds
the user message through it for memory recall, a real blocking call and the one the benchmark caught regressing
([HOT-PATH-PERFORMANCE.md](../../docs/architecture/HOT-PATH-PERFORMANCE.md) section 4a), so do not turn it off to save memory.

The thresholds are k6 thresholds inside `turn-overhead.js` (set only when the `GATE_*` variables are), so k6 itself exits
99 when one is crossed; `bench.sh` and `cell.sh` set none and are unchanged.

| Variable | Gate | Default in CI | Default for `make load-gate` |
|---|---|---|---|
| `GATE_P95_MS` | p95 of the whole turn (`turn_total_ms`) below this | 2000 | 1000 |
| `GATE_MAX_FAILED_RATE` | failed turns (non-200, no SSE data, or a cut stream) at or below this | 0.01 | 0.01 |
| `GATE_TTFT_P95_MS` | optional: p95 time to first token (`turn_ttft_ms`) below this | off | off |

How the defaults were picked: every 32-user row in `kind/results-*.jsonl` (kind stage on a 10-core Mac, 2026-10-13)
has `failed_rate` 0 and a whole-turn p95 between 559 and 709 ms; the row the gate's configuration matches
(`replicas=1 caches=on vus=32`) is 571 ms, and the slowest (`final main all-features`) 709 ms. The local default
(1000 ms) is the slowest row plus about 40%, which a real regression clears but run-to-run noise (about 15% between
the rows) does not. The CI default (2000 ms) adds room for a 4-vCPU GitHub runner, which shares its CPUs between
the cluster and k6 and has not been measured yet: in a closed loop, half the throughput doubles the latency. After
the first week of nightly runs, set the repository variable `LOAD_GATE_P95_MS` to about 1.4 times the slowest
observed p95 (`result.json` in each run's artifact), and record the numbers here. The failure rate is 1% because
every baseline run had none.

Runner: the stage needs about 16 GB. `ubuntu-latest` has 16 GB and 4 vCPUs on public repositories only; a private
repository needs a larger-runner label, passed as the `runner` input or the `LOAD_GATE_RUNNER` repository variable
(`LOAD_GATE_P95_MS`, `LOAD_GATE_MAX_FAILED_RATE` and `LOAD_GATE_TTFT_P95_MS` set the scheduled run's thresholds the
same way). Artifacts: `result.json` (the compact result plus a `gate` map of threshold to pass/fail),
`k6-summary.json` (the full k6 summary) and `logs/` (pod logs, events, ingress log).

Locally, against a kind stage that already has a load-test tenant (the order above), `make load-gate` runs the same
`kind/gate.sh` with `LT_USER`, `LT_PASS` and `LT_AGENT_ID` from the environment or from `kind/gate.env` (gitignored;
`kind/gate-provision.sh` writes it on a fresh stage). Results go to `load-gate-out/`. Run `kind/ropc.sh enable` first
and `kind/ropc.sh disable` afterwards. The run puts 32 users of chat load on the stage for about 80 s: do not run it
while someone else is measuring on the same stage.

Stage facts: the kind stage is served over plain http (`*.127.0.0.1.nip.io`), and keycloak-js needs a secure context, so a
browser must run with `--unsafely-treat-insecure-origin-as-secure` (the Playwright stage config does). Whether to move the
stage to https is a separate decision.

## What's in here

| File | Purpose |
|---|---|
| `lib/auth.js` | Keycloak ROPC login helper, shared by every script. Authenticates once per run (in `setup()`), not once per request. |
| `scripts/chat-load.js` | Load-tests the chat turn path: `POST /api/agents/{agentId}/chat`. |
| `scripts/read-heavy.js` | Load-tests a cheap, no-LLM-cost read: `GET /api/agents` (list agents). Safe to run at real target concurrency even without load-test mode — it's what actually stresses the DB connection pool. |
| `scripts/mixed-realistic.js` | Both of the above combined in one `k6` `scenarios` block at roughly an 80% read / 20% chat split — the closest single script to "audit all functionality under load." |

This directory is standalone: it is never installed on any host and never
touches `core/`, `web/`, `e2e/`, or `deploy/`. Everything runs
inside the official `grafana/k6` Docker image.

## Required environment variables

All scripts read configuration **only** from environment variables — never
hardcode a hostname or credential when invoking them. Every script fails
loudly in its `setup()` (a thrown `Error`, visible immediately in the k6
output) if a required variable is missing or empty, rather than silently
hitting an empty-string URL.

| Variable | Required by | Notes |
|---|---|---|
| `BASE_URL` | all three scripts | Core API origin, e.g. `https://api.staging.example.com`. **Printed in a startup banner every run** so you can abort if it's wrong. |
| `KEYCLOAK_URL` | all three scripts | Keycloak origin, e.g. `https://auth.staging.example.com`. Used to build the token endpoint: `{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token`. |
| `KEYCLOAK_REALM` | optional, all three | Defaults to `agentic-ai` (this platform's real realm — see `web/app/lib/auth.ts`). Override only for a dedicated test realm. |
| `KEYCLOAK_CLIENT_ID` | optional, all three | Defaults to `agentic-ai-web` (this platform's real public web client — confirmed from `web/app/lib/auth.ts`, not guessed). That client must have **Direct Access Grants** (ROPC) enabled for the token grant to succeed. |
| `LOAD_TEST_USERNAME` | all three | Username of a pre-provisioned test account. |
| `LOAD_TEST_PASSWORD` | all three | Password for that account. Pass via `-e`, never commit it anywhere. |
| `LOAD_TEST_AGENT_ID` | `chat-load.js`, `mixed-realistic.js` | The pre-provisioned, tenant-allowlist-gated load-test agent's id. **Not required by `read-heavy.js`**, which never calls chat. |
| `PEAK_VUS` | `chat-load.js` | Peak concurrent VUs for the ramp. **Safe default if unset: `20`.** Set explicitly for a real test, e.g. `-e PEAK_VUS=200`. |
| `READ_PEAK_VUS` | `read-heavy.js` | Peak concurrent VUs. **Safe default if unset: `50`** (higher than chat's default since this path has no LLM cost, but still a deliberately modest number, not "real target scale"). |
| `MIXED_PEAK_VUS` | `mixed-realistic.js` | Total peak VUs across both scenarios, split ~80% read / ~20% chat. **Safe default if unset: `20`.** |

## Running via Docker

`k6` is never installed on any host running these scripts — always run it
through the official `grafana/k6` image. Two forms exist; **read the note
below before picking one**.

### Form A — volume mount (the form that actually works for this harness)

All three scripts `import` the shared `lib/auth.js` helper via a relative
path (`../lib/auth.js`). k6 can only resolve that relative import when the
script is loaded from a real file path on a mounted volume — **this is the
form you should use.**

```bash
# from the repo root
docker run --rm -i \
  -e BASE_URL=https://api.staging.example.com \
  -e KEYCLOAK_URL=https://auth.staging.example.com \
  -e LOAD_TEST_USERNAME='your-test-user' \
  -e LOAD_TEST_PASSWORD='your-test-password' \
  -e LOAD_TEST_AGENT_ID='the-load-test-agent-id' \
  -e PEAK_VUS=20 \
  -v "$(pwd)/load-test:/load-test" \
  grafana/k6 run /load-test/scripts/chat-load.js
```

Swap the last path for `/load-test/scripts/read-heavy.js` (drop
`LOAD_TEST_AGENT_ID`, use `-e READ_PEAK_VUS=...`) or
`/load-test/scripts/mixed-realistic.js` (use `-e MIXED_PEAK_VUS=...`) to run
the other scenarios.

On Windows PowerShell, replace `-v "$(pwd)/load-test:/load-test"` with
`-v "${PWD}/load-test:/load-test"` (or the absolute path,
`-v "C:\path\to\agentic-ai-platform\load-test:/load-test"`).

### Form B — piping the script via stdin (`docker run ... grafana/k6 run -`)

```bash
docker run --rm -i \
  -e BASE_URL=https://api.staging.example.com \
  -e KEYCLOAK_URL=https://auth.staging.example.com \
  -e LOAD_TEST_USERNAME='your-test-user' \
  -e LOAD_TEST_PASSWORD='your-test-password' \
  -e LOAD_TEST_AGENT_ID='the-load-test-agent-id' \
  -e PEAK_VUS=20 \
  grafana/k6 run - < load-test/scripts/chat-load.js
```

**This form does NOT work for any script in this directory as written.**
k6 resolves local (relative-path) `import` statements against the script's
own file location on disk; a script piped in over stdin has no file-system
location, so k6 cannot resolve `import { getAuthToken } from "../lib/auth.js"`
and the run will fail immediately with a module-resolution error. Form B is
documented here (as the task asked for both forms) purely so this
limitation is explicit rather than discovered by trial and error — **use
Form A (volume mount) for every script in this harness.** Form B would only
become viable if a script were rewritten to be fully self-contained (the
Keycloak login logic inlined instead of imported), which none of these
three are.

## Running against staging vs. production

- **Staging (e.g. `staging.example.com`)**: `BASE_URL=https://api.staging.example.com`,
  `KEYCLOAK_URL=https://auth.staging.example.com`. This is what every default in
  this harness assumes you're pointed at.
- **Production (e.g. `example.com`)**: `BASE_URL=https://api.example.com`,
  `KEYCLOAK_URL=https://auth.example.com`. Pass `-e PROD_DOMAIN=example.com`
  so `ttft-compare.js` refuses to run against it without `ALLOW_PROD=1`.
  **Do not run any script against production without the explicit
  human sign-off called out in the warning banner at the top of this file**,
  and never at a concurrency above whatever cap was explicitly agreed for
  that run.

## Load-test mode dependency

A separate, in-flight backend effort wires a **tenant-ID-allowlist-gated**
"load-test mode" into the chat path: for one specific, pre-provisioned
tenant (added to an admin-controlled platform setting by a human operator —
never reachable via any header, query param, or anything a client
controls), chat calls to that tenant's agent(s) return instant canned
responses instead of invoking a real LLM provider. `LOAD_TEST_AGENT_ID`
above must be an agent belonging to that exact allowlisted tenant. Until
that allowlist entry exists and is confirmed active, running
`chat-load.js` or the `chat` scenario in `mixed-realistic.js` against a
non-allowlisted agent means every chat call is a real LLM call with real
provider cost — confirm the allowlist is live for your target tenant before
raising concurrency on either of those two.

`read-heavy.js` has no dependency on load-test mode at all — it never
touches an LLM provider, so it's safe (from a cost perspective) to run
against any real, valid test account/tenant at any point.

## Thresholds and stages (all three scripts)

- Stages: ramp `0 → PEAK_VUS`/`READ_PEAK_VUS`/`MIXED_PEAK_VUS` over 1
  minute, hold for 3 minutes, ramp back to 0 over 30 seconds (~4.5 minutes
  total per run).
- `http_req_failed` threshold: rate below 1%.
- `http_req_duration` threshold: `p(95) < 3000ms` for all three scripts,
  including the read-only `read-heavy.js`. This is intentionally the same
  number platform-wide (so one dashboard/CI gate applies everywhere), not
  because a plain list-agents read should take up to 3 seconds — chat is
  inherently slower than a static/list endpoint (it traverses auth, the
  agent controller, session/memory reads, and SSE framing even in
  load-test/canned-response mode), so 3s p95 is the realistic bar for "chat
  is not degraded," not for "any endpoint is not degraded." Tighten
  `read-heavy.js`'s threshold locally (e.g. `p(95)<500`) if you want a
  stricter DB-connection-pool regression signal specifically for that
  script.

## Known limitations (documented deliberately, not hidden)

1. **SSE/streaming latency**: the chat endpoint returns
   `text/event-stream`. k6's `http` module is not a streaming client —
   `http.post()` blocks until the full response body arrives and returns it
   as one string. Every latency number these scripts report for the chat
   endpoint is **total turn latency** (time to the last SSE chunk), not
   per-chunk / time-to-first-byte latency. A real user perceives the first
   chunk much sooner than these numbers suggest.
2. **Token lifetime vs. run length**: `lib/auth.js` authenticates once per
   run (in `setup()`) and reuses that token for every VU/iteration. Keycloak
   access tokens are typically short-lived (commonly ~5 minutes). The
   default ~4.5-minute run comfortably fits inside that, but extending the
   stages well past the defaults risks the cached token expiring mid-run —
   watch for a sudden `http_req_failed` spike if you do this.
3. **These scripts have not been executed.** They were authored without
   access to a k6 binary or Docker in the authoring environment. They were
   self-reviewed twice against k6's documented `http`/`check`/`scenarios` API
   syntax (imports, `setup()`/`data` wiring, `__ENV`/`__VU`/`__ITER` usage,
   `options.thresholds`/`options.stages`/`options.scenarios` shape), but a
   human or orchestrator **must** do the first real execution against
   staging, at the safe default `PEAK_VUS`/`READ_PEAK_VUS`/`MIXED_PEAK_VUS`,
   to catch any runtime bugs before anyone raises concurrency or targets
   production.
