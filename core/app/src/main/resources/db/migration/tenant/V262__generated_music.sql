-- Tracks composed by the generate_music agent tool (core/elevenlabs
-- GeneratedMusic). Same "object key in Postgres, bytes in object storage"
-- shape as V88 generated_image / V89 elevenlabs_generated_audio.
--
-- billed_to is the quota ledger discriminator: only PLATFORM rows (VegaDuta's
-- own ElevenLabs key paid) count against the tenant's music cap; TENANT rows
-- are the tenant's own BYOK spend. Additive only - no existing table touched.

CREATE TABLE IF NOT EXISTS generated_music (
    id               UUID PRIMARY KEY,
    filename         VARCHAR(255) NOT NULL,
    content_type     VARCHAR(100) NOT NULL,
    object_key       VARCHAR(512) NOT NULL,
    prompt           TEXT         NOT NULL,
    model            VARCHAR(128) NOT NULL,
    provider         VARCHAR(64)  NOT NULL,
    billed_to        VARCHAR(16)  NOT NULL,
    duration_seconds INTEGER      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_generated_music_billed_created ON generated_music (billed_to, created_at);
