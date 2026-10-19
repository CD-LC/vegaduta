-- Tenant outbound webhook subscriptions (P1 developer-extensibility,
-- 2026-09-23) - a tenant can finally subscribe to platform events
-- (channel.*.message.received, workflow.run.*, workflow.node.completed,
-- document.generation.completed, approval.*, agent.*, ...) and have them
-- POSTed, HMAC-signed, to their own endpoint. This is the "integrate the
-- platform into my stack" primitive every developer-facing platform ships
-- (Stripe webhooks are the reference shape).
--
-- TENANT-schema tables (no schema qualifier, no tenant_id column - same
-- convention as channel_session / workflow_event_outbox).
--
-- Delivery model: WebhookDispatchListener listens on the in-JVM
-- WorkflowEventSignal fan-out (fires unconditionally, with or without the
-- Kafka outbox path enabled) and writes PENDING webhook_delivery rows in
-- the publishing thread's transaction - cheap, and at-least-once because
-- WebhookDeliveryRelay (TenantSchemaIterator + PgAdvisoryJobLock, same
-- shape as WorkflowEventOutboxRelay) retries failures with backoff until
-- MAX_ATTEMPTS, then dead-letters the row and counts a consecutive
-- failure on the subscription; 25 consecutive failures auto-disable it.
--
-- secret_encrypted: the HMAC-SHA256 signing secret, encrypted at rest by
-- EncryptionService (APP_ENCRYPTION_KEY) like every other stored secret.
-- The plaintext is shown ONCE at create/rotate time, then never again.
--
-- Version V241: controlplane/ and tenant/ share ONE Flyway version stream.
-- Highest taken at write time: tenant/V240 + controlplane/V237. If it
-- collides at merge, rename to the next free number.

CREATE TABLE IF NOT EXISTS webhook_subscription (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    url                   VARCHAR(2048) NOT NULL,
    secret_encrypted      TEXT NOT NULL,
    -- Comma-separated event-type patterns. "*" subscribes to everything;
    -- a trailing ".*" is a prefix match ("workflow.*" -> every
    -- workflow.run.* / workflow.node.* event); anything else is exact.
    event_types           TEXT NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    consecutive_failures  INT NOT NULL DEFAULT 0,
    auto_disabled_reason  VARCHAR(300),
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id              UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES webhook_subscription(id) ON DELETE CASCADE,
    event_type      VARCHAR(200) NOT NULL,
    run_id          VARCHAR(100),
    payload_json    TEXT NOT NULL,
    -- PENDING (awaiting attempt/next_attempt_at) | DELIVERED (2xx) |
    -- DEAD (attempt_count hit the cap - kept for the deliveries view,
    -- never retried again)
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_code   INT,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    duration_ms     INT,
    CONSTRAINT webhook_delivery_status_chk
        CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD'))
);

-- The relay's poll predicate: pending rows whose retry backoff has elapsed.
CREATE INDEX IF NOT EXISTS webhook_delivery_pending_idx
    ON webhook_delivery (next_attempt_at)
    WHERE status = 'PENDING';

-- The per-subscription deliveries view (newest first).
CREATE INDEX IF NOT EXISTS webhook_delivery_by_subscription_idx
    ON webhook_delivery (subscription_id, created_at DESC);
