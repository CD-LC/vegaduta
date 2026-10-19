-- HTTP polling triggers and HMAC webhook-signature verification for workflow
-- Trigger nodes.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V175's note for the same rule). Highest before this
-- change: V481 (tenant, llm_spend_month_counter) / V479 (controlplane), so
-- V482 is the next slot free in BOTH folders as of this writing.
--
-- PART 1 - webhook signature verification
-- ---------------------------------------
-- The shared-secret webhook path (POST /webhook/workflows/{token}) proves the
-- caller knows the URL, nothing more. Senders that sign their deliveries
-- (GitHub X-Hub-Signature-256, Stripe Stripe-Signature, Slack
-- X-Slack-Signature, or any sender that can produce HMAC-SHA256 of the raw
-- body) get a second factor: the token resolves the trigger node, the node's
-- signature mode picks the verification scheme, and the HMAC key is stored
-- AES-256-GCM-encrypted exactly like webhook_token_encrypted (never returned
-- by any API - only `configured: true/false` is ever read back). 'NONE' keeps
-- today's behavior byte-for-byte, so existing triggers are unaffected.
ALTER TABLE workflow_trigger_node
    ADD COLUMN webhook_signature_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN webhook_signature_secret_encrypted TEXT;

-- PART 2 - HTTP polling triggers
-- ------------------------------
-- The trigger types that exist today all need the outside world to reach IN
-- (webhook delivery, a channel message) or to run on a wall-clock schedule.
-- The n8n/Zapier-shaped gap is "when a new item appears in <app>, run this":
-- most SaaS APIs can answer "list recent items" but can't push webhooks to a
-- self-hosted install. A poll trigger stores the endpoint, the cadence, where
-- the item list and each item's id live in the response (dot paths, not full
-- JSONPath - deliberately tiny, see PollItemExtractor's javadoc), and a small
-- state blob (the seen-id ring that makes re-delivery and restarts safe).
--
-- node_id is UNIQUE for the same reason as workflow_trigger_node (a trigger
-- node owns exactly one binding, whatever its kind). The headers blob is
-- encrypted at rest for the same reason webhook secrets are: it can carry an
-- Authorization header or an API key.
CREATE TABLE workflow_poll_trigger (
    id                    UUID PRIMARY KEY,
    workflow_id           UUID NOT NULL REFERENCES workflow_definition (id) ON DELETE CASCADE,
    node_id               VARCHAR(64) NOT NULL UNIQUE,
    url                   VARCHAR(4096) NOT NULL,
    method                VARCHAR(8) NOT NULL DEFAULT 'GET',
    headers_encrypted     TEXT,
    interval_seconds      INTEGER NOT NULL DEFAULT 300,
    items_path            VARCHAR(256) NOT NULL DEFAULT '',
    id_path               VARCHAR(256) NOT NULL DEFAULT 'id',
    seed_on_enable        BOOLEAN NOT NULL DEFAULT TRUE,
    state_json            TEXT,
    next_poll_at          TIMESTAMPTZ NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    last_status           VARCHAR(16),
    last_polled_at        TIMESTAMPTZ,
    consecutive_failures  INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

-- The ticker's per-tick query: enabled rows whose poll is due. Enabled is in
-- the index (not filtered in SQL alone) because the tick is a table walk
-- otherwise.
CREATE INDEX workflow_poll_trigger_due ON workflow_poll_trigger (enabled, next_poll_at);
