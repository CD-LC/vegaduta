# Personal install audit (2026-10-19)

Scope: the local personal install (`vegaduta-demo2` stack: console `:13100`, API `:18100`, local Ollama
`qwen3.5:4b-ctx32k` as the platform pool model, SearXNG, workspace runner, sidecar, WhatsApp QR bridge). Goal set by
the owner: make it work out of the box, flag what must be disabled or hidden on a personal install, check the
architecture and the install path, and find what it takes to match or beat Claude Desktop, Claude Code and
ChatGPT for "automate anything on this machine and the web", even with a weak local model. CI and release
versions were out of scope.

Method: a 28-agent workflow (six dimension auditors, a skeptic per finding, a synthesizer) read the code and the live
stack read-only; the main thread then fixed, rebuilt, redeployed and re-tested on the stack. Every claim below says
how it was checked: **run** (seen on the live stack), **tested** (unit or wiring tests pass), **compiled**, **read**
(code or log reading only).

## 1. Verdict

**Not ready for a public release; ready for a developer who builds from a checkout.** The launcher, secrets,
security defaults, console rail, terminal, workspace files, knowledge, memory, guides and document generation work
(700 of 700 logged tool calls succeeded). What blocked "out of the box" was packaging, the per-turn tool selector, and
several seams where a feature is advertised but its backend is missing. The selector and seam fixes below are in; the
packaging items need decisions that are not code.

Blockers that remain:

1. **No published release.** The repo, release assets, `install.sh` URL and `ghcr.io/cd-lc/*` images are private, so
   `install.sh` stops at "could not resolve the latest release". The only working path is a checkout and
   `scripts/vegaduta personal --build`. (read; the docs say so honestly.)
2. **Embeddings `auto` ignores an Ollama registered by the wizard** when bootstrap is off, falls to the sidecar, and
   collections embedded by Ollama lose vector search silently (read, confirmed by a skeptic).
3. **STDIO MCP cannot start in the container stack** (no `npx`/`uvx`, sidecar off). Another session has uncommitted
   work wiring `mcp-stdio-runner` into `personal.yml`; until it lands, the STDIO option in the MCP form should be
   hidden or defaulted away (read).
4. **Browser automation is opt-in, and was broken in the container.** Update: it is now a light add-on
   (`scripts/vegaduta personal --browser`: a `browser-runner` container, slim core) and has been run against a real
   Chromium (navigate, read, screenshot, click, private-address refusal; `BrowserRemoteRealTest`). Running it exposed a
   bug that made the in-core browser fail in every container (the Playwright driver was not found from the browser's
   worker thread inside the fat jar), now fixed. Still open: the sign-in takeover live view and saved-login reuse have
   not been run for real, the 4B model drives it unreliably (Ollama 500 on malformed tool calls), and
   `ghcr.io/cd-lc/browser-runner` is not built by any workflow yet.

## 2. Fixed in this pass

