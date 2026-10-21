# Build

The **Build** group is what you author: agents, workflows, memory, skills and the surfaces that generate
things. The unlabelled row above it is **Home**.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Home | `/home` | not shown to tenant-admins | any member |
| Assistant | `/assistant` | any member | signed-in; the picked agent's access rules apply |
| Agents | `/agents` | any member | create needs `PERM_MANAGE_AGENTS` or the delegated-user role; most edits need editor access on the agent |
| Connect | `/connect` | any member | signed-in; channel writes land on the Channels rules |
| Workflows | `/workflows` | any member | create, duplicate and graph generation need `PERM_MANAGE_AGENTS`; webhooks, schedules and import need `PERM_MANAGE_WORKFLOWS_ADMIN` |
| Agent Directory | `/directory` | any member (page is also public) | reads are public |
| Memory | `/memory` | any member | always the signed-in user's own data |
| Skills | `/skills` | any member | writes need `PERM_MANAGE_AGENTS` (or platform admin) |
| Skill library | `/skills/library` | any member | import/edit need `PERM_MANAGE_AGENTS`; approve is a review action |
| Data Tables | `/data-tables` | `PERM_MANAGE_WORKFLOWS_ADMIN` and the feature on | writes need `PERM_MANAGE_WORKFLOWS_ADMIN` |
| Scheduled tasks | `/scheduled` | any member, feature on | signed-in; owner rules in the service |
| Coding tasks | `/coding` | any member, feature on | signed-in |
| Research | `/research` | any member, feature on | signed-in |
| Studio | `/studio` | any member | signed-in |
| App Builder | `/build` | any member | signed-in |

## Home (`/home`)

The classic dashboard, kept one click away. When a signed-in member opens `/` they are sent to `/build` (the
Build Studio), or to `/assistant` on a personal install, not here. The row is hidden for tenant-admins.

## Assistant (`/assistant`)

The single conversation surface — one composer, an agent picker and a mode switch (chat, background job,
quick task). On a personal install it absorbed the Playground, Jobs and Quick Task launchers on 2026-10-18; their
routes redirect here, while other installs keep those pages in [Test and monitor](test-and-monitor.md).
Threads, attachments, voice calls, the work rail for background runs, the request X-ray and the per-browser
reasoning and reply-voice toggles are covered in [Assistant](assistant.md).

## Connect (`/connect`)

A hub of connect cards for the quickest first integrations — Telegram, WhatsApp, email, Google, Slack, SMS and
knowledge — each showing its live status from `/api/channels/status` and linking to the matching channel or
integration screen. Cards an install cannot use yet are still shown, marked so the first win is obvious.

## Agents (`/agents`)

The page has two modes, chosen by the URL.

**Index (no query).** A table of every agent in the workspace with a search box (name or model), a provider filter,
and the columns Name, Model, Updated (hidden on narrow screens) and Actions. Row actions: **Open**, **Duplicate**,
**Export** (downloads the agent as an [agent file](../agent-bundles.md)) and **Delete** (delete asks for confirmation
and cannot be undone). **New agent** opens the workbench on a fresh
draft. The list comes from `GET /api/agents`, which returns the full tenant list, so the pager is client-side
([Lists and paging](lists-and-paging.md)). The header buttons are **Quality** (`/agents/quality`),
**Learning** (`/agents/learning`), **Templates** (`/agents/templates`), **Agent files** (`/agents/bundles`, ready-made
agents and agent-file import, see [Share an agent as a file](../agent-bundles.md)) and **Import** (`/agents/import`);
with an agent selected, Quality and Learning open on that agent.

**Workbench (`?agentId=`, `?new=1`, `?template=`, `?lab=open`).** A two-pane build-and-test screen for one agent.
A back button, **All agents**, returns to the index. The sections the workbench shows include the persona and
system prompt, model and generation settings (max tokens, temperature, top-p, reasoning depth, fallback and cost
routing), reliability and performance, knowledge collections, tools and MCP servers, capabilities, sharing and
**Versions**. A setup hint above it links to Providers, Integrations, Knowledge, Edge, Channels and Workflows.

- API: `GET/POST /api/agents`, `/api/agents/{id}` and the per-field `PATCH` routes under it (`/prompt`, `/model`,
  `/guardrails`, `/tool-access`, `/knowledge-base`, ...), `POST /api/agents/{id}/duplicate`. Sharing uses
  `/api/agents/{id}/share` and the grants routes.
- Some guardrail-class edits need `PERM_MANAGE_GUARDRAILS` (for example `PATCH /api/agents/{id}/guardrails`,
  hooks, tool policies and accepting an improvement), see [Roles and permissions](roles-and-permissions.md).

