-- Direct Meta WhatsApp Business Platform (Cloud API) connection - the
-- alternative to twilio_credential (V4) for tenants who register their own
-- Meta App/WhatsApp Business Account rather than going through Twilio as a
-- BSP. Same shape as twilio_credential (one active row per tenant schema,
-- ciphertext-only secrets), with two encrypted secrets instead of one - see
-- MetaWhatsAppCredential's javadoc for why the access token and App Secret
-- are stored separately.

CREATE TABLE meta_whatsapp_credential (
    id                       UUID PRIMARY KEY,
    phone_number_id          VARCHAR(64) NOT NULL,
    waba_id                  VARCHAR(64) NOT NULL,
    encrypted_access_token   TEXT NOT NULL,
    encrypted_app_secret     TEXT NOT NULL,
    display_phone_number     VARCHAR(32),
    status                   VARCHAR(16) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    last_validated_at        TIMESTAMPTZ
);
