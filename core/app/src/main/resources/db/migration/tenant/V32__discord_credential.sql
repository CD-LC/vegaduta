-- A tenant's own Discord Application/Bot connection (Bot Token only - see
-- DiscordCredential's javadoc for why there's no second secret here, unlike
-- Slack/Meta). One active row per tenant schema, ciphertext-only token.

CREATE TABLE discord_credential (
    id                      UUID PRIMARY KEY,
    application_id          VARCHAR(32) NOT NULL,
    bot_username            VARCHAR(255),
    encrypted_bot_token     TEXT NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    last_validated_at       TIMESTAMPTZ
);
