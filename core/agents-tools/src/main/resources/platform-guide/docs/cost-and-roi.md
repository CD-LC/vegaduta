# Cost and ROI

The console page **Cost & ROI** (`/roi`) answers, per agent: what did it cost, how did its
conversations end, and, only if you have told the platform what a resolution is worth, what is that
worth. The rule behind every figure: **it comes from recorded data or from a number you typed.**
Nothing is defaulted, and a missing price or a missing denominator shows as a dash, not as zero.

Open it from the **Cost & ROI** row in the navigation (it needs the *View usage* permission; the row sits
outside the Plans group so it stays reachable when billing is off), from the link on the Insights page,
or go to `/roi`. A workspace seeded with `scripts/vegaduta seed-demo` shows an *Example* watermark on this
page. The page also hosts the pilot baseline and the one-page pilot report; see the [pilot kit](pilots/README.md).
The navigation is defined in `web/app/components/nav/navSections.ts`; see the [console guide](console/README.md).

API: `/api/tenant/roi/**` ([OpenAPI](api/README.md)). Reading needs the *View usage* permission;
changing prices, value assumptions or recording outcomes needs *Manage billing*.

## What is recorded per call

Inventory of what existed before this feature, and what it added.

| Fact | LLM call (`llm_call_log`) | Tool call (`tool_call_log`) |
|---|---|---|
| tenant | the tenant schema itself (no column) | same |
| agent | `agent_id` (null for system calls) | `agent_id` |
| agent version | `agent_version_id` | `agent_version_id` |
| session / conversation | `session_id` | `session_id` |
| provider and model | `provider_name`, `model` | n/a (`tool_name`) |
| tokens in / out | `prompt_tokens`, `completion_tokens`, `total_tokens` | none |
| cache tokens | `cache_creation_tokens`, `cache_read_tokens` | none |
| reasoning tokens | **`reasoning_tokens` (added by V496)** | none |
| latency | `latency_ms` | `completed_at - started_at` |
| outcome | `success`, `error_message`, `exception_type` | `error_message`, `validation_failed` |
| cost | `estimated_cost_usd` (see below) | none (a tool call has no token cost) |
| workflow run | not stored; a workflow agent node uses the run id as the session id, so `session_id = workflow_run.id` | same |
| channel | not stored; `channel_session.session_id = session_id` | same |
| team | **does not exist** in the data model; the nearest grouping is the agent owner (`agent.created_by`) | same |

Gaps this feature closes: reasoning tokens were priced into `estimated_cost_usd` but never stored,
so a cost recomputed later would have under-counted thinking calls (V496 adds the column,
`LlmCallLogService` fills it). Workflow and channel are joined at query time instead of copied onto
every row, which keeps the chat hot path unchanged.

Gaps that remain: there is no team dimension; successful calls whose provider reported no usage are
counted and flagged (`callsWithoutUsage`), not estimated; tool calls carry no cost.

`estimated_cost_usd` (used by the budget cap) is priced by `LlmPricingCatalog`, which falls back to a
fixed rate for models it does not list so that enforcement cannot be bypassed. The ROI view does not
use it.

## Cost model

* Prices live in `llm_price`, per tenant: USD per million tokens for input, output, cache write and
  cache read, plus `effective_from`, who set it and when. A new price is a new dated row; history is
  kept, so last month's cost does not change when this month's price does.
