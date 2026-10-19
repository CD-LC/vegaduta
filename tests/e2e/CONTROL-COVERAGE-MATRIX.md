# Control coverage matrix - agent workbench, workflow designer, Build studio

Question this answers: "we have so many controls - are all of them tested?"
Short answer: **no.** Of the 115 control rows below, 40 are behavior-verified (some only for part of their
range, noted per row), 13 are presence-only, and 62 had no test at all. `tests-prod/control-sweep-live.spec.ts`
now covers most of the "none" rows at the API layer (last column).

Derived from source, not memory:
`web/app/components/agents/AgentWorkbench.tsx` (+ `AgentWorkbench/`, `AgentSamplingControls`,
`AgentAdvancedPanels`, `AgentGuardrailsCard`, `AgentVersionsCard`, `AgentSkillsCard`, ...),
`web/app/lib/api/agents.ts`, `web/app/components/workflow/*` (`nodeDefs.ts`, `NodeConfigDrawer.tsx`,
`WorkflowTopbar/Inspector/TriggerStrip/IntelligenceModal/RunModal`), `web/app/build/studio/StudioSurface.tsx`,
cross-checked against every spec in `e2e/tests`, `e2e/tests-prod`, `web/e2e/specs`.

Legend - **Tested?**
- **behavior-verified**: a test changes the control and asserts an observable effect (output, run result, persisted value read back).
- **presence-only**: a test proves the control renders / the page loads / the endpoint answers; it never changes the control and checks the effect.
- **none**: no spec references the control.

Last column **Sweep** = whether the new `control-sweep-live.spec.ts` now exercises it
(`API` = change + read back + validation via the same REST endpoint the control calls;
`UI` = driven through the rendered control; `-` = not covered yet, see "Still uncovered").
The sweep has been written and lists cleanly (23 tests) but has **not been executed** - see "Run status" at the end.

Specs abbreviated: **WBB** `agent-workbench-behavior-live`, **AUT** `agent-autonomy-approval-live`,
**RDS** `agent-reasoning-depth-streaming-live`, **ATE** `agent-tool-execution-live`, **AWS** `agent-workflow-share-live`,
**CON** `console-authenticated`, **WNC** `workflow-node-config-live`, **WNA** `workflow-node-config-advanced-live`,
**WBL** `workflow-behavior-live`, **WIL** `workflow-intelligence-execution-limits-live`, **UJ** `user-journeys-behavior-live`,
**BAB** `build-app-behavior-live`, **MCP** `mcp-server-registration-live`, **CHN** `channel-connectors-and-devices`,
**CSL** `chat-session-lifecycle-live`, **EGP** `entitlement-gates-plan-caps-ui`, **SWEEP** `control-sweep-live`.
`web/e2e/specs/*` (`NN-*.spec.js`) is the local/staging suite (needs the Docker stack, not run against prod).

## 1. Agent workbench

### 1a. Persona / prompt

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| System prompt (edit + save) | Persona card | behavior-verified | WBB (marker phrase, edit replaces old persona), UJ | - |
| Agent name / create / Save draft | Header, draft flow | behavior-verified (create), presence-only (rename) | CON writes, EGP, WBB | - |
| Agent switcher (listbox) | Header | behavior-verified | WBB UI test | - |
| Prompt version list + Restore | Versions card | none | - | API |
| Config version list / label / restore | Versions card | none | - | API (list only) |
| Duplicate agent | Header menu | presence-only (a11y button name) | accessibility | API |
| Delete agent | Header menu | behavior-verified (cleanup only) | every WB spec | - |
| Starter templates / setup guide | Draft flow | none | - | - |

