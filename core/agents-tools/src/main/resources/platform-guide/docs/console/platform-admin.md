# Platform admin

The platform operator console, at `/platform-admin`. It is a separate section of the same web app with its own shell
and sidebar, for the operator of the deployment, not for tenants.

- **Who:** callers holding the Keycloak realm role `platform-admin`, a role granted by hand (nothing provisions it
  automatically; `./scripts/vegaduta make-admin you@example.com` does it, see [getting started](../getting-started.md)).
  Anyone else gets an access-restricted screen.
- **Backend rule:** everything under `/api/platform/**` requires `ROLE_platform-admin`, as do several other operator
  routes (for example `/api/admin/templates/**`). These tokens are allowed without a tenant. The routes read and write
  `control_plane` data and iterate every tenant's schema, which is why it is its own authority and not a tenant
  permission.
- **Billing:** only **Billing & Subscriptions** follows the billing flag (`NEXT_PUBLIC_BILLING_ENABLED=1`) and is hidden
  when billing is off; Budgets & Limits and Spend & Reports stay visible either way.

The sidebar groups and routes are generated from `web/app/platform-admin/components/AdminShell.tsx`. The one-line
descriptions below come from each page's own header and are what the page is for; several pages are read-only by design
and say so.

| Group | Label | Route | What it is for |
|---|---|---|---|
| (none) | Overview | `/platform-admin` | Headline tiles. A tile is "loading" until its fetch settles, then a measured value or "unavailable"; a failed fetch is never shown as 0. |
| Tenancy | Tenants & Users | `/platform-admin/tenants` | The cross-tenant roster with a plan-tier override, the per-plan limits editor and feature flags. |
| Tenancy | Platform Users | `/platform-admin/users` | The cross-tenant user directory: find, suspend or repair a user without opening the Keycloak console. Roles are the tenant-assignable set only; operators and the signed-in operator are never touchable. Server-paged. |
| Tenancy | Tenant Detail | `/platform-admin/tenant-detail` | Per-tenant control page. Suspend or reactivate is the reversible, audited emergency stop. Lists the tenant's users with a read-only "View as" entry point. |
| Tenancy | Demo Sandbox | `/platform-admin/demo-sandbox` | Controls and analytics for the anonymous "try it without registering" surface; the headline number is the conversion rate. |
| Tenancy | Demo Sandboxes (reclaim) | `/platform-admin/demo-sandboxes` | Reclaim the legacy per-visitor `/try` tenants. The shared demo tenant is protected. Irreversible and gated like a tenant delete. |
| Tenancy | Academy Learners | `/platform-admin/academy-learners` | Per-learner "unlock full course", independent of billing. |
| Models & Tools | LLM & Providers | `/platform-admin/llm-providers` | The hosted pool, per-task model routing, the model catalog and the translation job. |
| Models & Tools | Edge AI Models | `/platform-admin/edge-models` | Which on-device models consumers can see and download; edits the `edge.model-manifest` platform setting. |
| Models & Tools | IDE Plugins | `/platform-admin/plugins` | Publish first-party IDE and browser plugins (upload a built artifact; the server computes the checksum). Backs the public Apps & Plugins page. |
| Models & Tools | Voice & Tool Credentials | `/platform-admin/tool-credentials` | Operator-held keys for tool-capability providers that were previously BYOK only. |
| Models & Tools | MCP Catalog | `/platform-admin/mcp-catalog` | Read-only view of the curated MCP catalog every tenant sees. |
| Money | Budgets & Limits | `/platform-admin/budgets` | The per-principal dollar budget defaults; per-plan limits live on the tenants page. |
| Money | Spend & Reports | `/platform-admin/spend` | Platform-wide spend. Two figures side by side and never blended: all providers (including tenants' own keys; usage, not what the platform paid) and the hosted pool (the platform's own outlay). |
| Money | Billing & Subscriptions | `/platform-admin/billing` | Read-only billing and subscription lists; nothing here can charge, refund, cancel or change a plan. Some lists show the capped notice. |
| Trust & Safety | Safety & Moderation | `/platform-admin/safety` | Layered, human-reviewable content moderation; each control writes the platform settings the live guardrail engine reads. |
| Trust & Safety | Community Templates | `/platform-admin/templates` | The moderation queue: nothing a member submits is public until approved here. |
| Trust & Safety | MCP Review Queue | `/platform-admin/mcp-review` | Review queue for self-service stdio MCP registrations, which are refused a real connect or spawn until a human approves. |
| Trust & Safety | AI Content Reports | `/platform-admin/ai-reports` | Triage of in-app reports of offensive AI output (Google Play's AI-generated-content policy). Server-paged. |
| Trust & Safety | Abuse & Rate Control | `/platform-admin/abuse` | Rate limits, flood protection and circuit breakers. |
| Trust & Safety | Fraud & Anomaly | `/platform-admin/fraud` | The anomaly and abuse scoring layer: a 0 to 100 risk score per principal and tiered actions (watch, throttle, and higher). |
| Operations | Operations Hub | `/platform-admin/ops` | One screen for "is anything wrong right now"; cards link to the full page and say "unavailable" instead of showing a reassuring zero. |
| Operations | LLM Observability | `/platform-admin/observability` | Langfuse traces proxied by core, so no Langfuse key reaches the browser; shows a state, not an error, when Langfuse is not configured. |
| Operations | Maintenance & Banner | `/platform-admin/maintenance` | Maintenance mode (non-operator API calls answer 503 and the tenant app shows a maintenance screen) and the global announcement banner. |
| Operations | Scheduled Jobs | `/platform-admin/jobs` | Every `@Scheduled` job with its last run; failed jobs first; "Run now" only where the job is idempotent. |
| Operations | Schema Migrations | `/platform-admin/migrations` | Read-only Flyway state for the control plane and every tenant schema. |
| Operations | Email Suppressions | `/platform-admin/email-suppressions` | The outbound-email suppression list; removing an address is the fix for "I never got the verification email". |
| Operations | Skill Security Review | `/platform-admin/skill-security` | The skill supply-chain review queue: scan findings and an approve action that records the exact content hash reviewed. |
| Operations | Impersonation | `/platform-admin/impersonation` | Read-only "view as" sessions (30 minutes, every write refused, recorded in the tenant's audit log). |
| Operations | Fleet Health | `/platform-admin/fleet` | Every agent in every tenant, one row each, sorted so the ones needing an operator surface first; escalation is the per-tenant emergency stop. |
| Operations | Reliability & SLOs | `/platform-admin/reliability` | What is up and how well; a number the server cannot back with data is "n/a" with a reason, never 0. |
| Operations | Audit & Compliance | `/platform-admin/audit` | Cross-tenant audit search (bounded and honest about truncation), the live activity feed, the platform-wide kill switch and data residency. |
| Operations | Platform Settings | `/platform-admin/settings` | Every admin-editable `platform_setting` row, searchable; writes apply on the next read without a redeploy. |
| Operations | Diagnostics & Observability | `/platform-admin/diagnostics` | External-tool deep links (Grafana, Prometheus, Langfuse, Temporal) with reachability dots and more. |
| Growth | North Star | `/platform-admin/north-star` | The headline numbers; a cell with fewer than five contributing users is withheld by the server. |
| Growth | Landing Funnel | `/platform-admin/funnel` | The read-out for the landing-page beacon counters. |
| Growth | Newsletter | `/platform-admin/newsletter` | Write a subject and HTML body once and send to the selected tenants' admins, only those who opted in to news. |

The operator console reuses the shared pager and its capped notice for the lists that need them. The paging contract
for the platform lists is in [list endpoint paging](../api/LIST-ENDPOINT-PAGING.md).
