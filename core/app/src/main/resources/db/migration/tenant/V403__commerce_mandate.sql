-- AP2-style commerce mandates (agentic commerce half).
--
-- commerce_mandate carries BOTH halves of the AP2 mandate chain in one table,
-- split by kind:
--
--   INTENT  - "user authorizes this agent to shop for X up to N" (open-ended,
--             ceiling-only). Can parent many cart mandates.
--   CART    - "user authorized THIS specific spend" - concrete items, total,
--             payee. Always references its intent mandate; the buyer's
--             authorization is itself a signed sub-document (buyer_auth_json
--             + buyer_signature over the same tenant key) so the record proves
--             WHICH user signed WHICH cart - the cryptographic receipt AP2's
--             "cart mandate" is for.
--
-- Like payment_mandate (V345): canonical_json is the exact signed byte string,
-- signatures come from the tenant's payment_signing_key, and rows are
-- immutable - a change is revoke + re-create.
--
-- Status machine: INTENT is born ACTIVE; CART is born PENDING_AUTHORIZATION,
-- moves to AUTHORIZED on buyer signature, to CAPTURED when the wallet debit
-- settles (wallet_debit_ref -> wallet_ledger.id), to REFUNDED on reversal, and
-- to REVOKED/EXPIRED on teardown. FAILED marks a capture attempt that could
-- not settle.
--
-- Amounts are minor units (BIGINT) with an explicit currency - the platform's
-- money rule, generalized past paise since mandates are currency-agnostic.

CREATE TABLE commerce_mandate (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    kind                VARCHAR(8)   NOT NULL CHECK (kind IN ('INTENT','CART')),
    intent_mandate_id   UUID         NULL REFERENCES commerce_mandate(id) ON DELETE RESTRICT,
    agent_id            UUID         NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    buyer_user_id       VARCHAR(128) NOT NULL,   -- authorizing user's JWT subject
    currency            CHAR(3)      NOT NULL DEFAULT 'INR',
    max_total_minor     BIGINT       NULL CHECK (max_total_minor > 0),   -- INTENT: ceiling
    cart_total_minor    BIGINT       NULL CHECK (cart_total_minor > 0),  -- CART: exact total
    items_json          TEXT         NULL,   -- CART: [{sku,name,qty,unit_price_minor}]
    payee               VARCHAR(256) NULL,   -- CART: who is paid (wallet id, VPA, merchant ref)
    categories          TEXT         NULL,   -- INTENT: JSON array of allowed categories
    intent_text         TEXT         NULL,   -- INTENT: the buyer's own words
    status              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','PENDING_AUTHORIZATION','AUTHORIZED',
                                          'CAPTURED','REFUNDED','FAILED','REVOKED','EXPIRED')),
    canonical_json      TEXT         NOT NULL,  -- exact signed bytes
    signature           BYTEA        NOT NULL,  -- Ed25519 over canonical_json
    key_id              VARCHAR(64)  NOT NULL REFERENCES payment_signing_key(key_id),
    buyer_auth_json     TEXT         NULL,   -- CART: the signed buyer-authorization document
    buyer_signature     BYTEA        NULL,
    authorized_at       TIMESTAMPTZ  NULL,
    wallet_debit_ref    BIGINT       NULL,   -- wallet_ledger.id of the capture debit
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ  NULL,
    revoked_by          VARCHAR(128) NULL,
    revoke_reason       TEXT         NULL,
    PRIMARY KEY (id),
    CHECK (kind = 'INTENT' AND max_total_minor IS NOT NULL
        OR kind = 'CART' AND cart_total_minor IS NOT NULL AND intent_mandate_id IS NOT NULL)
);

CREATE INDEX idx_commerce_mandate_agent ON commerce_mandate (agent_id, status);
CREATE INDEX idx_commerce_mandate_intent ON commerce_mandate (intent_mandate_id)
    WHERE intent_mandate_id IS NOT NULL;

-- Append-only audit trail for mandate lifecycle transitions (created,
-- authorized, captured, revoked, refunded) - the dispute evidence AP2 expects.
CREATE TABLE commerce_mandate_event (
    id          BIGSERIAL    PRIMARY KEY,
    mandate_id  UUID         NOT NULL REFERENCES commerce_mandate(id) ON DELETE CASCADE,
    event_type  VARCHAR(32)  NOT NULL,
    actor       VARCHAR(128) NOT NULL,
    detail      TEXT         NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_commerce_mandate_event_mandate ON commerce_mandate_event (mandate_id, id);
