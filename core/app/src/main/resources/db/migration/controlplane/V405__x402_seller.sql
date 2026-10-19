-- x402 seller surface (agentic commerce: agents that EARN).
--
-- x402_offer: a pay-per-call offer a tenant publishes for one of its agents -
-- "GET /x402/{slug} costs price_minor of currency (internal CREDIT scheme,
-- settled between agent wallets) OR evm_price_atomic of an EVM token (the
-- existing 'exact'/EIP-3009 scheme a facilitator settles on-chain)".
--
-- CONTROL-PLANE like commerce_product (V252): the public /x402/{slug} request
-- arrives with NO TenantContext, so the offer has to be resolvable
-- schema-free; tenant_id records which schema owns it for settlement hops
-- and for the tenant's own offer management.
--
-- x402_payment: one row per presented payment - the nonce UNIQUE is the
-- replay guard (an EIP-3009 nonce or a delegation-token jti can only ever
-- settle once). status: SETTLED = wallet legs written both sides;
-- VERIFIED = cryptographic proof checked, on-chain settlement deferred to
-- the facilitator path (we verify, we do not broadcast); REJECTED = kept as
-- an audit trail of refused payments.

CREATE TABLE IF NOT EXISTS control_plane.x402_offer (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    slug              VARCHAR(128) NOT NULL UNIQUE,   -- URL-safe, tenant-chosen
    tenant_id         VARCHAR(255) NOT NULL,          -- seller schema name
    agent_id          UUID         NOT NULL,
    wallet_id         UUID         NOT NULL,          -- seller wallet (in tenant schema) to credit
    title             VARCHAR(200) NOT NULL,
    description       TEXT         NULL,
    -- Internal-scheme price: minor units of `currency`, debited/credited
    -- between agent wallets.
    price_minor       BIGINT       NOT NULL CHECK (price_minor > 0),
    currency          CHAR(3)      NOT NULL DEFAULT 'INR',
    -- Optional EVM 'exact' scheme: when set, the 402 accepts list also
    -- advertises an EIP-3009 transferWithAuthorization requirement.
    evm_price_atomic  BIGINT       NULL CHECK (evm_price_atomic > 0),
    evm_asset         VARCHAR(64)  NULL,   -- token contract address
    evm_network       VARCHAR(32)  NULL,   -- e.g. base-sepolia
    -- EIP-712 domain fields for the token (USDC: "USD Coin"/"2") - a payer
    -- cannot produce a valid authorization without them, and a seller cannot
    -- verify one without them either.
    evm_asset_name    VARCHAR(64)  NULL,
    evm_asset_version VARCHAR(16)  NULL,
    pay_to            VARCHAR(256) NULL,   -- EVM address receiving payment
    -- Arbitrary JSON served to the caller once payment verifies - the paid
    -- resource itself (v1: static payload configured at publish; upstream
    -- proxying deliberately NOT built - SSRF surface).
    resource_payload  TEXT         NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','DISABLED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_x402_offer_tenant ON control_plane.x402_offer (tenant_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_x402_offer_slug ON control_plane.x402_offer (slug)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS control_plane.x402_payment (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    offer_id          UUID         NOT NULL REFERENCES control_plane.x402_offer(id),
    payer_tenant_id   VARCHAR(255) NULL,   -- buyer schema (internal scheme)
    payer_ref         VARCHAR(256) NOT NULL,  -- wallet id or 0x address
    amount_atomic     BIGINT       NOT NULL CHECK (amount_atomic > 0),
    scheme            VARCHAR(32)  NOT NULL,   -- 'vegaduta-delegation' | 'exact'
    network           VARCHAR(32)  NOT NULL,
    nonce             VARCHAR(160) NOT NULL UNIQUE,  -- replay guard
    status            VARCHAR(16)  NOT NULL
                      CHECK (status IN ('SETTLED','VERIFIED','REJECTED')),
    buyer_ledger_ref  BIGINT       NULL,
    seller_ledger_ref BIGINT       NULL,
    reject_reason     VARCHAR(128) NULL,
    payload           TEXT         NULL,   -- presented X-PAYMENT body (audit)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    settled_at        TIMESTAMPTZ  NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_x402_payment_offer ON control_plane.x402_payment (offer_id, id DESC);
