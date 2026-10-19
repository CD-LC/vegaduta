# core/templates

Community template gallery: a **sanitised, moderated, publicly-browsable**
catalogue of agent/workflow *designs*. A member snapshots one of their own
agents/workflows into a template, an admin moderates it, and approved templates
become importable into any tenant as a brand-new agent/workflow.

Read `docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md` first — it is the binding
contract; this module implements it.

## Why moderation is the feature

Public submission of executable configuration is an attack surface (the
*ClawHavoc* precedent). **Nothing a member submits is publicly visible until an
admin approves it.** The two public endpoints return `APPROVED` rows only, and
the list never ships `config_json`.

## Data model

`control_plane.community_template` (migration `V156__community_template.sql`) — a
**control-plane** table, not a tenant-schema one: an approved template is visible
to the whole world, so it can't live in a single tenant's schema.

| column | notes |
|---|---|
| `id` | PK |
| `kind` | `AGENT` \| `WORKFLOW` |
| `title` / `summary` / `category` | presentation |
| `tags` | JSON-array string in a TEXT column (not `text[]` — keeps Hibernate `validate` trivial; tags filtered in-service) |
| `status` | `DRAFT`\|`PENDING`\|`APPROVED`\|`REJECTED`\|`WITHDRAWN` |
| `config_json` | the **allow-list snapshot** — see below |
| `source_tenant_id` | provenance for admin traceability; NEVER public, NEVER in `config_json` |
| `submitted_by` / `submitted_at` | owner + submission time |
| `reviewed_by` / `reviewed_at` / `reject_reason` | moderation trail |
| `import_count` | bumped on each import |
| `version` | bumped on each owner edit |
| `created_at` / `updated_at` | |

## The sanitising snapshot — the security boundary

`TemplateSnapshotService` builds `config_json` by **ALLOW-LIST**: it copies only
the known-safe design fields off the source entity, one by one. It never
serialises the whole entity, never reads a credential/provider-key/channel table,
never touches tenant identity. Deny-by-default: a field not named in a
`snapshotXxx` method never enters the snapshot.

**`config_json` field names deliberately match the existing DTOs** (`AgentResponse`
/ the React-Flow workflow graph) so the admin moderation viewer and public gallery
render it directly.

### Agent `config_json` shape (keys)

`systemPrompt`, `providerName`, `model`, `maxTokens`, `temperature`, `topP`,
`tuningMode`, `reasoningDepth`, `autonomyTier`, `experienceMode`,
`knowledgeBaseEnabled`, `sarvamEnabled`, `e2bEnabled`, `codeWorkspaceEnabled`,
`responseCachingEnabled`, `semanticResponseCachingEnabled`, `shareMemoryWithTenant`,
`costRoutingEnabled`, `economyProviderName`, `economyModel`, `fallbackProviderName`,
`fallbackModel`, `voiceModelProvider`, `voiceModel`, `videoModelProvider`,
`videoModel`, `allowedToolNames`, `enabledGuardrails`, `allowedKnowledgeCollectionIds`.

- `allowedKnowledgeCollectionIds` — **key retained for the renderer, value ALWAYS
  `[]`**: the source's collection ids are tenant-scoped UUIDs that mean nothing
  (and are a minor cross-tenant reference leak) in another tenant.

### Workflow `config_json` shape (keys)

`name`, `description`, `graph` — where `graph` is the React-Flow `{nodes, edges}`
document (so `config.graph.nodes` / `config.graph.edges`). The webhook token,
schedule, trigger-channel binding and `createdBy` are **not** copied.

### Deliberately EXCLUDED (and why)

`createdBy` (identity/PII), `id`/`createdAt`/`updatedAt` (instance metadata),
`allowedCodeIntelRepoIds` + `collaboratorAgentIds` (tenant-scoped ids), fallback
telemetry counters, and — by construction, since they aren't entity columns at
all — provider API keys, channel secrets, tenant ids, knowledge documents.
`TemplateSnapshotServiceTest` proves none of these appear even for a
fully-populated agent. **That test is the acceptance gate.**

## Import

`POST /api/templates/{id}/import` instantiates a **new** agent/workflow in the
caller's tenant by feeding the snapshot through `AgentService.create` /
`WorkflowService.create` — never a raw insert — so tenant caps, FREE-tier
provider coercion, edit-lock windows, ownership stamping and validation all apply.
Only the fields the public create request accepts are carried through on import;
the richer snapshot fields (guardrails, tool allow-list, voice/video) are
preserved in `config_json` for display but are not settable via the public create
request and are intentionally not back-doored in.

