# core/sharing

Cross-user resource sharing foundation: lets a user who created an Agent or
Workflow grant specific teammates access to it, on top of today's tenant-only
isolation. This module is the **foundational, shared piece** two other
workstreams build against:

1. Retrofitting `core/agents` + `core/workflow` to actually enforce this
   (adding `created_by` to `Agent`/`WorkflowDefinition`, populating it on
   create, and calling `ResourceAccessService#requireAccess` on every
   read/execute/edit path).
2. A web UI (share dialogs, "who has access" panels, "shared with me").

Read this file before building against this module - it documents every
public method's contract so neither workstream has to re-read this module's
source to use it correctly.

## Dependencies

`core/sharing` depends on `core/security` (`TenantContext`), `core/audit`
(`AuditLogService`), and `core/admin` (`KeycloakAdminClient` - same-tenant
grantee validation, the identical dependency `UserAdminController` already
has it for). It also declares `spring-boot-starter-data-redis` directly
(`ShareRateLimiter` - see "Rate limiting" below), same as `core/chat` did
for `LlmGatewayRateLimiterService`.

**It has zero dependency on `core/agents` or `core/workflow`.** Neither
`Agent` nor `WorkflowDefinition` has an owner column today - adding one is
explicitly the next workstream's job, not this module's. Every method that
needs to know "who owns this resource" takes `createdBy` as a plain
`String` parameter instead of looking it up itself.

## Data model

`resource_grant` table (`V147__resource_sharing.sql`, tenant-schema, no
`tenant_id` column - schema-per-tenant already scopes it):

| column | notes |
|---|---|
| `id` | PK |
| `resource_type` | `AGENT` \| `WORKFLOW` |
| `resource_id` | the Agent/Workflow's own id - no FK (cross-module, can't reference a table this module doesn't own) |
| `grantee_user_id` | Keycloak subject id, NOT an email |
| `permission_level` | `RUNNER` \| `VIEWER` \| `EDITOR` |
| `granted_by` | who created/last-updated this grant |
| `granted_at` / `revoked_at` / `revoked_by` | soft-revoke; a partial unique index on `(resource_type, resource_id, grantee_user_id) WHERE revoked_at IS NULL` allows re-granting after a revoke without a constraint violation, while guaranteeing at most one ACTIVE grant per pair |

## Enums

- `ResourceType { AGENT, WORKFLOW }`
- `PermissionLevel { RUNNER, VIEWER, EDITOR }` - **declaration order is
  load-bearing**, ordinal comparison (`compareTo`) decides whether a grant
  satisfies a required level. RUNNER = execute only, no config visibility.
  VIEWER = RUNNER + read-only config visibility. EDITOR = VIEWER + can
  modify config. Never reorder; insert new levels at the correct rank.

## `ResourceAccessService`

```java
AccessCheckResult requireAccess(ResourceType type, UUID resourceId, String createdBy, PermissionLevel required)
```
Throws `ResourceAccessDeniedException` (maps to **HTTP 404**, see below)
unless the caller (read off `TenantContext.current()`) has at least
`required` access. Check order and what it returns on success:

1. `createdBy == null` **and** `required` is `RUNNER` ->
   `AccessCheckResult.LEGACY_OPEN`. A legacy resource stays runnable by
   anyone; reading or editing its configuration falls through to steps 2-5.
2. caller's subject == `createdBy` -> `AccessCheckResult.OWNER`.
3. caller has `ROLE_tenant-admin` or `ROLE_platform-admin` -> `AccessCheckResult.ADMIN_OVERRIDE`.
4. an active grant for the caller on this resource, at or above `required` -> `AccessCheckResult.GRANTED`.
5. none of the above -> throws.

> **Changed 2026-07-29 - read this before trusting older docs.** Step 1 used
> to return `LEGACY_OPEN` for *any* `required`, before it even looked for a
> caller, on the argument that "no backfill, no breaking change" made the
> module safe to integrate. In practice `tenant/V151__agent_workflow_owner.sql`
> added `created_by` with no backfill, so **every** row was legacy: a live
> check found 38 agents, all 38 with `created_by IS NULL`, which made every
> `requireAccess` call in the product - RUNNER, VIEWER and EDITOR alike - a
> no-op. The feature shipped; nothing it gated was gated.
> `tenant/V155__backfill_agent_workflow_owner.sql` now backfills the rows
> whose owner is unambiguously resolvable (the tenant's earliest admin, via
> `control_plane.app_user`), and the rule above closes the hole for the rows
> it cannot - chiefly demo-sandbox schemas, which have no user identity at
> all. RUNNER stays open because the anonymous surfaces (demo sandbox, public
> share links, omnichannel webhooks, agent-to-agent delegation) all run under
> synthetic role-less subjects that can never satisfy an ownership check.