### Share link status (Share panel)

The workbench Share panel (and the workflow Share dialog) shows the state of the public link. While it is active
the panel lists the link, **when it expires** ("Link expires ..." or a no-expiry line for an older link), the
number of viewers so far and, once there is at least one, the abuse reports against the limit ("N abuse report(s).
The link is revoked automatically at 3."). The status call is `GET /api/agents/{id}/share` (workflow:
`GET /api/workflows/{id}/share`); it returns `active`, `shareUrl`, `viewerCount`, `reportCount`, `expiresAt` and,
for an inactive link that once existed, `revokedReason` (`ABUSE_REPORTS`, `OWNER_REVOKED` or `EXPIRED`) and
`revokedAt`. The backend revokes a link automatically on the third abuse report.

When a link that was active in this browser session stops being active, a notice explains why: expired, "may have
been revoked automatically after abuse reports", or simply "no longer active". The panel infers this from the last
active status it saw (and re-reads the status when the tab regains focus); it does not read `revokedReason` from
the server, and a revoke you do by hand is not reported as automatic. Still open, per
[UI gaps](../engineering/UI-GAPS-OPEN.md): the abuse-report reasons are not shown to the owner, and there is no
admin page listing a tenant's public links.

### Agent versions (section of the workbench)

Configuration snapshots are written by the server on every save; there is deliberately no "save snapshot" button.
The **Versions** card lists them newest first and offers:

- **Expand** a version to read the configuration it holds.
- **Compare**: opens a dialog with a field-by-field diff (field, before, after). Compare and Promote diff against the
  current Production version; Roll back diffs against the version just below.
