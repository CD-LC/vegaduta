-- Master-Roadmap-2026.md item #4 (voice cloning). All three ElevenLabs
-- tables introduced together (unlike Sarvam's staged V60/V61/V62) since
-- this feature was built in one pass.

CREATE TABLE elevenlabs_credential (
    id UUID PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE elevenlabs_voice (
    id UUID PRIMARY KEY,
    elevenlabs_voice_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE elevenlabs_generated_audio (
    id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    voice_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_elevenlabs_generated_audio_created_at ON elevenlabs_generated_audio (created_at);
