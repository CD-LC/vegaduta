-- Text-to-speech (TTS) provider selection (Stage 2 of the multi-provider
-- modality architecture - see docs/MULTI-PROVIDER-MODALITY-ARCHITECTURE.md).
-- Output-side twin of V148's voice_model_provider/voice_model (STT):
--
-- - tts_provider: which text-to-speech provider this agent's voice replies
--   are synthesized through (ELEVENLABS, SARVAM)
-- - tts_model: the specific model name within that provider (e.g.
--   "eleven_multilingual_v2" for ElevenLabs, "bulbul:v3" for Sarvam)
--
-- Both columns are nullable, same "leave unset = default fallback chain"
-- semantics as voice_model_provider: VoiceCallController's existing
-- ElevenLabs -> Sarvam -> text-only-fallback chain is UNCHANGED for any
-- agent that leaves this null - selecting a provider here only changes
-- ordering (skip straight to the chosen provider) for that one agent, it
-- does not replace the chain's quota/persistence logic. See
-- TextToSpeechProvider's javadoc (core/providers) for the full explanation
-- of what selecting a provider changes vs. leaves untouched.
--
-- Justification for NOT backfilling: identical reasoning to V148 - every
-- pre-existing agent was always synthesized via the hardcoded
-- ElevenLabs-then-Sarvam chain, and backfilling either provider here would
-- incorrectly pin agents that should keep using the full fallback chain
-- (e.g. an agent whose tenant later configures a NEW Sarvam credential
-- should immediately benefit from the chain trying it, not be stuck on
-- whatever was backfilled).

ALTER TABLE agent
    ADD COLUMN tts_provider VARCHAR(32),
    ADD COLUMN tts_model VARCHAR(128);