- **Label** a version Staging or Production, or **clear** its label. A label is a pointer ("we consider this one
  good"); it does not deploy anything.
- **Promote** a version to Production. Review the diff, add an optional reason, confirm. If the workspace configured
  an eval promotion gate (see Agent quality, Datasets and gate) and no qualifying run exists, the server refuses with
  HTTP 412 and the dialog shows the gate report. A caller with `PERM_MANAGE_AGENTS` can then choose **Force promote**
  with a required reason, which is written to the audit log.
- **Roll back** Production to the previous version. This is a label move: it creates no snapshot and leaves the
  draft untouched.
- **Restore** a version: the agent's current configuration is replaced by that version's. The restore is itself saved
  as a new version, so it can be undone. Restore is the only action that changes what the live agent runs.

API: `GET /api/agents/{id}/versions`, `GET .../versions/{versionId}`, `GET .../versions/diff?from=&to=`,
`POST .../versions/{versionId}/promote`, `POST .../versions/rollback`, `POST .../versions/{versionId}/restore`,
`PUT .../versions/{versionId}/label`, `POST .../versions`. The older prompt-only history stays at
`/api/agents/{id}/prompt/versions`.

### Agent learning (`/agents/learning`)

A read-and-observe screen for the reinforcement-learning loop, deep-linked with `?agentId=`: reward trend, arms
table and policy controls. API: `GET /api/rl/agents/{id}/summary`, `/policies`, `/policies/{dimension}/arms`,
`/export/trajectories`. Policy, preference and export routes need `PERM_MANAGE_AGENTS`.

### Agent templates (`/agents/templates`) and import (`/agents/import`)

**Templates** is a browse-only gallery (starter templates for common jobs and a second gallery). Picking one goes
back to `/agents?template=<key>` and pre-fills the create form; nothing is created until you name it and submit.
**Import** takes a pasted or uploaded export from OpenAI Assistants, OpenAI Agent Builder or Flowise, shows a
preview of exactly what converts, then creates an unpublished draft agent. The export is kept only in page state,
never put in a URL or browser storage, and credentials are never imported. A 404 from the server means import is
switched off and the page says so calmly. API: `POST /api/agents/import/preview`, `POST /api/agents/import`
(`PERM_MANAGE_AGENTS`).

## Workflows (`/workflows`)

A searchable, client-paged table of workflows: name and description, status, structure (node and connection
counts), updated, and actions (edit, copy link, duplicate, export, delete with confirmation). Stat tiles show total
workflows, active workflows and capacity against the plan limit; at the limit the create button is replaced by an
upgrade prompt. **New workflow** reveals an inline form (name, description). Links lead to **Templates**
(`/workflows/templates`) and **Import** (`/workflows/import`).

- **Templates** is browse-only like the agent gallery; picking one returns to `/workflows?template=<key>` with a real
  starter graph (trigger, agent step, output) ready to create.
- **Import** accepts a VegaDuta export, an n8n export or a Dify app DSL, previews the fidelity of the conversion
  (validation issues and the agents, knowledge bases and credentials it refers to), then creates an unpublished
  draft. Same privacy posture as agent import. API: `POST /api/workflows/import/preview`, `POST /api/workflows/import`
  (`PERM_MANAGE_WORKFLOWS_ADMIN`).
- API for the list: `GET/POST /api/workflows`, `DELETE /api/workflows/{id}`, `POST /api/workflows/{id}/duplicate`,
  `GET /api/workflows/{id}/export`.

### Workflow designer (`/workflows/{id}`)

A dedicated full-height canvas for one workflow. The top bar carries the editing actions, a **Version history**
button and a **Comments** toggle.

**Version history.** A drawer that lists every saved version of the workflow, with a search box and the shared
pager (client-side). It offers:

- **Save version** with an optional change note. If the canvas has unsaved edits the drawer warns that they are not
  part of the new version. If nothing changed since the latest version, no new version is created and it says so.
- **Compare** two versions (the existing diff panel; a diff row can focus the node on the canvas).
- **Restore** a version to the draft. This replaces the draft graph; unsaved canvas edits are lost, and the page
  reloads so the canvas re-reads the graph.
- **Promote** a version to **Staging** or **Production**. The graph is not changed; runs resolve the chosen label
  when they next start.
- **Roll back Production** to the previous version (a label move, never a new snapshot; HTTP 409 when there is no
  earlier version).

Reads need viewer access to the workflow; save, restore, promote and rollback need editor access. If the server has
no version routes the drawer says history is not available. API: `GET /api/workflows/{id}/versions`,
`GET .../versions/{n}`, `GET .../versions/diff`, `POST .../versions`, `POST .../versions/{n}/restore`,
`POST .../versions/{n}/promote`, `POST .../versions/rollback`.

**Comments.** A comment layer over the canvas. Toggle **Comments** to show it; click to place a pin on a node or on
an empty spot of the canvas, write a thread, reply, mention teammates, resolve and reopen. A **Threads** list opens
a side panel, and clicking a thread pans to its node. Viewers can read and write comments; authors edit and delete
their own; the thread author or an editor resolves. A body is at most 4000 characters with at most 20 mentions. A
mentioned user receives a [notification](notifications.md). The layer feature-detects: against a core without the
route nothing renders. API: `GET/POST /api/workflows/{id}/comments`, `PATCH/DELETE /api/workflows/{id}/comments/{commentId}`.

### Runs (`/workflows/{id}/runs`, `/workflows/{id}/runs/{runId}`)

The run history for one workflow: a list with status badges and server paging (`limit`/`offset`, with
`X-Total-Count`), each row linking to the run trace. The trace page shows a run summary and a fully expanded
step-by-step record (what every agent, tool and code node did) with a Steps view and a Raw trace view. API:
`GET /api/workflows/{id}/runs`, `GET /api/workflows/{id}/runs/{runId}`, plus pause, resume, cancel and download.

## Agent Directory (`/directory`)

The public directory of agents that tenants chose to publish, each with a signed capability manifest (an A2A card).
It is a public page, so it also renders for signed-out visitors. Publishing and unpublishing is done on
[Agent trust](administration.md#agent-trust-adminagent-trust). API: `GET /api/directory/agents` (public, `limit` at
most 100; see [list endpoint paging](../api/LIST-ENDPOINT-PAGING.md) for `offset`).

## Memory (`/memory`)

What the assistant remembers about the signed-in user, editable in one place, always scoped to that user. Tabs:

- **Your notes**: short notes (persona, profile, project notes) placed in conversations with agents that have
  shared memory on. Hidden when the backend has blocks off.
- **Assistants**: the per-agent switch for reading your notes. Notes do nothing until at least one agent has it on.
- **Learned facts**: facts agents saved or you added; search, filter, edit, delete and ask where each came from.
- **Import**: bring in memory from another assistant and keep old chats as read-only records; hidden when core
  answers 404.
- **Export & erase**: download everything stored about you, or erase all of it. Hidden when self-service is off.

API: `/api/memory`, `/api/memory/blocks/{label}`, `/api/memory/facts`, `/api/memory/import`, `/api/memory/export`,
`/api/chat-import/**`.

## Skills (`/skills`)

Packaged expert instructions (a `SKILL.md` plus optional files) that agents load on demand. Built-in skills ship with
the platform; custom ones belong to the workspace. Everyone can browse; creating, editing, importing, enabling and
deleting need `PERM_MANAGE_AGENTS` (or platform admin), enforced by the server and mirrored in the page. The panel
supports import (`POST /api/skills/import`), install from a registry (`/api/skills/registry/search`,
`/registry/install`), export, enable or disable, and a pre-approval security scan preview
(`/api/skill-security/preview/*`, `/api/skill-security/skills/{name}/approve`) because a custom skill is third-party
text that lands in an agent's prompt.

## Skill library (`/skills/library`)

The review surface for skills with a lifecycle: a skill is pending, active, rejected or archived, and imported,
learned or authored — only **active** skills reach an agent. The library holds approve, reject, archive, restore,
edit (editing an active skill saves a new version; 20 are kept), per-agent enable, export and delete, plus usage
counts (loads, outcomes, a stale flag after 90 days unused). On a personal install this is also where **learned**
skills land: drafts the agent wrote from its own successful turns, pending until a human approves. The learning
loop and its limits are in [Personal mode](../personal-mode.md#skills). API: `/api/skills/**`
(`/api/skills?status=…`, `/api/skills/{id}/approve`, `/archive`, `/agents`, `/improvement`).

## Data Tables (`/data-tables`)

Spreadsheet-like tables that workflows (the Data Table node) and agents (`data_table_query`, `data_table_write`) use
to keep state between runs. Off by default (`DATA_TABLES_ENABLED`); when off, the API answers 404 except `GET
/api/data-tables/config` and the page says so. A free-plan tenant gets tighter built-in caps unless free-plan use is
turned off, which makes it a Premium feature. The grid supports columns, rows, CSV import and export, and a client
pager. API: `/api/data-tables/**`.

## Scheduled tasks (`/scheduled`)

Prompts an agent runs by itself on a schedule ("every weekday at 8am summarise my unread email"), created here or by
asking an agent in chat (the `schedule_task` tool, which always asks for confirmation). Off by default
(`SCHEDULED_TASKS_ENABLED`). Each firing is a normal agent job, so it inherits the per-user budget and kill switch;
a tool call needing confirmation parks in the approvals inbox and fails closed. Controls: create, edit, pause,
resume, run now, delete, and a runs list per task (server-paged). API: `/api/scheduled-tasks/**`.

## Coding tasks (`/coding`)

An agent's model edits a repository in an isolated sandbox until the tests pass; a person then reviews the diff and
approves or rejects it (the approval is unconditional). Off by default (`CODING_HARNESS_ENABLED`); with it off the
nav row is hidden and every route but `/capabilities` answers 404. Controls: start a task, then approve, reject,
cancel or roll back. API: `/api/coding-tasks/**` (task state is kept in Redis; the list is the newest 20 and is not
paged).

## Research (`/research`)

Ask a question and get a report with quoted, linked sources. A run is a background job on the signed-in user's own
research agent, so it keeps going after the tab closes; the history below is that user's alone and uses the shared
pager. Availability is server-driven: the page shows exactly what `GET /api/research/status` says (not available,
switched off, waiting for the platform search key, or ready). The sidebar row appears only when research runs are
on (`app.research.runs.enabled`, default off; a personal install also needs web search). `/research/{jobId}` shows live progress and then the
cited report. API: `/api/research/runs`, `/api/research/status`.

## Studio (`/studio`) and Clip Studio (`/clips`)

The page is titled "What your agents can make". It has a gallery, **Made by your agents**, of images the workspace's
agents generated (each tile can be downloaded, reused for a variation or turned into a clip, and a link opens all of
them in Files); a **Clip Studio** card (free, rendered on the device); an **Image generation** card that states the
plan allowance and links to the Playground and Files; and a **Start an image** form (idea, look, shape) that hands
the prompt to the Playground. Clip Studio (`/clips`, optionally `?imageId=`) turns a still image into a short motion
clip in the browser. API: `GET /api/imagegen/images`.

## App Builder (`/build`)

"Say it, watch it build." A guided flow: pick a template and describe the app, create a project, keep chatting to
change it (each turn is one generate call), start a live preview and share the preview URL. The default **Guided
build** tab talks only to core and works everywhere; the **App Builder** tab needs the separate app-builder service
and shows an honest not-available state without it. There is no publish-to-production step on the backend, and the
page does not pretend there is one. `/build/{id}` shows one applied envoy (graph, needs, proof ledger, trust ladder,
rehearsal) and `/build/github` is the connect-GitHub screen. API: `/api/briefs/**`, `/api/studio-projects/**`,
`/api/studio-apps/**`. Several of the briefs writes need `PERM_MANAGE_AGENTS`.
