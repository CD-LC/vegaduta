-- Text model provider routing - Stage 1
--
-- Completes the multi-provider text architecture foundation by adding:
-- - text_model: the specific model name within the selected text provider (e.g. "claude-opus-4-8" for Anthropic)
--
-- The text_model_provider column was added in V148 as a reserved VARCHAR(32) field,
-- now populated via the TextModelProvider enum for agent-specific LLM provider selection.
--
-- Nullable columns (matching voice_model_provider/voice_model pattern):
-- - When null, agent uses its primary providerName/model fields (backward compatible)
-- - When set, agent routes all chat turns through the selected text provider/model
--
-- Plan gating: Premium/Unlimited only - FREE tenants cannot select custom text providers
--
-- No backfill needed: pre-existing agents get null values, which preserves today's behavior.

ALTER TABLE agent
    ADD COLUMN text_model VARCHAR(128);

-- Future migrations may add additional text/modality columns or expand this feature.
