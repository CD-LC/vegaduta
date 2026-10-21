# One box on a laptop: the personal assistant surface

**Date:** 2026-10-17. **Status:** research and design, nothing built. Everything about the
platform below was read from this repository on that date (main at `2f25a7d2f`, plus the
`integration/personal-agent` branch at `282f89736`); file paths are relative to the repo root.
Everything about other products comes from their public docs and press, listed at the end.
Nothing was measured on real users.

## The brief

Someone installs VegaDūta on a laptop with the new wizard (`installer/`) and the `personal`
tier that the personal-agent branch adds. They do not want to tune agents, pick tools or build
workflows. They want one box, like Claude Desktop, ChatGPT, Hermes or OpenClaw: type what you
want, the assistant works out what kind of request it is, does it, keeps going until it is
finished, asks only when it must, and remembers you next time.

Constraints set for this document:

1. **No change to existing behaviour.** The surface is a new route in the console that calls
   APIs that already exist. Where a capability exists behind a flag, the personal profile turns
   the flag on. Nothing is renamed, moved or re-implemented.
2. **Every claim about the platform points at code.** Where something is missing, it is listed
   as missing, with the workaround that stays inside constraint 1, or marked as backend work
   for later.

## What the others do, and what it teaches

| Product | Shape | What makes it feel like "one intelligent piece" |
|---|---|---|
| **Claude Desktop / Cowork** | Chat, Code and Cowork tabs; Cowork is merging into chat so Claude decides whether a request is a quick answer or a task. Tasks run in the cloud and keep running when the laptop is closed; phone notification when done or when input is needed; folder access only while the app is open; connectors, skills, plugins; computer use and a built-in browser; scheduled tasks. | The *decision is hidden*: one composer, the system picks answer vs task. Progress, questions and results are visible from any surface. |
| **ChatGPT (agent mode, Projects, Tasks)** | Agent mode spins up a sandbox with browser, terminal and files, pauses before consequential actions; Projects scope files, instructions and memory; Tasks are scheduled runs; memory draws on past chats, files and connected mail. | Memory that spans sources and a per-project scope; approval before real-world effects. |
| **Hermes Agent (Nous)** | CLI/TUI, Hermes Desktop, one gateway for 20+ messaging platforms, cron in natural language, subagents, 40+ tools, seven terminal backends. The **learning loop**: MEMORY.md (2,200 chars) and USER.md (1,375 chars) the agent curates itself, a background review after turns that saves memory and *creates or patches skills* (SKILL.md), `/journey` shows what it learned. Approvals: `smart` mode (auxiliary model rates risk, auto-approves low risk), 300 s timeout then deny, allowlist, headless contexts default to deny, hard blocklist even in yolo. | The assistant visibly **gets better at your work**, and safety is a ladder (smart, manual, off) with a floor that cannot be lowered. |
| **OpenClaw** | A local Gateway (WebSocket, port 18789) owns sessions, routing and channels; Control UI with chat, session rail, agents roster, automations, usage, memory, skills, nodes (phone camera, screen, location); ClawHub skills marketplace. Agent loop: runs serialised per session, lifecycle `start / finishing / end / error`, queue modes for messages arriving mid-run (steer, followup, collect, interrupt), stuck-session diagnostics after two minutes without progress, 48 h per-attempt timeout. Transcript UX: consecutive tool calls share one expandable log, finished turns collapse under "Worked for …", skipped calls shown as "Skipped", separate Subagents and Processes panels. | The best **progress language**: a working indicator distinct from messages, collapsed "Worked for 4 m 12 s, 1 failed call", and a clear answer to "what happens if I type while it is working". |
| **Grok** | Standalone app, voice with live camera, Imagine, DeepSearch, scheduled automations, companions. | Voice and camera as first-class entry points, not settings. |
| **Open WebUI / LibreChat / AnythingLLM / Jan** | Self-hosted chat front-ends over Ollama and APIs; RAG, web search, voice, MCP agents (LibreChat), desktop app (Jan, AnythingLLM). None runs an autonomous task to completion or learns skills. | This is the bar a local install is measured against today, and it is a chat bar, not an agent bar. |

