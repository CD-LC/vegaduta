# Capped list endpoints: `limit` / `offset` contract

Applies to `GET /api/approvals`, `GET /api/channels/contacts`,
`GET /api/knowledge/collections/{id}/documents`, `GET /api/workflows/{id}/runs`.

- `?limit=N` clamps to `[1, max]` (approvals 1000, contacts 2000, documents 2000, runs 1000);
  `?offset=M` skips the newest M rows. Order is newest first with `id DESC` as tiebreaker, so
  consecutive offset pages never duplicate or skip equal-timestamp rows.
- Every response carries `X-Total-Count` and `X-Has-More`; the JSON body is still a plain array.
- **No `limit` = the complete list, exactly as before pagination existed.** Mobile apps, the
  outreach MCP server and the generated SDK send no limit and keep working unchanged. Only the web
  app (which pages with explicit limits) gets bounded responses.
- Decision: truncating limit-less callers silently is data loss for clients that cannot read the
  headers, so the capped default is opt-in server-side: set `LIST_DEFAULT_CAP=true` (or
  `-Dagenticai.list.default-cap=true`) to cap limit-less requests at the per-endpoint default
  (approvals 200, contacts 500, documents 500, runs 200). Enable it only after every non-web
  consumer sends `limit` or follows `X-Has-More`.
- `offset` above 1,000,000,000 is clamped and returns an empty page (200), never a 500.

## Keyset (cursor) paging

`GET /api/audit-log` and `GET /api/workflows/{id}/runs` also page by cursor. A paged response (`limit` given) that has
more rows carries `X-Next-Cursor`; pass it back unchanged as `cursor` (keep `limit`) for the next page. In cursor mode
`offset` is ignored, no count query runs and `X-Total-Count` is not sent; `X-Has-More` and `X-Next-Cursor` say whether
and where to continue. Unlike `offset`, a deep page costs the same as the first, and rows inserted while you page do
not shift what you see. The cursor is opaque (`KeysetCursor` in `core/audit`); an altered one is a 400.

## Additional endpoints

These endpoints follow the same `limit` / `offset` / `X-Total-Count` / `X-Has-More` convention (body
stays the same shape as before; headers are additive). Unlike the four endpoints above they were
silently capped before, so each keeps a bounded default instead of "no limit = everything".

