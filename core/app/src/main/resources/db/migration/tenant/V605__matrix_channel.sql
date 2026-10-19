-- Matrix channel: a tenant's own bot user on a homeserver the operator chooses (Client-Server API,
-- sync loop). One row per tenant schema, ciphertext-only access token, same shape as
-- telegram_credential. There is no webhook secret: Matrix has no inbound webhook, the platform
-- pulls with /sync.
--
-- since_token is the /sync "next_batch" of the last fully processed batch. It lives on the
-- credential row so a restart resumes where it stopped instead of replaying history.
CREATE TABLE matrix_credential (
    id                      UUID PRIMARY KEY,
    homeserver_url          VARCHAR(512)  NOT NULL,
    bot_user_id             VARCHAR(255)  NOT NULL,
    device_id               VARCHAR(128),
    encrypted_access_token  TEXT          NOT NULL,
    status                  VARCHAR(16)   NOT NULL,
    -- OFF (default: never accept an invite) or ALLOWLIST (join when the inviter is allow-listed).
    join_policy             VARCHAR(16)   NOT NULL DEFAULT 'OFF',
    -- PAIRING (default: any sender reaches the admin-approval pairing flow) or ALLOWLIST (only
    -- allow-listed senders do; everyone else is dropped silently).
    sender_mode             VARCHAR(16)   NOT NULL DEFAULT 'PAIRING',
    -- Newline/comma separated MXIDs or "@*:server" patterns.
    allowed_senders         TEXT          NOT NULL DEFAULT '',
    since_token             TEXT,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    last_validated_at       TIMESTAMPTZ
);

-- One-shot notices per room (today: "this room is end-to-end encrypted, unsupported"), so the bot
-- says it once and then stays quiet.
CREATE TABLE matrix_room_notice (
    id          UUID PRIMARY KEY,
    room_id     VARCHAR(255) NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_matrix_room_notice UNIQUE (room_id, kind)
);

-- A Matrix room id ("!opaque:server", up to 255 bytes) is the session key. The widening is a
-- metadata-only change in Postgres (no table rewrite) and keeps every existing row as it was.
ALTER TABLE channel_session ALTER COLUMN external_id TYPE VARCHAR(255);
ALTER TABLE channel_message_log ALTER COLUMN external_id TYPE VARCHAR(255);
ALTER TABLE privacy_consent_record ALTER COLUMN external_id TYPE VARCHAR(255);
