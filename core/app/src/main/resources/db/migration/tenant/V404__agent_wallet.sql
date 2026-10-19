-- Agent wallet + ledger (agentic commerce: the spendable balance).
--
-- agent_wallet: one wallet per (agent, currency) - the balance an agent can
-- spend FROM, funded by the tenant operator. Balance lives on the wallet row
-- and is only ever mutated inside a SELECT ... FOR UPDATE serialization
-- point (AgentWalletService), same idiom as mandate_ledger's cap row.
--
-- wallet_ledger: append-only; every balance change is a CREDIT or DEBIT row
-- carrying the resulting balance_after, so the ledger alone reconstructs any
-- wallet's history. idempotency_key is CALLER-SUPPLIED and UNIQUE - unlike
-- payment_transaction's argument-derived key, wallet operations are invoked
-- by other subsystems (x402 settlement nonce, cart-mandate capture id) that
-- must be able to safely retry the same logical movement.
--
-- reference_type/reference_id link a ledger row back to what caused it
-- (e.g. 'commerce_mandate' + cart id, 'x402' + payment id) - enough for
-- reconciliation without a join contract the other table must keep.

CREATE TABLE agent_wallet (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    agent_id      UUID        NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    currency      CHAR(3)     NOT NULL DEFAULT 'INR',
    balance_minor BIGINT      NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_by    VARCHAR(128) NOT NULL,   -- operator subject who opened it
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_agent_wallet_agent_currency UNIQUE (agent_id, currency)
);

CREATE TABLE wallet_ledger (
    id              BIGSERIAL    PRIMARY KEY,
    wallet_id       UUID         NOT NULL REFERENCES agent_wallet(id) ON DELETE CASCADE,
    entry_kind      VARCHAR(8)   NOT NULL CHECK (entry_kind IN ('CREDIT','DEBIT')),
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor > 0),
    balance_after   BIGINT       NOT NULL CHECK (balance_after >= 0),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    reference_type  VARCHAR(32)  NULL,
    reference_id    VARCHAR(128) NULL,
    note            TEXT         NULL,
    created_by      VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_ledger_wallet ON wallet_ledger (wallet_id, id DESC);
CREATE INDEX idx_wallet_ledger_reference ON wallet_ledger (reference_type, reference_id)
    WHERE reference_type IS NOT NULL;
