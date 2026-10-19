-- Omnichannel Build Plan Stage 2: Twilio WhatsApp credential storage,
-- BYOK/encrypted-at-rest, same shape as llm_provider_credential (V1).

CREATE TABLE twilio_credential (
    id                     UUID PRIMARY KEY,
    account_sid            VARCHAR(64) NOT NULL,
    encrypted_auth_token   TEXT NOT NULL,
    whatsapp_from_number   VARCHAR(32) NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    last_validated_at      TIMESTAMPTZ
);
