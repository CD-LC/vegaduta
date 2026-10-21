# Console guide

The console is the web app (`web/`) that tenant members and administrators use day to day: build agents
and workflows, connect channels, test, watch cost and quality, and administer the workspace. This guide
describes every signed-in route, what it is for, which permission it needs and which API sits behind it.
It describes layout in words; there are no screenshots.

Every statement here was checked against the code at the time of writing: the navigation blueprint
(`web/app/components/nav/navSections.ts`), the pages under `web/app/**/page.tsx`, the English labels
(`web/messages/en.json`) and the security rules (`core/security/.../*Rules.java`). Where a screen depends on
a deployment flag, the page says so.

## How to read the pages

Each route entry has the same shape:

- **Route** and the **sidebar label** (English).
- **Nav needs**: the rule the sidebar uses to show the row. `PERM_X` means the caller's effective permissions
  (from `GET /api/me`) must include `X`; a tenant-admin only sees what their own grants cover, because grants bind
  administrators too. This is a convenience: the page also guards itself and, more importantly, the backend
  enforces its own rule on every request.
- **Backend rule**: what `core/security` requires on the endpoints the page calls. Plain reads fall through to
  "any signed-in tenant member" unless stated.
- **Controls** and **API**: the main controls on the page and the endpoints behind them. Endpoint paths are listed
  without verbs where one control uses several; the full contract is in the [OpenAPI document](../api/README.md).

## The sidebar

The sidebar (and the mobile drawer and the command palette) is generated from one blueprint. Nine groups, in
order. A group appears only when at least one of its rows is visible to the caller; the Administration group
appears when the caller is a tenant-admin or holds any permission.

| Group | Page | Rows |
|---|---|---|
| (no header) | [Build](build.md) | Home |
| Build | [Build](build.md) | [Assistant](assistant.md), Agents, Connect, Workflows, Agent Directory, Memory, Skills, Skill library, Data Tables, Scheduled tasks, Coding tasks, Research, Studio, App Builder |
| Library | [Library](library.md) | Community Templates, Knowledge, Shared with me |
| Inbox & people | [Inbox and people](inbox-and-people.md) | Channels, Contacts, Inbox, Leads, Approvals, Input requests |
| AI & engine | [AI and engine](ai-and-engine.md) | Providers, Decision providers, Code Intel, Browser models, Local code |
| Configuration | [Configuration](configuration.md) | Integrations, Secrets, Data & Infrastructure, [Add-ons](add-ons.md) (personal and lite installs), Troubleshoot |
| Test & Monitor | [Test and monitor](test-and-monitor.md) | Playground, Agent quality, Turn inspector, Quick Task, Jobs, Insights, Cost & ROI, Usage, Files, Issues, Live activity, Audit log |
| Plans & billing | [Plans and billing](plans-and-billing.md) | Plans & Features, Billing |
| Help & resources | [Help and resources](help-and-resources.md) | Guide, Docs, Apps & Plugins |
| Administration | [Administration](administration.md) | Admin, Maintenance, Team access, API keys, Workspace integrations, MCP access, Guardrails, Instructions, Branding, Data & privacy, Single sign-on, Agent trust, Agent commerce, Agent payments |

Rows that appear only under a condition:

- **Home** is hidden for tenant-admins. Signed-in members land on `/build` when they open `/` (on a personal
  install, on `/assistant`); Home (`/home`) is the older dashboard, one click away.
- **Data Tables**, **Scheduled tasks**, **Coding tasks** and **Research** appear only when the deployment turned the
  feature on (`DATA_TABLES_ENABLED`, `SCHEDULED_TASKS_ENABLED`, `CODING_HARNESS_ENABLED`, `app.research.runs.enabled`;
  all default off, and Data Tables and Scheduled tasks default **on** in the `personal` profile). The sidebar asks
  the backend and hides the row when the answer is no, so there is no dead link. See
  [configuration](../configuration.md).
- **Plans & Features** and **Billing** are removed when billing is off (the default for self-hosted installs).
  They appear only with `NEXT_PUBLIC_BILLING_ENABLED=1` (Compose: `WEB_BILLING_ENABLED`). With billing off,
  `/billing`, `/features` and `/admin/billing` redirect (`/admin/billing` goes to `/usage`, which also holds
  the model-spend view).
- **Integrations** shows a count badge when MCP servers need attention; **Contacts** shows a count badge of
  senders waiting for approval.
- On a **personal install** (one person, local sign-in) the rail leaves out the screens a single-person install
  does not use: Shared with me, Agent Directory, Studio, Cost & ROI, Plans & Features, Leads, Agent trust, Agent
  commerce, Agent payments, Branding, MCP access, API keys and Single sign-on. Playground, Quick Task and Jobs are
  also left out there, because their routes lead to the [Assistant](assistant.md). The pages still open from a
  direct link (`web/app/lib/personalInstall.ts`).
