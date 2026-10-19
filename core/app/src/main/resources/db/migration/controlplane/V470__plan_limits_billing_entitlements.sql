-- Freemium billing hardening (2026-10-05) - five new data-driven feature
-- entitlements on control_plane.plan_limits, same runtime-editable posture as
-- byok_allowed/whatsapp_allowed/white_label/live_vision_allowed (V101, V180):
-- a platform-admin's web-admin plan-limits edit flips them without a redeploy,
-- and platform.free-mode.enabled still resolves every tenant to UNLIMITED's
-- row while it is on.
--
--   external_channels_allowed - connecting a third-party inbound/outbound
--     channel OTHER than the always-free ones (Telegram + Email, which need
--     no customer infrastructure): Slack, Discord, Teams, X, Twilio SMS/voice,
--     Meta Messenger/Instagram, and WhatsApp's QR path. WhatsApp keeps its own
--     whatsapp_allowed flag for the Meta-BYOK and Embedded-Signup paths; the
--     QR pairing path now honours whatsapp_allowed too (it bypassed it before).
--   developer_api_allowed     - minting MCP server access-grant API keys
--     (/api/mcpserver/grants) - the same key system that authenticates
--     /api/dev/v1 and /mcp/**, so one flag gates programmatic access.
--   sso_allowed               - configuring a tenant's own SAML/OIDC IdP
--     (PUT /api/tenant/sso). PREMIUM and above.
--   scim_allowed              - SCIM-lite user provisioning tokens + the
--     /scim/v2 surface they authenticate. UNLIMITED only (enterprise-tier
--     feature) - one tier above SSO, matching the usual market split.
--   workflow_export_allowed   - GET /api/workflows/{id}/export (portable DSL
--     download). Import stays free; taking work OUT of the platform is the
--     paid-tier surface.
--   automation_allowed        - unattended execution: agent schedules
--     (/api/agent-schedules) and event triggers (/api/agents/{id}/triggers) -
--     the surfaces that run the platform's money (hosted LLM calls, channel
--     sends) while nobody is watching. A FREE tenant can still run agents
--     interactively; hands-off automation is the paid tier's capability.
--
-- Seed values implement the plan: FREE gets the aha-moment surface only
-- (build/chat/run within caps, Telegram + Email, one KB collection);
-- PREMIUM unlocks the external channels, the developer API, SSO and
-- unattended automation; UNLIMITED adds SCIM, workflow export and
-- white-label.
ALTER TABLE control_plane.plan_limits
    ADD COLUMN external_channels_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN developer_api_allowed     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sso_allowed               BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scim_allowed              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN workflow_export_allowed   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN automation_allowed        BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE control_plane.plan_limits SET
    external_channels_allowed = TRUE,
    developer_api_allowed     = TRUE,
    sso_allowed               = TRUE,
    automation_allowed        = TRUE
WHERE plan = 'PREMIUM';

UPDATE control_plane.plan_limits SET
    external_channels_allowed = TRUE,
    developer_api_allowed     = TRUE,
    sso_allowed               = TRUE,
    scim_allowed              = TRUE,
    workflow_export_allowed   = TRUE,
    automation_allowed        = TRUE
WHERE plan = 'UNLIMITED';
