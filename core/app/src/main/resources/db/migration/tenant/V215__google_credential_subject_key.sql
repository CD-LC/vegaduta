-- Per-end-user Google credentials - google_credential gains a subject_key.
--
-- THE DEFECT THIS CLOSES
-- -----------------------
-- Until now google_credential was strictly one row per tenant schema, and both
-- of the endpoints that write it (GET /api/channels/google/authorize-url,
-- DELETE /api/channels/google/connection) are gated on ROLE_tenant-admin in
-- SecurityConfig. The practical consequence was not "the calendar tool reads
-- the wrong calendar": GoogleScopes.REQUESTED includes gmail.readonly AND
-- gmail.send, and AgentToolProvider#resolveGoogleTools hands all five Google
-- tools to EVERY agent in the tenant the moment the tenant connects, with no
-- per-agent opt-in. So any member who could chat with any agent could read the
-- admin's inbox and send mail as the admin. A second, personal Google account
-- was not merely unsupported - it was structurally impossible.
--
-- WHAT subject_key MEANS, AND WHY IT IS NULLABLE
-- ----------------------------------------------
--   subject_key IS NULL  -> the legacy tenant-wide account. Connected by an
--                           admin, used as the fallback for every caller who
--                           has no personal row of their own.
--   subject_key = '<sub>' -> one end user's own Google account, keyed on the
--                           Keycloak `sub` claim that TenantContextFilter puts
--                           into TenantContext (the identical value
--                           AgentController derives `subjectKey` from for agent
--                           memory, so the two subsystems agree on identity by
--                           construction rather than by convention).
--
-- NULL is load-bearing, not laziness. It is the whole backward-compatibility
-- story: every google_credential row that exists today keeps subject_key NULL,
-- GoogleOAuthService resolves personal-first-then-tenant-wide, and a tenant
-- that never adopts personal accounts therefore resolves exactly the row it
-- resolved before this migration, on every path, forever. A NOT NULL column
-- with a synthetic backfill would have orphaned every existing tenant on the
-- first tool call after deploy. This also satisfies application.yml's
-- additive-only / no-destructive-backfill rule, which is what makes Flyway's
-- out-of-order:true safe here.
--
-- WHY subject_key AND NOT tenant_id
-- ----------------------------------
-- Schema-per-tenant: the tenant is the schema this table physically lives in,
-- and nothing here is read by a worker thread that has to rebuild TenantContext
-- from the row itself (the way agent_job and document_generation_job do, which
-- is the ONLY justification this codebase accepts for a redundant tenant_id).
-- Credential resolution always happens on a thread that already has
-- TenantContext - either the request thread, or a tool-call thread where
-- TenantContextPropagatingToolCallback has re-set it. So: no tenant_id.
--
-- WHY THIS DEPARTS FROM THE PER-TENANT CREDENTIAL NORM
-- ----------------------------------------------------
-- Every other credential table in this repo is one active row per tenant schema
-- selected positionally - V80__e2b_credential.sql names sarvam_credential (V60)
-- and ocr_provider_config (V76) as the same shape, and google_credential was
-- built to match. That shape is right for a credential the TENANT owns (an E2B
-- API key, an OCR provider key): there is one correct answer per tenant and no
-- per-person dimension to model. It is wrong for a delegated end-user identity,
-- where "whose Gmail?" is the entire question. slack_credential (V31),
-- x_credential (V33) and telegram_credential (V46) pair encrypted_* with a
-- user-ish column, but those are external PLATFORM account ids, not platform
-- users - so this is genuinely the first per-user credential row here, and the
-- column type is chosen to match how user identity is already stored elsewhere:
-- VARCHAR(255), opaque, never FK'd. Same reasoning as
-- controlplane/V199__user_permission_grant.sql's keycloak_user_id - an FK to
-- control_plane.app_user would silently fail for everyone an admin later
-- invited, because app_user only has rows for the original signup admin.
--
-- WHY TENANT AND NOT CONTROLPLANE
-- --------------------------------
-- google_credential is already tenant-schema and must stay there. It holds an
-- encrypted refresh token that mints access to a customer's own mailbox, drive
-- and calendar - about as squarely "customer data" as this platform has. It has
-- to vanish with TenantDataErasureService's DROP SCHEMA; moving it to the
-- control plane would be a right-to-erasure defect, not a design preference
-- (V207's phrasing, and it applies with more force here than there).
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream (one Flyway bean, both folders, ordered purely by number), so
-- a number is free only when it is free in BOTH. Checked at authoring time by
-- numeric sort, not directory listing order: tenant/'s highest is V211
-- (agent job approval parking), controlplane/'s highest is V206 (moderation
-- admin surface). V212 is claimed by the sibling agent-versioning workstream in
-- this same fan-out, and V213/V214 by two other siblings; V215 was assigned to
-- this one. See docs/ZERO-DOWNTIME-DEPLOY-RULES.md for the 2026-07-31
-- production outage a duplicate V156 caused - a collision here is an OUTAGE,
-- not a merge conflict.

