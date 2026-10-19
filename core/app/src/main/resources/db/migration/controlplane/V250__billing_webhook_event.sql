-- Billing webhook event ledger (x-razorpay-event-id dedupe; closes the
-- carried-forward "Razorpay expire_by + event-id dedupe" follow-up and the
-- "not deduplicated by x-razorpay-event-id" limit in billing/commerce's
-- package-info). Shape follows the billing_webhook_events table sketched in
-- docs/plans/VegaDuta-Build-Specs.md, with one deliberate addition:
--
-- `consumer` - TWO ingestion paths process the same Razorpay event stream:
-- /webhook/razorpay (RazorpayWebhookController -> RazorpayWebhookService,
-- tenant plan state) and /api/billing/webhook/{gateway}
-- (BillingWebhookController -> BillingEventDispatcher: dunning emails,
-- checkout-session completion). If Razorpay is ever configured to deliver to
-- both, each delivery carries the SAME x-razorpay-event-id, so dedupe keyed
-- on the event id alone would silently suppress whichever path ran second.
-- The dedupe key is therefore (gateway, consumer, gateway_event_id).
--
-- `gateway_event_id` is the gateway's delivery id (Razorpay's
-- x-razorpay-event-id header). For a gateway that sends no delivery id the
-- writer stores "sha256:<hex of the raw body>", which still dedupes the
-- byte-identical redeliveries a retry produces.
--
-- Version 250: controlplane/ and tenant/ share ONE combined Flyway version
-- sequence (see application.yml's spring.flyway comment); 240 was the
-- highest across both folders when this was written, and the concurrent
-- P1-extensibility session has claimed V241+ - the 250-259 range is claimed
-- for this work in docs/AGENT-COORDINATION.md (the V156 prod incident was a
-- duplicate version number).

CREATE TABLE IF NOT EXISTS control_plane.billing_webhook_event (
    id                UUID         PRIMARY KEY,
    gateway           VARCHAR(32)  NOT NULL,
    consumer          VARCHAR(64)  NOT NULL,
    gateway_event_id  VARCHAR(160) NOT NULL,
    event_type        VARCHAR(64),
    payload           JSONB,
    -- Always TRUE on rows written post-verification (both controllers only
    -- record after the signature check passes); the column exists so a
    -- future pre-verify quarantine path can record rejected deliveries.
    signature_valid   BOOLEAN      NOT NULL DEFAULT TRUE,
    -- RECEIVED -> PROCESSED | FAILED | SKIPPED (SKIPPED = verified but
    -- nothing to apply - no handler, unknown tenant, etc.)
    processing_status VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    error             TEXT,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);

-- The dedupe contract itself: one row per (gateway, consumer, event id),
-- enforced by the database even when two redeliveries race.
CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_webhook_event_dedupe
    ON control_plane.billing_webhook_event (gateway, consumer, gateway_event_id);

-- Ops queries: recent failures, and "did event X ever arrive".
CREATE INDEX IF NOT EXISTS idx_billing_webhook_event_status_received
    ON control_plane.billing_webhook_event (processing_status, received_at DESC);
