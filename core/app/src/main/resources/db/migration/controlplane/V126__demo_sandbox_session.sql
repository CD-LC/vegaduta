-- "Try it without registering" demo sandbox (core/tenancy/demo) - the
-- control-plane bookkeeping for an UNREGISTERED visitor's throwaway
-- workspace.
--
-- Version note: this repo's Flyway locations merge controlplane/ and
-- tenant/ into one shared version sequence (see application.yml's flyway
-- comment). V123-V125 are taken (tenant_news_opt_in / newsletter_campaign_log
-- / app_user_avatar), so this is V126.
--
-- ---------------------------------------------------------------------------
-- WHY TWO THINGS AND NOT ONE (the design call this migration is making)
-- ---------------------------------------------------------------------------
-- 1. `control_plane.demo_session` - the session itself. It has to be its own
--    table rather than a handful of extra nullable columns on
--    `control_plane.tenant`, because the interesting lifecycle here belongs
--    to the SESSION, not the tenant: a token hash (which must be rotatable
--    and revocable independently of the tenant), an expiry, a
--    reminder-sent stamp, and - critically - a `converted_tenant_id`
--    pointing at the REAL tenant that a converting visitor ends up with.
--    That last one is a tenant->tenant edge; it cannot live as a column on
--    either tenant row without being ambiguous about which side it means.
--
-- 2. `control_plane.tenant.is_demo` - a denormalised boolean on the tenant
--    row itself. Deliberately redundant with "there exists a demo_session
--    row for this tenant", and worth it: EVERY existing cross-tenant read in
--    this codebase (the platform-admin tenant roster, NewsletterService's
--    recipient query, TenantSchemaIterator's per-tenant job fan-out, any
--    future revenue/reporting query) needs a cheap way to exclude throwaway
--    demo tenants, and none of them should have to learn about the
--    demo_session table to do it. It also SURVIVES the demo_session row
--    being purged after expiry, which the join would not - so "was this
--    tenant ever a demo tenant" stays answerable for as long as the tenant
--    row exists.
--
-- ---------------------------------------------------------------------------
-- WHY token_hash AND NOT THE TOKEN
-- ---------------------------------------------------------------------------
-- The demo token is a bearer credential: whoever holds it can talk to that
-- sandbox's agent. Same posture the platform already takes for the workflow
-- webhook token (WorkflowDefinition#webhookTokenHash) and inbound MCP API
-- keys - store only the SHA-256 hex of the raw token, look up by hashing the
-- caller's value. A database dump therefore does not hand anyone a working
-- set of live demo sessions. The raw token is returned to the browser
-- exactly once, at creation, and never again.

CREATE TABLE control_plane.demo_session (
    id                  UUID PRIMARY KEY,
    -- SHA-256 hex of the raw demo token (always exactly 64 chars). UNIQUE so
    -- a lookup by hash is a single index probe and a collision is a hard
    -- error rather than an ambiguous multi-row match.
    --
    -- VARCHAR(64), deliberately NOT CHAR(64), for two reasons: Postgres
    -- blank-pads CHAR on read, and - decisively - this app runs Hibernate
    -- with ddl-auto: validate, which expects varchar for a String field and
    -- would fail startup against a bpchar column.
    token_hash          VARCHAR(64)  NOT NULL UNIQUE,
    -- The throwaway tenant this sandbox provisioned. Not an FK constraint on
    -- purpose: control_plane.tenant rows are never hard-deleted in this
    -- codebase (see TenantStatus.ERASED's javadoc), so an FK buys nothing,
    -- while a NO ACTION FK would make a future operator-driven hard purge of
    -- old demo tenants fail confusingly.
    tenant_id           UUID         NOT NULL,
    tenant_schema       VARCHAR(63)  NOT NULL,
    -- The pre-seeded sandbox agent, so the demo chat endpoint resolves it
    -- from the control plane without first switching schemas to go looking.
    -- Nullable: seeding is best-effort and must never fail session creation
    -- (see DemoSessionService's javadoc).
    agent_id            UUID,
    -- Optional, visitor-supplied, and NEVER required to use the sandbox -
    -- the only thing it is used for is the pre-expiry reminder. Null for
    -- every visitor who does not volunteer one, which is the default path.
    email               VARCHAR(320),
    -- The creating client's IP, retained only as an abuse-forensics aid for
    -- the lifetime of the session row (the live rate limit itself is the
    -- Redis counter in DemoSessionRateLimiter, not this column). Wide enough
    -- for a full IPv6 literal.
    created_ip          VARCHAR(45),
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    -- Stamped by DemoSessionExpiryJob when the pre-expiry nudge goes out, so
    -- the reminder is sent at most once per session even across restarts and
    -- multiple core replicas re-running the job.
    reminder_sent_at    TIMESTAMPTZ,
    -- Set when the expiry job has retired the session (tenant suspended).
    expired_at          TIMESTAMPTZ,
    -- CONVERSION SEAM. Set when the visitor registers for real: the id of
    -- the tenant their /signup created. Its presence is what makes an
    -- artifact copy from tenant_id -> converted_tenant_id replayable and
    -- auditable after the fact. See DemoConversionService#copyDemoArtifacts
    -- for exactly how much of that copy is implemented today (honestly: the
    -- seam and the bookkeeping, not the row copying).
    converted_tenant_id UUID,
    converted_at        TIMESTAMPTZ,
    -- Cheap abuse cap counter - how many demo chat turns this session has
    -- spent. Enforced in DemoSessionService against a platform setting.
    message_count       INTEGER      NOT NULL DEFAULT 0
);

-- The expiry job's two scans: "which live sessions are past expiry" and
-- "which live sessions are inside the reminder window and not yet nudged".
-- Both are driven by expires_at, so one index on it covers both.
CREATE INDEX idx_demo_session_expires_at ON control_plane.demo_session (expires_at);

-- Conversion lookup at signup time: "the visitor handed us this demo token,
-- which sandbox is it?" goes through token_hash's UNIQUE index. This second
-- one covers the reverse direction (admin/debug: "show me the demo session
-- that became this tenant").
CREATE INDEX idx_demo_session_tenant_id ON control_plane.demo_session (tenant_id);

-- See the header note: default FALSE so every pre-existing tenant is
-- correctly a real tenant, and NOT NULL so no read anywhere has to
-- three-state it.
ALTER TABLE control_plane.tenant
    ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index - the overwhelming majority of tenant rows are is_demo =
-- FALSE, so indexing only the TRUE side keeps this tiny while still making
-- "list/purge the demo tenants" a fast scan.
CREATE INDEX idx_tenant_is_demo ON control_plane.tenant (is_demo) WHERE is_demo;