| # | Problem | Fix | Evidence |
|---|---|---|---|
| 1 | The relevance selector gave a request that names a tool zero scored slots when the always-kept set (about 15 names) filled the cap (12), so web, channel and browser tools were never offered | `ToolRelevanceSelector`: reserve a small floor (4 tools, 1,600 schema tokens) for positive matches only when the always-kept set fills the budget | tested (`ToolRelevanceSelectorAlwaysKeptFloorTest`, 10 selector suites); **run**: the same two-part request now offers `web_search` with the workspace tools and calls both |
| 2 | Small-model mode (6-tool cap) kept one family and dropped another ("search the web and list files"); `schedule_task` and shell questions shared no word with their tools; "how" pulled the setup guide into every question | `AgentToolSelection`: best tool per signalled intent, round-robin; new schedule and machine intents; "how" removed | tested (`SmallModelModeTest`); **run** (two tools, one turn) |
| 3 | The continuation pass after a tool result ran without the tenant, so `llm_call_log` and `rl_episode` writes failed (5 of 5 passes) | `FastStreamTurn`: continuation inside `TenantScopedFlux.subscribeInScope(context.scope(), ...)` | **run**: rows written after the tool results, 0 SQL errors in 20 minutes of logs |
| 4 | A free local model was booked at the unknown-model default rate ($0.50/$2 per MTok) into `llm_call_log`, the monthly spend counter and the hosted-spend cap | `LlmCallLogService.record(..., estimatedCostUsdOverride)`, fed by the sink from the advisor's own (zero) estimate | tested (`LlmCallLogServiceCostTest` + 4 advisor suites); **run**: rows show `0.000000` |
| 5 | A harmless tool output was blocked by the "Prompt-injection markers" rule because a wall-clock deadline (250 ms) expired while the laptop was starved (a 78 s stall is in the log) | `TenantGuardrailRuleEvaluator`: a linear (RE2/J) rule that missed only the deadline is retried once with a fresh deadline; the step cap and fail-closed behaviour are unchanged | tested (existing suite); the stall itself was not reproduced |
| 6 | WhatsApp QR polled forever with no bridge URL and the console said "waiting for QR scan" | receiver idles, channel shows "WhatsApp bridge not configured"; overlay passes `APP_WHATSAPPQR_BRIDGE_URL/SECRET` | **run**: one WARN per boot, no polling |
| 7 | Changing `WEB_HOST_PORT` or `CORE_HOST_PORT` left the console and CORS on `localhost:3000/8080` | `personal_sync_public_urls` in `scripts/vegaduta`; compose defaults derive from the port variables | tested on a temp `.env` (local form rewritten, `https://` untouched); not run with changed ports on a live stack |
| 8 | Agents page showed raw message keys; four strings missing in `en.json` | `agentsUi` namespace, four strings | **run** |
| 9 | `/research` row in the rail led to a "not switched on" page | `useResearchEnabled` gate | **run** (row absent) |
| 10 | Spurious sign-outs: a refused refresh cleared the cookie another tab had just rotated | proxy keeps the cookie; Web Locks serialise refreshes across tabs | tested; **run**: stayed signed in across a container recreate and repeated navigation |
| 11 | The Assistant allow-list was frozen at creation: `schedule_task`, `data_table_*` and `browser_*` never reached it although the prompt names them | `CONNECTION_TOOLS` sync regex widened | tested; **run**: allow-list went from 30 to 46 tools (3 + 12 new) |
| 12 | Workflow palette offered nodes a one-person install cannot run (`wait`, `voice_menu`, `bulk_email`, `crm_lead`, `device_control`) | `workflowNodeOffered` in both palettes | tested; not seen on the canvas (needs a saved workflow) |
| 13 | A small model wrote an answer before its tool results arrived, then a second one after (seen live: two contradictory Node.js answers in one reply) | one clause added to the tool-trust directive: call the tool first and answer only after its result | tested (`AgentChatServiceAgenticCompletionDirectiveTest`, which caps the directive at 135 words); **run**, one sample: a fresh two-part request gave one answer after both tool results in 23 s |
| 14 | Smaller: Valkey 64 -> 128 MB (a rate-limit counter evicted under pressure fails open); localhost public-URL notice is INFO on personal; shell tool tells the model it is on a Mac (BSD flags); `schedule_task` schema says to omit delivery fields and use `maxRuns: 1` for one-offs; `docker-desktop` cask; `>40 tools` log line INFO | edits in `personal.yml`, `PublicWebUrlStartupValidator`, `ShellRunner`, `ScheduleTaskToolCallback`, `AgentToolDiscovery`, installer | tested where a suite exists |

## 3. Still open, ranked

Status notes in **bold** were added on 2026-10-21 (v0.1.9) from `CHANGELOG.md` and the code; the rest of the table is as written.

