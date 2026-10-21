# Authorization model

How a request to core is allowed or refused, from the token to the row. The
handbook page [Security Architecture](../platform-documentation/security/security-architecture.html)
describes the same mechanisms with code evidence; this page is the model.

## Identity

Every console or API call carries a Keycloak access token (JWT). Core validates
the signature, the issuer set (primary issuer plus `KEYCLOAK_ADDITIONAL_ISSUERS`)
and the authorized party (`azp` allow-list), then copies the token's `tenant_id`
claim into the request's `TenantContext`. Every database query of the request
runs in that tenant's Postgres schema. A token without a tenant is refused,
except for `platform-admin` and users still in onboarding.

That is `app.auth.mode=keycloak`, the default. A personal install runs with
`app.auth.mode=local` (`APP_AUTH_MODE`, set by the `personal` profile): core
then issues and validates its own tokens for a single owner, with no Keycloak
(`core/security/.../localauth/LocalAuth.java`, sign-in under
`/api/auth/local/*`). Any other value stops the boot.

Non-human callers have their own credentials: MCP grant keys (`/mcp/**`,
`/api/dev/**`), device tokens, share-link visitor tokens, channel webhook
signatures and delegation tokens (which act as the delegating user).

## Roles

Keycloak realm roles become `ROLE_*` authorities:

| Role | Who | Effect |
|---|---|---|
| `platform-admin` | the operator | cross-tenant administration (the `/platform-admin` console section); tenant-less tokens allowed |
| `tenant-admin` | a tenant's administrators | seeds every permission below; owner-or-admin everywhere |
| `agent-builder` | people who build agents | seeds `MANAGE_WORKFLOWS_ADMIN`, `MANAGE_INTEGRATIONS`, `MANAGE_AGENTS`, `MANAGE_TOOLS` |
| `delegated-user`, `end-customer` | members, external customers | no permissions by default; `end-customer` is also blocked from every write by `EndCustomerWriteGuardFilter` |

## Permissions (`PERM_*`)

Fine-grained permissions are stored per user (`UserPermissionService`), seeded
from the role bundles above on first use, and turned into `PERM_<NAME>`
authorities on every request (`GranularPermissionConverter`). The catalogue is
`core/admin/.../permission/Permission.java`; each constant's javadoc lists the
routes it gates. In short:

| Permission | Gates |
|---|---|
| `MANAGE_USERS` | user administration, `/api/admin/**`, tenant REST API keys (`/api/tenant/api-keys/**`) |
| `MANAGE_BILLING` | plan, invoices, billing profile, subscription, commerce catalog writes, payment checkout |
| `MANAGE_INTEGRATIONS` | MCP registry and grants, provider keys, SDLC credentials, knowledge connectors, OCR credential |
| `MANAGE_TENANT_SETTINGS` | tenant settings, branding, model preferences, tenant SSO and SCIM tokens (`/api/tenant/sso/**`, reads too) |
| `MANAGE_WORKFLOWS_ADMIN` | workflow webhooks, schedules, trigger channels |
| `VIEW_AUDIT_LOG` | the audit trail |
| `MANAGE_APPROVALS` | approval policy, tool-call approvals |
| `MANAGE_DATA_GOVERNANCE` | data export, deletion, usage, and the DPDP-oriented controls (`/api/tenant/privacy/**`) |
| `MANAGE_CHANNELS` | every channel credential and routing write |
| `MANAGE_AGENTS` | agent schedules and triggers, agent-learning policies, knowledge-base authoring (ingest, upload, re-embed, delete, collections, RAG eval, face enrollments) |
| `MANAGE_GUARDRAILS` | tenant guardrail rules |
| `MANAGE_TOOLS` | custom code tools and OpenAPI tool sources |
| `MANAGE_TEMPLATES` | the tenant's own community templates |
| `VIEW_USAGE` | LLM usage and spend reports |
| `MANAGE_LEADS` | the Leads CRM (reads too: leads are PII) |

Rule of thumb: **writes are gated, reads are open to members** unless the data
is personal (leads, audit log, usage) or belongs to one user.

## Route rules

`SecurityConfig` holds an ordered `authorizeHttpRequests` list split across
`PublicSurfaceRules`, `AdminIntegrationRules`, `ToolChannelWorkflowRules` and
`TenantFeatureRules`. Anything not matched falls to
`anyRequest().authenticated()`, so a new mutating endpoint is **open to every
member of the tenant** until it gets a rule. Three tests keep that honest:

- `AuthorizationCoverageTest` scans every `@Controller` (including those behind
  `@Profile` or a property flag) and fails for any `POST`/`PUT`/`PATCH`/`DELETE`
  endpoint without a rule, without `@PreAuthorize`, and without a reviewed line
  in `core/app/src/test/resources/security/authorization-coverage-allowlist.txt`
  (`HANDLER`: the handler checks access; `MEMBER`: meant for any member; `FIX`:
  a known gap, and the count may only go down).
- `SecurityRuleSnapshotTest` pins the exact rule list; a change must be
  regenerated with `-Dauthz.snapshot.write=true` and reviewed in the diff.
- `scripts/ci/entity-name-lint.sh` refuses two controllers on one base path
  unless allow-listed, so a rule cannot silently cover the wrong handler.

## Resource grants (`PermissionLevel`)

Agents and workflows are owned by whoever created them and can be shared with
other members of the tenant (`/api/{agents|workflows}/{id}/grants`). A grant
carries one level, in increasing order:

| Level | Allows |
|---|---|
| `RUNNER` | execute / chat, no view of the configuration (prompt, tools, model, graph) |
| `VIEWER` | `RUNNER` plus read the full configuration |
| `EDITOR` | `VIEWER` plus change it |

The enum's declaration order is load-bearing (`ResourceAccessService` compares
with `compareTo`). The owner and a `tenant-admin`/`platform-admin` always have
`EDITOR` or more. Handlers call `agentService.requireAccess(id, level)` /
`workflowService.requireAccess(id, level)`; a miss is a 404 (never a 403), so a
caller cannot enumerate ids they are not allowed to see.

## Conversation ownership

A chat session id is chosen by the client and is not a secret. Session memory is
keyed by a server-derived id, `c1:sha256(tenant, agent, subject, clientSessionId)`,
and the first turn records an owner (`tenant + subject`). Transcript reload,
export, rewind, branching and the LLM-request inspector resolve the caller's own
scoped id and check that owner record; another user with the same client id
reads nothing and gets 404. Details:
[Security Architecture §4.1](../platform-documentation/security/security-architecture.html).

## Adding an endpoint

1. Prefer a rule in the matching `*Rules` class (or `@PreAuthorize`) over an
   allowlist line.
2. If the handler itself decides (owner, grant level, caller's own data), add a
   `HANDLER` or `MEMBER` line to the allowlist with the class and check that
   enforces it.
3. Run `mvn -pl app -am test -Dtest=AuthorizationCoverageTest,SecurityRuleSnapshotTest`
   and regenerate the snapshot if you added a rule.

## In the console

The console hides or disables controls the caller's permissions do not cover (the navigation
blueprint in `web/app/components/nav/navSections.ts` carries a `perm` per row, and pages such as
`/admin/data-privacy` guard on the same scope the backend requires). That is a convenience, not a
control: the rules above are enforced on the server. See the [console guide](../console/README.md).
