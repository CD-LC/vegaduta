-- Sarvam Integration Build Plan Stage 4: storage for SarvamSpeakToolCallback's
-- synthesized WAV output, so the tool result can hand the LLM a retrievable
-- /api/sarvam/audio/{id} reference instead of raw base64 audio bytes. See
-- SarvamGeneratedAudio's javadoc for why this is a dedicated table rather
-- than reusing workflow_run's output columns (core/agents can't depend on
-- core/workflow - that dependency runs the other way).

CREATE TABLE sarvam_generated_audio (
    id           UUID PRIMARY KEY,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    audio_data   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
