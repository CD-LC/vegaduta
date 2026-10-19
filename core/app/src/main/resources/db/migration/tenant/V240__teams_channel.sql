-- V240 - Microsoft Teams channel (2026-09-23).
--
-- BYOK credential for a tenant's own Azure Bot registration: inbound Teams
-- messages arrive as Bot Framework activities (JWT-signed POSTs) at
-- /webhook/teams/{appId}; replies go through the Bot Framework connector API
-- using a client-credentials token minted from app_id + encrypted_app_password.
-- Same "one active row per tenant schema, ciphertext-only secret" shape as
-- telegram_credential (V-sibling), with aad_tenant_id added because Bot
-- Framework tokens are minted against a specific Entra tenant ("botframework.com"
-- for multi-tenant bot registrations, the tenant's own GUID for single-tenant).
--
-- teams_conversation_route stores the per-conversation serviceUrl the Bot
-- Framework requires for replies - it varies per conversation (regional
-- endpoints like https://smba.trafficmanager.net/amer/) and is NOT part of the
-- credential. Written on every inbound activity; read by async send paths
-- (operator inbox replies, workflow "teams" Output nodes, manual sends) that
-- no longer have the triggering activity in hand.
--
-- Version V240: controlplane/ and tenant/ share ONE Flyway version stream.
-- Highest taken at write time: tenant/V239 (agent_xai_server_tools, same
-- session) after V238 + controlplane/V237.

CREATE TABLE IF NOT EXISTS teams_credential (
    id                    UUID PRIMARY KEY,
    app_id                VARCHAR(64) NOT NULL,
    aad_tenant_id         VARCHAR(64) NOT NULL DEFAULT 'botframework.com',
    encrypted_app_password TEXT       NOT NULL,
    status                VARCHAR(16) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_validated_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS teams_conversation_route (
    conversation_id VARCHAR(64)  PRIMARY KEY,
    service_url     VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