* There is **no seed and no fallback**. A model matches a price by provider and by case-insensitive
  model-name prefix (longest prefix, then latest effective date not after the call's day). No match,
  or a blank rate the call needs, makes that usage *unpriced*: it is listed under *Model prices*,
  excluded from the cost, and the cost is shown as a lower bound (`>=`).
* Cache tokens are priced only when a cache rate is set; they are never assumed to cost the input
  rate. Reasoning tokens are billed at the output rate, as providers bill them.
* The figure is always called **estimated**. Bring-your-own-key tenants pay the provider directly, so
  the BYOK estimate is separate from the platform-hosted estimate, and both are separate from
  **platform charges** (billable, undisputed resolution events, in the currency they were billed in).

## Attribution

`GET /api/tenant/roi/costs?groupBy=agent|workflow|channel|owner|day&from&to` groups the same priced
usage. Calls with no match for a dimension fall under `unattributed`. Dates are UTC days, inclusive,
at most 366 days.

## Outcomes and metrics

An agent, workflow or operator marks how a conversation ended with
`POST /api/tenant/roi/outcomes` (`RESOLVED`, `HANDED_TO_HUMAN`, `ABANDONED`, `CORRECTED_BY_HUMAN`).
This reuses the existing records instead of adding a second ledger:

| Mark | Stored as |
|---|---|
| `RESOLVED` | `resolution_event` `RESOLVED_AI`, evidence `EXPLICIT_API` (billable on an outcome-priced plan, like `POST /api/tenant/resolutions`) |
| `HANDED_TO_HUMAN` | `resolution_event` `ESCALATED` |
| `ABANDONED` | `resolution_event` `ABANDONED` |
| `CORRECTED_BY_HUMAN` | `conversation_correction` |

Already recorded without any call: channel conversations get a `resolution_event` from the hourly
sweep (handover state, feedback, idle close), and a `turn_feedback` `EDIT` on a conversation's session
counts as a human correction. Repeating the same mark is a no-op. A conversation counts once, under
the outcome of its latest event in the window.

| Metric | Definition | No data |
|---|---|---|
| resolved | conversations the agent resolved (`RESOLVED_AI`) | 0 |
| cost per resolved conversation | estimated LLM cost / resolved | dash |
| human-handover rate | handed to a human / conversations with an outcome | dash |
| human-correction rate | corrected by a human / conversations with an outcome | dash |
| approval-denial rate | approvals rejected / approvals decided (status other than `PENDING`) | dash |

The idle-close heuristic (`NO_ESCALATION_IDLE`, confidence 0.6, "assumed resolved") is shown
separately and **not counted as resolved** unless you switch on *Count idle-closed conversations as
resolved*, so ROI is not inflated by guesses. Approvals are attributed to an agent through the
`agent:<id>:session:<id>` label the approval gate writes on `approval_request.requested_by`.

## Value assumptions and ROI

Per agent you may enter a value per resolved conversation (with its currency) and minutes saved per
resolved conversation. Both are optional and never defaulted. With them:

* value total = value x resolved; minutes saved = minutes x resolved;
* net value = value total - what you actually pay (BYOK estimated cost + platform charges), and ROI
  % = net / that cost, only when the value is in USD and any platform charges are USD too. There is
  no recorded exchange rate, so otherwise the page says the currencies differ and shows no net;
* a cost of zero gives no percentage; an incomplete cost is flagged.

Every ROI figure carries the words **based on your assumptions** (page, CSV column names, API field
`roiBasis`).

## Budget and alerts

The budget bar shows this month's spend against the monthly cap the LLM gateway already enforces
(`LlmGatewayBudgetService`), read from the counter that enforcement uses (`llm_spend_month`). Plans
without a cap show no bar. A sweep every ten minutes raises one bell notification (category
*Billing*) when spend first reaches 80% and one at 100%, once per month each
(`roi_budget_alert`); turn it off with `roi.budget-alerts.enabled=false`. The alert can trail the
crossing by up to ten minutes. The per-user daily budget and the per-run budget are separate limits
and are not shown here.

## Endpoints

| Method and path | Needs | Purpose |
|---|---|---|
| `GET /api/tenant/roi/agents` | View usage | the per-agent table (`includeAssumed`) |
| `GET /api/tenant/roi/agents.csv` | View usage | the same table as CSV |
| `GET /api/tenant/roi/costs` | View usage | cost grouped by a dimension |
| `GET /api/tenant/roi/budget` | View usage | spend against the monthly cap, alerts sent |
| `GET /api/tenant/roi/prices` | View usage | price versions and unpriced models in the range |
| `POST /api/tenant/roi/prices`, `DELETE .../{id}` | Manage billing | add or remove a price version |
| `GET/PUT /api/tenant/roi/agents/{id}/assumptions` | View usage / Manage billing | value assumptions |
| `POST /api/tenant/roi/outcomes` | Manage billing | mark an outcome |
| `GET/PUT /api/tenant/roi/agents/{id}/baseline` | View usage / Manage billing | the tenant-entered pre-pilot baseline ([pilot kit](pilots/README.md)) |
| `GET /api/tenant/roi/agents/{id}/pilot-report` | View usage | one-page pilot report, `format=html\|pdf\|json` |
| `GET /api/tenant/roi/demo` | View usage | whether this workspace is a seeded Example tenant |

Not yet built: a native agent tool and workflow node for marking outcomes (today the endpoint is
called with an API key that has *Manage billing*), and a "use the platform's list prices" import for
the price table.