### 1b. Model + generation

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| Provider select | Model card | presence-only | CON, entitlement-gates-premium | - |
| Model select / custom model | Model card | presence-only | WBB clones a model via API | API (bogus provider) |
| Change model (edit) | Model card | none | - | API (invalid only) |
| Tuning mode Simple / Manual | Generation tuning | none | - | API + UI |
| Max tokens slider | Generation tuning | behavior-verified (24 tokens truncates, 1500 allows long) | WBB | - |
| Temperature slider | Generation tuning | presence-only (sent, never asserted on output) | WBB | API (persist + bounds) |
| Top-P slider | Generation tuning | presence-only | WBB | API |
| Top-K | Advanced sampling | none | - | API |
| Stop sequences (add/remove chip) | Advanced sampling | behavior-verified (reply cut at STOP) | WBB | API (limits: 8 entries, 64 chars, blank) |
| Frequency penalty | Advanced sampling | none | - | API |
| Presence penalty | Advanced sampling | none | - | API |
| Seed | Advanced sampling | none | - | API |
| Clear sampling params | Advanced sampling | none | - | API |
| Fallback provider/model | Generation tuning | none | - | API |
| Cost routing toggle + economy provider/model | Generation tuning | none | - | API |
| JEV threshold | Cost routing | none (jev-byok-live covers the BYOK backend, not this field) | - | API |
| Experience mode Simple / Advanced | Header | none | - | API |
| Agent tier (Consumer/Professional/Expert) | Model card | none | - | API (accepted, no read-back field exists) |
| Edge / device routing policy | Device routing | none | - | - |
| Input/output JSON schema | Advanced | none | - | - |

### 1c. Behaviour

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| Reasoning depth Fast/Careful/Rigorous/Planned | Behaviour | behavior-verified for Fast/Careful/Rigorous (persist + correct answer); Planned none | WBB, RDS | API (workflow depth only) |
| Autonomy tier Supervised / Semi / Fully | Behaviour | behavior-verified for Supervised (tool call lands in approvals, Approve releases it); Semi and Fully none | AUT | - |
| Voice / TTS / video model providers | Transcription card | none | - | API (invalid input only) |
| Biometric consent | Behaviour | none | - | API |

### 1d. Memory, knowledge, tools, capabilities

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| Tool allow-list (checkbox picker) | Tools / MCP | behavior-verified | WBB (restricted agent never calls fetch_web_page), web/e2e 05 | - |
| Tools "Auto-select / Manual" mode | Tools / MCP | none | - | - |
| MCP server register / toggle / remove | Tools / MCP + /tools | behavior-verified | MCP, CHN | - |
| Knowledge base attach toggle | Knowledge card | presence-only | ATE, console-core-smoke | API |
| Knowledge collection scope (checkboxes) | Knowledge card | behavior-verified (staging suite only) | web/e2e 05 | API |
| Share memory with workspace | Memory | none | - | API |
| Memory list / forget | Memory | none | - | API (list + forget 4xx) |
| Response caching | Advanced | none | - | API |
| Semantic response caching | Advanced | none | - | API |
| Capabilities toggles (per key) | Capabilities card | none | - | API (one capability) |
| Code-intel repo access | Code intelligence | none | - | API |
| Agent skills scope | Skills card | presence-only (/skills page loads) | CON | - |
| Specialties / tags | Specialties card | none | - | API |
| Collaborators (multi-agent) | Advanced | none | - | API |

### 1e. Guardrails, quality, reliability, sharing

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| Per-agent guardrails (profanity, jailbreak, pii, policy, toxicity, custom) | Guardrails card | none for the per-agent picker. Workspace rules: FREE authoring gate verified | entitlement-gates-* | API + behavior (PII redaction) |
| Guardrail rules (workspace) | /admin/guardrails | presence-only | CON | - |
| Reliability panel | Reliability card | none | - | API (endpoint answers) |
| Quality / eval panel | Quality card | presence-only (/agents/quality loads) | CON | - |
| Health chip / learning panel / RL arms | Header, Learning | none | - | - |
| Turn feedback (thumbs) | Playground | none | - | - |
| Share agent (mint link, anon chat, report) | Sharing card | behavior-verified | AWS | - |
| Playground send / stream | Playground | behavior-verified | WBB, UJ, CSL | - |
| Playground attachments / camera / voice call | Playground | none | - | - |

## 2. Workflow designer

### 2a. Node types (all 20 in `nodeDefs.ts`)

"Config fields" is what `NodeConfigDrawer.tsx` renders for that type.

