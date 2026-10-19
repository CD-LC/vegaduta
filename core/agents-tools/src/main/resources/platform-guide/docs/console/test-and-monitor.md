# Test and monitor

The **Test & Monitor** group is pre-launch verification and post-launch observation in one place. Gating is per
row: the evaluation and insight pages are for everyone, while the `/admin/*` observability screens carry a
permission. Playground, Quick Task and Jobs folded into [Assistant](assistant.md) on 2026-10-18 — their routes
redirect there, and what follows describes the behaviour that moved.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Agent quality | `/agents/quality` | any member | reads open; dataset, calibration and simulation writes need `PERM_MANAGE_AGENTS`; accepting an improvement needs `PERM_MANAGE_GUARDRAILS` |
| Turn inspector | `/agents/turns` | any member | `GET /api/agents/{agentId}/turns/{turnId}/journal` needs `PERM_VIEW_AUDIT_LOG` and VIEWER on the agent |
| Insights | `/insights` | any member | spend reports (`/api/admin/spend-reports/**`) need `PERM_VIEW_USAGE`; job counts are open |
| Cost & ROI | `/roi` | `PERM_VIEW_USAGE` | reads `PERM_VIEW_USAGE`; writes `PERM_MANAGE_BILLING` |
| Usage | `/usage` | any member | signed-in |
| Files | `/files` | any member | the caller's own files |
| Issues | `/admin/issues` | `PERM_VIEW_AUDIT_LOG` | `/api/admin/issues/**` needs `PERM_VIEW_AUDIT_LOG` |
| Live activity | `/admin/audit-tail` | `PERM_VIEW_AUDIT_LOG` | `/api/audit-log/**` needs `PERM_VIEW_AUDIT_LOG` |
| Audit log | `/admin/audit` | `PERM_VIEW_AUDIT_LOG` | `/api/audit-log/**` needs `PERM_VIEW_AUDIT_LOG` |

## Playground (`/playground` → `/assistant`)

The Playground folded into [Assistant](assistant.md) on 2026-10-18: `/playground` redirects to
`/assistant`, and `?agent=` / `?prompt=` deep links keep working. The agent picker under the box is the old
dropdown, and each finished reply still carries the request X-ray (prompt, sampling, tool list) the Playground
showed. The chat exposes the agent's memories (with delete), the tool-call log, the model request log of a
session, inline approval decisions, per-turn feedback, attachments, chat branching (edit and regenerate a
message) and session export. Generated images, audio, video, documents and sandbox artifacts render in the
thread. API: `POST /api/agents/{id}/chat` (streamed), `/api/agents/{id}/chat/attachments`,
`/api/agents/{id}/tool-calls`, `/api/agents/{id}/sessions/{sessionId}/llm-requests`, `/api/agents/{id}/memories`.

## Agent quality (`/agents/quality`)

Titled "Agent Quality". Evaluation scoring for one agent, deep-linked with `?agentId=`. An agent picker sits above
five tabs; links go to the Agent Lab (`/agents?agentId=...&lab=open`) and to [Agent learning](build.md#agent-learning-agentslearning).

| Tab | What it holds |
|---|---|
| Live quality | Turn-by-turn scores on real conversations (helpfulness, accuracy, tone, length, relevance, safety), then the **Improvements** card below. |
| Datasets & gate | Golden-case eval datasets, case import and export, regression runs and history, judge calibration, statistics, and the **eval gate** that decides whether promoting the agent's head configuration to Production is allowed. |
| Simulations | Persona-driven simulation sessions and attack reports. |
| Reliability | Reliability runs and comparison against a measured noise floor. |
| Solutions | The proof ledger of an applied App Builder envoy, next to the per-agent quality panels. The full envoy view is at `/build/{id}`. |

API: `/api/agents/{id}/quality/**` (summary, history, datasets, runs, gate, judge calibration, simulations,
reliability, statistics, trajectory eval). The Datasets, judge-calibration and statistics lists use the shared pager.

### Improvements

A card on the Live quality tab: "Suggestions for this agent's prompt and procedures, based on its recent behaviour."

- **Generate suggestions** scans the agent's open recurring issue clusters and distils new pending suggestions. It
  spends LLM budget on this agent. The result is bounded and de-duplicated; the toast reports how many new suggestions
  were created.
- A **status filter** (Pending, Accepted, Dismissed, All) and the shared pager (client-side) page through the
  suggestions. Each shows its rationale and proposed text.
- **Accept** applies the suggestion: a *prompt revision* replaces the agent's system prompt; a *new procedure*
  creates a procedure from the text and attaches it. **Dismiss** parks it.
- Permissions: listing and the health snapshot need viewer access to the agent; generate, accept and dismiss need
  editor access; accept additionally needs `PERM_MANAGE_GUARDRAILS` because it can create and attach a procedure.

API: `GET /api/agents/{id}/improvements`, `POST .../improvements/generate`, `POST .../improvements/{suggestionId}/accept`,
`POST .../improvements/{suggestionId}/dismiss`, `GET .../improvements/health` (the last computed health and confidence
snapshot, null until the first sweep or generate).

## Quick Task (`/quick-task` → `/assistant?mode=task`)

A fast alternative to building a workflow: describe a task and run it while you watch (streamed). The launcher
folded into [Assistant](assistant.md) on 2026-10-18 (`/quick-task` redirects to `/assistant?mode=task`).
`/quick-task/history` remains the history page: a paged list of the user's runs (server paging with
`limit`/`offset` and `X-Total-Count`, so the footer can say "50 of 200") that opens a run's full untruncated log.
API: `POST /api/quick-task`, `/api/quick-task/preview`, `/api/quick-task/stream`, `GET /api/quick-task/recent`,
`GET /api/quick-task/{taskId}`.

