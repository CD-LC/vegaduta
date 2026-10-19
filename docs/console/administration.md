# Administration

The **Administration** group appears for a tenant-admin or for anyone holding at least one permission, and then lists
only the rows their grants cover (see [Roles and permissions](roles-and-permissions.md)).

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Admin | `/admin` | tenant-admin role | telemetry reads |
| Maintenance | `/admin/maintenance` | tenant-admin role | the member, approvals and data-governance rules of the panels it embeds |
| Team access | `/admin/access` | `PERM_MANAGE_USERS` | `/api/admin/users/**` needs `PERM_MANAGE_USERS` |
| API keys | `/admin/api-keys` | `PERM_MANAGE_USERS` | `/api/tenant/api-keys/**` needs `PERM_MANAGE_USERS` |
| Workspace integrations | `/admin/integrations` | `PERM_MANAGE_INTEGRATIONS` | Google connection and OAuth writes need `PERM_MANAGE_INTEGRATIONS` (and some `PERM_MANAGE_CHANNELS`) |
| MCP access | `/admin/mcp-servers` | `PERM_MANAGE_INTEGRATIONS` | `/api/mcpserver/grants/**` writes need `PERM_MANAGE_INTEGRATIONS` |
| Guardrails | `/admin/guardrails` | `PERM_MANAGE_GUARDRAILS` | rule writes need `PERM_MANAGE_GUARDRAILS`; reads are open |
| Branding | `/admin/branding` | `PERM_MANAGE_TENANT_SETTINGS` | `PUT /api/tenant/settings` and the logo routes need `PERM_MANAGE_TENANT_SETTINGS` |
| Data & privacy | `/admin/data-privacy` | `PERM_MANAGE_DATA_GOVERNANCE` | see [Data and privacy](data-and-privacy.md) |
| Single sign-on | `/admin/sso` | `PERM_MANAGE_TENANT_SETTINGS` | see [Single sign-on and SCIM](single-sign-on.md) |
| Agent trust | `/admin/agent-trust` | `PERM_MANAGE_AGENTS` | directory publish and unpublish need `PERM_MANAGE_AGENTS`; delegation grants and tokens need only a signed-in user and are bound to the caller |
| Agent commerce | `/admin/commerce` | `PERM_MANAGE_AGENTS` | wallet create and credit and x402 offers need `PERM_MANAGE_AGENTS`; commerce mandates are signed-in and bound to the buyer |
| Agent payments | `/admin/payments` | `PERM_MANAGE_AGENTS` | granting or revoking a mandate needs `PERM_MANAGE_BILLING`; reads and the preview are open |

## Admin (`/admin`)

