-- Master Roadmap 2026 item #17 fast-follow (voice DTMF handling) - see
-- VoiceDtmfMapping.java's javadoc for the full design: one row per keypad
-- digit (0-9, *, #), mapping that digit to the agent a caller should be
-- routed to for the REST of the current call (VoiceRelayWebSocketHandler
-- reads this table when a "dtmf" Conversation Relay message arrives - a gap
-- that class's own javadoc had explicitly flagged as "Stage 1 scope,"
-- deferred rather than silently dropped).
--
-- V98, not V96/V97 - core/app/src/main/resources/application.yml's
-- flyway.locations merges controlplane/ and tenant/ into one version
-- stream; V96 (controlplane/V96__tenant_data_residency_and_erasure.sql) and
-- V97 (this same tenant/ directory's V97__agent_e2b_enabled.sql) are both
-- already taken as of this write - see V97's own header comment for the
-- earlier collision this avoids repeating.
CREATE TABLE voice_dtmf_mapping (
    id UUID PRIMARY KEY,
    digit VARCHAR(1) NOT NULL UNIQUE,
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
