# Community Templates & Gallery — design + agent contract (2026-07-30)

A section where anyone can **share** an agent or workflow design as a reusable
template, those submissions pass an **approval/moderation** step, and approved
ones become **publicly browsable and importable**. Read fully before coding.

## Why moderation is not optional

Public submission of executable configuration is exactly the attack surface that
produced the *ClawHavoc* incident (341 confirmed malicious skills on an unvetted
community marketplace, Feb 2026). Every incumbent that survived — GPT Store, the
major MCP hubs — gates public visibility behind review. So: **nothing a member
submits is publicly visible until an admin approves it.** That gate is the
feature, not a nice-to-have.

## What a template IS (and is NOT)

A template is a **sanitised, declarative snapshot** of an agent's or workflow's
*design*: system prompt, model/provider *choice* (not keys), tool/MCP names,
guardrail settings, knowledge-collection *shape* (not the documents), workflow
graph. It is data, not a running resource.

A template MUST NOT carry, and the submission path MUST strip: provider API
keys, channel secrets, tenant ids, learner/user PII, per-tenant knowledge
documents, `created_by` subjects, or any credential. This is the single most
important correctness property. Snapshotting is allow-list (copy only known-safe
fields), never deny-list.

This is distinct from the existing `agent_share` (public link to a *live* agent)
and from `core/sharing` `ResourceGrant` (same-tenant RUNNER/VIEWER/EDITOR grants).
Reuse their patterns; do not repurpose their tables.

## Lifecycle

    DRAFT ──submit──▶ PENDING ──approve──▶ APPROVED (public)
                        │                     │
                        └──reject──▶ REJECTED └──unpublish/report──▶ WITHDRAWN

- **DRAFT** — owner is iterating; visible only to the owner.
- **PENDING** — submitted; in the moderation queue; owner can still withdraw.
- **APPROVED** — publicly listed and importable. Carries the approving admin +
  timestamp for audit.
- **REJECTED** — with a reason the submitter can see; editable and resubmittable.
- **WITHDRAWN** — was public, pulled (by owner or admin, e.g. after a report).

Every transition is audited via the existing `AuditLogService`
(`template.submit` / `template.approve` / `template.reject` / `template.import`),
same pattern as `agent.share.grant`.

## Data model (core)

New table `community_template` (control-plane schema — these are cross-tenant by
nature, unlike per-tenant agents). Suggested columns:

    id, kind ('AGENT'|'WORKFLOW'), title, summary, category, tags[],
    status, config_json (the sanitised snapshot), source_tenant_id,
    submitted_by, submitted_at, reviewed_by, reviewed_at, reject_reason,
    import_count, version, created_at, updated_at

Next free Flyway version is **V155** (tenant stream is at V154; the controlplane
+ tenant streams share one sequence per application.yml — verify at build time,
the number may have moved). This table is control-plane, so it goes in
`controlplane/`.

## API surface

Authenticated (member):
- `POST /api/templates` — create a DRAFT from one of my agents/workflows (server
  does the sanitising snapshot; client never supplies the config).
- `PUT /api/templates/{id}` — edit my own DRAFT/REJECTED.
- `POST /api/templates/{id}/submit` — DRAFT → PENDING.
- `POST /api/templates/{id}/withdraw` — owner pulls their own.
- `POST /api/templates/{id}/import` — instantiate a new agent/workflow in MY
  tenant from an APPROVED template (or my own, any status). Returns the new id.
- `GET /api/templates/mine` — my submissions with their statuses.

Public (no auth — approved only):
- `GET /api/templates/gallery` — approved templates, filterable by kind/category/
  tag, paginated. Never leaks config_json in the list; summary card only.
- `GET /api/templates/gallery/{id}` — one approved template's public detail
  (config shown read-only for inspection before import; still no secrets because
  none were ever stored).

Admin (platform-admin / tenant-admin per existing RBAC):
- `GET /api/admin/templates/pending` — the moderation queue.
- `POST /api/admin/templates/{id}/approve`
- `POST /api/admin/templates/{id}/reject` `{ reason }`
- `POST /api/admin/templates/{id}/withdraw` `{ reason }` — pull an approved one.

Anti-enumeration: a non-owner asking for a non-approved template gets 404, not
403 — same posture as `ResourceAccessService`.

## Rate / abuse limits

Public gallery GETs use the existing per-IP limiter (see the Caddy
trusted_proxies fix). Submissions are capped per tenant per day. Import is capped
per user per day. Log any cap hit rather than silently dropping.

## File scopes (disjoint — for the fan-out)

1. **core (Java)** — `core/` template module: entity, repo, service (incl. the
   sanitising snapshot + import instantiation), the three controllers,
   `controlplane/V155__community_template.sql`, and unit tests. THE critical
   test: assert the snapshot strips keys/secrets/PII for a fully-populated agent.
   Must `mvn -pl app -am test` green.
2. **web** — public `/templates` gallery (server-rendered enough to be
   crawlable), template detail, a "Share as template" action on the agent/
   workflow screens, an "Import" flow, and `/templates/mine`. Use the
   2026-07-29 design system (`.cat-chip`, `--state-*` for the lifecycle badge:
   pending=queued, approved=success, rejected=error). NO raw palette colours.
3. **web-admin** — the moderation queue screen: list pending, inspect the full
   config diff, approve / reject-with-reason. This is where a human actually
   reviews, so make the config legible, not a JSON blob.

## Hard rules

- **The snapshot allow-list is the security boundary.** If unsure whether a
  field is safe to include, exclude it and note it. A test must prove secrets
  never enter `config_json`.
- Public endpoints return APPROVED only. Ever. No exceptions for "preview".
- Do not deploy. Coordinator integrates + deploys after tests pass.
- core: real `mvn` test gate. web/web-admin: `tsc`/build gate. State plainly
  what you could not verify.
- Reuse `AuditLogService`, the per-IP limiter, and the 404-anti-enumeration
  posture. Don't reinvent them.
