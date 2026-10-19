-- V610 - iMessage channel through a BlueBubbles server (2026-10-17).
--
-- Apple offers no API for iMessage, so the only workable open path is a Mac that
-- stays logged in to Messages and runs the open-source BlueBubbles server. This
-- row is the tenant's connection to THAT server: its URL (usually a private LAN
-- or tailnet address, reachable only if the operator lists the host in
-- app.security.outbound.allowed-private-hosts), the server password and the
-- webhook secret, both ciphertext-only (EncryptionService, same as every other
-- channel credential). One row per tenant schema.
--
-- webhook_id is the public, unguessable path segment of
-- /webhook/imessage/{webhookId} (also linked in control_plane.channel_account_link
-- as provider IMESSAGE so the unauthenticated webhook can find its tenant);
-- the call is then authenticated by the per-tenant webhook secret.
--
-- allowed_senders are pre-approved handles (phone numbers in E.164 form or
-- lower-case emails, one per line). Anyone else is default-denied: they get a
-- pairing code (unknown_sender_policy = PAIR) or are dropped silently (IGNORE).
-- allow_groups is off by default; in a group only allow-listed senders are served.
--
-- poll_cursor_ms is the BlueBubbles dateCreated (epoch ms) of the newest message
-- the polling fallback has handled. NULL until polling first starts, which sets it
-- to "now" so history is never replayed into an agent.
--
-- Version V610: controlplane/ and tenant/ share ONE Flyway version stream; this
-- change's reserved range is V610-V614.

CREATE TABLE IF NOT EXISTS imessage_credential (
    id                       UUID PRIMARY KEY,
    webhook_id               VARCHAR(48)  NOT NULL,
    server_url               VARCHAR(512) NOT NULL,
    encrypted_password       TEXT         NOT NULL,
    encrypted_webhook_secret TEXT         NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    inbound_mode             VARCHAR(16)  NOT NULL DEFAULT 'WEBHOOK',
    allowed_senders          TEXT         NOT NULL DEFAULT '',
    allow_groups             BOOLEAN      NOT NULL DEFAULT FALSE,
    unknown_sender_policy    VARCHAR(16)  NOT NULL DEFAULT 'PAIR',
    poll_cursor_ms           BIGINT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_validated_at        TIMESTAMPTZ
);
