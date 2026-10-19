-- Tenant product catalog (core/billing/commerce) - the sellable side of the
-- ACP-shaped commerce surface. Checkout sessions (V221) already collect
-- money; until now there was nothing to PUT on a shelf: the
-- create_payment_link tool took a bare amount+description. commerce_product
-- is the tenant-managed catalog that a public product feed
-- (GET /api/commerce/feed/{tenantId}) publishes for shopping agents, and
-- that the feed's checkout endpoint prices server-side (a buyer can pick
-- product+quantity, never the amount).
--
-- CONTROL-PLANE table like checkout_session itself: products are referenced
-- cross-tenant by the public feed path and by webhook reconciliation
-- indirectly, and this module is plain JdbcTemplate (no JPA) - the same
-- posture InvoiceRepository/CheckoutSessionRepository document. tenant_id is
-- the SCHEMA NAME (what TenantContext.getTenantId() carries), matching
-- checkout_session.tenant_id, NOT control_plane.tenant.id.
--
-- published is the feed-visibility flag, deliberately separate from
-- availability: IN_STOCK but unpublished = sellable internally by an agent
-- that already knows the product, not advertised to shopping agents.
--
-- Version 252: V250-259 claimed for this work in docs/AGENT-COORDINATION.md;
-- the shared controlplane+tenant version namespace applies.

CREATE TABLE IF NOT EXISTS control_plane.commerce_product (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     VARCHAR(255) NOT NULL,
    sku           VARCHAR(128),
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    category      VARCHAR(64),
    -- Minor units, never a floating-point major - the same money rule as
    -- every amount in core/billing.
    price_paise   BIGINT       NOT NULL CHECK (price_paise >= 0),
    currency      VARCHAR(3)   NOT NULL DEFAULT 'INR',
    availability  VARCHAR(16)  NOT NULL DEFAULT 'IN_STOCK',
    image_url     VARCHAR(1024),
    product_url   VARCHAR(1024),
    published     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_commerce_product_availability
        CHECK (availability IN ('IN_STOCK', 'OUT_OF_STOCK', 'PREORDER'))
);

-- Backs the tenant's own catalog list in the console.
CREATE INDEX IF NOT EXISTS idx_commerce_product_tenant
    ON control_plane.commerce_product (tenant_id, updated_at DESC);

-- Backs the public feed: one tenant's PUBLISHED rows only.
CREATE INDEX IF NOT EXISTS idx_commerce_product_feed
    ON control_plane.commerce_product (tenant_id)
    WHERE published;