| Endpoint | `limit` | Without params | Notes |
|---|---|---|---|
| `GET /api/audit-log` | 1..1000 | Neither `limit` nor `offset`: newest 200 rows, exactly as before. Either param present: default `limit` is 50. | Order is `createdAt DESC, id DESC`; combine with `actionType`, `targetType`, `from`, `to`, `actor` (exact `actorId`, indexed) and `q` (case-insensitive literal substring over action type, actor, target id and detail; max 100 chars, wildcards escaped). `X-Total-Count` is the count of rows matching the filters. |
| `GET /api/platform/users` | 1..500, default 100 | First 100 matches | Adds `offset`. The body's `total` (match count before paging) is unchanged and now mirrored in `X-Total-Count`. Platform-admin only. |
| `GET /api/notifications` | 1..200, default 50 | First 50 | Adds `offset` (the bell's `unreadOnly` flag still applies; `X-Total-Count` counts visible notifications matching it). The tenant-less platform-admin token gets an empty page with `X-Total-Count: 0`. |
| `GET /api/quick-task/recent` | 1..200, default 50 | First 50 (the old hard cap) | Adds `limit` and `offset`; `X-Total-Count` is the user's real run count, so a UI can show "50 of 200" instead of implying 50 is everything. |
| `GET /api/platform/ai-reports` | 1..200, default 50 | First 50 | Adds `offset`; the body stays `{ "reports": [...] }`; `X-Total-Count` is the number of reports with the requested `status` (all when absent). Platform-admin only. |

Shared rules: a negative `offset` is treated as 0 and an `offset` above 1,000,000,000 is clamped and
returns an empty page (200). `X-Total-Count` is computed with a count query only when it can change the
answer (a short first page already is the total). The audit module cannot depend on `core/tenancy`
(which depends on it), so `com.agenticai.core.audit.AuditPaging` mirrors `ListPaging` for the audit log
and notifications.

### Paged in the console standardisation (additive; no params = today's behaviour)

| Endpoint | `limit` | Without params | Notes |
|---|---|---|---|
| `GET /api/agent-jobs` | 1..200, default 50 | First 50 (unchanged) | `offset` already existed; now also `X-Total-Count` (rows matching `status` / `agentId`) and `X-Has-More`. The total is counted **before** the per-row "may this caller view this agent" filter, so a caller with partial visibility can see a total larger than what they can page through, and a page may come back short; page on `offset`, never on "did I get a full page". `X-Has-More` is computed from the raw window, not the filtered rows. |
| `GET /api/scheduled-tasks/{id}/runs` | 1..50, default 20 | Newest 20 (unchanged) | Adds `offset`; `X-Total-Count` is the retained run history (the runner keeps the newest 50 per task). Order is `startedAt DESC, id DESC`. |
| `GET /api/admin/issues` | 1..1000, default 50 once a param is present | Neither `limit` nor `offset`: newest 200, as before | Adds `limit`, `offset`, `X-Total-Count` (rows matching `status`). Tenant-admin only. |
| `GET /api/payments/transactions` | 1..200, default 50 | Newest 50 (unchanged) | Adds `offset`; `X-Total-Count` counts the agent's transactions, or the mandate's when `mandateId` is given. |
| `GET /api/wallets/{id}/ledger` | 1..200, default 50 | Newest 50 (unchanged) | Adds `offset` and `X-Total-Count`. |
| `GET /api/directory/agents` | 1..100, default 50 | First 50 (unchanged) | Public. Adds `offset`. The body keeps `entries` and `count` (rows in this page) and adds `total`, `offset`, `hasMore`; `X-Total-Count` / `X-Has-More` mirror them. Order is `publishedAt DESC, agentId DESC`. |
| `GET /api/shared-with-me` | 1..500 | **Every** active grant (unchanged) | Optional `limit` / `offset` window the caller's grant list (newest grant first); `X-Total-Count` is always sent. |
| `GET /api/channels/whatsapp-compliance/opt-in` (new) | 1..500, default 50 | First 50 | The tenant's WhatsApp opt-in records, newest consent first; `activeOnly=true` hides revoked ones. Rows carry `phoneDigits` (digits only, exactly as `POST /opt-in` returns it), `source`, `active`, `consentedAt`, `revokedAt`; evidence and the recording user are never returned. Tenant-schema scoped. |

Also new (not paging): `GET /api/payments/mandates/spend?agentId=...` returns the spend-vs-cap meters
for every mandate of the agent in one call (same shape as `GET /api/payments/mandates/{id}/spend`,
newest mandate first), replacing one request per mandate card.

Share status (`GET /api/agents/{id}/share`, `GET /api/workflows/{id}/share`) gained `revokedReason`
(`ABUSE_REPORTS`, `OWNER_REVOKED`, `EXPIRED`, or null while the link is live or never existed) and
`revokedAt`; `reportCount` and `expiresAt` are now also filled when `active` is false. The reason is
derived (a revoked link whose report count reached the auto-revoke threshold was ended by abuse
reports), not stored; `revokedAt` is the row's last update, which is the revoke itself because a dead
link cannot be viewed or reported afterwards. It is null for expiry (use `expiresAt`).

### Paged in the backend-leftovers pass (additive; no params = today's behaviour)

| Endpoint | `limit` | Without params | Notes |
|---|---|---|---|
| `GET /api/agents` | 1..200, default 50 once `LIST_DEFAULT_CAP` is on | **Every** visible agent (unchanged) | Adds `limit` / `offset`, `X-Total-Count`, `X-Has-More`. The window is cut after the per-user visibility filter (owner, admin, grants), so the total is exactly what the caller can page through. |
| `GET /api/agents/directory/mine` | 1..200 | **Every** entry (unchanged) | The tenant's own directory entries (any status), `updatedAt DESC`; adds `limit` / `offset` and the two headers. |
| `GET /api/agents/{id}/jobs` | 1..200, default 50 | Newest 50 (unchanged) | `offset` already existed; now also `X-Total-Count` (rows matching `status`) and `X-Has-More`. |
| `GET /api/coding-tasks` | 1..100, default 20 | Newest 20 (unchanged) | Adds `limit` / `offset`. `X-Total-Count` is the size of the user's recent list. The Redis recent-task index now keeps the newest **200** (was 20) so `offset` can reach older tasks; tasks still expire after 6 hours, so older ids whose document is gone are skipped and a page can come back short. Page on `offset`, not on "did I get a full page". |
| `GET /api/approvals` | 1..1000 | Every approval (unchanged) | Already paged (see the top of this file); the console can now stop paging the full list client-side. |
| `GET /api/leads` (board) | `size` 1..200, default 50 | First 50 (unchanged) | Per-column paging is `stage=<STAGE>&page=<n>&size=<n>`; the body's `total` is that column's own count, so each board column loads more independently instead of capping at 200. |
| `GET /api/imagegen/images`, `GET /api/videogen/jobs`, `GET /api/sarvam/audio`, `GET /api/elevenlabs/audio` | 1..200 | **Every** row (unchanged) | The Files page's generated-media tables. Newest first with `id DESC` as tiebreaker; adds `limit` / `offset`, `X-Total-Count`, `X-Has-More`. |

Still unpaged: the platform billing lists (platform-admin, cross-tenant).

### Platform lists (additive; no params = the whole bounded list, as before)

| Endpoint | `limit` | Notes |
|---|---|---|
| `GET /api/platform/issues`, `GET /api/platform/activity` | 1..500, default 50 once a param is present | The cross-tenant fan-out is already capped in memory; `offset` windows that list and `X-Total-Count` is its size. |
| `GET /api/platform/abuse-monitor/scores`, `/anomalies`, `/throttled` | same | Same windowing of the in-memory list. |
| `GET /api/platform/audit/search` | existing `limit` (max 1000) | Adds `offset` (0..2000). Each tenant is read up to `offset + limit` rows, then merged newest first. The body gains `offset`, `hasMore`, `knownTotal`; `X-Total-Count` is a lower bound while `X-Has-More` is true. |

`GET /api/workflows/{id}/versions` rows gained `label` (`STAGING`, `PRODUCTION` or null). Agent and
workflow GET/list responses carry `accessLevel` (`EDITOR`, `VIEWER`, `RUNNER`; null when unresolved).

### Wave 2 (A): conformance ratchet, paths /api/a.. to /api/k.. (additive; no params = the whole list, as before)

Every endpoint below gained optional `limit` (1..500, default 50 only once `LIST_DEFAULT_CAP` is on) and
`offset`, and now sends `X-Total-Count` / `X-Has-More`. The body is still a plain array. The window is cut
by `ListPaging.window` after the existing visibility and mapping logic, so the total is exactly what the caller
can page through.

| Area | Endpoints |
|---|---|
| Agent-scoped | `GET /api/agents/{agentId}/versions`, `/experiments`, `/triggers`, `/delegations`, `/improvements`, `/skills/distill`, `/x402/offers` |
| Console | `GET /api/browser/profiles`, `/api/browser/recipes`, `/api/data-tables`, `/api/face-enrollments`, `/api/guardrails/rules`, `/api/briefs`, `/api/briefs/{id}/versions`, `/api/admin/templates/pending`, `/api/admin/templates/approved` |
| Devices and email | `GET /api/devices`, `/api/devices/{deviceId}/commands`, `/api/email/messages` |
| Channels | `GET /api/channels/routing`, `/api/channels/contact-groups`, `/api/channels/pairing/pending`, `/api/channels/voice/numbers`, `/api/channels/voice/dtmf-menu`, `/api/channels/whatsapp/templates`, `/api/handoff/inbox` |
| Knowledge | `GET /api/knowledge/collections`, `/api/knowledge/connectors` |
### Wave 2 (B): `/api/l..z` lists (additive; no params = the complete list, as before)

Every endpoint below gains optional `limit` / `offset` and sends `X-Total-Count` / `X-Has-More`. Without
`limit` the whole list is returned exactly as before (unless `LIST_DEFAULT_CAP=true`, which applies the
default). The lists are small per-tenant sets that were already loaded fully in memory, so the window
is cut after the existing visibility and ordering, and `X-Total-Count` is exact. Defaults and maxima:
default 50 / max 200 unless noted.

| Endpoint | Default / max | Notes |
|---|---|---|
| `GET /api/leads/pipeline` | 100 / 500 | Per-stage totals. |
| `GET /api/leads/{id}/activities` | 50 / 200 | |
| `GET /api/knowledge/collections` | 50 / 200 | |
| `GET /api/knowledge/collections/embedding-models`, `/text-search-configs` | 100 / 500 | Static catalogs. |
| `GET /api/knowledge/connectors?collectionId=` | 50 / 200 | |
| `GET /api/mcp/registry`, `/api/mcp/tool-pins`, `/api/secrets`, `/api/rl/agents/{agentId}/policies` | not paged yet | Their modules do not depend on `core/tenancy` (where `ListPaging` lives). |
| `GET /api/model-pricing` | not paged | Requires an explicit `modelIds` list; the caller already bounds it. |
| `GET /api/musicgen/tracks` | 50 / 200 | |
| `GET /api/procedures` | 50 / 200 | Only the returned page pays the per-row rule resolution. |
| `GET /api/providers` | 100 / 500 | |
| `GET /api/providers/{provider}/models` | 200 / 1000 | Catalogs reach ~500 models. |
| `GET /api/scheduled-tasks` | 50 / 200 | |
| `GET /api/sdlc/sandbox` | 50 / 200 | |
| `GET /api/skills` | 50 / 200 | |
| `GET /api/skill-security/reviews` | 50 / 200 | |
| `GET /api/studio-apps/{appId}/domains` | 50 / 200 | |
| `GET /api/studio-projects`, `/{id}/members`, `/{id}/comments` | 50 / 200 | |
| `GET /api/templates/mine` | 50 / 200 | |
| `GET /api/tenant/api-keys` | 50 / 200 | |
| `GET /api/tools/code`, `/api/tools/openapi`, `/api/tools/scripts` | 50 / 200 | |
| `GET /api/tools/openapi/{id}/tools` | 100 / 500 | |
| `GET /api/tutor/plans` | 50 / 200 | |
| `GET /api/webhooks`, `/{id}/deliveries` | 50 / 200 (deliveries max 100) | Deliveries stay capped at the newest 100. |
| `GET /api/webhooks/event-types` | 100 / 500 | Static catalog. |
| `GET /api/workflows/input-requests` | 50 / 200 | The default "pending" filter is unchanged. |
| `GET /api/workflows/{id}/comments` | 100 / 500 | Roots in creation order; replies stay nested in their thread. |
| `GET /api/workflows/{id}/pins` | 50 / 200 | |
| `GET /api/workflows/{id}/versions` | 50 / 200 | |
| `GET /api/guardrails/rules` | 100 / 500 | |
| `GET /api/moderation/review` | 50 / 200 | |
| `GET /api/handoff/inbox` | 50 / 200 | The needs-attention queue. |
| `GET /api/email/messages?runId=` | 50 / 200 | |
| `GET /api/data-tables` | 50 / 200 | |

Not touched on purpose: billing, payments, subscription and `/api/platform/**` lists.
