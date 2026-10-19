-- /try Agent Studio "Deploy" stage: a real WhatsApp binding to the
-- PLATFORM-owned Meta WhatsApp Business number (design A - wa.me click-to-chat
-- prefilled with a single-use VD-XXXX pairing code). Absent unless the
-- operator configures app.demo.whatsapp.* (see DemoWhatsAppBot).
--
-- Version note: controlplane/ and tenant/ share one Flyway version sequence.
-- V352 is the highest taken; V341 is reserved by another initiative; this is V353.
--
-- 1) demo_channel_link.channel gains 'WHATSAPP'. external_id for this channel is
--    a keyed HMAC-SHA256 hex digest of the sender's number - NEVER the number.
-- 2) demo_whatsapp_phone: lifetime abuse counters keyed by that same hash, so
--    one phone cannot multiply free inference by pairing many sandboxes
--    (demo_channel_link forgets external_id the moment a link is revoked).

ALTER TABLE control_plane.demo_channel_link
    DROP CONSTRAINT chk_demo_channel_link_channel;
ALTER TABLE control_plane.demo_channel_link
    ADD CONSTRAINT chk_demo_channel_link_channel
        CHECK (channel IN ('TELEGRAM', 'WHATSAPP_PREVIEW', 'WHATSAPP'));

CREATE TABLE control_plane.demo_whatsapp_phone (
    phone_hash   VARCHAR(128) PRIMARY KEY,
    turn_count   INTEGER     NOT NULL DEFAULT 0,
    pair_count   INTEGER     NOT NULL DEFAULT 0,
    first_seen   TIMESTAMPTZ NOT NULL,
    last_seen    TIMESTAMPTZ NOT NULL
);
