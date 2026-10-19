-- A tenant's own Signal connection, reached through the signal-cli REST API
-- (bbernhard/signal-cli-rest-api) that the operator runs themselves. One
-- active row per tenant schema. There is no platform-held secret in Signal:
-- the registered number lives in signal-cli. The only secret here is an
-- OPTIONAL bearer token for a reverse proxy in front of that REST API, kept
-- ciphertext-only like every other channel credential.
--
-- unknown_senders: PAIR (default) answers a first-time sender once with a pairing
-- code the owner approves in the console; IGNORE drops senders that are not
-- already a paired session or a contact, without replying.
-- group_mode: IGNORE (default) | MENTION | ALL - whether the agent answers in
-- Signal group chats at all, and if so only when it is mentioned or always.
-- channel_session.channel is VARCHAR(16) with no CHECK, so SIGNAL needs no
-- change there.

CREATE TABLE signal_credential (
    id                    UUID PRIMARY KEY,
    base_url              VARCHAR(512) NOT NULL,
    account_number        VARCHAR(32)  NOT NULL,
    encrypted_auth_token  TEXT,
    unknown_senders       VARCHAR(16)  NOT NULL DEFAULT 'PAIR',
    group_mode            VARCHAR(16)  NOT NULL DEFAULT 'IGNORE',
    status                VARCHAR(16)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    last_validated_at     TIMESTAMPTZ
);