Five things to copy, one to avoid:

1. **One composer, hidden dispatch.** The user never chooses "chat" or "task" or "schedule".
2. **A visible work ledger.** Running, waiting for you, done; from the same screen and from the
   phone (channels) later.
3. **A learning loop the user can see.** Memory and skills the assistant wrote, in a timeline, with
   approve, edit and delete.
4. **A safety ladder with a floor.** Auto-approve the harmless, ask for the consequential, never
   allow the destructive, and tell the user which rung they are on.
5. **Progress language.** "Working for 2 m", collapsed tool logs, "Skipped", "needs you".
6. **Avoid** the OpenClaw-style open skill marketplace as a default on a laptop: security
   researchers reported hundreds of malicious skills on ClawHub in February 2026. The platform's
   skill security scanning and the pending/approve skill library on the personal-agent branch
   are the right default.

## What the platform already has

The surface is a composition of existing endpoints. The full list is `docs/api/openapi.json`
(1,064 paths; streaming operations carry `x-sse: true`) and `docs/api/asyncapi.json`.

| Need | Existing API | Where |
|---|---|---|
| Send a message, stream the answer with tool steps | `POST /api/agents/{id}/chat` (SSE, `stepEvents:true` adds `tool_call`, `tool_result`, `plan`, `guardrail`, `handoff`, `swarm`, `memory`, `context_compacted`, `heartbeat`, `run_started`, `run_finished`) | `core/agents-turn/.../turn/AgentChatStreamController.java`, payloads in `core/agents/.../tools/AgentStepEventPayloads.java` |
| Same, as a typed protocol | AG-UI: `POST /api/agents/{id}/agui/run`, resume at `GET /runs/{runId}/events` | `core/agents-interop/.../agui/AgUiRunController.java` |
| Survive a tab close or reload mid-answer | Resumable turns `GET /api/chat/turns/{turnId}/stream?after=` (flag `app.chat.resumable-streams.enabled`) | `core/agents-turn/.../resumable/ResumableChatTurnController.java` |
| Cancel, rewind, attach files | `POST .../chat/sessions/{sid}/cancel`, `/rewind`, `POST .../chat/attachments` | `AgentChatSessionController.java` |
| Read a conversation back | `GET /api/agents/{id}/sessions/{sid}/transcript`, `/export` | `core/agents-chatport/.../ChatTranscriptController.java` |
| Edit and regenerate | Branching (flag `app.chat.branching.enabled`), which also **persists messages in Postgres** instead of the 24 h Redis TTL | `core/agents-turn/.../branching/ChatBranchController.java`, `application.yml:1218` |
| Pick the right agent for free text, without an extra LLM call | `AgentRelevanceSelector` (lexical, Jev-upgraded when configured) and `ToolRelevanceSelector` | `core/agents-turn/.../decision/AgentRelevanceSelector.java` |
| "Type it and go" with a preview and a delivery channel | Quick Task: `POST /api/quick-task/preview`, `/stream`, `/recent`, `/{id}/cancel` | `core/app/.../quicktask/QuickTaskController.java`, UI `web/app/components/SimpleTaskRunner.tsx` |
| Long work that outlives the request | Agent jobs: `POST /api/agents/{id}/jobs {goal, timeoutSeconds, maxToolCalls}` (202), `GET .../progress`, `.../trace`, `.../trace/stream` (SSE), states QUEUED, RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED; runs in-process, no Temporal needed | `core/agents-jobs/.../job/AgentJobController.java`, `AgentJobObservabilityController.java` |
| Routines | `POST /api/scheduled-tasks {agentId, name, prompt, frequency, time, day, cron, delivery, deliveryTo}`, `/preview` (next runs), pause, resume, run-now, runs (flag `SCHEDULED_TASKS_ENABLED`); also the agent-side tool `schedule_task` | `core/agents-jobs/.../scheduledtask/ScheduledTaskController.java` |
| Deep research with citations | `POST /api/research/runs {question, depth, seedUrls, language}` (flag `app.research.runs.enabled`); creates a per-user "Deep Research" agent on demand, a precedent for provisioning a personal agent through existing services | `core/agents-jobs/.../research/ResearchRunService.java` |
| Sub-agents and parallel work | tools `delegate_to_specialist`, `delegate_parallel`; `PATCH /api/agents/{id}/collaborators`; `swarm` and `handoff` step events | `core/agents-tools` |
| Ask the human | tool `request_human`; approvals `GET /api/approvals?status=PENDING`, `POST /{id}/approve`, `/reject`; the request's `requestedBy` is `agent:<agentId>:session:<sessionId>`, so a chat can claim its own approvals | `core/approval/.../ApprovalController.java`, `core/guardrails/.../GuardedToolCallback.java:566` |
| Tell the user later | `GET /api/notifications`, `/unread-count`; `NotificationBell` polls every 30 s | `web/app/components/NotificationBell.tsx` |
| Memory the user owns | `GET/PUT /api/memory/blocks/{label}`, `/facts`, `/export`, `/import`; per-agent memories; the `memory` step event says what was used in a turn | `core/agents-turn/.../usermemory/UserMemoryController.java`, UI `web/app/memory` |
| Skills the assistant learns | personal-agent branch: `app.skills.learning.enabled`, `SkillLifecycleController`, library UI with Active, Pending, Archived tabs, import dropzone, diff view | `web/app/components/skillLibrary/*` (branch) |
| Files and knowledge | `POST /api/knowledge/upload`, `/search`, collections; `/files` page aggregates generated media and documents | `core/knowledge`, `web/app/files` |
| Web, browser, code, documents, Google | tools `web_search`, `fetch_web_page`, `deep_research`, `verify_citations`, `browser_*`, `web_task`, `run_code`, `coding_task`, `generate_document`, `google_calendar/gmail/drive/sheets`, `http_request`, `data_table_*`, `channel_send_message` | `core/agents-tools`, `core/agents-browser`, `core/google` |
| Bring your own MCP server | `POST /api/mcp/registry {name, type: STDIO or SSE, command, args, env, url}`, `/catalog`, `/search`, OAuth; STDIO under an OS sandbox on the personal profile | `core/mcp/.../McpRegistryController.java` |
| A model with no key | host Ollama through the hosted pool (zero-cost rows), and on the branch the local-model wizard at `/providers/local-model` with a probe for local OpenAI-compatible servers | `docs/local-models.md`, `web/app/components/localModel/*` (branch) |
| The same assistant on your phone | `PUT /api/channels/{telegram, whatsapp, sms, signal, imessage, matrix...}/default-agent`; Telegram long-polls on the personal profile, so no public URL is needed | `core/omnichannel`, `application-personal.yml` (branch) |
| Sign in once on a laptop | personal-agent branch: `app.auth.mode=local`, `LocalAuthController`, `LocalLogin.tsx`; API keys `vdak_` as personal tokens | `core/admin/.../localauth` (branch), `core/security/.../apikey` |
| Guardrails that fail closed, approvals that cannot double-run | `APP_GUARDRAILS_FAIL_MODE`, `TOOL_UNCLASSIFIED_ACTION`, row-locked approval decisions, tool fingerprint on replay | `CHANGELOG.md` Unreleased |
| Console pieces to reuse | `api.streamChat` and `pumpChatSse`, `AgentActivityTrace`, `ToolCallCard`, `PendingToolApprovals`, `RichText`, `MediaCard`, `GenerativeUiRenderer`, `ArtifactPane`, `SourcesPanel`, `PlanStream`, `chatStreamMachine`, `useTranscript`, `useRequestLifecycle`, `ChatAttachments`, `AppShell fillViewport`, `CommandPalette`, `ThemeProvider` | `web/app/lib/api/chat.ts`, `chatSse.ts`, `web/app/components/{agents,chat,research,brief,artifacts}` |

