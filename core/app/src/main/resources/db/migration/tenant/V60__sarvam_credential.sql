-- Sarvam Integration Build Plan Stage 1: encrypted-at-rest Sarvam AI API key
-- storage, same shape as twilio_credential (V4) / llm_provider_credential (V1).

CREATE TABLE sarvam_credential (
    id                 UUID PRIMARY KEY,
    encrypted_api_key  TEXT NOT NULL,
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);
