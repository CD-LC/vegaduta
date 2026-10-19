-- A tenant's own Telegram Bot connection (Bot Token + a platform-generated
-- webhook secret used to verify Telegram's inbound calls). One active row
-- per tenant schema, ciphertext-only token.

CREATE TABLE telegram_credential (
    id                      UUID PRIMARY KEY,
    bot_user_id             VARCHAR(32) NOT NULL,
    bot_username            VARCHAR(255),
    encrypted_bot_token     TEXT NOT NULL,
    webhook_secret          VARCHAR(64) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    last_validated_at       TIMESTAMPTZ
);