| Sev | Item | Where | Smallest fix |
|---|---|---|---|
| high | Embeddings `auto` and a registered Ollama (blocker 2). **Done in 0.1.3** (`RegisteredOllamaEmbeddingResolver`, core/knowledge) | `EmbeddingProviderSelection.java:43-66`; `personal.yml:208` | resolve `auto` against the registered Ollama before the sidecar and pin it per install |
| high | STDIO MCP form defaults to a transport the container cannot run. **Partly done in 0.1.4**: the `mcp-stdio-runner` profile landed (`--profile tools`, the personal Tools/Browser add-on); the form still defaults to STDIO | `CustomServerForm.tsx:46,157`; `core/Dockerfile:16-17` | land the `mcp-stdio-runner` profile (in flight elsewhere) or hide STDIO until `StdioSandboxStatus` reports launchers |
| high | No published release (blocker 1). **Done**: signed releases are published (v0.1.0 onward; the 0.1.5 entry moves install instructions to the release artifacts and pinned GHCR images) | `install.sh:166`; `installer/release.go:52` | publish; until then make the installer and README say "clone and run `scripts/vegaduta personal --build`" |
| medium | Local model 500 on a malformed tool call ("expected element type function but have parameter", 1 of 6 background failures). **Partly done in 0.1.3**: streaming chat replays the request once | Ollama `/v1/chat/completions` | retry once; record the failure with the model's raw output |
| medium | Core has no identical-call guard: the 4B re-issued the same `web_search` query before answering (bounded only by 25 rounds). **Done in 0.1.3** (an identical lookup later in the same reply is answered "use the earlier result") | `ArgumentSanitizingToolCallingManager.java:110` | refuse a byte-identical repeat in the same turn and say so in the tool result |
| medium | **Still open at v0.1.9** (`SMALL_MODEL_TOOL_CAP = 6`). Small-model cap of 6 is the limiting factor for qwen3.5, which chose correctly 6 of 6 with 12 tools in the benchmark | `AgentToolSelection.SMALL_MODEL_TOOL_CAP` | make the cap depend on the pool row's measured grade and size; benchmark other models first |
| medium | Wizard preflight blocks Install when the busy ports are the user's own running stack. **Done in 0.1.3** (`vegadutaHeldPorts` in `installer/preflight.go`; Windows in 0.1.7) | `installer/preflight.go:97-140` | report them as ok when this install publishes them (the doctor already does) |
| medium | macOS download is an unsigned bare binary; the working `.app`/`.dmg` is a hand build. **Partly done**: `release.yml` builds an ad-hoc-signed `.dmg` (not notarized) | `release.yml:545-564` | build and sign in the workflow or document "Open Anyway" |
| medium | Terminal install and Setup-app install produce different stacks (workspace profile only in the wizard) | `installer/runner.go:18-30`; `install.sh:226-231` | make `workspace` the default on the container stack |
| medium | Webhook-only channels (Slack, SMS, Meta WhatsApp, Discord, Teams, Meta social, X) are advertised on `/connect`. **Partly done in 0.1.3**: Slack and SMS carry a "Needs a public address" badge and WhatsApp goes to the QR flow | `connectCatalog.ts:18-24` | mark "needs a public address"; send the WhatsApp card to the QR tab |
| medium | `taint=enforce` marks `search_knowledge_base` untrusted, so `local_shell` gets an approval card although approval is off | `BuiltInToolClassifications.java:163-164` | document, show the taint source on the card, allow trusting a collection |
| medium | **Persisting done in 0.1.3** (both written to `.env`). `HOST_RAM_GB` / `HOST_ACCELERATOR` exported but not persisted; memory budget understated (wizard default about 5.4 GB of limits, documented 4 GB) | `scripts/vegaduta:1598-1602`; `personal.yml` | persist both; scale doctor and preflight by enabled profiles |
| low | Dead tool schemas offered with nothing behind them: `telegram_read_messages`, four `read_device_*` | `AgentChannelToolResolver.java:106-124,262-287` | gate on a configured bot and a paired device (needs a per-turn lookup) |
| low | Prompt names `run_code`, `delegate_parallel`, `deep_research`, which are not offered. **Still open at v0.1.9** (`assistantAgent.ts` still names all three) | `assistantAgent.ts:50,53` | build the prompt from the offered tools; point the `run_code` hint at `local_shell` |
| low | Share pages dial `localhost` in the web container; CORS list hardcodes `:3000` (the compose half is fixed). **Share pages done in 0.1.3** (prefer `CORE_INTERNAL_URL`) | `web/app/a/[shareId]/layout.tsx:45-47` | read `CORE_INTERNAL_URL` |
| low | `/templates` is empty on a one-person install; nested `/jobs/*` routes still render | `TemplateGalleryController.java:22`; `WorkRail.tsx` | seed or empty state; redirect |
| low | Doc drift: `getting-started.md` has no personal section; `install.sh:217` prints `up --lite`; `core.env.example` has no personal block. **Partly done**: `getting-started.md` now points to Personal mode | docs | text edits |

## 4. What to disable or hide on a personal install