```java
AccessCheckResult requireOwnerAccess(String createdBy)
```
The strictest bar in the module: owner **or** tenant-admin/platform-admin
only - no grant level, not even `EDITOR`, satisfies it. Use it for actions
that hand the resource away rather than merely read or edit it. Today's one
caller is `AgentShareOwnerController`, which mints/inspects/revokes a public,
unauthenticated share URL; those three endpoints were previously gated on
`AgentService#get` (i.e. `VIEWER`), so exposing an agent to the entire
internet sat a level *below* the bar for handing one named colleague read
access. A legacy resource resolves to admin-only here, by design.

Call this from wherever `core/agents`/`core/workflow` already load the
entity (you'll have its `createdBy` in hand there). The returned
`AccessCheckResult` tells you *how* the caller qualified - useful if you
want to audit-log an `ADMIN_OVERRIDE` access specially (a tenant-admin
reaching a resource they don't own/have no grant on is worth a different
audit trail entry than an owner's own normal access).

```java
ResourceGrant grant(ResourceType type, UUID resourceId, String createdBy, String granteeUserId, PermissionLevel level)
```
Owner-only (or tenant-admin) - throws `ResourceAccessDeniedException` (404)
if the caller is neither. Takes a **Keycloak user id**, not an email -
resolving an email to an id, and confirming it belongs to the same tenant,
is the *caller's* job (`ShareController` does this via `KeycloakAdminClient`
before ever calling this method); this method independently re-validates
same-tenant membership as a defense-in-depth backstop and throws
`InvalidGranteeException` (**HTTP 403**) if that fails. Upserts: re-granting
an already-active `(resource, grantee)` pair updates the existing row's
level rather than erroring or duplicating. Audits
`"agent.share.grant"`/`"workflow.share.grant"`.

```java
void revoke(ResourceType type, UUID resourceId, String createdBy, UUID grantId)
```
Owner-only (or tenant-admin). **Idempotent-safe by design**: revoking an
already-revoked or unknown `grantId` is a silent no-op (no exception, no
audit entry) - never distinguish "already revoked" from "never existed" in
the response, that distinction itself would leak whether a given grant id
was ever real. `grantId` lookup is scoped to `(type, resourceId)` so a
guessed/leaked grant id from a different resource can never be used here.
Audits `"agent.share.revoke"`/`"workflow.share.revoke"` **only** when an
actual state change happened.

```java
List<ResourceGrant> listGrants(ResourceType type, UUID resourceId, String createdBy)
List<ResourceGrant> listSharedWithMe()
```
`listGrants` is owner-only (or admin) - the "who has access" panel.
`listSharedWithMe` is the current caller's own active grants across both
resource types - no ownership check needed, it's always "my own grants".

## Anti-enumeration contract

**`ResourceAccessDeniedException` maps to HTTP 404, never 403.** A caller
with no access to a private resource must see exactly the same response as
if the resource didn't exist - a 403 confirms existence, which is itself a
leak within a tenant. This mirrors `AgentNotFoundException`'s existing 404
mapping for a cross-tenant agent id, and `AgentShareController`'s
indistinguishable "unknown share id" vs "revoked share id" responses. The
exception's message is a constant, generic string - never interpolate the
resource id, resource type, or *why* access was denied into it.

`InvalidGranteeException` (403) is the one deliberate exception to
"denial is always 404" - it's thrown for a completely different reason
(the *grant target*, not the *resource*, is invalid) and the caller
performing a grant is always already the resource's owner/admin, so there's
no resource-existence secret at stake. Its message is still generic (never
reveals which of "no such email" / "wrong tenant" applied) so a tenant
member can't use it to enumerate valid emails in other tenants.

Both exceptions are mapped in `core/app`'s `GlobalExceptionHandler`
(`handleResourceAccessDenied` / `handleInvalidGrantee`) - that is the ONLY
place these HTTP statuses are decided; do not add competing
`@ExceptionHandler`s elsewhere for either type.

## Audit contract

Every grant/revoke goes through `AuditLogService#record` -
`"agent.share.grant"`, `"agent.share.revoke"`, `"workflow.share.grant"`,
`"workflow.share.revoke"` (`targetType` = `"agent"`/`"workflow"`,
`targetId` = the resource id). `requireAccess` itself does **not** audit -
it's a read-path check, potentially called on every request; only actual
grant/revoke mutations are logged from inside this module. If a caller of
`requireAccess` wants to audit an `ADMIN_OVERRIDE` read specially, that's
the caller's own `AuditLogService.record` call, using the returned
`AccessCheckResult` to decide.

## `ShareController` / `SharedWithMeController` (REST surface)

- `POST /api/agents/{id}/grants` / `POST /api/workflows/{id}/grants` - body
  `{granteeEmail, permissionLevel}`. 201 with the created/updated grant, 403
  (`InvalidGranteeException`) if the email doesn't resolve to a same-tenant
  user, 404 (`ResourceAccessDeniedException`) if the caller isn't the owner/admin,
  429 (`ShareRateLimitExceededException`) over the per-caller write cap - see
  "Rate limiting" below.
- `GET /api/agents/{id}/grants` / `GET /api/workflows/{id}/grants` - owner/admin-only list.
- `DELETE /api/agents/{id}/grants/{grantId}` / `DELETE /api/workflows/{id}/grants/{grantId}` - revoke, 204 always (idempotent).
- `GET /api/shared-with-me` - current caller's grants across both types.

No `permitAll()` was added to `SecurityConfig` - these fall through to the
existing `anyRequest().authenticated()` default, same as every other
normal (non-webhook, non-public-share) authenticated endpoint.

### `ResourceOwnerLookup` - how the controller resolves an owner it can't query for

`ShareController` only ever has a bare resource id off the URL, not a
loaded `Agent`/`WorkflowDefinition` entity - and this module can't query
either table directly (see "Dependencies" above). `ResourceOwnerLookup` is
the seam that resolves this without a forward dependency:

```java
public interface ResourceOwnerLookup {
    ResourceType supportedType();
    Optional<String> findCreatedBy(UUID resourceId);
}
```

**`core/agents` and `core/workflow` must each register a `@Component`
implementing this interface** once they add a `created_by` column - both now
do (`AgentResourceOwnerLookup`, `WorkflowResourceOwnerLookup`, each
delegating to its own repository). Where a resource's `createdBy` still
resolves to `null` (a row `V155` could not attribute - see the note under
`requireAccess`), `ResourceAccessService` treats it as `LEGACY_OPEN` for
`requireAccess` **at RUNNER only**, and as "admin-only" for
`grant`/`revoke`/`listGrants`/`requireOwnerAccess` (no owner recorded means
nobody but a tenant-admin can manage or publish it). Per-owner grant
self-service works normally for every row that does have an owner.

## Rate limiting (`ShareRateLimiter`)

`ShareController#grant`'s email -> user-id resolution is a potential
user-enumeration oracle across a large tenant, and repeated grant calls
against one resource are a grant-spam vector. Both are now capped by
`ShareRateLimiter` - the platform's standard Redis fixed-window limiter
contract (plain `INCR` + conditional `EXPIRE`, **fail-open on any Redis
error**), the exact same shape `SignupRateLimiter`,
`LlmGatewayRateLimiterService`, `DeviceClaimRateLimiter` and
`AgentShareService`'s `tryAcquire*` family already use. Keys are per
authenticated caller (`tenantId:subject`), not per IP, and Redis-backed
means the cap holds across multiple `core` replicas.

Two independent per-minute windows, both configurable:

| window | applied to | property | default |
|---|---|---|---|
| write | `POST .../grants` (checked **before** the Keycloak email lookup, so an over-limit caller never exercises the oracle) | `app.sharing.rate-limit.max-grant-writes-per-minute` | 20 |
| read | `GET .../grants`, `GET /api/shared-with-me` | `app.sharing.rate-limit.max-grant-reads-per-minute` | 120 |

Over the cap throws `ShareRateLimitExceededException` -> **HTTP 429**,
mapped (like this module's other two exceptions, and nowhere else) in
`core/app`'s `GlobalExceptionHandler`. Its message is a constant, generic
string - it never reveals which window tripped, the configured limit, or the
current count. `DELETE .../grants/{grantId}` is deliberately un-metered:
revoke is idempotent, touches no Keycloak lookup, and throttling it would
only slow down cleaning up after abuse.

## Tests

`ResourceAccessServiceTest` (plain JUnit5 + Mockito, no Spring context,
same style as `AgentServiceTest`) covers: legacy-open, owner-always-allowed,
tenant-admin-always-allowed, RUNNER grant passes RUNNER but fails
VIEWER/EDITOR, EDITOR grant implies VIEWER implies RUNNER, no-access throws,
re-grant upserts instead of duplicating, non-owner (including an
EDITOR-level grantee) cannot `grant()`/`revoke()`, cross-tenant grantee
rejected, admin can grant on behalf of another owner, revoke is
idempotent-safe (including the "unknown grant id" case), and revoke is
scoped so a wrong-resource grant id can't be revoked.
