-- A tenant's connected Facebook Page - the single credential that unlocks
-- both Facebook Messenger and (if instagram_business_account_id is set)
-- Instagram DMs, mirroring how Meta's Graph API actually works (see
-- MetaSocialCredential's javadoc). Same shape as meta_whatsapp_credential
-- (V25): one active row per tenant schema, ciphertext-only secrets.

CREATE TABLE meta_social_credential (
    id                              UUID PRIMARY KEY,
    page_id                         VARCHAR(64) NOT NULL,
    page_name                       VARCHAR(255),
    encrypted_page_access_token     TEXT NOT NULL,
    encrypted_app_secret            TEXT NOT NULL,
    instagram_business_account_id   VARCHAR(64),
    instagram_username              VARCHAR(255),
    status                          VARCHAR(16) NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL,
    updated_at                      TIMESTAMPTZ NOT NULL,
    last_validated_at               TIMESTAMPTZ
);
