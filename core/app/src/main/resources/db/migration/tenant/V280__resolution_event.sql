-- Outcome-based pricing substrate ("resolution metering", Tier-4 structural
-- batch): the durable, auditable record of "did this conversation reach a
-- resolved end-state, and is it billable". One row per resolution decision
-- on a conversation - the metering unit an outcome-priced plan charges on,
-- and the evidence a tenant can dispute.
--
-- WHAT THIS IS NOT: it does not charge anything by itself. Invoicing stays
-- with the existing subscription machinery; this table is the meter that a
-- future per-resolution price plan (and today's reporting) reads. Under
-- platform.free-mode.enabled=true rows are still written with the price
-- snapshot columns populated - metering is orthogonal to whether anyone is
-- billed for it.
--
-- OUTCOME VOCABULARY
--   RESOLVED_AI    - the agent resolved the conversation with no human
--                    takeover (the outcome an outcome-priced plan bills)
--   RESOLVED_HUMAN - a human operator resolved it (recorded for honest
--                    reporting; never AI-billable)
--   ESCALATED      - handed to / held by a human at close time
--   UNRESOLVED     - explicit negative signal (UNRESOLVED / THUMBS_DOWN
--                    feedback) or operator-marked unresolved
--   ABANDONED      - conversation ended before the agent meaningfully
--                    engaged (unpaired sender, no agent reply)
--
-- EVIDENCE VOCABULARY (how the outcome was decided)
--   USER_RESOLVED_SIGNAL - explicit turn_feedback RESOLVED on the session
--   FEEDBACK_NEGATIVE    - explicit UNRESOLVED / THUMBS_DOWN
--   NO_ESCALATION_IDLE   - quiet-close heuristic: conversation went idle
--                        past the configured window while agent-handled
--   OPERATOR_CLOSE       - a human operator closed the conversation
--   ESCALATION_OPEN      - handoff_state was AWAITING_HUMAN / HUMAN at close
--   EXPLICIT_API         - recorded through POST /api/tenant/resolutions
--   JUDGE_VERDICT        - reserved for an evals-judge classifier; no
--                        producer yet - the column exists so the enum does
--                        not need a migration when one lands
--
-- DEDUPE MODEL
-- dedupe_key is UNIQUE and caller-computed. The channel-session sweep uses
-- "cs:<channel_session uuid>:<last_message_at epoch-ms>": an idle session's
-- last_message_at never moves, so re-running the sweep hits the unique
-- constraint and skips; when the customer writes back, last_message_at
-- moves and the NEXT idle-close is legitimately a new resolution event.
-- Explicit-API callers supply their own natural key for the same
-- exactly-once guarantee.
--
-- VERSION NUMBER
-- ---------------
-- controlplane/ and tenant/ share ONE Flyway version stream. Checked at
-- authoring time (2026-09-23): repo's highest is V271 (tenant
-- workflow_input_request) / V264 (controlplane); V272-V279 left as headroom
-- for in-flight sessions. This file is the only V280 in the repository.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- Same reasoning as V260's header: these rows are conversational evidence
-- about one tenant's end users and must vanish with
-- TenantDataErasureService's DROP SCHEMA. billing_invoice lives in
-- control_plane because it is platform-facing financial state; this table
-- is tenant-facing usage evidence - different residency, deliberately.

CREATE TABLE resolution_event (
    id                  UUID          PRIMARY KEY,

    -- Stable identifier of the conversation this decision is about, e.g.
    -- "cs:<channel_session.id>" for channel conversations or a caller-chosen
    -- key for anything else. Not an FK: chat-side sessions are not rows.
    conversation_key    VARCHAR(160)  NOT NULL,

    agent_id            UUID          NOT NULL,

    -- ChannelType name when the conversation came through a channel
    -- (WHATSAPP, TELEGRAM, ...); NULL for non-channel resolutions.
    channel             VARCHAR(32),

    outcome             VARCHAR(24)   NOT NULL,
    evidence            VARCHAR(32)   NOT NULL,

    -- 0.000-1.000 classifier confidence. Explicit signals are 1.0; the
    -- idle-close heuristic writes 0.6 so a disputed bill can sort
    -- "confident resolutions" from "assumed resolutions".
    confidence          NUMERIC(4,3)  NOT NULL DEFAULT 1.0,

    billable            BOOLEAN       NOT NULL,
    -- Why the row is (not) billable, in plain text - the audit trail a
    -- tenant reads when disputing a resolution charge.
    billable_reason     VARCHAR(300),

    -- Price snapshot at record time, in the currency's minor unit
    -- (cents/paise). NULL = unpriced (no configured unit price); disputes
    -- and plan changes must never rewrite history, so the price travels
    -- with the row.
    unit_price_minor    BIGINT,
    currency            CHAR(3),

    resolved_at         TIMESTAMPTZ   NOT NULL,
    dedupe_key          VARCHAR(200)  NOT NULL UNIQUE,

    -- Actor for EXPLICIT_API rows (Keycloak subject, opaque VARCHAR, never
    -- FK'd - same convention as user_permission_grant.keycloak_user_id,
    -- V199). NULL for sweep-detected rows.
    created_by          VARCHAR(255),

    -- Dispute bookkeeping (an outcome-priced meter a tenant cannot contest
    -- is a support-ticket generator): disputed rows stay, billable flips
    -- false, and the who/when/why is preserved.
    disputed_at         TIMESTAMPTZ,
    disputed_by         VARCHAR(255),
    dispute_reason      VARCHAR(500),

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The reporting queries: per-agent resolution rates over a period, and the
-- billable-events scan the summary endpoint runs.
CREATE INDEX idx_resolution_event_agent_period ON resolution_event (agent_id, resolved_at);
CREATE INDEX idx_resolution_event_billable_period ON resolution_event (billable, resolved_at);
CREATE INDEX idx_resolution_event_conversation ON resolution_event (conversation_key);
