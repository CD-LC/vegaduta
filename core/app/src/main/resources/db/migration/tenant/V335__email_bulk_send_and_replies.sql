-- V335 (claimed 2026-09-25, email bulk-send + reply-detection stream; V336 is
-- reserved to the same stream and intentionally unused - every column the reply
-- detector needs is created here in one shot).
--
-- email_message   : one row per outbound `bulk_email` workflow-node send, keyed by
--                   the RFC 5322 Message-ID we put on the wire so an inbound
--                   In-Reply-To/References header can be matched back to the
--                   originating run/node/recipient. message_id is stored WITHOUT
--                   angle brackets. reply_kind is REPLY | AUTO_REPLY | BOUNCE.
-- email_suppression: recipients that must never be emailed again (unsubscribe
--                   link, bounce). email is stored lower-cased and is unique.

CREATE TABLE IF NOT EXISTS email_message (
    id             UUID PRIMARY KEY,
    run_id         VARCHAR(64),
    node_id        VARCHAR(64),
    recipient      VARCHAR(320) NOT NULL,
    subject        VARCHAR(998),
    message_id     VARCHAR(512),
    thread_key     VARCHAR(128),
    status         VARCHAR(16)  NOT NULL,
    error          VARCHAR(1000),
    sent_at        TIMESTAMPTZ,
    replied_at     TIMESTAMPTZ,
    reply_kind     VARCHAR(16),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_email_message_message_id ON email_message (message_id);
CREATE INDEX IF NOT EXISTS idx_email_message_run_id     ON email_message (run_id);
CREATE INDEX IF NOT EXISTS idx_email_message_recipient  ON email_message (lower(recipient));

CREATE TABLE IF NOT EXISTS email_suppression (
    id          UUID PRIMARY KEY,
    email       VARCHAR(320) NOT NULL UNIQUE,
    reason      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
