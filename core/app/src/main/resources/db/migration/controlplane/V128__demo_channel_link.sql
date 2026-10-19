-- "Connect a real messenger to your demo sandbox" - the control-plane
-- bookkeeping that lets an UNREGISTERED visitor talk to their throwaway
-- sandbox agent from a real messaging app (Telegram today), and that records
-- honestly which channels visitors actually try.
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into one shared version sequence (see application.yml's flyway comment).
-- V126 (demo_sandbox_session) and V127 are taken, so this is V128.
--
-- ---------------------------------------------------------------------------
-- WHY THIS LIVES IN control_plane AND NOT IN THE DEMO TENANT'S OWN SCHEMA
-- ---------------------------------------------------------------------------
-- Identical reasoning to V126's demo_session: an inbound Telegram update
-- arrives carrying nothing but a chat_id. Resolving that chat_id to a
-- Postgres schema is the *precondition* for entering a tenant schema at all,
-- so the mapping cannot itself be stored inside one. This is the same shape
-- as control_plane.channel_account_link (V6) for registered tenants - a
-- deliberately separate table rather than a reuse of it, because that table
-- keys an external account to a TENANT for a paying, authenticated,
-- indefinitely-lived binding, whereas everything here is scoped to a 5-day
-- anonymous session, expires with it, and must never grant a real tenant
-- anything.
--
-- ---------------------------------------------------------------------------
-- WHAT A ROW MEANS PER CHANNEL (one row per (session, channel), always)
-- ---------------------------------------------------------------------------
--   channel = 'TELEGRAM'
--       A real, working binding to the shared platform demo bot.
--       state PENDING  -> a pairing code has been issued and shown on /try,
--                         nobody has redeemed it yet.
--       state LINKED   -> external_id holds the Telegram chat_id, and messages
--                         to the bot from that chat are answered by THIS
--                         sandbox's agent.
--       state REVOKED  -> the visitor disconnected, the session expired, or
--                         the same Telegram account re-paired to a newer
--                         sandbox (see the partial unique index below).
--
--   channel = 'WHATSAPP_PREVIEW'
--       NOT a WhatsApp connection, and deliberately named so nobody reading
--       this table later mistakes it for one. The platform has no verified
--       business sender that can message an arbitrary stranger's number, and
--       asking an anonymous visitor to scan their personal WhatsApp into an
--       unregistered sandbox (the Baileys channels/whatsapp-qr path) is both
--       a Meta-ToS violation and an unreasonable trust ask. So the /try page
--       renders the visitor's REAL agent reply inside a WhatsApp-styled
--       frame, clearly labelled as a preview with simulated delivery, and
--       this row records only that the visitor tried it.
--       state LINKED   -> the visitor used the preview at least once;
--                         linked_at is that first use. external_id is always
--                         NULL: no phone number is collected, anywhere.
--
-- ---------------------------------------------------------------------------
-- ANALYTICS (the "how many tried each channel" question, answerable as-is)
-- ---------------------------------------------------------------------------
--   -- funnel per channel
--   SELECT channel,
--          COUNT(*)                                        AS sessions_offered,
--          COUNT(*) FILTER (WHERE linked_at IS NOT NULL)   AS sessions_activated,
--          COUNT(*) FILTER (WHERE message_count > 0)       AS sessions_conversed,
--          SUM(message_count)                              AS turns
--     FROM control_plane.demo_channel_link
--    GROUP BY channel;
--
--   -- did connecting a messenger correlate with registering?
--   SELECT l.channel,
--          COUNT(*)                                              AS linked,
--          COUNT(*) FILTER (WHERE s.converted_tenant_id IS NOT NULL) AS converted
--     FROM control_plane.demo_channel_link l
--     JOIN control_plane.demo_session s ON s.id = l.demo_session_id
--    WHERE l.linked_at IS NOT NULL
--    GROUP BY l.channel;
--
-- Everything an analytics job needs is on this one table plus demo_session -
-- no per-tenant schema fan-out required, which is the whole point of keeping
-- it in the control plane.

CREATE TABLE control_plane.demo_channel_link (
    id                UUID PRIMARY KEY,
    -- The sandbox this binding belongs to. Not an FK, same call and same
    -- reasoning as demo_session.tenant_id in V126: a future operator-driven
    -- hard purge of old demo rows should not fail on a NO ACTION constraint.
    demo_session_id   UUID         NOT NULL,
    -- 'TELEGRAM' | 'WHATSAPP_PREVIEW'. VARCHAR + a CHECK rather than a
    -- Postgres ENUM type, matching every other status/type column in this
    -- schema - adding a channel is then an ALTER on the constraint, not a
    -- type migration.
    channel           VARCHAR(24)  NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    -- Human-typeable pairing code, e.g. 'VD-7F3K'. Short on purpose: it is
    -- read off a screen and typed into (or deep-linked at) a phone, and it is
    -- NOT a credential in the bearer-token sense - redeeming one binds a chat
    -- to a message-capped, 5-day, FREE-plan sandbox and nothing else. It is
    -- still generated from SecureRandom over an unambiguous alphabet, and it
    -- stops being redeemable the moment it is used.
    pairing_code      VARCHAR(16),
    -- Telegram chat_id once redeemed; ALWAYS NULL for WHATSAPP_PREVIEW.
    -- Deliberately never a phone number, an email, or anything else that
    -- identifies a person off-platform.
    external_id       VARCHAR(128),
    -- What /try shows next to "Connected" - a Telegram first name or
    -- @username, truncated. Display only; never used for lookup or auth.
    peer_label        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL,
    -- First successful activation. NULL means "offered but never used",
    -- which is exactly the denominator the funnel query above needs.
    linked_at         TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    last_message_at   TIMESTAMPTZ,
    -- Turns exchanged over this channel. Distinct from
    -- demo_session.message_count, which is the sandbox-wide cap counter that
    -- BOTH /api/demo/chat and the Telegram bridge spend from - this one is
    -- per-channel attribution for the funnel, not a limit.
    message_count     INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT chk_demo_channel_link_channel
        CHECK (channel IN ('TELEGRAM', 'WHATSAPP_PREVIEW')),
    CONSTRAINT chk_demo_channel_link_state
        CHECK (state IN ('PENDING', 'LINKED', 'REVOKED')),
    -- One row per sandbox per channel, forever. Re-pairing after a revoke
    -- resets THIS row rather than inserting a second one, which is what keeps
    -- the analytics denominator ("sandboxes offered this channel") honest
    -- instead of inflating it every time someone taps the button twice.
    CONSTRAINT uq_demo_channel_link_session_channel
        UNIQUE (demo_session_id, channel)
);

-- A pairing code must resolve to exactly one sandbox while it is live.
-- Partial: spent/abandoned codes are cleared to NULL on redeem, and NULLs
-- would otherwise pile up under a plain unique index's distinct-NULL rule
-- for no benefit.
CREATE UNIQUE INDEX uq_demo_channel_link_pairing_code
    ON control_plane.demo_channel_link (channel, pairing_code)
    WHERE pairing_code IS NOT NULL;

-- THE load-bearing abuse constraint: one external chat can be bound to at
-- most one live sandbox at a time. Without this, one Telegram account could
-- hold several sandboxes open and multiply its free-inference allowance;
-- with it, redeeming a new code necessarily revokes the previous binding
-- (DemoChannelLinkService does that revoke in the same transaction, so the
-- index is a backstop against a concurrent double-redeem, not the primary
-- mechanism). Partial on LINKED so revoked history stays queryable.
CREATE UNIQUE INDEX uq_demo_channel_link_live_external
    ON control_plane.demo_channel_link (channel, external_id)
    WHERE state = 'LINKED' AND external_id IS NOT NULL;

-- The inbound webhook's hot path: chat_id -> live binding, one probe.
CREATE INDEX idx_demo_channel_link_external
    ON control_plane.demo_channel_link (channel, external_id);

-- The /try page's poll: "what does this sandbox have bound?"
CREATE INDEX idx_demo_channel_link_session
    ON control_plane.demo_channel_link (demo_session_id);


-- ---------------------------------------------------------------------------
-- The mirror. Why a second table and not a jsonb column on the link row.
-- ---------------------------------------------------------------------------
-- The product requirement is that a turn the visitor typed on their PHONE
-- shows up on the /try page they left open on their laptop ("reply from
-- Telegram appears here too"). That is an append-from-one-process,
-- read-from-another feed, which a jsonb blob rewritten on every message would
-- serve badly (lost updates under concurrent inbound + poll, and a read that
-- has to deserialise the whole history to find what is new).
--
-- Bounded by construction: DemoChannelLinkService prunes to the most recent
-- N rows per session after each append, and nothing here outlives its
-- sandbox. This is a display buffer, NOT the conversation of record - the
-- real transcript lives in the demo tenant's own schema, written by the same
-- AgentChatService path every registered tenant uses.
CREATE TABLE control_plane.demo_channel_message (
    id               UUID PRIMARY KEY,
    demo_session_id  UUID         NOT NULL,
    channel          VARCHAR(24)  NOT NULL,
    -- 'IN'  = the visitor -> the agent (typed in the messenger)
    -- 'OUT' = the agent -> the visitor (the reply the bot sent)
    direction        VARCHAR(8)   NOT NULL,
    body             TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_demo_channel_message_direction
        CHECK (direction IN ('IN', 'OUT'))
);

-- Both accesses are "this session's recent messages, newest last": the /try
-- poll (created_at > :since) and the prune (offset past the newest N).
CREATE INDEX idx_demo_channel_message_session_time
    ON control_plane.demo_channel_message (demo_session_id, created_at);
