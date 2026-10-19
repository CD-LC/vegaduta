-- Master-Roadmap-2026.md item #3 (image generation, Flux/GPT-Image), Stage 1.
-- Mirrors sarvam_generated_audio's shape (see that table's own migration)
-- but WITHOUT a legacy base64 column - there is no pre-existing data to
-- preserve here, so this table goes straight to a NOT NULL object_key
-- column pointing at FileStorageService (S3-compatible object storage).
-- No tenant_id column: schema-per-tenant multi-tenancy already isolates
-- this table, same as every other tenant-scoped table in this schema.
CREATE TABLE generated_image (
    id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    prompt TEXT NOT NULL,
    model VARCHAR(64) NOT NULL,
    size VARCHAR(32),
    provider VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_generated_image_created_at ON generated_image (created_at);
