-- Contacts & request mapping (/admin/contacts) - a tenant-level contact
-- directory for inbound channel senders, decoupled from channel_session so a
-- contact can exist BEFORE the person ever messages (CSV/XLSX import) and so
-- an approved sender survives their session row being deleted.
--
-- Routing resolution for a brand-new inbound session (ChannelSessionService
-- #resolveOrCreate) becomes:
--   contact.agent_id -> contact's group.agent_id -> channel_default_agent
-- and a session whose sender matches a known contact starts paired=TRUE
-- immediately - an imported contact IS the approval, so re-challenging them
-- through the pending-pairing queue would be pointless.
--
-- auto_approve_new_senders on channel_default_agent is the tenant's own
-- switch for "not every inbound request needs manual mapping" (Punee's
-- request): TRUE = a never-seen sender goes straight to the channel's default
-- agent; FALSE (default) keeps today's require-approval pairing gate.
--
-- TENANT-schema tables (no schema qualifier, no tenant_id column - same
-- convention as channel_session / channel_default_agent themselves).
--
-- Version V238: controlplane/ and tenant/ share ONE Flyway version stream
-- (application.yml merges both locations). Highest taken at write time:
-- tenant/V235 + controlplane/V237. Reserved in docs/AGENT-COORDINATION.md
-- (2026-09-22 claim); if it collides at merge, rename to the next free number.

CREATE TABLE IF NOT EXISTS contact_group (
    id          UUID PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(500),
    -- Optional group-level routing: a member with no per-contact agent_id
    -- override answers to this agent. Nullable so a group can also be a
    -- purely organizational label ("VIP list") that changes no routing.
    agent_id    UUID REFERENCES agent(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS channel_contact (
    id           UUID PRIMARY KEY,
    channel      VARCHAR(16)  NOT NULL,
    -- The sender's identity AS WRITTEN (phone as typed, Telegram chat id,
    -- WhatsApp LID like "12345@lid", email address). identifier_digits is
    -- the digit-only projection used for phone matching, so a contact
    -- imported as "+1 (555) 123-4567" still matches a session whose
    -- external_id is "15551234567@s.whatsapp.net".
    identifier        VARCHAR(128) NOT NULL,
    identifier_digits VARCHAR(64),
    display_name      VARCHAR(255),
    group_id          UUID REFERENCES contact_group(id) ON DELETE SET NULL,
    -- Per-contact agent override - wins over the group's and the channel's
    -- default agent. SET NULL so deleting an agent never deletes contacts.
    agent_id          UUID REFERENCES agent(id) ON DELETE SET NULL,
    -- INBOUND (created by approving a pending sender) or IMPORT (CSV/XLSX or
    -- manual add). Informational only - both count as "known contact".
    source            VARCHAR(16)  NOT NULL DEFAULT 'IMPORT',
    -- Links back to the live channel_session once this person has actually
    -- messaged in. SET NULL: rejecting/deleting a session must not delete
    -- the contact (the directory entry is the durable record).
    session_id        UUID REFERENCES channel_session(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ,
    UNIQUE (channel, identifier)
);

-- The hot-path read is "is this (channel, identifier) a known contact", once
-- per inbound message; the UNIQUE(channel, identifier) index covers exact
-- matches. This index covers the digit-normalized fallback for phone-shaped
-- identifiers (imported numbers vs LID/wire-format external ids).
CREATE INDEX IF NOT EXISTS idx_channel_contact_digits
    ON channel_contact (channel, identifier_digits)
    WHERE identifier_digits IS NOT NULL;

ALTER TABLE channel_default_agent
    ADD COLUMN IF NOT EXISTS auto_approve_new_senders BOOLEAN NOT NULL DEFAULT FALSE;