The "Admin Control Panel": an overview for tenant-admins. When the workspace is new it shows a setup checklist
(activate an LLM provider, create your first agent, connect a channel, invite a teammate) and a "Create your first
agent" card with starter templates. Once set up it shows tiles for LLM providers, agents deployed against the plan,
seats used, approvals over 30 days with their outcome, channels live, workflows live, database pool, MCP registry, JVM
heap and the Kafka queue; a workspace pulse; **Pending approvals** with a review link; **Connected channels** with a
manage link; and a compact **Live audit tail** that opens [Live activity](test-and-monitor.md#live-activity-adminaudit-tail).
Telemetry that cannot be loaded says "unavailable" with the reason. API: `GET /api/admin/users`, `GET /api/approvals`,
`GET /api/audit-log`, `GET /api/system/status`, `GET /api/providers`, the channel credential status routes.

## Maintenance (`/admin/maintenance`)

"Platform Maintenance": live health for the backend, database, message queue and every connected messaging platform,
the same telemetry the dashboard hero reads, in one admin-only view, with a **Refresh** button. Tabs: **Overview**,
**Users** (the member panel), **Approvals** (the approvals panel) and **Data & privacy** (the workspace export and
deletion section; the scoped version of that section is [Data and privacy](data-and-privacy.md)). A note says LLM usage
and cost now live on Billing and usage. API: `GET /api/system/status`, the channel status routes.

## Team access (`/admin/access`)

The member list, a roles and permissions editor and the access matrix. Described in
[Roles and permissions](roles-and-permissions.md#team-access-adminaccess). API: `GET/POST /api/admin/users`,
`PUT /api/admin/users/{id}/enabled`, `/roles`, `/permissions`.

## API keys (`/admin/api-keys`)

Tenant REST API keys: machine credentials so CI, partner systems and scripts can call `/api/**` with
`Authorization: Bearer vdak_...` instead of an OIDC login. Issuing a key is permission administration, because a key
carries `PERM_*` scopes; the server validates both that each name is in the catalogue and that the issuer holds it, so a
key can never carry a scope its issuer lacks. The page has an **Issue a new key** form (a name and permission
checkboxes that mirror the catalogue), the key list with the shared pager, and per key **rotate**, **enable or
disable** and **revoke** (rotate and revoke ask for confirmation). The plaintext key is shown exactly once, in a
dialog, on create and on rotate. API: `GET/POST /api/tenant/api-keys`, `PATCH /api/tenant/api-keys/{id}`,
`POST /api/tenant/api-keys/{id}/rotate`, `DELETE /api/tenant/api-keys/{id}`.

## Workspace integrations (`/admin/integrations`)

The Google Workspace connection page. It connects or disconnects the workspace's Google account, shows the connection
state, and starts the YouTube-upload authorisation; the OAuth return lands with `?google=connected|error`. API:
`/api/channels/google/status`, `/api/channels/google/authorize-url`, `/api/channels/google/connection`,
`/api/channels/google/youtube-upload/authorize-url`. (Per-user Google connections are a separate flow.)

## MCP access (`/admin/mcp-servers`)

The page title is "Developer API". It manages **MCP grant keys**: API keys that let an external MCP client connect
into this workspace's `/mcp` endpoint and call curated agents, knowledge collections and workflows as tools. For
machine credentials to the REST API use API keys instead; a hint at the top points there and a card shows how callers can
authenticate to this deployment (grant keys, OAuth, allowed origins). Per key you choose the **Exposed agents**,
**Exposed knowledge collections** and **Exposed workflows**; a key with no agents selected can call none. Controls:
create, rotate (the old key stops immediately), edit exposure, enable or disable, revoke; the list uses the shared pager.
A reveal dialog shows the key once and a **Developer API quick start** with example REST calls for chat with an exposed
agent and for running an exposed workflow. API: `/api/mcpserver/grants`, `/api/mcpserver/grants/{id}`,
`/api/mcpserver/grants/{id}/rotate`, `/api/mcpserver/grants/auth-options`, `/api/mcpserver/exposure/{kind}/{resourceId}`.
A plan flag (`developer_api_allowed`) gates minting keys.

## Guardrails (`/admin/guardrails`)

Workspace guardrail rules, the console for the rules that the chat and tool-call path evaluates. A list of rules with
create, edit and delete (shared pager) and the workspace **content policy** for the LLM gateway. The page's reads are
open to everyone, so a member who follows a direct link sees what is being filtered but cannot edit. API:
`/api/guardrails/rules`, `PUT /api/tenant/llm-gateway/content-policy`, `PUT /api/tenant/settings`; writes need
`PERM_MANAGE_GUARDRAILS`. See [guardrails](../architecture/GUARDRAILS-AND-INTEGRATION.md).

## Branding (`/admin/branding`)

White-label branding: a **brand display name** and a **logo**. The logo is a real upload (not just a URL box), stored
and served by the platform; the name and logo show on the public share pages, the workflow share page and the
embeddable widget. API: `POST/DELETE /api/tenant/branding/logo`, `PUT /api/tenant/settings`.

## Data & privacy and Single sign-on

Both are described on their own pages: [Data and privacy](data-and-privacy.md) and
[Single sign-on and SCIM](single-sign-on.md).

## Agent trust (`/admin/agent-trust`)

"Agent trust & identity": delegation grants, signed manifests and public directory listing for each agent. Sections:

- **Delegation grants**: a **New delegation grant** wizard (with per-grant spend caps), a list per agent, **mint token**
  (an inline panel) and **revoke** (with confirmation). Grants and tokens act as the delegating user, so they need only
  a signed-in member and are bound to the caller, not to a permission.
- **Public directory & manifest**: generate the signed Know-Your-Agent manifest and publish or unpublish the agent in the
  public [Agent Directory](build.md#agent-directory-directory). Publishing needs `PERM_MANAGE_AGENTS`.

API: `/api/agents/{id}/delegations`, `.../delegations/{grantId}/revoke`, `.../delegations/{grantId}/tokens`,
`/api/agents/{id}/manifest`, `/api/agents/{id}/directory/publish`, `/unpublish`, `GET /api/agents/directory/mine`,
`GET /api/wallets?agentId=`.

## Agent commerce (`/admin/commerce`)

"Wallets, AP2 mandates and paid endpoints for each agent." Amounts are integer minor units end to end, and the page
labels them that way instead of pretending a currency conversion. Sections:

- **Agent wallets**: create a wallet, **credit** it, and read its ledger (paged; see
  [list endpoint paging](../api/LIST-ENDPOINT-PAGING.md)). Needs `PERM_MANAGE_AGENTS`.
- **AP2 mandates**: the intent to cart mandate chain. **New intent mandate** wizard, then create a cart, authorize,
  capture, refund, record events, revoke, with an event trail per mandate. Bound to the buyer.
- **x402 paid endpoints**: create a **New x402 offer** (slug, title, description, price in minor units with a currency, the resource payload, and optional EVM price, asset, network and pay-to fields) and disable it. Needs
  `PERM_MANAGE_AGENTS`.

API: `/api/wallets`, `/api/wallets/{id}/credit`, `/api/wallets/{id}/ledger`, `/api/commerce/mandates/**`,
`/api/agents/{id}/x402/offers`.

## Agent payments (`/admin/payments`)

"Signed UPI mandates, spend caps and payment history for each agent." Amounts cross the wire as integer paise; the page
converts only for display and input. Sections: **Payment mandates** (a **Grant a payment mandate** wizard with a
preview step, spend-versus-cap meters, revoke with confirmation) and **Transactions** (a paged table). Note that the
sidebar row follows `PERM_MANAGE_AGENTS`, but granting or revoking a mandate calls an endpoint that requires
`PERM_MANAGE_BILLING`, because a mandate lets an agent spend tenant money; a person with only `MANAGE_AGENTS` can read
but not sign one. API: `GET/POST /api/payments/mandates`, `POST /api/payments/mandates/preview`,
`POST /api/payments/mandates/{id}/revoke`, `GET /api/payments/mandates/{id}/spend`, `GET /api/payments/transactions`.
