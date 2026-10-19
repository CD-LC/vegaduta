-- OCR provider abstraction ("give an option to use any OCR AI service like
-- google ocr...in tenant admin if required, else use open source OCR
-- reading library"). One row per tenant schema, same "findFirstByOrderByCreatedAtAsc"
-- single-active-credential shape as sarvam_credential (V60) - see
-- OcrProviderConfigService's javadoc.
CREATE TABLE ocr_provider_config (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(32) NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
