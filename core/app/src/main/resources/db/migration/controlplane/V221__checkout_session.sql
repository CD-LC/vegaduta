-- Agentic commerce v0 (core/billing/commerce, see that package's
-- package-info.java): one row per "collect this amount from this customer"
-- request an agent raised via the create_payment_link tool, backed by a
-- Razorpay UPI Payment Link (plink_...) or QR code (qr_...) and closed by
-- the payment_link.paid / qr_code.credited webhook (UpiPaymentConfirmedHandler).
--
-- ACP-shaped, not ACP-conformant: the status vocabulary and line_items shape
-- mirror OpenAI's Agentic Commerce Protocol CheckoutSession so a later
-- adapter is thin; nothing here verifies an ACP caller or takes a delegated
-- payment token.
--
-- tenant_id is the tenant's SCHEMA NAME (what TenantContext carries and what
-- WorkflowEvent.tenantId expects), not control_plane.tenant.id - both writers
-- (the agent tool and the webhook handler) start from the schema name, and
-- resolving the UUID would cost a tenant lookup per call for no consumer
-- that needs it. Sized to match control_plane.tenant.schema_name (V2).
--
-- line_items is TEXT holding a JSON array, not JSONB - same reasoning as
-- V132/V185: nothing queries into it, it is round-tripped as a document.
--
-- Version 221: controlplane/ and tenant/ share ONE combined Flyway version
-- sequence (see application.yml's spring.flyway comment); 220 (controlplane,
-- V220__marketing_content_engine_workflow_template) was the highest across
-- both folders when this was written. NOTE for the orchestrator: re-verify
-- 221 is still free in BOTH folders before commit - parallel agents this
-- session may have claimed it (the V156 prod incident of 2026-07-31 was a
-- duplicate version number).
--
-- LOCATION NOTE: this file lives in core/billing's resources (the module
-- that owns the table and the only code that touches it), under the same
-- classpath:db/migration/controlplane location application.yml already
-- scans - Flyway 10 (Spring Boot 3.4) merges that location across every
-- jar on the classpath. Every prior migration lives in core/app; if the
-- boot-time migration listing ever fails to pick this one up, moving the
-- file to core/app/src/main/resources/db/migration/controlplane/ unchanged
-- is the fix.

CREATE TABLE IF NOT EXISTS control_plane.checkout_session (
    id                UUID         PRIMARY KEY,
    tenant_id         VARCHAR(63)  NOT NULL,
    agent_id          UUID,
    session_id        VARCHAR(255),
    -- 'agent' for the tool; 'whatsapp' | 'web' | 'a2a' reserved for later writers.
    channel           VARCHAR(32)  NOT NULL DEFAULT 'agent',
    customer_phone    VARCHAR(32),
    customer_name     TEXT,
    currency          VARCHAR(3)   NOT NULL DEFAULT 'INR',
    -- Minor units (paise), never a floating-point major amount - same money
    -- rule as billing_invoice.amount_minor.
    amount_paise      BIGINT       NOT NULL CHECK (amount_paise > 0),
    description       TEXT         NOT NULL,
    line_items        TEXT,
    -- CheckoutSessionStatus enum name: NOT_READY_FOR_PAYMENT | READY_FOR_PAYMENT
    -- | COMPLETED | CANCELED | EXPIRED.
    status            VARCHAR(32)  NOT NULL,
    payment_provider  VARCHAR(16)  NOT NULL DEFAULT 'razorpay',
    -- Razorpay plink_... / qr_... - the key the webhook hands the handler.
    gateway_ref_id    VARCHAR(64),
    short_url         TEXT,
    paid_at           TIMESTAMPTZ,
    -- Gateway captured-payment id (pay_...), when PaidPaymentLookup could
    -- resolve it; NULL means "completed, reconcile the payment id by hand".
    payment_id        VARCHAR(64),
    -- Non-null only when a human needs to look (amount mismatch, gateway refusal).
    review_note       TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- App-side expiry (default 24h); the hosted gateway link itself is not
    -- given an expire_by today - see package-info's "known v0 limits".
    expires_at        TIMESTAMPTZ
);

-- Console list: newest first per tenant.
CREATE INDEX IF NOT EXISTS idx_checkout_session_tenant_created
    ON control_plane.checkout_session (tenant_id, created_at DESC);

-- Webhook attribution: one local row per gateway link, enforced by the
-- database even if two create calls race - and the lookup the
-- payment_link.paid handler does is a single indexed hit.
CREATE UNIQUE INDEX IF NOT EXISTS uq_checkout_session_gateway_ref
    ON control_plane.checkout_session (gateway_ref_id)
    WHERE gateway_ref_id IS NOT NULL;