| Node | Config fields | Tested? | Spec | Sweep |
|---|---|---|---|---|
| trigger | channel, webhookAuthMode, webhook, per-node channel binding | presence-only (runs as first node everywhere); schedule/webhook API-level in staging suite | web/e2e 11 | API (invalid channel) |
| agent | agentId, structured input, RL toggle, requiresApproval, retryAttempts | behavior-verified for agentId (persona flows into output, swap changes output); structured input / RL / retry / requiresApproval none | WBL, UJ | API (missing/malformed/vanished agentId, good config) |
| knowledge | collectionId, topK | none | - | API (validation) |
| condition | expression ("contains") | behavior-verified | WNC, WBL | API (missing expression) |
| approval | message, adminOnly | behavior-verified (auto-approve default, pauses when policy requires, Approve resumes) | WNA | - |
| output | channel (log/file/email/telegram/whatsapp/google_drive/event), per-channel fields | behavior-verified for log and field visibility per channel; file/email/telegram/whatsapp/drive/event delivery none | WNC | API (bad channel) |
| device_control | commandType, deviceId, payload | none | - | API (persist + validate) |
| tool | agent + tool + args | behavior-verified | WNA | - |
| http | url, method, headers, body type, save-as, non-2xx handling | behavior-verified | WNC, WBL | API (bad method/body type) |
| loop | itemsField, maxIterations | behavior-verified (cap sweep, odd inputs) | WNC, WBL | - |
| code | operation + per-operation fields | behavior-verified (every operation) | WNC, WBL | API (bad operation) |
| workflow (Trigger Workflow) | target workflow | behavior-verified | WNA | - |
| supervisor | agentId, instructions, routes[] | none | - | API (validation only) |
| parallel | (branches by edges) | behavior-verified | WNA | - |
| fan_out | itemsField, maxItems, maxConcurrency | none | - | API + behavior (maxItems cap, empty list) |
| human_input | message, fields[] | none | - | API + behavior (run pauses, cancel) |
| sub_flow | body graph, entryNodeId, itemsField, maxIterations | none | - | API + behavior (body runs per item) |
| voice_menu | prompt, numDigits, timeout, retries, finishOnKey, branches | none | - | API (validation) |
| bulk_email | subject, body, recipientsSource, group/static/items, limits, unsubscribe, skipIfReplied, html, replyTo | none. Deliberately never run by the sweep (sends mail) | - | API (validation only) |
| crm_lead | action, stage, activityType, activityBody | none | - | API (persist only) |

### 2b. Designer chrome and canvas

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| New workflow (create) | /workflows | behavior-verified | helpers, EGP | - |
| Save draft / autosave | Topbar | behavior-verified | WBL (history/version bump) | - |
| Run now + run modal (prompt) | Topbar / Run modal | behavior-verified | WBL, workflow-execution | - |
| Run modal: runtime params, attach file, run comment | Run modal | none | - | - |
| Validate | Topbar | presence-only (validation errors read via run refusal) | WNA | API (/validate per node) |
| Activate / Revert to draft | Topbar | behavior-verified (published vs draft both run) | WBL | API |
| Intelligence: max steps | Intelligence modal | behavior-verified | WIL | API (bounds) |
| Intelligence: step timeout, max retries | Intelligence modal | none | - | API (persist + bounds) |
| Intelligence: reasoning depth | Intelligence modal | none | - | API |
| Intelligence: tuning mode | Intelligence modal | none | - | API |
| Intelligence: step caching | Intelligence modal | none | - | API |
| Intelligence: cost routing | Intelligence modal | none | - | API |
| Stop run / Pause / Resume | Run inspector | none | - | API (cancel only) |
| Inspector tabs Steps / Raw trace / History, export logs | Run inspector | behavior-verified for history + per-run output; export logs, raw trace none | WBL | - |
| Schedule (frequency/day/time, turn off) | Trigger strip | behavior-verified (API-level, staging suite only) | web/e2e 11 | API |
| Webhook + trigger channel binding | Trigger strip | behavior-verified (staging suite only) | web/e2e 11 | - |
| Share workflow (link, anon run, report) | Share modal | behavior-verified | AWS | - |
| Diagram modal (source, PDF, share) | Diagram modal | none | - | API (formats answer) |
| Version history, diff, restore, promote, rollback | Versions API / panel | none | - | API (publish, list, diff, restore) |
| Duplicate workflow | Topbar | none | - | API |
| Undo / redo, Ctrl+Z / Ctrl+Y | Canvas | none | - | - |
| Command palette (Ctrl+K) | Canvas | none | - | - |
| Node palette: click-to-add / drag-to-add | Palette | presence-only (graphs built via API on purpose, drag is flaky) | helpers/workflow-graph.ts | - |
| Edge delete, node delete, focus mode, tour, "generate with AI" | Canvas | none | - | - |

