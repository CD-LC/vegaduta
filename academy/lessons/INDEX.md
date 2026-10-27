# Academy curriculum — complete console coverage

Two tracks. **Series A "The Ledger"** is the flagship narrative: one running
story (an AI newspaper) that introduces every concept in the order a new
user needs it. **Series B** is the deep-dive library — one short lesson per
console surface, so every one of the ~67 nav routes has a citable video.

Topics below map to the "academy" sections the videos are filed under.

## Series A — The Ledger (build a newspaper end to end)

| Ep | Topic | Surfaces |
|----|-------|----------|
| 00 | The map: mental model of the console | `/home`, rail, `/assistant` |
| 01 | Your first agent + runtime settings | `/agents`, `/admin/runtime-settings`, `/providers` |
| 02 | Knowledge: feeding the newsroom | `/knowledge` (incl. Docling formats), `/files` |
| 03 | Tools, MCP & SaaS connectors | `/tools`, `/connect`, Composio catalog entry |
| 04 | Workflows: the production line | `/workflows`, triggers, HTTP node, schedules |
| 05 | Approvals, inbox & channels | `/admin/approvals`, `/admin/inbox`, `/admin/channels` |
| 06 | Guardrails: the red pen | `/admin/guardrails`, agent-level policies |
| 07 | Memory & learning | `/memory`, `/skills`, `/skills/library` |
| 08 | Quality: grading the paper | `/agents/quality`, evals, `/agents/turns` replay |
| 09 | Operate: cost, health, audit | `/insights`, `/usage`, `/roi`, `/troubleshoot` |
| 10 | Ship it: scheduled editions & long-running agents | `/scheduled`, `/jobs`, `/quick-task`, background runs |

## Series B — deep-dive library (one lesson per surface)

### Build
`/assistant` (deep), `/studio`, `/templates`, `/playground`, `/coding`,
`/research`, `/build`, `/quick-task`, `/data-tables`, `/skills`,
`/skills/library`

### Connect
`/connect`, `/tools`, `/plugins`, `/add-ons`, `/admin/channels`,
`/admin/contacts`, `/admin/inbox`, `/admin/leads`, `/admin/mcp-servers`,
`/admin/integrations`, `/secrets`, `/edge`, `/code-intel`, `/code`

### Keep memory & knowledge
`/knowledge`, `/memory`, `/files`, `/data-tables`, `/shared-with-me`

### Govern
`/admin/guardrails`, `/admin/approvals`, `/admin/input-requests`,
`/admin/instructions`, `/admin/agent-trust`, `/admin/data-privacy`,
`/features`

### Verify quality
`/agents/quality`, `/eval`, `/agents/turns`, `/compare`,
`/admin/audit`, `/admin/audit-tail`

### Operate & administer
`/insights`, `/usage`, `/roi`, `/data-usage`, `/jobs`, `/scheduled`,
`/admin/issues`, `/admin/maintenance`, `/admin/access`,
`/admin/api-keys`, `/admin/sso`, `/admin/branding`, `/admin/commerce`,
`/admin/payments`, `/admin/billing`, `/admin`

### Help surface
`/help`, `/help/docs`, `/help/controls`, `/glossary` — lessons link back
from these pages.

## Production rules

- Order: all of Series A first (the "one stop"), then Series B by the
  frequency users get lost on each screen.
- Series B reuses the Ledger tenant where possible; niche surfaces
  (`/edge`, `/code-intel`) get their own mini-scenario.
- Enterprise-only routes (`/admin/mcp-servers`, SSO, billing) are flagged
  in-script and recorded against the full edition, not the personal tier.
