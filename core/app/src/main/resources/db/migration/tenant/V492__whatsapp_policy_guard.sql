-- WhatsApp policy guard (docs/channels/whatsapp-policy.md).
--
-- 1. The channel binding records WHY an agent is attached to a channel: the task the
--    tenant declared (WhatsApp's Business Solution Terms restrict general-purpose AI
--    assistants), who declared it and when, and whether they proceeded past the
--    open-ended-agent warning.
-- 2. whatsapp_opt_in: proof that a recipient gave the business their number, checked
--    before every template send.
-- 3. whatsapp_recipient_backoff: per-recipient stop after Meta error 131049 (24 h) or
--    131050 (recipient stopped marketing messages; until opt-in is recorded again).
--
-- Version V492: controlplane/ and tenant/ share ONE Flyway version stream.

ALTER TABLE channel_default_agent
    ADD COLUMN IF NOT EXISTS declared_task VARCHAR(32),
    ADD COLUMN IF NOT EXISTS declared_task_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS declared_task_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS open_ended_override BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS whatsapp_opt_in (
    id            UUID PRIMARY KEY,
    phone_digits  VARCHAR(32)  NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    evidence      TEXT,
    consented_at  TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ,
    recorded_by   VARCHAR(255),
    CONSTRAINT uq_whatsapp_opt_in_phone UNIQUE (phone_digits)
);

CREATE TABLE IF NOT EXISTS whatsapp_recipient_backoff (
    phone_digits  VARCHAR(32) PRIMARY KEY,
    blocked_until TIMESTAMPTZ,
    permanent     BOOLEAN     NOT NULL DEFAULT FALSE,
    meta_code     INTEGER     NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