## Jobs (`/jobs` → `/assistant?mode=background`)

The console for background agent runs (start a job and close the tab). The launcher folded into
[Assistant](assistant.md) on 2026-10-18 (`/jobs` redirects to `/assistant?mode=background`); `/jobs/{agentId}/{jobId}`
still shows one job live: status, result, progress and the trace streamed over SSE (job endpoints are
agent-scoped, so the agent id is in the route). The work rail inside Assistant shows running and finished
background work for the current thread. API: `GET /api/agent-jobs`, `POST /api/agents/{id}/jobs`,
`/api/agents/{id}/jobs/{jobId}` (and `/cancel`, `/progress`, `/trace`, `/trace/stream`),
`/api/agents/{id}/schedule` (and `/resume`, `/run-now`, `/runs`). Schedule writes need `PERM_MANAGE_AGENTS`.

## Insights (`/insights`)

"How your AI workers are doing", in plain words. Charts for AI calls per day and estimated cost per day, a success
rate and response-time summary, **Top agents by estimated cost**, **Top models by estimated cost**, and a **Jobs right
now** bar with a job status chart. A 403 on the spend data is an expected state: the page then shows the jobs section
only, with a short note. Every number follows the same rules as Cost & ROI (no rate from zero calls, cost always
"estimated"). A link points to [Cost & ROI](cost-and-roi.md). API: `/api/admin/spend-reports/{summary,by-day,by-model,by-agent}`
(`PERM_VIEW_USAGE`), `GET /api/agent-jobs/counts`.

## Turn inspector (`/agents/turns`)

**Dependent on an off-by-default backend flag.** A sidebar row since 2026-10-18. The inspector shows the recorded journal of one agent
turn: an ordered, hash-chained list of the stages the turn went through. It is the UI for the turn journal in
[ADR-0020](../architecture/ADR-0020-turn-journal.md) and is built on the turn inspector decision (ADR-0021, not yet
in this repository's architecture folder; treat the screen as proposed and still changing). It has no sidebar row;
the route is reached by link (for example from a chat turn).

- **You give it** an agent and a turn id. It reads `GET /api/agents/{agentId}/turns/{turnId}/journal`.
- **You see** one row per event, in order: type (`INTAKE`, `GUARD`, `MODEL_CALL`, `TOOL_CALL`, `APPROVAL`, `VERIFY`,
  `COMMIT`), a short redacted summary, the SHA-256 of the payload, the previous and own hash, and the time. The server
  also returns `chainIntact` and, when the chain does not verify, `brokenAtSeq` (the first event whose hash does not
  recompute, which means an event was edited, removed, inserted or reordered).
- **What it never shows**: prompts, tool arguments, tool results or answers. The journal stores only hashes and a short
  summary with emails, bearer tokens, key-like tokens and long digit runs masked.
- **Empty and denied states** are shown on the page rather than as an error toast: `404` means nothing was recorded
  for that turn, `403` means you lack `PERM_VIEW_AUDIT_LOG` or access to the agent.
- **Needs the flag**: nothing is recorded unless the deployment sets `app.turn-journal.enabled=true` (environment
  `APP_TURN_JOURNAL_ENABLED`; default `false`). Only the streaming fast path is journaled today; buffered depths and
  workflow runs are not, and the approval gate does not write `APPROVAL` events yet. The read works with the flag off
  (it serves what was recorded earlier), so an empty result on a fresh install is expected. See
  [configuration](../configuration.md).

## Cost & ROI (`/roi`)

Per-agent cost, outcomes, budget and the pilot report. Described in full in [Cost and ROI](cost-and-roi.md).

## Usage (`/usage`)

Titled "Usage & limits". How much of the plan's hosted-AI token allowance the workspace used this month (the same
counter the platform enforces), with warnings above 80 percent and at the limit. It is deliberately in this group
rather than Plans so it survives with billing off. For a workspace using its own provider keys (BYOK) the page says
the platform pool allowances do not apply. Further cards:

