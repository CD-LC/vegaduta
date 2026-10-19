-- Multi-provider voice/video/text architecture foundation (Stage 1)
--
-- Extends Agent entity with modality-specific provider selection:
-- - voice_model_provider: which speech-to-text provider this agent uses (SIDECAR_WHISPER, SARVAM, etc.)
-- - voice_model: the specific model name within that provider (e.g. "saaras:v3" for Sarvam)
--
-- Reserved for future expansion:
-- - video_model_provider: video-to-text provider (future stage)
-- - video_model: video model name (future stage)
-- - text_model_provider: alternative text/LLM provider for this agent (future stage)
--
-- Migration strategy: both columns are nullable on creation. Defaults to SIDECAR_WHISPER
-- (if available) with its default model, or SARVAM (if Sidecar unavailable), matching
-- the voice endpoint's existing fallback chain. Agents created or updated via web UI
-- will have both set explicitly. VoiceCallController validates availability at turn time.
--
-- Justification for NOT backfilling: pre-existing agents were always transcribed via
-- the hardcoded fallback chain (Sidecar → Sarvam). Backfilling with SIDECAR_WHISPER
-- would be incorrect for tenants without a sidecar. Backfilling with SARVAM would
-- break agents that should use Sidecar when it's available. Leaving them NULL keeps
-- the fallback chain as-is (see VoiceCallController#turn). New agents created after
-- this migration will have one or both set explicitly.

ALTER TABLE agent
    ADD COLUMN voice_model_provider VARCHAR(32),
    ADD COLUMN voice_model VARCHAR(128),
    ADD COLUMN video_model_provider VARCHAR(32),
    ADD COLUMN video_model VARCHAR(128),
    ADD COLUMN text_model_provider VARCHAR(32);

-- Future migrations may add more text_model or other modality columns as those
-- features are implemented. For now, these are reserved and unused.
