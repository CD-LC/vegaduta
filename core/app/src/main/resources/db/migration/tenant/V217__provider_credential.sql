-- Generic Provider & Capability Registry, Phase 1 (docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md
-- section 5, "Phase 1 - Registry scaffolding, no data migration"): the unified
-- credential table from section 3.1. Additive only - elevenlabs_credential (V89)
-- and sarvam_credential (V60) are untouched and remain the tables actually read
-- by SpeakBackendRegistry today. Nothing writes to this table yet; it exists so
-- the *next* new capability provider can be onboarded through it instead of a
-- sixth hand-written *_credential table.
--
-- Same shape as llm_provider_credential (V1) plus the two columns that table
-- can't express: capabilities (a credential can serve more than one capability,
-- e.g. Sarvam covers TTS/STT/TRANSLATE) and extra_config (a typed slot for
-- per-provider second-step config, e.g. ElevenLabs' selected voice_id).
--
-- Version V217: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE shared version sequence - V216 was already taken by
-- controlplane/V216__moderation_unify_on_guardrails_engine.sql, so this is the
-- next actually-free number (checked both folders, not just tenant/).

CREATE TABLE provider_credential (
    id                 UUID PRIMARY KEY,
    provider_key       VARCHAR(32) NOT NULL,
    capabilities       VARCHAR(32)[] NOT NULL,
    encrypted_secret   TEXT NOT NULL,
    status             VARCHAR(16) NOT NULL,
    extra_config       JSONB,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

-- One active credential per provider per tenant schema, mirroring
-- llm_provider_credential's uniqueConstraints = @UniqueConstraint(columnNames = "provider_name").
CREATE UNIQUE INDEX idx_provider_credential_provider_key ON provider_credential (provider_key);

-- "give me the ready ACTIVE providers for capability X" is the registry's core
-- query (see CapabilityRegistries#resolveBest) - a GIN index makes the array
-- containment check (capabilities @> ARRAY['TTS']) cheap once this table is
-- actually read from (Phase 2/3).
CREATE INDEX idx_provider_credential_capabilities ON provider_credential USING GIN (capabilities);

CREATE INDEX idx_provider_credential_status ON provider_credential (status);