- **What's protecting your spend**: the active cost mechanisms (prompt caching share, cost-aware model routing,
  tool-schema minimization, approval gates), active or inactive only, with no invented savings figures.
  `GET /api/tenant/spend-protection`.
- **Monthly AI spend limit**: the cap set by the platform administrator on hosted-model use this calendar month (UTC),
  with an 80 percent marker and, when reached, the date calls resume. Hidden when no cap is set.
- **Model spend & call log** (only for holders of `PERM_MANAGE_BILLING`): with billing on, a link to the money view
  on Billing; with billing off, the model-spend section (`#llm-usage`) is rendered directly on this page.
- **Top up budget**: see below.

API: `GET /api/tenant/usage-quota`, `GET /api/system/status/llm-telemetry`, `/api/admin/llm-usage/**`
(`PERM_VIEW_USAGE`).

### Budget top-up

The **Top up budget** card lets a member buy more of their own per-user usage budget when it runs out (the
follow-through for the HTTP 402 "per-user budget exhausted" answer). The text reads "Add to your usage budget when
it runs out. Amounts from $min to $max."

1. Pick a preset amount or enter a custom amount in USD (checked against the minimum and maximum).
2. **Top up** creates a payment order (`POST /api/budget/top-up`) and opens the gateway checkout window.
3. After payment the card confirms it (`POST /api/budget/top-up/confirm`). Nothing is credited until the confirmation
   succeeds; the server takes the amount and modality from its own pending-order record, never from the browser.
4. Outcomes the card can show: "Added $X to your budget"; "This payment was already applied to your budget"; "The
   payment was received but nothing was credited. Contact support."; or, if confirmation failed, "Do not pay again;
   retry the confirmation" with a **Retry confirmation** button.

The card hides itself when top-ups are off. Top-ups are controlled by platform settings
(`platform.budget.per-user.topup.enabled`, the preset list, and minimum and maximum amounts; the built-in defaults are
a $0.15 minimum and $50 maximum) and require a configured payment gateway. The credit lands on the very counter the
chat path checks, for the signed-in user. The web card buys the default text modality. API: `GET /api/budget/top-up/options`,
`POST /api/budget/top-up`, `POST /api/budget/top-up/confirm`. Any signed-in user may call them; they are not under
`/api/platform/**`.

## Files (`/files`)

One place to see, search and download generated files. It merges six sources into one searchable table with the
shared pager: knowledge documents (aggregated across every collection) and generated images, ElevenLabs and Sarvam
speech, generated video and generated documents. Images, speech and video cannot be paged by the API, so they are
fetched whole and paged in the browser; a notice tells the operator when one of those lists hit its cap, and a
failure to load a source is reported rather than shown as an empty table. API: `/api/knowledge/collections/{id}/documents`,
`/api/imagegen/images`, `/api/elevenlabs/audio`, `/api/sarvam/audio`, `/api/videogen/jobs`, `/api/documentgen/documents`.

## Issues (`/admin/issues`)

The admin issues portal: deduplicated failure records. A recurring failure accumulates onto one row
(occurrence count and last seen) instead of flooding the list, so what is shown is distinct problems. Status tabs
(All, Open, Acknowledged, Resolved), a search box, **Acknowledge** and **Resolve** actions, and the shared pager.
The list is server-paged (`limit`/`offset` with `X-Total-Count`; without parameters the API still returns the newest
200). Against an older core that ignores paging, the page loads up to the newest 200 rows once and pages them in the
browser, so paging works but there is no true total.
API: `GET /api/admin/issues`, `POST /api/admin/issues/{id}/acknowledge`, `POST /api/admin/issues/{id}/resolve`.

## Live activity (`/admin/audit-tail`)

The live audit tail given a whole page. It polls the append-only audit trail every 6 seconds and shows a **live**,
**paused** or **history** state with **Pause**, **Resume** and **Latest** controls and a filter that applies to the
current page only. It is server-paged. API: `GET /api/audit-log`.

## Audit log (`/admin/audit`)

The tenant's audit trail as a filterable table. The time-range and action-type filters are applied by the server
(`from`, `to`, `actionType`, `targetType`) and cover the whole log; the search box and the actor filter narrow the
current page only, and the page says so. **Export this page (CSV)** exports the rows on the current page that match
the page filters. The log is server-paged (`limit` 1..1000, `offset`, `X-Total-Count` counted over the server
filters). API: `GET /api/audit-log`, `/api/audit-log/action-types`, `/api/audit-log/counts`. See
[list endpoint paging](../api/LIST-ENDPOINT-PAGING.md).
