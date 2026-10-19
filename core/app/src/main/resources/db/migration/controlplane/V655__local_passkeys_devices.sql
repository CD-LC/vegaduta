-- Passkey, device-PIN and recovery-code sign-in for local mode (app.auth.mode=local; see core/admin
-- .../localauth). Like V510 these tables are created in every install and stay EMPTY unless the mode
-- is switched on and someone enrols a credential. local_account.password_hash is already nullable,
-- which is what lets an account exist with no password at all.
--
-- local_passkey        one WebAuthn credential. credential_id is the base64url id the browser
--                      reports (unique: it is how an assertion finds its row). attested_credential_data
--                      is the serialised AAGUID + credential id + COSE public key, exactly what
--                      webauthn4j rebuilds a credential record from. sign_count and the backup flags are
--                      updated on every use; rp_id is the relying-party id the credential was created for
--                      and is what is verified against at sign-in, so a changed console host makes the
--                      credential unusable rather than silently wrong.
-- local_device         one browser/device binding for PIN sign-in. A PIN is only valid TOGETHER with the
--                      device secret the browser holds, so pin_hash is a hash of pin and secret and no PIN
--                      hash exists without a device. failed_attempts counts consecutive wrong PINs; at the
--                      limit the device is revoked (revoked_at). Rows are kept after revocation.
-- local_recovery_code  one-time recovery codes. Only a SHA-256 of the normalised code is stored. A code
--                      is spent by setting used_at in a single conditional UPDATE; regenerating replaces
--                      the whole set.
CREATE TABLE control_plane.local_passkey (
    id                        UUID PRIMARY KEY,
    account_id                UUID NOT NULL REFERENCES control_plane.local_account (id) ON DELETE CASCADE,
    credential_id             VARCHAR(1400) NOT NULL,
    attested_credential_data  BYTEA NOT NULL,
    sign_count                BIGINT NOT NULL DEFAULT 0,
    uv_initialized            BOOLEAN NOT NULL DEFAULT FALSE,
    backup_eligible           BOOLEAN NOT NULL DEFAULT FALSE,
    backup_state              BOOLEAN NOT NULL DEFAULT FALSE,
    transports                VARCHAR(255) NOT NULL DEFAULT '',
    rp_id                     VARCHAR(255) NOT NULL,
    label                     VARCHAR(100) NOT NULL DEFAULT '',
    created_at                TIMESTAMPTZ NOT NULL,
    last_used_at              TIMESTAMPTZ,
    CONSTRAINT uq_local_passkey_credential UNIQUE (credential_id)
);

CREATE INDEX idx_local_passkey_account ON control_plane.local_passkey (account_id);

CREATE TABLE control_plane.local_device (
    id              UUID PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES control_plane.local_account (id) ON DELETE CASCADE,
    label           VARCHAR(100) NOT NULL DEFAULT '',
    pin_hash        VARCHAR(255) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_local_device_account ON control_plane.local_device (account_id);

CREATE TABLE control_plane.local_recovery_code (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES control_plane.local_account (id) ON DELETE CASCADE,
    code_hash   CHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    CONSTRAINT uq_local_recovery_code_hash UNIQUE (code_hash)
);

CREATE INDEX idx_local_recovery_code_account ON control_plane.local_recovery_code (account_id);
