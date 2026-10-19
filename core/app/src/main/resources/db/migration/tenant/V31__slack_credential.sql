-- A tenant's own Slack App connection (Bot Token + Signing Secret). Same
-- shape as meta_whatsapp_credential/meta_social_credential: one active row
-- per tenant schema, ciphertext-only secrets. bot_user_id is stored so
-- SlackWebhookController can filter the bot's own messages back out of
-- inbound Events API payloads (see SlackCredential's javadoc).

CREATE TABLE slack_credential (
    id                          UUID PRIMARY KEY,
    team_id                     VARCHAR(32) NOT NULL,
    team_name                   VARCHAR(255),
    bot_user_id                 VARCHAR(32) NOT NULL,
    encrypted_bot_token         TEXT NOT NULL,
    encrypted_signing_secret    TEXT NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    last_validated_at           TIMESTAMPTZ
);