| Surface | State | How |
|---|---|---|
| `/research` rail row | **done** | `useResearchEnabled` |
| Workflow nodes `wait`, `voice_menu`, `bulk_email`, `crm_lead`, `device_control` | **done** (palette; saved graphs still open) | `workflowNodeOffered` |
| WhatsApp QR "waiting for scan" when unconfigured | **done** | honest not-configured state |
| STDIO MCP transport | **partly done** (runner profile, 0.1.4) | see section 3 |
| Webhook-only channels | **partly done** (0.1.3) | see section 3 |
| Device and Telegram tool schemas | open | see section 3 |
| Voice/Call UI (no livekit or voice-agent in `personal.yml`) | open | hide when `LiveKitTokenService` reports not configured |
| Custom code tools and skill scripts (`TOOL_SANDBOX_ENABLED=false`) | open | panel already shows a disabled state; reword the hint |
| Computer use (`local_desktop_*`) | open | needs `APP_ASSISTANT_DESKTOP_AGENT_URL`; keep the info copy honest |
| `http_request` as one all-methods tool | open | set `app.tools.http-request.safe-mode=true` in `application-personal.yml` |
| Embeddings, Telegram bot token, Google, Slack | n/a | each shows its own setup state |

## 5. Architecture assessment

Fits one person on one machine. Auth is local with a URN issuer and no localhost fallback (AGENTS.md rule 7 holds).
The core container is read-only, drops all capabilities, sets no-new-privileges and publishes on `127.0.0.1` only;
Postgres and Valkey publish no port; child processes do not inherit `APP_ENCRYPTION_KEY` or database passwords; the
SSRF allow-list is three named hosts. Scheduled tasks tick in the single core process. The hot path stays in-process
(rule 6).

Deviations: rule 12 (the continuation pass and some background learning calls ran without a tenant: fail-closed, so
lost writes, no leak; the continuation is fixed, background non-streaming calls are not traced to a caller); rule 4
(defaults of `localhost:3000/8080` were not derived from the port variables: fixed in compose and the launcher); rule
11 (the docs say a real Chromium run never happened and the audit found none).

Seams to fix by design, not by default: (a) tool selection is three stages tuned separately (always-kept set,
relevance selector, small-model cap) and together left the model the same six tools every turn; the floor and the
intent round-robin repair the worst of it, but the cap should become a function of the measured model; (b) capability
gating is inconsistent: the rail hides pages by flag, while STDIO MCP, workflow nodes, webhook channels and device
tools were offered with no check that their backend exists; (c) configuration lives in `personal.yml`,
`application-personal.yml`, `core.env` and the installer-owned `.env`, and several values (`HOST_RAM_GB`, `PUBLIC_*`,
the WhatsApp bridge) were set in only some of them.

Architecture test results on a clean checkout of HEAD plus these changes: wiring and boundary tests pass (22 of 23 in
`core/app`); `StructureRulesTest.noPackageIsPartOfADependencyCycle` fails for `providers.localmodels`, introduced by
commit `b7ebfab5d` (not by this work). Rule 13 says to fix, not refreeze.

## 6. Agent quality on the local model

`qwen3.5:4b-ctx32k` emits structured tool calls 100% of the time (about 130 calls, zero text-form calls) with 4, 12
and 16 tools and a 4k-token system prompt, thinking on or off; keep it as the 16 GB default. The 9B gave no accuracy
gain at 1.5 to 3 times the latency. `llama3.2:3b` over-calls tools and stays chat-only. Thinking adds 3 to 20 times
latency for no accuracy gain; core already sends `reasoning_effort: none`. The constrained router is unavailable on
this Ollama (HTTP 501 for `json_schema` on the MLX runner), so text-call recovery parsing is not worth more
investment.

Weaknesses seen on the stack: invented arguments (`delivery: email`, a placeholder recipient, GNU flags on macOS: the
schema and shell-note edits address these), a repeated identical search, an answer drafted before the tool result
(the directive edit addresses this), 43 s average response time, and answers that are fluent but wrong about facts the
search result contradicts (the 4B misreads search snippets; a hosted fallback is the real mitigation). The Lab pages
(`/agents/quality`) render honest empty states; the Assistant agent shows 94% of 283 model calls succeeded and 100% of
700 tool calls.

## 7. Against Claude Desktop, Claude Code and ChatGPT

What the competitors ship that this install does not, ranked by value times feasibility (full matrix in the audit
workflow output):

1. **Browser in the default install.** All three drive a browser out of the box. Here: the `browser` add-on, tools
   and takeover UI now exist and a real run is recorded; missing is making it a default (or one-click) choice, a
   published `browser-runner` image, and the takeover run for real.
2. **Local STDIO MCP servers** (filesystem, git, SQLite, Playwright): the main extension path on a laptop. In flight.
3. **Computer use as a toggle**: `services/desktop-agent` exists but is source-only and started by hand.
4. **More than one folder** for the Assistant (today only `~/VegaDuta-Projects`; the home folder is refused).
5. **Event triggers for routines** (new email, file, webhook, message): `WorkflowPollTrigger` and
   `WebhookSubscriptionService` exist; add the trigger kind to scheduled tasks.
