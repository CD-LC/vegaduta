-- SDLC automation sandbox scaffold (#849) - E2B BYOK credential. One row
-- per tenant schema, same "findFirstByOrderByCreatedAtAsc" single-active-
-- credential shape as sarvam_credential (V60)/ocr_provider_config (V76) -
-- see E2bCredentialService's javadoc.
CREATE TABLE e2b_credential (
    id                UUID PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    status            VARCHAR(16) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);
