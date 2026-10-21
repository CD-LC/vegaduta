# Roles and permissions

What a person sees in the sidebar depends on their **permissions**. The model is in
[Authorization model](../security/authorization.md); this page lists the 15 permissions and the console rows each one
unlocks. The catalogue is the `Permission` enum in `core/admin/.../permission/Permission.java`; there is no way to define
a new permission from the console.

## How the console uses them

- The console asks `GET /api/me` for the caller's effective permissions. Each sidebar row may declare `perm`, one
  `PERM_*` scope, and shows only when the caller holds it. A row may instead be `adminOnly`, which means the tenant-admin
  **role** specifically; only `/admin` (Admin) and `/admin/maintenance` (Maintenance) are role-shaped like that.
- The **Administration** group appears when the caller is a tenant-admin or holds any permission, and then contains only
  the rows their grants cover, so a delegated "support lead" can administer channels without seeing any other admin
  screen.
- Scoped pages also guard themselves: a caller without the permission is redirected to `/` and the page renders
  nothing. This is a convenience only. The real enforcement is on the endpoints, in
  `core/security/.../*Rules.java`, which require the matching `PERM_<NAME>` authority.
- **Grants bind every caller, tenant-admins included.** The tenant-admin role seeds the full bundle the first time, but
  it is not an ongoing bypass: revoking a permission from an admin removes it. Team access (below) edits grants.

Role bundles seeded on first use:

| Role | Seeded permissions |
|---|---|
| `tenant-admin` | all 15 |
| `agent-builder` | `MANAGE_WORKFLOWS_ADMIN`, `MANAGE_INTEGRATIONS`, `MANAGE_AGENTS`, `MANAGE_TOOLS` |
| `delegated-user`, `end-customer` | none (`end-customer` is also blocked from every write) |
| `platform-admin` | not a tenant role: the platform operator, cross-tenant (see [Platform admin](platform-admin.md)) |

## The 15 permissions

| Permission | Sidebar rows it unlocks | Other things it gates (examples) |
|---|---|---|
| `MANAGE_USERS` | Team access, API keys | `/api/admin/users/**` (members, roles, permissions), `/api/tenant/api-keys/**` |
| `MANAGE_BILLING` | Billing | plan upgrade, invoices, billing profile, subscription cancel, payment checkout, commerce catalog writes, agent payment mandates, **all writes under `/api/tenant/roi/**`** (prices, value assumptions, baseline) |
| `MANAGE_INTEGRATIONS` | Workspace integrations, MCP access | MCP registry and OAuth writes, approving a changed MCP tool list, `POST /api/providers`, SDLC and OCR credentials, decision-capability credentials, MCP access grants and exposure |
| `MANAGE_TENANT_SETTINGS` | Instructions, Branding, Single sign-on | `PUT /api/tenant/settings`, branding logo, AI model preferences, every `/api/tenant/sso/**` call |
| `MANAGE_WORKFLOWS_ADMIN` | Data Tables (with the feature on) | workflow webhooks, schedules, poll and channel triggers, workflow import, Data Tables writes |
| `VIEW_AUDIT_LOG` | Turn inspector, Issues, Live activity, Audit log | `/api/audit-log/**`, `/api/admin/issues/**`, turn journals |
| `MANAGE_APPROVALS` | Approvals, Input requests | `PUT /api/approvals/policy/**` |
| `MANAGE_DATA_GOVERNANCE` | Data & privacy | `/api/tenant/privacy/**`, data export, data deletion, `GET /api/tenant/data-usage` |
| `MANAGE_CHANNELS` | Channels, Contacts, Inbox | every channel credential write, default-agent binding, sends, pairing, routing, contacts and groups, WhatsApp opt-in, device pairing, handoff actions |
| `MANAGE_AGENTS` | Agent trust, Agent commerce, Agent payments | agent create, duplicate and import, schedules and triggers, agent-learning policies, knowledge-base authoring, skills writes, template import, delegation, directory publish, x402 offers, briefs/App Builder writes |
| `MANAGE_GUARDRAILS` | Guardrails | tenant guardrail rules, procedures, per-agent guardrails, hooks and tool policies, accepting an agent improvement, the LLM gateway content policy |
| `MANAGE_TOOLS` | (none) | custom code tools, user tool scripts and OpenAPI tool sources |
| `MANAGE_TEMPLATES` | (none) | the workspace's own community templates (submit); gallery moderation is platform-admin only |
| `VIEW_USAGE` | Cost & ROI | `GET /api/tenant/roi/**`, `/api/admin/spend-reports/**`, `/api/admin/llm-usage/**` |
| `MANAGE_LEADS` | Leads | every verb under `/api/leads/**` (leads are personal data, so reads too) |

Not every page maps to a row. For example Insights is visible to everyone but its spend charts need `VIEW_USAGE` (a
403 shows the jobs section only), Data & Infrastructure is visible to everyone but its data needs
`MANAGE_DATA_GOVERNANCE`, and Agent quality is visible to everyone while its writes need `MANAGE_AGENTS`.

The two role-shaped rows are **Admin** (`/admin`) and **Maintenance** (`/admin/maintenance`), shown to the tenant-admin
role only. **Home** is hidden for tenant-admins.

## Rows with no permission

These rows are visible to every signed-in member (the page may still limit writes, see each page): Home, Assistant,
Agents, Connect, Workflows, Agent Directory, Memory, Skills, Skill library, Scheduled tasks, Coding tasks and
Research (when the feature is on), Studio, App Builder, Community Templates, Knowledge, Shared with me, Providers,
Decision providers, Code Intel, Browser models, Local code, Integrations, Secrets, Data & Infrastructure, Add-ons
(personal and lite installs), Troubleshoot, Playground, Quick Task and Jobs (not on a personal install), Agent
quality, Insights, Usage, Files, Plans & Features (billing on), Guide, Docs and Apps & Plugins. A personal install
also hides some of these; see [the sidebar](README.md#the-sidebar).

## Team access (`/admin/access`)

Needs `MANAGE_USERS` (the same scope the `/api/admin/users/**` endpoints need). It is the scoped user-administration
home: the member list with create, enable or disable, a roles and permissions editor, and the **access matrix**, a
users-by-permissions grid. The caller's own row is read-only. Full tenant-admins also have the same member panel on
[Maintenance](administration.md#maintenance-adminmaintenance).

The server only accepts permission names from the catalogue and rejects, rather than silently drops, unknown names;
and an API key can never carry a scope its issuer did not hold ([API keys](administration.md#api-keys-adminapi-keys)).