6. **A reachable hosted fallback chain** for the weak-model case: it sits behind Platform Admin.
7. **Deterministic hooks** (PreToolUse, PostToolUse, TurnEnd): rules that do not depend on the model, which is what
   makes a 4B model safe and repeatable.
8. **Fan-out from `/assistant`**: `run_subtasks` (in flight) and `delegate_parallel` exist; give each specialist its own
   six-tool set.
9. **Routines while the laptop sleeps**: cannot be copied from the cloud products; keep-awake during a run, a login
   service, and an honest "missed run" display can.
10. **Ready-made script-backed automations** so the model supplies judgement and a script does the work.

What they do not have and this does (keep and advertise): small-model mode, approve-first learning, taint
enforcement, zero-cloud operation, channel reach (Telegram, WhatsApp QR, Signal, Matrix, iMessage) with pairing.

For "automate anything even with a weak model" the platform principle should be: every capability is a tool or a
skill with a script, the selector guarantees the right one reaches the model, rules (hooks, guardrails, approvals) do
not depend on the model, and a hosted fallback is one click away. This pass fixed the second part; items 1, 6 and 7
above are the next three to build.

## 8. Not verified

- Authenticated endpoints under `/api/local-models/*`, `/api/system/*`, `/api/research/status` were not re-probed with
  a token after the clean restart.
- No `browser_*` call was run against Chromium; no STDIO MCP registration was attempted; no scheduled task, knowledge
  upload, voice call, Google connect, device pairing or desktop-agent run was exercised end to end.
- The approval flows (taint card for `local_shell`, FULLY_AUTONOMOUS sends and writes, `schedule_task` confirmation
  with a placeholder recipient) were read, not run.
- The Setup app on a clean machine (preflight on re-run, Docker bootstrap, changed ports, upgrade) and Gatekeeper on a
  quarantined binary.
- The 32-user load benchmark was not re-run after the continuation change (see the project memory on validating the
  turn path under load).
- `scripts/vegaduta personal` with changed ports on a live stack; the workflow palette on a saved canvas; the directive
  clause on more than one turn; the guardrail deadline retry under a real stall.

## 9. Working tree notes

Other sessions were editing the same tree while this ran. Files this pass touched (commit by explicit path, and
split hunks where a file is shared): `FastStreamTurn`, `AgentToolSelection` (+ `SmallModelModeTest`),
`ToolRelevanceSelector` (+ `ToolRelevanceSelectorAlwaysKeptFloorTest`), `ChatPromptDirectives`, `ShellRunner`,
`ScheduleTaskToolCallback`, `AgentToolDiscovery` (my hunk is the log level only; the file also holds another
session's), `LlmCallLogService`, `LlmCallLogSink` and five chat tests, `TenantGuardrailRuleEvaluator`,
`PublicWebUrlStartupValidator`, the three WhatsApp QR classes, `deploy/compose/personal.yml` and `whatsapp-qr.yml`
(shared with the STDIO runner work), `scripts/vegaduta` (`personal_sync_public_urls`; shared), `install.sh`,
`installer/*`, and in `web/`: `agents/page.tsx`, `useNavSections.ts`, `useResearchEnabled.ts`, both workflow palettes,
`personalInstall.ts`, `localAuthProxy.ts`, `localAuthClient.ts` (two hunks; the file also holds the passkey work),
`assistantAgent.ts` (the regex edit was already committed in `a682f29da`; `browser_` is not), and four strings in
`messages/en.json`.

Known breakage that is not from this pass: `HookGuardChainTest.anApprovedReplayStillRunsAndStillGetsItsHooks` fails at
pure HEAD `a682f29da` (1 of 1,416 `agents-turn` tests; the approval-parking change is the likely cause); the
`providers.localmodels` package cycle (`b7ebfab5d`); the admin
`LocalAuthHttpTest` compile against a changed `LocalAuthService` constructor; `tour/steps/personal.ts` does not
typecheck against `TourAudience`; the passkey files fail `tsc`; and the i18n parity ratchet fell about 0.25 points for
every locale because new English strings are not yet translated (four of them are from this pass).

The deployed stack runs a jar and web image built from a clean worktree of HEAD plus only the changes above, so it
shows what this pass did and nothing else.
