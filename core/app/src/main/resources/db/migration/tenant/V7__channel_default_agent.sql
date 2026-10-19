-- Phase 0 Stage 4 follow-up: which agent handles inbound messages for a
-- given channel, scoped per-tenant automatically by living in each
-- tenant's own schema (same mechanism as `agent`, `twilio_credential` -
-- no schema override on the entity, so it lands wherever the current
-- connection's schema is). Replaces the single global
-- WHATSAPP_DEFAULT_AGENT_ID env var, which could only ever name one agent
-- platform-wide, not one independently per tenant.
--
-- NOT auto-applied to any manually-created second tenant schema (e.g. the
-- tenant_b schema from the Stage 4 verification walkthrough) - Flyway only
-- migrates the schema its own connection targets (public) until Stage 5
-- automates per-tenant migration. Copy this CREATE TABLE statement into any
-- additional tenant schema the same way V1's tables were copied.

CREATE TABLE channel_default_agent (
    id          UUID PRIMARY KEY,
    channel     VARCHAR(16) NOT NULL,
    agent_id    UUID NOT NULL REFERENCES agent (id),
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_channel_default_agent_channel UNIQUE (channel)
);
