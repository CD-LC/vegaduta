-- Autonomy/Guardrails Strategy Stage A: untrusted-inbound-sender pairing
-- (OpenClaw's "DM pairing" pattern, adapted). Before this, ANY inbound
-- WhatsApp sender reached the tenant's default agent immediately on first
-- contact - a real gap: a stranger who discovers a tenant's WhatsApp number
-- got a live agent session with whatever tools/knowledge that tenant's
-- default agent has, no approval step at all.
--
-- `paired` defaults TRUE here specifically to backfill EXISTING rows only
-- (every channel_session created before this migration keeps working
-- exactly as it did) - the default is dropped immediately after, so every
-- *new* row from here on must set it explicitly (ChannelSession's
-- constructor sets it to FALSE for brand-new sessions - see its javadoc).
-- Same "backfill true, new default false" asymmetry Stage A's tool
-- allow-list migration uses for the same reason: don't silently break
-- what's already working.
ALTER TABLE channel_session
    ADD COLUMN paired              BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN pairing_code        VARCHAR(16),
    ADD COLUMN pairing_notice_sent BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE channel_session ALTER COLUMN paired DROP DEFAULT;
ALTER TABLE channel_session ALTER COLUMN pairing_notice_sent DROP DEFAULT;
