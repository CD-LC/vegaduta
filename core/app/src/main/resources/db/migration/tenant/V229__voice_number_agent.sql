-- Per-number -> per-agent inbound voice routing.
--
-- Until now a tenant's inbound phone presence was all-or-nothing: every call
-- on every one of that tenant's Twilio numbers reached ONE agent, the
-- ChannelDefaultAgent row for ChannelType.VOICE. TwilioVoiceWebhookController
-- never read the dialed ("To") number at all, so "sales on one number,
-- support on another" was not expressible. This table is that mapping.
--
-- One row per E.164 number, same shape and reasoning as V98's
-- voice_dtmf_mapping (per-row CRUD rather than a settings blob, so one
-- number can be re-pointed without rewriting the rest). See
-- VoiceNumberAgent.java's javadoc for why this is a PER-CALL override of the
-- agent rather than a rebind of the caller's ChannelSession row.
--
-- V229, not V226/V227/V228 - core/app/src/main/resources/application.yml's
-- flyway.locations merges controlplane/ and tenant/ into ONE version stream,
-- and 226 (controlplane/V226__industry_template_expansion.sql), 227 and 228
-- (this directory) are all taken as of this write. Same collision V98's own
-- header comment documents.
CREATE TABLE voice_number_agent (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(16) NOT NULL UNIQUE,
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- The only read on the hot path is "which agent owns this dialed number",
-- once per inbound call, on a column that is already UNIQUE (and therefore
-- already indexed). No further index is needed and none is created.