ALTER TABLE google_credential
    ADD COLUMN IF NOT EXISTS subject_key VARCHAR(255);

-- Defensive quarantine, run BEFORE the unique index below so the index can
-- never be the thing that fails a tenant's migration.
--
-- In practice every tenant has at most one row: pre-V215 connect() called
-- findFirstByOrderByCreatedAtAsc() with no status filter and no subject filter,
-- so it ALWAYS reused the oldest row rather than inserting a second one. The
-- only way a duplicate could exist is two OAuth callbacks racing on a tenant
-- that had never connected before, each reading "no row" and each inserting.
-- That is vanishingly unlikely, but a CREATE UNIQUE INDEX that trips on it
-- would abort this migration for that tenant - and a failed tenant migration is
-- an outage, which is precisely the failure mode this repo has already had once.
--
-- So rather than assert the invariant and hope, we make it true: keep the
-- oldest NULL-subject row as the tenant default and re-key any others to a
-- synthetic, self-unique quarantine key. Nothing is deleted (that would be a
-- destructive backfill, and the rows are forensic evidence of a real race), and
-- a quarantined key can never be resolved by a real caller because it does not
-- match any Keycloak subject and is no longer NULL. The subquery reads the
-- statement's own pre-UPDATE snapshot, so "oldest" is stable while we write.
UPDATE google_credential
   SET subject_key = 'quarantine:duplicate-tenant-default:' || id::text
 WHERE subject_key IS NULL
   AND id <> (
        SELECT id FROM google_credential
         WHERE subject_key IS NULL
         ORDER BY created_at ASC, id ASC
         LIMIT 1
   );

-- One personal row per person. This is the upsert key for
-- GoogleOAuthService#connect(code, subjectKey): without it a second consent
-- from the same user could quietly create a contradictory second row and which
-- Google account the tools used would depend on row order (the argument
-- controlplane/V206__moderation_admin_surface.sql makes for its own unique
-- indexes). Partial, because Postgres treats NULLs as distinct: a plain
-- UNIQUE (subject_key) would permit unlimited tenant-default rows, which is the
-- opposite of what we need. Also serves the personal-row lookup
-- (WHERE subject_key = ?), so no separate index is added for it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_google_credential_subject_key
    ON google_credential (subject_key)
    WHERE subject_key IS NOT NULL;

-- ...and exactly one tenant-wide row. Indexing on the constant expression
-- ((TRUE)) with a WHERE subject_key IS NULL predicate is the standard Postgres
-- idiom for "at most one row satisfying this predicate". Serves the tenant-wide
-- fallback lookup (WHERE subject_key IS NULL) too, which is the hottest read in
-- the module - it is the fallback leg of every resolution for every caller who
-- has not connected a personal account.
--
-- Honest caveat, the same one V211 records for idx_agent_job_approval_deadline:
-- these are partial indexes, and Hibernate binds `status` as a parameter, so
-- Postgres can only prove a partial predicate holds when it builds a CUSTOM
-- plan. Here the predicate is on subject_key rather than status and the
-- IS NULL / IS NOT NULL tests are literal in the generated SQL, so this is
-- sturdier than the agent_job case - but it is still not a guarantee for every
-- plan Postgres might pick, and at this table's size (a handful of rows per
-- tenant schema) it does not matter either way. The indexes are here for
-- correctness first, speed second.
--
-- No CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CREATE INDEX CONCURRENTLY cannot run in one. Same as every other index in
-- this folder.
CREATE UNIQUE INDEX IF NOT EXISTS uq_google_credential_tenant_default
    ON google_credential ((TRUE))
    WHERE subject_key IS NULL;
