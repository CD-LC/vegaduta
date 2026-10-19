-- Platform-admin-settable credentials for tool-capability providers that
-- were previously BYOK-only with zero admin path (ElevenLabs voice, E2B code
-- execution) - 2026-08-03, direct feedback ("even I should be able to add in
-- platform admin"). One row per tool, keyed by a short constant string
-- ("ELEVENLABS", "E2B") rather than an auto-increment id, since there is
-- exactly one platform-wide credential per tool by design (unlike
-- platform_hosted_llm_provider, which is genuinely multi-row/priority-
-- ordered). encrypted_api_key uses the same EncryptionService AES scheme as
-- every other credential table in this schema (platform_hosted_llm_provider,
-- sarvam_credential, elevenlabs_credential, e2b_credential) - never stored
-- in plaintext. extra_config is a single free-text field for whatever the
-- tool needs beyond a bare key (ElevenLabs: a platform-owned voice_id; E2B: a
-- platform-owned code-execution template id) - deliberately not split into
-- typed columns since exactly one string is needed per tool today and a
-- second tool needing a second field can extend this later without a new
-- migration, the same "single JSON-ish string field" pragmatism
-- MULTI_FIELD_PROVIDERS' encode/decode already uses client-side.
CREATE TABLE control_plane.platform_tool_credential (
    tool_key VARCHAR(32) PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    extra_config VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(255)
);