Nothing comparable in the open-source field ships channels, approvals, guardrails, workflows,
knowledge, memory, skills and on-device models in one process. What is missing is the one
screen that makes a laptop user feel all of that as a single assistant.

## Gaps, and how each is handled without touching the backend

| # | Gap | Evidence | Handling |
|---|---|---|---|
| G1 | ~~No endpoint lists a user's conversations~~ **Done:** `GET /api/chat/threads` lists the signed-in user's sessions (title = first user line, agent, last activity; keyset paging on `updatedAt`) and `DELETE /api/chat/threads/{agentId}/{sessionId}` removes one with its live history, both over the branching tables (`ChatThreadController`, 404 while `APP_CHAT_BRANCHING_ENABLED` is off). The browser index is now a cache of that list plus local-only state (rename, pin, sessions of a core that stores nothing); the Assistant reads the server tree after each turn so its sessions are recorded, and the transcript route falls back to the stored tree after the 24 h Redis window. | no `GET .../sessions` in any chat controller; `application.yml:1090` | Client-side conversation index in IndexedDB (same layer as `lib/api/persistentCache.ts` and the playground's `persistence.ts`), one row per session: id, title, agent, last message, pinned. Transcript re-fetched from `/sessions/{sid}/transcript` when opened. The personal profile sets `APP_CHAT_BRANCHING_ENABLED=true` so messages persist in Postgres and survive longer than 24 h. Titles: first user line, truncated; optional rename stored locally. Export via `/export`. A server-side list is the first backend follow-up (one controller over the branching tables). |
| G2 | **No agent-agnostic chat entry.** Every turn needs an `agentId`. | `AgentChatStreamController` | One personal **Assistant agent per user**, created on first run through `POST /api/agents` (or `POST /api/agents/generate` from a one-paragraph description), marked with a name and a memory block so it can be found again, like Deep Research does with its capability row. Quick Task's auto-selection stays available as "ask a specialist" when the tenant has more agents. |
| G3 | **Approvals are not in the stream.** The request id reaches the model as text in a `tool_result`; the decision outcome arrives through notifications. | `GuardedToolCallback.java:581`, `ApprovalController` | While a turn or job is live, poll `GET /api/approvals?status=PENDING` every 3 s (what `PendingToolApprovals` already does) and claim rows whose `requestedBy` names this session. Render as an inline card; approve and reject call the existing endpoints. After the decision the agent job resumes on its own (AWAITING_APPROVAL to RUNNING); for a chat turn the surface re-sends "continue" so the tool runs again. |
| G4 | **No thinking or citation events** in the chat stream. | `AgentStepEventPayloads` | Show `plan{text}` as the "thinking" line. Citations: use `SourcesPanel` on research-run results (which do carry sources) and on `fetch_web_page` and `web_search` tool results in the trace. |
| G5 | **No local shell or local file-system tool.** Code runs in E2B or the tool-sandbox; files reach the assistant only as uploads or knowledge documents. | `core/agents-tools` | In scope for v1: drag-and-drop to `/chat/attachments` and `/knowledge/upload`; `run_code` when a sandbox is configured. A folder-scoped local file tool (the Cowork model) is backend work and listed under follow-ups. |
| G6 | **Job-vs-turn decision is the client's.** The chat turn has a per-turn tool-step cap; a job has a timeout and `maxToolCalls`. | `ToolStepLimitExceededException`, `AgentJobStartRequest` | The dispatch ladder below. The model also escalates itself: the Assistant agent gets `schedule_task`, `delegate_parallel`, `deep_research` and `request_human`, so "every Monday" or "research all three" is handled inside the turn without the UI guessing. |
| G7 | Several needed features are **off by default**: resumable streams, branching, scheduled tasks, research runs, turn journal, artifacts directive. | `application.yml` | Turned on in the personal profile (table below). No code change. |
| G8 | **Ollama registration is platform-admin only** on main. | `/api/platform/hosted-llm-providers` | The personal-agent branch adds the local-model wizard and the local OpenAI-compatible probe; on the personal profile the single user is the admin. |
| G9 | No desktop wrapper, no offline shell. | `web/public/manifest.webmanifest` only | The installer already opens the console; it should open `/assistant` and offer "Install as app" (PWA, standalone, already declared in the manifest). A Tauri shell is a follow-up, not a prerequisite. |
| G10 | The turn-journal `turnId` is never returned to the client. | `TurnRecorder.java:40` | Not needed for v1; the "what did it do" view is the step trace plus `GET .../tool-calls` and `/sessions/{sid}/llm-requests`. |

## The design

### One screen

Route `web/app/assistant/page.tsx`, inside `AppShell` with `fillViewport`, no new framework,
existing tokens. The rail collapses to icons; on the personal profile this route is the landing
page after sign-in (today `/` sends members to `/build`; the personal profile redirects to
`/assistant` instead, a one-line, profile-gated change in `web/app/page.tsx`).

```
┌───────────┬──────────────────────────────────────────────┬──────────────────┐
│ Threads   │  Assistant                                   │ Work             │
│ (local    │                                              │ ● Running (1)    │
│  index)   │  user: book me a dentist next week and put   │   dentist search │
│           │        it in my calendar                     │   2 m 10 s       │
│ Today     │                                              │ ◐ Needs you (1)  │
│  dentist… │  ▸ Working for 2 m 10 s · 6 tool calls       │   send email?    │
│  trip…    │     web_search  "dentists near …"            │ ○ Scheduled (2)  │
│ Yesterday │     browser_navigate  …                      │   Mon brief 8:00 │
│  …        │     google_calendar  create  ✓               │ ✓ Done today (3) │
│           │                                              │                  │
│           │  assistant: Booked Tue 10:30 at … Added to   │ Learned          │
│           │  your calendar. Want a reminder the day      │  + skill: book   │
│           │  before?                                     │    appointments  │
│           │                                              │  + fact: prefers │
│           │  [ Yes, 9:00 ]  [ No ]                       │    mornings      │
│           │                                              │                  │
│ + New     │  ┌────────────────────────────────────────┐  │                  │
│           │  │ Ask or tell me what to do…   📎 🎤  ↵  │  │                  │
│           │  └────────────────────────────────────────┘  │                  │
└───────────┴──────────────────────────────────────────────┴──────────────────┘
```

- **Threads** (left): the IndexedDB index (G1), a cache of `GET /api/chat/threads`. Search is local full-text over cached
  transcripts, the Hermes `session_search` idea without a server.
- **Transcript** (centre): `RichText`/`MediaCard` for answers, `GenerativeUiRenderer` for
  model-emitted forms and buttons (the "[Yes, 9:00] [No]" chips are that), `ArtifactPane` when an
  artifact directive arrives, `SourcesPanel` for research. Tool steps collapse under one
  "Working for …" row per turn, OpenClaw style; failed calls counted in the heading; `guardrail`
  events shown as "Skipped: reason".
- **Work** (right, collapsible): everything that outlives a turn. Running agent jobs with their
  `trace/stream`, approvals that need the user (G3), scheduled tasks with next run from
  `/preview`, today's finished jobs and quick tasks. Clicking any item opens its thread.
  "Learned" lists memory facts and skills written since the user last looked (the `memory` step
  event and the skill library's Pending tab), with approve, edit and forget inline.
- **Composer**: text, paste or drop files (attachments or knowledge, by size and type), microphone
  (existing voice turn `POST /api/voice/agents/{id}/turn` and edge speech), slash commands
  that map to existing features: `/research`, `/schedule`, `/remember`, `/forget`, `/agent <name>`,
  `/export`. Typing while a run is live offers "Steer" (sends into the same session; the server
  serialises turns per session) or "New thread".

### The Assistant agent (first run)

On the first visit, if no agent named `Assistant` with the marker memory block exists for this
user, the surface creates it with calls that exist today:

1. `POST /api/agents` with `name: "Assistant"`, `experienceMode: SIMPLE`, the provider and model
   the local-model wizard or BYOK step produced (`GET /api/providers/default-model`), a system
   prompt written for autonomous completion (plan briefly, act, verify, report; prefer tools
   over asking; escalate to `schedule_task`, `delegate_parallel`, `deep_research`; call
   `request_human` for anything consequential; save durable facts with `remember_fact`).
2. `PATCH /tool-access` with every name from `GET /available-tools` minus payments and
   `http_request_write`; `PATCH /knowledge-base {enabled:true}`; `PATCH /share-memory`;
   `PATCH /autonomy-tier` to the highest tier the install allows; `PATCH /run-budget` from the
   personal defaults.
3. `PUT /api/memory/blocks/assistant` with the marker and the user's name, locale and time zone
   (the Hermes USER.md idea, on the existing block API).
4. If the user connected Telegram or WhatsApp in the wizard, `PUT /api/channels/<ch>/default-agent`.

Everything is reversible from the existing Agents page; the surface never hides that page, it just
does not require it. The 77 templates remain reachable as "specialists": `/agent <name>` or the
Quick Task auto-selector routes a message to one of them when the user has imported some.

### The dispatch ladder (how it "understands what it is")

The surface does not run its own intent model. It makes one cheap structural choice, then lets the
agent do the understanding with its tools. Rungs, top to bottom:

| Rung | Trigger (client, deterministic) | Call | What the user sees |
|---|---|---|---|
| 0 Slash | message starts with a known slash | the mapped endpoint | the matching card |
| 1 Turn (default) | everything else | `POST /api/agents/{assistant}/chat` with `stepEvents:true`, `X-Resumable-Stream: 1` | streaming answer; "Working for …" when tools run |
| 2 Job | the turn ends with a `guardrail` or `error` step naming the tool-step cap, **or** the assistant replies with a structured "this needs more than one sitting" marker (prompted), **or** the user presses "Keep going in the background" on a running turn | `POST /api/agents/{assistant}/jobs {goal: <thread so far + request>, timeoutSeconds: 3600}` then `trace/stream` | the thread shows "Continuing in the background", the Work rail shows the job; result posted back into the thread when `SUCCEEDED` |
| 3 Routine | the assistant called `schedule_task` (visible as a `tool_call`), or the user used `/schedule`, or the preview card's "Make this a routine" | `POST /api/scheduled-tasks` with `/preview` first for the next three runs | a routine card with next run and delivery (chat, Telegram, WhatsApp, email) |
| 4 Research | `/research`, or the assistant called `deep_research`, or the message matches the research-run depth heuristics already in `ResearchRunPlan` | `POST /api/research/runs` | a report card with sources (`SourcesPanel`) and "verify citations" |
| 5 Specialist | `/agent <name>`, or the tenant has more than one agent and `POST /api/quick-task/preview` (no `agentId`) names a different agent with a non-zero score | the chosen agent's `/chat` | a chip "Answering as <agent>" with "use Assistant instead" |

The ladder is a pure function (`dispatch.ts`) with unit tests, the same pattern as
`web/app/start/flow.ts` and `chatStreamMachine.ts`. Because rungs 2 to 4 can also be entered by
the model's own tool calls, the UI only has to notice them in the step stream and render the right
card; it never has to be smarter than the agent.

### Keeping going until it is done

- Within a turn, the server's tool loop already iterates until the model stops or the step cap
  is hit; `heartbeat` events keep the connection warm; `context_compacted` is shown as a quiet
  "condensed earlier context" line.
- Resumable streams mean a reload or a closed lid mid-answer resumes from `GET
  /api/chat/turns/{turnId}/stream?after=`; the `lib/resumableStream.ts` helper already does this
  for the playground.
- Agent jobs continue when the browser is gone. On return, the Work rail reconciles from `GET
  /api/agent-jobs?status=live` and posts results into the right thread (the job's `sessionId` is
  derived from the job id, so the thread index stores job id to thread id).
- Approvals park a job in `AWAITING_APPROVAL`; the notification bell and, when a channel is
  connected, the channel's approval message reach the user away from the laptop; deciding from
  any of them resumes the job.
- Stuck detection, the OpenClaw two-minute rule: no step event and no heartbeat for 120 s shows
  "Still working, nothing new for 2 minutes" with Cancel and "Move to background" (rung 2).

### Safety ladder on a laptop

The platform already has the rungs; the surface names them in one setting, "How much should I
check with you?":

| Rung | Platform settings behind it |
|---|---|
| Ask before anything that leaves this machine | `TOOL_UNCLASSIFIED_ACTION=require-approval`, approval policy rows for send, write and payment action types |
| Ask only for consequential things (default) | `TOOL_UNCLASSIFIED_ACTION=allow`, policy rows for `channel_send_message`, `google_gmail` send, `http_request_write`, payments; `app.agents.taint.mode=enforce` (branch) so untrusted web content cannot drive a consequential tool without a check |
| Just do it | same, with the per-agent autonomy tier raised; the fail-closed guardrails and the hard refusals (payments, taint) remain, matching Hermes' floor that even yolo cannot lower |

The approval card always shows the tool, the summarised input and the fingerprint-changed warning
the backend now produces. Decisions time out on the server; the card says so.

### Memory and the learning loop, visibly

- The `memory` step event lists which blocks and facts a turn used; the transcript shows a small
  "Used 3 memories" link that opens them.
- New facts (`remember_fact`) and new skills (branch: Pending tab) appear in "Learned" on the Work
  rail with approve, edit and forget. This is Hermes' `/journey` and `write_approval` on the
  existing memory and skill APIs.
- The user's profile block (name, locale, time zone, preferences) is editable as plain text, the
  USER.md idea on `PUT /api/memory/blocks/{label}`.
- Import from ChatGPT or Claude exports uses the existing `/api/memory/import` and
  `/api/chat-import` paths, surfaced once at first run.

### Same assistant, other doors

The Assistant agent is the default agent of every channel the user connects, so a WhatsApp
message, a Telegram voice note or an SMS lands in the same memory and the same approval queue.
The Work rail shows channel conversations from `GET /api/handoff/conversations`. This is what
Hermes and OpenClaw lead with, and the platform has more channels than either.

### Local model, no key

On the personal-agent branch the Providers page opens with the local-model first-run and the
wizard. The surface defers to it: if `GET /api/providers/availability` reports nothing usable,
the composer shows one card, "Pick a model to start", linking to `/providers/local-model`, and
Quick Task's existing no-provider banner logic (`providerGate.ts`) is reused rather than copied.

## Profile settings the surface relies on

All exist today; the personal profile (branch) and `deploy/compose/personal.yml` set them.

| Setting | Value on personal | Why |
|---|---|---|
| `APP_CHAT_BRANCHING_ENABLED` | `true` | messages persist in Postgres (G1); edit and regenerate |
| `app.chat.resumable-streams.enabled` | `true` | reload-safe answers |
| `SCHEDULED_TASKS_ENABLED` | `true` | routines |
| `app.research.runs.enabled` | `true` | research with citations |
| `NEXT_PUBLIC_ARTIFACTS` | `true` | artifact pane |
| `TOOL_UNCLASSIFIED_ACTION` | `allow` (default rung) | safety ladder |
| `app.agents.taint.mode` | `enforce` (branch default) | untrusted content cannot drive consequential tools |
| `app.skills.learning.enabled` | `true` (branch default) | learned skills, with the Pending gate |
| `app.chat.session-ttl-hours` | leave at 24 | branching retention governs persistence |

Each needs a line in `docs/configuration.md` where it is not already documented.

## Build plan

Sized so each phase is one person, one or two weeks, each independently shippable. Gates: file
size cap (665 lines per `.ts`/`.tsx`/`.css`), ESLint (no raw `process.env`, no native select, a11y),
`node scripts/api-contract.mjs --write` for every new `apiFetch` call site, i18n keys in
`messages/en.json`, co-located vitest tests, `navSections.test.ts` for the rail entry, a
`CHANGELOG.md` line.

**Phase 1, the box (new files only):**

- `web/app/assistant/page.tsx`, `web/app/components/assistant/{AssistantPane,AssistantComposer,AssistantTranscript,WorkedForBlock,WorkRail,ThreadList}.tsx`
- `web/app/components/assistant/{dispatch.ts,threadIndex.ts,assistantAgent.ts}` with tests
  (`dispatch.test.ts`, `threadIndex.test.ts`, `assistantAgent.test.ts` against a mocked `api`)
- `web/app/lib/api/assistant.ts`: thin wrappers over existing modules (`streamChat`, approvals,
  jobs, scheduled tasks, research, memory), no new endpoints
- rail entry in `navSectionsAuthor.ts`; `/assistant` as the personal-profile landing in
  `web/app/page.tsx` behind `NEXT_PUBLIC_SITE_MODE` or an equivalent public env read through
  `getPublicEnv`
- preview harness entry in `web/app/ui-kit/console-preview` so the screen renders without a core

**Phase 2, work that outlives a turn:** rungs 2 to 4, the Work rail's live sections over
`/api/agent-jobs`, `/api/approvals`, `/api/scheduled-tasks`, `/api/research/runs`; result
posting back into threads; stuck detection; notifications merged into the rail.

**Phase 3, the learning loop:** "Learned" section, `memory` event rendering, profile block
editor, skill Pending approvals (needs the personal-agent branch merged), first-run import.

**Phase 4, the doors:** channel default-agent wiring from the first-run flow, channel
conversations in the rail, voice composer, PWA install prompt and the installer opening
`/assistant`.

**Follow-ups that need backend work (out of scope here, listed so nobody re-discovers them):**

1. `GET /api/agents/{id}/sessions` over the branching tables, with title and last activity,
   replacing the IndexedDB index as the source of truth.
2. An approval event in the chat stream (`approval{id, tool, summary}`) and an outcome event, so
   the client stops polling.
3. A `thinking` step event when the provider streams reasoning.
4. A folder-scoped local file tool for the personal profile (read, write under an allowlisted
   root, no shell), the Cowork and Hermes `HERMES_WRITE_SAFE_ROOT` idea.
5. The turn journal `turnId` on the response headers so the inspector is one click from a turn.
6. A Tauri shell with a tray icon and OS notifications, after the PWA has been measured.

## Open decisions

1. **Name and route.** `/assistant` is used above. The product may want a name that is not the
   platform's ("Dūta"?). Cheap to change before Phase 1 ships.
2. **One Assistant agent per user, or per tenant.** Per user matches the personal profile and
   memory ownership; per tenant is what the lite tier with a family on one install would want.
   The design above is per user, found by name plus marker block.
3. **Default safety rung.** "Ask only for consequential things" is proposed. The alternative, ask
   for everything that leaves the machine, is what ChatGPT agent mode does and is safer for a
   first run.
4. **Branching on by default** doubles the persistence of every chat on the personal profile.
   The retention purge exists (`ChatBranchRetentionPurge`), so this is a disk question, not a
   correctness one.
5. **Whether `/assistant` replaces `/build` as the landing page** for lite as well as personal.
   The build studio is a maker's front door; a laptop user wants the box. Proposed: personal only.
6. **Ship behind the personal-agent branch, or before it.** Phases 1 and 2 need nothing from the
   branch except the local login; they can land on main behind the rail entry and work on lite
   today, with Keycloak.

## Sources

Platform: files cited inline; `docs/api/openapi.json`; `docs/architecture/AI-FOR-EVERYONE-2026-10-14.md`;
`installer/README.md`; branch `integration/personal-agent`.

Other products (public pages, read 2026-10-17):

- Anthropic, Claude Cowork support article: https://support.claude.com/en/articles/15520349
- Nous Research, Hermes Agent repository and docs: https://github.com/NousResearch/hermes-agent,
  https://hermes-agent.nousresearch.com/docs/ (memory, skills, security pages)
- OpenClaw docs: https://docs.openclaw.ai/ (architecture, agent loop, Control UI);
  Acronis on ClawHub malicious skills: https://www.acronis.com/en/tru/posts/openclaw-agentic-ai-in-the-wild-architecture-adoption-and-emerging-security-risks/
- ChatGPT agent mode and desktop: https://getcoai.com/news/openai-adds-agent-mode-to-chatgpt-mac-app-for-plus-users/,
  https://www.promptspace.in/blog/chatgpt-agent-mode-complete-guide-2026
- Grok app features: https://releases.sh/xai/grok.md
- Self-hosted chat front-ends compared: https://toolhalla.ai/blog/open-webui-vs-anythingllm-vs-librechat-2026,
  https://www.droptica.ai/blog/open-source-llm-ui-best-interfaces-local-ai-deployment-2026/