- **Add-ons** is left out on a full install (`NEXT_PUBLIC_DEPLOY_ENV=full`), where every add-on already ships.
- **Code Intel** appears only when the build has a Code Intel address (`NEXT_PUBLIC_CODE_INTEL_URL`).

## Cross-cutting behaviour

- [Lists and paging](lists-and-paging.md): the one pager every list uses, and which lists page on the server.
- [Roles and permissions](roles-and-permissions.md): the 15 `PERM_*` permissions and the rows each unlocks.
- [Notifications](notifications.md): the bell and the full history page.
- [Shared controls and the UI preview](ui-components.md): Select, Checkbox, Combobox, ConfirmDialog, and the dev-only UI preview.

## Pages for the newer screens

| Screen | Where it is described |
|---|---|
| Cost & ROI and the pilot kit | [Cost and ROI](cost-and-roi.md) |
| Notifications | [Notifications](notifications.md) |
| Data & privacy, six tabs | [Data and privacy](data-and-privacy.md) |
| Single sign-on and SCIM | [Single sign-on and SCIM](single-sign-on.md) |
| Workflow History and Comments | [Build](build.md#workflow-designer-workflowsid) |
| Agent versions: rollback, diff, promote | [Build](build.md#agent-versions-section-of-the-workbench) |
| Agents index | [Build](build.md#agents-agents) |
| Improvements | [Test and monitor](test-and-monitor.md#agent-quality-agentsquality) |
| WhatsApp opt-in | [Inbox and people](inbox-and-people.md#whatsapp-opt-in-records) |
| Budget top-up | [Test and monitor](test-and-monitor.md#usage-usage) |
| MCP tool-pin status | [Configuration](configuration.md#mcp-tool-pin-status) |
| Share link status (expiry, reports, ended notice) | [Build](build.md#share-link-status-share-panel) |
| Turn inspector (new, needs the off-by-default turn journal flag) | [Test and monitor](test-and-monitor.md#turn-inspector-agentsturns) |
| Select, Checkbox, Combobox, ConfirmDialog; dev-only UI preview | [Shared controls and the UI preview](ui-components.md) |
| Platform operator console | [Platform admin](platform-admin.md) |

## Routes that are not in the sidebar

These exist and are signed-in routes (or redirects) but have no sidebar row:

| Route | What it is |
|---|---|
| `/` | Signed-out visitors see the public landing; signed-in members are sent to `/build`. |
| `/home` | The classic dashboard. Sidebar row "Home" (hidden for tenant-admins). |
| `/start` | The guided first-agent flow. Reached by link only (for example the dashboard "Start here" card for a member with no agents). |
| `/notifications` | Full notification history. Reached from the bell. See [Notifications](notifications.md). |
| `/agents/learning`, `/agents/templates`, `/agents/import` | Sub-pages of Agents. See [Build](build.md). |
| `/workflows/templates`, `/workflows/import`, `/workflows/{id}`, `/workflows/{id}/runs`, `/workflows/{id}/runs/{runId}` | Sub-pages of Workflows. |
| `/playground`, `/jobs`, `/quick-task` | On a personal install these redirect to [Assistant](assistant.md) (since 2026-10-18; `?agent=`/`?agentId=` and `?prompt=` deep links keep working). On other installs they are sidebar rows. `/quick-task/history`, `/research/{jobId}`, `/jobs/{agentId}/{jobId}`, `/jobs/all` (tenant-wide ledger) and `/jobs/schedules` are live pages everywhere. |
| `/tools/servers`, `/tools/credentials`, `/tools/code` | Sub-pages of Integrations. See [Configuration](configuration.md). |
| `/ai-models` | Per-feature preferred provider and model overrides. Linked from Providers. See [AI and engine](ai-and-engine.md). |
| `/clips` | Clip Studio. Linked from Studio and from a file's "Make a clip" action. See [Build](build.md). |
| `/build/{id}`, `/build/github` | One applied envoy in the App Builder; the connect-GitHub screen. |
| `/learn/tutor` | The personal tutor. Appears in the command palette; see [Help and resources](help-and-resources.md). The rest of `/learn` was removed in 0.1.8: lessons live in the separate Academy. |
| `/capabilities` | No page of its own; redirects to `/capabilities/decision`. |
| `/jev` | Old URL; redirects to `/capabilities/decision`. |
| `/devices` | Redirects to Channels with the Mobile SDK tab (`/admin/channels?channel=mobile-sdk`). |
| `/view-as` | Landing route for a read-only "view as" link opened by a platform operator. It reads a grant from the URL fragment, activates view-as for that browser tab only, and goes to the app home. |
| `/platform-admin/**` | The platform operator console. See [Platform admin](platform-admin.md). |

Public pages (marketing, pricing, `/docs`, `/downloads`, `/templates` gallery, `/directory`, `/plugins`, `/status`, `/try`,
shared agent and workflow links and others) are listed in `web/app/lib/publicRoutes.ts` and are covered in
[site modes and public pages](../site-modes.md), not here.
