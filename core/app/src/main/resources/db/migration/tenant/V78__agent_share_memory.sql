-- Shared cross-agent memory tier (#847) - explicit per-agent opt-in for the
-- new tenant_memory pool (V77), same "off by default, tenant-admin turns it
-- on per agent" shape knowledge_base_enabled (Stage 6) and sarvam_enabled
-- (V63) already established for their own tools - no agent's behavior
-- silently changes on deploy, and a fact an agent's owner never intended to
-- share tenant-wide never becomes readable by every other agent by default.
ALTER TABLE agent ADD COLUMN share_memory_with_tenant BOOLEAN NOT NULL DEFAULT false;
