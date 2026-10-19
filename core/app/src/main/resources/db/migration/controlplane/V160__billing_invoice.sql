-- GST-ready invoicing (docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md §10 item
-- D6): one immutable tax-invoice row per captured payment, written by
-- InvoiceService#recordPaymentInvoice (core/billing/invoice). Amounts are
-- minor units (paise/cents); for INR the charged amount is treated as GST-
-- INCLUSIVE (amount_minor = base + gst_amount_minor at gst_rate_bp, default
-- 1800 bp = 18% IGST for SaaS/OIDAR); USD invoices carry zero GST under the
-- LUT export-of-services assumption - see InvoiceService's javadoc.
--
-- invoice_number is VD-YYYYMM-NNNNN where NNNNN comes off the sequence below
-- (one global monotonic series, NOT reset per month - GST rules only require
-- consecutive/unique within a financial year, and a single gapless-enough
-- series satisfies that without per-month bookkeeping).
--
-- Version 160: the tenant/ and controlplane/ folders share ONE combined
-- Flyway version sequence (see V114's header); 158 (controlplane) / 155
-- (tenant) were the highest across both folders when this was written, and
-- V159/V161 are RESERVED by parallel agents this session. NOTE for the
-- orchestrator: re-verify 160 is still free in BOTH folders before commit
-- (duplicate version numbers = the V156 prod incident of 2026-07-31).

CREATE SEQUENCE control_plane.billing_invoice_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE control_plane.billing_invoice (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL,
    invoice_number     VARCHAR(32) NOT NULL UNIQUE,
    gateway            VARCHAR(16),
    gateway_payment_id VARCHAR(64),
    plan               VARCHAR(16),
    period             VARCHAR(8),
    currency           VARCHAR(3),
    amount_minor       BIGINT,
    gst_rate_bp        INT,
    gst_amount_minor   BIGINT,
    place_of_supply    VARCHAR(64),
    customer_gstin     VARCHAR(15),
    customer_name      TEXT,
    issued_at          TIMESTAMPTZ
);

CREATE INDEX idx_billing_invoice_tenant ON control_plane.billing_invoice (tenant_id, issued_at DESC);

-- Durable idempotency backstop for recordPaymentInvoice: one invoice per
-- gateway payment id, enforced by the database even if two webhook
-- deliveries race past the application-level pre-check.
CREATE UNIQUE INDEX uq_billing_invoice_gateway_payment
    ON control_plane.billing_invoice (gateway_payment_id)
    WHERE gateway_payment_id IS NOT NULL;

-- Tenant billing profile (PUT /api/tenant/billing-profile): the customer-
-- side details snapshotted onto each invoice at issue time. gstin nullable -
-- B2C/unregistered customers simply have none.
ALTER TABLE control_plane.tenant
    ADD COLUMN gstin           VARCHAR(15),
    ADD COLUMN billing_name    TEXT,
    ADD COLUMN billing_address TEXT;
