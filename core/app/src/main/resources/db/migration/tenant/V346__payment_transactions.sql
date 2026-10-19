-- Agentic UPI Payments (Build Specs §E1) - transactions + the append-only
-- mandate spend ledger.
--
-- payment_transaction: every money movement an agent initiated under a
-- mandate. INITIATED -> PENDING_APPROVAL (mandate policy asked for a human)
-- or straight to AWAITING_PAYMENT once the gateway has a live link; terminal
-- states CAPTURED / FAILED / EXPIRED / REFUNDED. idempotency_key is UNIQUE -
-- a replayed tool call returns the existing row instead of double-charging.
-- razorpay_entity_id is the gateway-side object (plink_.../qr_...) the
-- webhook's reference id resolves against.
--
-- mandate_ledger: append-only running totals - the ONLY place caps are
-- enforced, via SELECT ... FOR UPDATE on the latest row for the mandate
-- (MandateLedgerRepository#findLatestForUpdate), never by recomputing SUM()
-- over history. A CHARGE row reserves spend at initiation so a
-- PENDING_APPROVAL transaction still counts against the cap; a REVERSAL row
-- releases it on rejection/failure/expiry. UNIQUE (transaction_id,
-- entry_kind) makes the reversal idempotent under retries.
--
-- Amounts are paise (BIGINT) everywhere - never float/double.

CREATE TABLE payment_transaction (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    mandate_id          UUID         NOT NULL REFERENCES payment_mandate(id),
    agent_id            UUID         NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id          VARCHAR(128) NULL,
    type                VARCHAR(20)  NOT NULL
                        CHECK (type IN ('PAYMENT_LINK','UPI_COLLECT','PAYOUT')),
    amount_paise        BIGINT       NOT NULL CHECK (amount_paise > 0),
    currency            CHAR(3)      NOT NULL DEFAULT 'INR',
    vpa                 VARCHAR(128) NULL,
    description         TEXT         NULL,
    merchant_category   VARCHAR(64)  NULL,
    gateway             VARCHAR(16)  NOT NULL DEFAULT 'RAZORPAY',
    razorpay_entity_id  VARCHAR(64)  NULL,
    gateway_payment_id  VARCHAR(64)  NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'INITIATED'
                        CHECK (status IN
                            ('INITIATED','PENDING_APPROVAL','AWAITING_PAYMENT',
                             'CAPTURED','FAILED','EXPIRED','REFUNDED')),
    approval_id         UUID         NULL,
    idempotency_key     VARCHAR(160) NOT NULL UNIQUE,
    short_url           VARCHAR(512) NULL,
    receipt_url         VARCHAR(512) NULL,
    failure_reason      TEXT         NULL,
    expires_at          TIMESTAMPTZ  NULL,
    next_poll_at        TIMESTAMPTZ  NULL,
    poll_count          INTEGER      NOT NULL DEFAULT 0,
    paid_at             TIMESTAMPTZ  NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_payment_transaction_agent ON payment_transaction (agent_id, created_at DESC);
CREATE INDEX idx_payment_transaction_mandate ON payment_transaction (mandate_id);
CREATE INDEX idx_payment_transaction_razorpay ON payment_transaction (razorpay_entity_id);
-- The reconciliation poller's working set.
CREATE INDEX idx_payment_transaction_poll
    ON payment_transaction (status, next_poll_at) WHERE status = 'AWAITING_PAYMENT';

CREATE TABLE mandate_ledger (
    id                    BIGSERIAL    PRIMARY KEY,
    mandate_id            UUID         NOT NULL REFERENCES payment_mandate(id),
    transaction_id        UUID         NULL REFERENCES payment_transaction(id),
    entry_kind            VARCHAR(12)  NOT NULL CHECK (entry_kind IN ('CHARGE','REVERSAL')),
    amount_paise          BIGINT       NOT NULL CHECK (amount_paise >= 0),
    -- Running totals AFTER this entry, for the windows stamped on the row.
    day_key               DATE         NOT NULL,
    daily_spend_paise     BIGINT       NOT NULL,
    month_key             CHAR(7)      NOT NULL, -- 'YYYY-MM'
    monthly_spend_paise   BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (transaction_id, entry_kind)
);

CREATE INDEX idx_mandate_ledger_mandate ON mandate_ledger (mandate_id, id DESC);
