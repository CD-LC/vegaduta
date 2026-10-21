# Configuration

The **Configuration** group is where agents get their tools and secrets, plus a transparency page about how the
platform's infrastructure handles your data.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Integrations | `/tools` | any member (count badge when servers need attention) | registering, connecting, patching or deleting an MCP server, OAuth connections and approving a tool-list change need `PERM_MANAGE_INTEGRATIONS`; custom code and OpenAPI tool sources need `PERM_MANAGE_TOOLS` |
| Secrets | `/secrets` | any member | members store user-scoped secrets; tenant-scoped writes need the tenant-admin role |
| Data & Infrastructure | `/data-usage` | any member | `GET /api/tenant/data-usage` needs `PERM_MANAGE_DATA_GOVERNANCE` |
| Add-ons | `/add-ons` | any member on a personal or lite install (hidden on a full install) | reading needs sign-in; changing needs tenant-admin on a personal install, platform-admin otherwise. See [Add-ons](add-ons.md) |
| Troubleshoot | `/troubleshoot` | any member | local-model checks are signed-in; the local-model part shows on a personal install only |

## Integrations (`/tools`)

Titled "Tools & MCP". The page fetches the server list, the curated catalog and the Google connection once, and the
top bar has the search box and the "add MCP server" and OpenAPI import buttons. The body is a set of tabs:

- **Servers**: a grid of MCP servers with an inspector for the selected one (connect, test, trust, enable or
  disable, tool cards). Above the grid sits the [tool-pin review](#mcp-tool-pin-status) and the pinning mode strip.
- **Connect apps** (OAuth connections): one-click OAuth connections (Google, GitHub, Slack, Notion, Linear, HubSpot,
  Microsoft, Asana, Zoom). This tab exists only when the deployment turned provider OAuth on
  (`GET /api/mcp/oauth/providers` reports `enabled`), or briefly after returning from a consent screen.
- **Classification**: the risk classification of every tool an agent can call (trust, sensitivity, determinism),
  with `unclassified` shown as the blocked state it is. `GET /api/tools/classifications`.
- **Custom Code**: per-user tool scripts (`/api/tools/scripts`).
- **OpenAPI Sources**: import an OpenAPI spec and every operation becomes a tool (`/api/tools/openapi`); preview,
  refresh, enable or disable the source or one operation, and test a call.

API: `/api/mcp/registry`, `/api/mcp/registrations`, `/api/mcp/oauth/**`, `/api/mcp/tool-pins`,
`/api/mcp/registry/catalog`. See [registering a custom MCP server](../core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md).

### Integrations sub-pages

- **All enabled MCP servers** (`/tools/servers`): a flat list of every enabled MCP server for "which of my servers
  need attention right now", with Test, Trust-all-actions and Enable or Disable per row plus a page-level bulk trust,
  and the tool-pin badge on each row. It uses the shared pager.
- **MCP server credentials** (`/tools/credentials`): the credentials (API keys, tokens, database URLs) for every
  configured MCP server, shown masked, with links to credential guides and an Edit dialog.
- **Custom code tools** (`/tools/code`): write, validate, test and enable code tools under the full page width.
  Writes need `PERM_MANAGE_TOOLS` (the stateless generate and validate pair needs the tenant-admin or agent-builder
  role). API: `/api/tools/code/**`.

### MCP tool-pin status

A remote MCP server can change a tool's description or schema after an admin reviewed it. The backend records a hash
of each server's tool list ("pin") and flags a difference. The console shows it in three places:

- A **badge** per server (the inspector, and each row of `/tools/servers`) with one of five states: **Pinning off**,
  **Pinned** (the tool list matches the list an admin approved), **Changed** (the list changed since approval;
  agents still use it, review and approve), **Awaiting approval** (the list changed and agents cannot use the server
  until it is approved) and **Not pinned yet**. If the status call fails the badge says **Pin unknown**, never
  "pinned".
- A **mode strip**, "Tool pinning", with the active mode (Off, Warn or Enforce) and a one-line consequence ("All pins
  are current" or "N server(s) changed their tool list and need review").
- A **review card**, "Tool definitions changed - review before trusting", listing each changed server with the tools
  that changed, were added or were removed and an **Approve** button that accepts the new list as the baseline.
  It renders nothing when nothing changed, pinning is off or the endpoint is unavailable.

The mode is a deployment setting, `app.mcp.tool-pinning.mode` (`APP_MCP_TOOL_PINNING_MODE`): `off` records nothing,
`warn` (the default) records, audits (`mcp.tools.changed`) and reports but agents keep using the server, `enforce`
also withholds a changed server's tools until an admin approves. See [configuration](../configuration.md) and
[MCP specification alignment](../architecture/mcp-spec-alignment.md). API: `GET /api/mcp/tool-pins` (changed
servers), `GET /api/mcp/tool-pins/{id}`, `POST /api/mcp/tool-pins/{id}/approve`. Reads are open to any member;
approving needs `PERM_MANAGE_INTEGRATIONS`.

## Secrets (`/secrets`)

The tenant secrets vault. A vault entry is what an integration authenticates with. The form creates or rotates a
secret (a store is a create-or-rotate on the server, so the row that comes back can differ from what was sent: id
kept on rotation, preview recomputed). Each secret has a **scope**: `USER` (any member) or `TENANT` (the form forces
USER for non-admins). A secret can carry **allowed hosts**, one per line, that restrict where it may be sent. Delete
removes a secret; **purge** removes it permanently. API: `/api/secrets`, `/api/secrets/{id}/allowed-hosts`,
`DELETE /api/secrets/{id}?purge=true`. See also the [consumer secrets vault](../architecture/CONSUMER-SECRETS-VAULT-2026-09-11.md).

## Data & Infrastructure (`/data-usage`)

A transparency page answering "how does our infrastructure use your data": one card per underlying system (for
example Postgres, MinIO, Redis, Langfuse, Kafka, Temporal and Keycloak), each with a description, an isolation fact
and a live statistic. The copy comes from the API response, so the page does not write its own claims. The sidebar
row is visible to every member, but the endpoint needs `PERM_MANAGE_DATA_GOVERNANCE`; without it the page shows an
inline error instead of the cards. API: `GET /api/tenant/data-usage`.

## Troubleshoot (`/troubleshoot`)

The owner's "something is wrong" panel for a personal install. It checks what the browser can see — **API
connection**, **Local model server**, **Model downloaded** and **Agent tool calls** (with a **Test tool calling**
button) — and **Re-run checks** runs them again. Below the checks is the **Model catalog** (recommended model plus
alternatives, download size, RAM fit, tool-call grade) with **Download** and **Use** buttons; **Use** switches the
agents to that model from the next turn, without a restart.

The page says which model agents use now ("Agents currently use …") and, when chats still cannot use it, why: the
platform model pool is switched off, its provider entry is disabled, or the entry was saved under an earlier
encryption key or its address is blocked. The fix it gives is to restart VegaDūta (`scripts/vegaduta restart` in the
install folder), which repairs the entry on start, or to fix the entry under Platform Admin → LLM & Providers.

A **Learn how this works** box links to the Academy guides when the install has an Academy address. What the page cannot see (Docker, host ports, the whole
app being down) it delegates to `scripts/vegaduta doctor --personal`. On lite and full installs the models belong to
the platform operator, so the page shows only a note to contact your admin. API: the local-model
endpoints behind [local models](../local-models.md).
