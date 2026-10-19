-- Channel-based trigger selection (#422) - "I want an option to be provided
-- for consumers to select the channel from where it should be triggered:
-- whatsapp, sms, call - webhook is default and it should be secured...
-- using the secured token oauth and oidc".
--
-- trigger_channel: WEBHOOK (unchanged default) | WHATSAPP | SMS | CALL.
-- webhook_auth_mode: only meaningful while trigger_channel = WEBHOOK -
-- SHARED_SECRET (original #163 token-in-URL behavior, unchanged) or OAUTH2
-- (new - see WorkflowWebhookController's OIDC endpoint).
ALTER TABLE workflow_definition
    ADD COLUMN trigger_channel VARCHAR(16) NOT NULL DEFAULT 'WEBHOOK',
    ADD COLUMN webhook_auth_mode VARCHAR(16) NOT NULL DEFAULT 'SHARED_SECRET';

-- Which workflow handles inbound messages for a given non-webhook trigger
-- channel, for the currently-active tenant. One row per channel (a tenant
-- can only route WHATSAPP-triggered automation to a single workflow at a
-- time) - the exact same one-row-per-channel shape as channel_default_agent
-- (V7), just pointing at a workflow instead of an agent. CALL rows are never
-- actually written today (ChannelWorkflowTriggerService rejects it) but the
-- column accepts it structurally so enabling real Twilio Voice later needs
-- no migration.
CREATE TABLE channel_workflow_trigger (
    id          UUID PRIMARY KEY,
    channel     VARCHAR(16) NOT NULL UNIQUE,
    workflow_id UUID NOT NULL REFERENCES workflow_definition (id) ON DELETE CASCADE,
    updated_at  TIMESTAMPTZ NOT NULL
);