## Lifecycle

```
DRAFT --submit--> PENDING --approve--> APPROVED (public)
                    |                    |
                    +--reject--> REJECTED +--withdraw--> WITHDRAWN
REJECTED/WITHDRAWN --submit--> PENDING
```

The legal-transition graph lives on `TemplateStatus.canTransitionTo` and is
enforced in exactly one place (`CommunityTemplateService#transition`). An illegal
move is a `409` (`IllegalTemplateTransitionException`). Every transition + import
is audited via `AuditLogService` (`template.submit` / `template.approve` /
`template.reject` / `template.withdraw` / `template.import` / `template.create`),
same pattern as `agent.share.grant`.

## Anti-enumeration

A non-owner asking for a non-approved template gets **404, never 403**
(`TemplateNotFoundException`) — same posture as `core/sharing`'s
`ResourceAccessDeniedException`. The moderation queue and private drafts are
invisible: a request for one is indistinguishable from a request for a made-up id.

## API surface

Member (authenticated, falls through to `anyRequest().authenticated()`):
- `POST /api/templates` — create a DRAFT from my agent/workflow (server snapshots; client never sends config)
- `PUT /api/templates/{id}` — edit my DRAFT/REJECTED presentation
- `POST /api/templates/{id}/submit` — DRAFT/REJECTED/WITHDRAWN → PENDING
- `POST /api/templates/{id}/withdraw` — owner pulls their own
- `POST /api/templates/{id}/import` — instantiate into my tenant; returns the new id
- `GET /api/templates/mine` — my submissions + statuses

Public (no auth — `SecurityConfig` `GET /api/templates/gallery/**` permitAll; APPROVED only):
- `GET /api/templates/gallery` — approved cards, `?kind=&category=&tag=&page=&size=`; never returns `config_json`
- `GET /api/templates/gallery/{id}` — one approved template's public detail (config shown read-only)

Admin (`/api/admin/**` → `ROLE_tenant-admin` at the filter; service re-checks admin, accepting `ROLE_platform-admin` too):
- `GET /api/admin/templates/pending` — moderation queue
- `GET /api/admin/templates/approved` — live templates to pick a withdraw target (full owner/admin projection)
- `POST /api/admin/templates/{id}/approve`
- `POST /api/admin/templates/{id}/reject` `{ reason }` (reason required)
- `POST /api/admin/templates/{id}/withdraw` `{ reason }` (reason optional)

## Abuse limits

Draft creation is capped per tenant per 24h (`app.templates.max-submissions-per-tenant-per-day`,
default 25) — a hit is a `429` (`TemplateSubmissionLimitException`) and is logged,
never silently dropped. Public gallery GETs rely on the existing per-IP edge
limiter (Caddy). Imports are capped per **user** per 24h
(`app.templates.max-imports-per-user-per-day`, default 20) by
`TemplateImportRateLimiter` — imports leave no `community_template` row to count
against cheaply, so this cap is a Redis counter (the platform's standard
`INCR` + conditional `EXPIRE` fixed-window shape, fail-open on Redis errors,
correct across replicas) keyed by the caller's subject, enforced at the top of
`CommunityTemplateService#importTemplate` (failed attempts count too, which also
lids template-id enumeration). A hit is a `429` (`TemplateImportLimitException`)
and is logged, same posture as the submission cap.

## Tests

- `TemplateSnapshotServiceTest` — **the acceptance gate**: a fully-populated agent
  (with a createdBy subject, tenant-scoped collection/collaborator/repo ids, and
  sentinel provider-key/channel-secret strings) snapshots to a `config_json` that
  contains **none** of them, while carrying the safe design fields; plus the
  round-trip back into an `AgentCreateRequest`, and the workflow-graph-only
  snapshot that drops the webhook secret + owner.
- `CommunityTemplateServiceTest` — lifecycle transitions (illegal `DRAFT→APPROVED`
  rejected; happy `submit→approve`), public-only-approved (`galleryDetail` 404s a
  non-approved id), admin-only enforcement (non-admin `pending`/`reject` denied),
  and import creating a real new agent/workflow via `AgentService`/`WorkflowService`.