## 3. Build studio (`/build`)

| Control | Surface | Tested? | Spec | Sweep |
|---|---|---|---|---|
| Surface tabs (agent builder / AI app studio / projects) | /build | presence-only | tests/build-page | - |
| Starter apps (to-do, expense, focus timer) | Studio | behavior-verified | BAB 1-3 | - |
| Describe an app / Ask for a change | Studio | behavior-verified (LLM, tolerant) | BAB 7-8 | - |
| Design: theme presets | Design panel | behavior-verified | BAB 4 | - |
| Design: corner roundness slider | Design panel | none | - | UI |
| Design: font picker | Design panel | none | - | UI |
| Design: colour tokens / contrast | Design panel | none | - | - |
| Device toggle Phone/Tablet/Desktop | Preview | behavior-verified | BAB 4 | - |
| Element panel (tap to edit) | Element panel | behavior-verified | BAB 6 | - |
| Code panel (edit, apply, undo) | Code panel | behavior-verified | BAB 5 | - |
| Undo | Toolbar | behavior-verified | BAB 5 | - |
| Connect: sign-in mode radios (none / login / ...) | Connect panel | none | - | UI (presence) |
| Connect: AI toggle, agent/workflow connections, Test | Connect panel | none | - | - |
| Share: Publish it, QR, link | Share panel | none | - | UI (presence) |
| Engine picker ("Which AI builds your app") | Studio | none | - | - |
| Work survives reload (localStorage project) | Studio | behavior-verified | BAB 5 | - |
| GitHub connect (/build/github) | Build | presence-only | tests/build-page | - |

## Still uncovered after the sweep (be honest)

- UI-layer coverage of nearly every workbench control. The sweep proves the endpoint the control calls stores and returns the value; it does not prove the checkbox/slider in the rendered card is wired to that endpoint (only Simple/Manual is driven through the UI). A miswired onClick would still pass.
- Behavior (not just persistence) for: temperature, top-P, top-K, penalties, seed, response/semantic caching, share memory, collaborators, capabilities, fallback and cost routing. These have non-deterministic or infra-dependent effects; persistence plus bounds is what can be asserted reliably. Only stop sequences, max tokens and the PII guardrail have deterministic output effects.
- Autonomy Semi/Fully, reasoning depth Planned, tools Auto/Manual mode, device routing, JSON schemas, voice/TTS/video providers.
- Workflow: Run modal params/attachments, pause/resume, undo/redo, command palette, drag-drop, AI graph generation, output channels other than log, Supervisor routing at run time, Voice Menu / Bulk Email / CRM / Device runtime (sends mail or touches devices - needs a sandbox).
- Build: Connect/Share behavior, engine picker, colour tokens.

## Run status

`control-sweep-live.spec.ts` (agent workbench) and `control-sweep-live-workflows.spec.ts` (workflow
designer and build studio) together enumerate 23 tests per project under `--list`. They write real rows to whatever tenant
`playwright.prod.config.ts` targets (set via the e2e environment variables), so point it at a disposable
environment, never a production tenant. Run it deliberately with:

```powershell
cd e2e; . .\e2e.local.ps1
npx playwright test -c playwright.prod.config.ts control-sweep-live --project=chromium --workers=1
```

Selectors and response-shape assumptions that most need a first-run check: the workflow `/validate` body
(`{errors: []}`), `versionNumber` on workflow version rows, the Build Design panel's `input[type=range]` /
`<select>`, and the Connect panel radios. A failure there is more likely a wrong assumption than a product defect;
a 5xx or a persisted-value mismatch is a real defect.
