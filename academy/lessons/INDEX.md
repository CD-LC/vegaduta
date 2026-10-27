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

## Series B — deep-dive library (grouped lessons, every route covered)

62 routes render on the personal edition; Series A already covered ~15.
The rest map to these lessons (dir `bNN-slug`, each 3–5 scenes):

| # | Lesson | Routes |
|---|--------|--------|
| b01 | Assistant deep-dive | `/assistant` (composer, background, quick task) |
| b02 | Agent templates & learning | `/agents/templates`, `/agents/learning`, `/templates` |
| b03 | Playground | `/playground` |
| b04 | Coding & code | `/coding`, `/code`, `/code-intel` |
| b05 | Research & build modes | `/research`, `/build` |
| b06 | Quick tasks & jobs | `/quick-task`, `/jobs` |
| b07 | Data & files | `/data-tables`, `/files`, `/clips` |
| b08 | Skills library | `/skills`, `/skills/library` |
| b09 | Connect, plugins & add-ons | `/connect`, `/plugins`, `/add-ons` |
| b10 | Credentials & secrets | `/tools/credentials`, `/secrets` |
| b11 | Edge (on-device AI) | `/edge` |
| b12 | Admin integrations & features | `/admin/integrations`, `/features` |
| b13 | Contacts, routing & instructions | `/admin/contacts`, `/admin/input-requests`, `/admin/instructions` |
| b14 | Approvals deep-dive | `/admin/approvals` |
| b15 | Data privacy | `/admin/data-privacy` |
| b16 | Turns & compare | `/agents/turns`, `/compare` |
| b17 | Audit trail | `/admin/audit`, `/admin/audit-tail` |
| b18 | Usage & data usage | `/usage`, `/data-usage` |
| b19 | Admin: access, issues, maintenance, billing | `/admin/access`, `/admin/issues`, `/admin/maintenance`, `/admin`, `/admin/billing` |
| b20 | Providers & capability decisions | `/providers`, `/capabilities/decision` |
| b21 | Troubleshoot | `/troubleshoot` |
| b22 | Workflow templates | `/workflows/templates` |
| b23 | Help surface | `/help`, `/help/docs`, `/help/controls`, `/glossary` |

Deferred to lite/full edition capture (404 on personal):
`/studio`, `/admin/leads`, `/admin/mcp-servers`, `/shared-with-me`,
`/admin/agent-trust`, `/eval`, `/roi`, `/admin/api-keys`, `/admin/sso`,
`/admin/branding`, `/admin/commerce`, `/admin/payments`, `/directory`

## Production rules

- Order: all of Series A first (the "one stop"), then Series B by the
  frequency users get lost on each screen.
- Series B reuses the Ledger tenant where possible; niche surfaces
  (`/edge`, `/code-intel`) get their own mini-scenario.
- Enterprise-only routes (`/admin/mcp-servers`, SSO, billing) are flagged
  in-script and recorded against the full edition, not the personal tier.
