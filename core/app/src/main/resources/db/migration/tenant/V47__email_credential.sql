-- A tenant's own IMAP mailbox connection (PRD Section 3.2's Mail-Checking
-- Agent, made real and per-tenant). One active row per tenant schema,
-- ciphertext-only password. Outbound replies reuse the platform's existing
-- shared JavaMailSender bean (see EmailChannelAdapter) - only inbound IMAP
-- is per-tenant here.

CREATE TABLE email_credential (
    id                          UUID PRIMARY KEY,
    imap_host                   VARCHAR(255) NOT NULL,
    imap_port                   INTEGER NOT NULL,
    imap_username               VARCHAR(255) NOT NULL,
    encrypted_imap_password     TEXT NOT NULL,
    use_ssl                     BOOLEAN NOT NULL DEFAULT TRUE,
    status                      VARCHAR(16) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    last_validated_at           TIMESTAMPTZ,
    last_polled_at              TIMESTAMPTZ
);
