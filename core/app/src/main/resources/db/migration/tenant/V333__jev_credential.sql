-- Tenant BYOK for Jev (TypeSafe's System One decision model): encrypted-at-rest
-- key storage so a tenant can bring their own TypeSafe key - same shape as
-- sarvam_credential (V60) / twilio_credential (V4). Resolved by
-- JevCredentialService; resolution order (tenant row -> platform TYPESAFE
-- credential -> env bootstrap) lives in JevDecisionService.

CREATE TABLE jev_credential (
    id                 UUID PRIMARY KEY,
    encrypted_api_key  TEXT NOT NULL,
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);
