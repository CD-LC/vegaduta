# workflow-templates

Known-good `WorkflowCreateRequest` bodies you can POST to `/api/workflows`.

## Single-file examples

- [`sdlc-modernization-search-and-propose.json`](sdlc-modernization-search-and-propose.json) —
  code-intel search + migration proposal. Read-only end to end. Replace both
  `REPLACE_WITH_A_REAL_AGENT_ID` placeholders before POSTing.
- [`deep-research-plan-search-synthesize.json`](deep-research-plan-search-synthesize.json) —
  the long-horizon research archetype: planner agent → `fan_out` (one researcher
  per sub-question, concurrent) → synthesizer → cited report. The planner's reply
  must be `{"questions": [...]}`; give the researcher agent `web_search` /
  `fetch_web_page` tools. Replace all three `REPLACE_WITH_A_REAL_AGENT_ID`
  placeholders before POSTing.

## Three-vertical demo set — manufacturing, digital marketing, medical billing

Seven workflows plus the ten agents they reference live in
[`../demo/three-verticals/`](../demo/three-verticals/README.md). They are kept there rather
than copied here **on purpose**: the workflows are useless without the matching agent
definitions, the sandbox MCP connectors they call, and the bootstrap that wires the three
together. A copy in this folder would drift from the set that actually works.

| Workflow | Vertical | Shape |
|---|---|---|
| `wf1-rcm-eligibility-sweep` | Medical billing | trigger → agent → condition → **approval** → file output |
| `wf2-rcm-denial-to-appeal` | Medical billing | two chained agents → condition → **approval** → file output |
| `wf3-mfg-sourcing-decision` | Manufacturing | agent → **approval** → file output |
| `wf4-mfg-invoice-exceptions` | Manufacturing | agent → condition → **approval** → file output |
| `wf5-mkt-weekly-review` | Digital marketing | two chained agents → **approval** → file output |
| `wf6-mkt-seo-opportunity` | Digital marketing | agent → file output |
| `wf7-demo-concierge` | Cross-industry | **supervisor** routing to three specialists |

Agent references are written as `@AGENT:<agent name>@` and substituted with real UUIDs by
`demo/three-verticals/bootstrap.sh`. A workflow whose `agentId` does not resolve is rejected
by `OrchestratorService.validate` at save time, so these are not POSTable as-is.

Validate any change offline before it reaches an API:

```bash
python demo/three-verticals/validate-graphs.py
```

That mirrors the engine's own save-time rules — cycles, unique node ids, at least one
trigger, required per-type config, and supervisor route handles matching edge
`sourceHandle` values.

## Two things the graph schema will catch you on

**`condition` matches a substring of the upstream node's whole output text.** Probing for a
business word (`"TERMINATED"`, `"appealable"`) matches the agent's own section headings
whether or not the condition is true, so the true-branch fires on every run and the false
branch becomes dead code. The demo agents instead end their output with an explicit marker
line and the conditions probe the marker *including its value* (`EXCEPTIONS_FOUND: YES`).

**With no branch, the FIRST outgoing edge wins** — edge order is load-bearing. Only
`condition` (`true`/`false`) and `supervisor` (route handle) set a branch.

## Published templates

These same agents and workflows are also published to the in-product template marketplace:

```bash
curl -s "$CORE_BASE_URL/api/templates/gallery?category=Medical%20Billing"
curl -s "$CORE_BASE_URL/api/templates/gallery?category=Manufacturing"
curl -s "$CORE_BASE_URL/api/templates/gallery?category=Digital%20Marketing"
```

A marketplace template is a **snapshot taken at publish time** — editing the source agent or
workflow afterwards does not update it. Re-publish with
`demo/three-verticals/publish-templates.sh`.
