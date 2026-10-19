-- Agentic UPI Payments (Build Specs §E1) - the mandate half.
--
-- payment_signing_key: the per-tenant Ed25519 keypair every payment_mandate
-- row is signed with. The private half is stored ONLY as
-- EnvelopeEncryptionService ciphertext (DEK wrapped by the platform KEK) -
-- plaintext key material never touches the database. One ACTIVE row per
-- tenant (partial unique index enforces it); rotation appends a RETIRED row,
-- never mutates.
--
-- payment_mandate: a human-granted, cryptographically signed authorization
-- for an agent to move money. canonical_json is the EXACT byte string that
-- was signed (deterministic field order, AP2-envelope-shaped - see
-- com.agenticai.core.payments.MandateCanonicalizer), so execution-time
-- verification re-derives nothing: verify(signature, canonical_json) must
-- pass before any money moves. Mandates are immutable after signing - a
-- "change" is revoke + re-grant, which is what keeps the signature honest.
--
-- Amounts are paise (BIGINT) everywhere - never float/double, same money
-- rule the rest of the codebase already follows.

CREATE TABLE payment_signing_key (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    key_id                 VARCHAR(64) NOT NULL UNIQUE,
    public_key             BYTEA       NOT NULL,
    private_key_encrypted  TEXT        NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','RETIRED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- One live signing key per tenant schema.
CREATE UNIQUE INDEX uq_payment_signing_key_active
    ON payment_signing_key ((status)) WHERE status = 'ACTIVE';

CREATE TABLE payment_mandate (
    id                          UUID         NOT NULL DEFAULT gen_random_uuid(),
    agent_id                    UUID         NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    granted_by_user_id          VARCHAR(128) NOT NULL,
    scope                       VARCHAR(8)   NOT NULL
                                CHECK (scope IN ('COLLECT','PAY','BOTH')),
    max_per_txn_paise           BIGINT       NOT NULL CHECK (max_per_txn_paise > 0),
    max_daily_paise             BIGINT       NOT NULL CHECK (max_daily_paise > 0),
    max_monthly_paise           BIGINT       NOT NULL CHECK (max_monthly_paise > 0),
    -- JSON array of VPA strings ("name@bank"); NULL/empty = unrestricted.
    allowed_vpas                TEXT         NULL,
    -- JSON array of merchant-category strings; NULL/empty = unrestricted.
    allowed_merchant_categories TEXT         NULL,
    approval_policy             VARCHAR(20)  NOT NULL DEFAULT 'ALWAYS'
                                CHECK (approval_policy IN ('ALWAYS','ABOVE_THRESHOLD','NEVER')),
    approval_threshold_paise    BIGINT       NULL,
    valid_from                  TIMESTAMPTZ  NOT NULL,
    valid_until                 TIMESTAMPTZ  NOT NULL,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE','REVOKED','EXPIRED','SUSPENDED')),
    -- The exact signed payload + the Ed25519 signature over it, and which
    -- payment_signing_key row produced it.
    canonical_json              TEXT         NOT NULL,
    signature                   BYTEA        NOT NULL,
    key_id                      VARCHAR(64)  NOT NULL REFERENCES payment_signing_key(key_id),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at                  TIMESTAMPTZ  NULL,
    revoked_by                  VARCHAR(128) NULL,
    revoke_reason               TEXT         NULL,
    PRIMARY KEY (id),
    CHECK (valid_until > valid_from),
    CHECK (approval_policy <> 'ABOVE_THRESHOLD' OR approval_threshold_paise IS NOT NULL)
);

CREATE INDEX idx_payment_mandate_agent ON payment_mandate (agent_id);
CREATE INDEX idx_payment_mandate_agent_status ON payment_mandate (agent_id, status);
