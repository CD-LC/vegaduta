-- Face recognition (2026-08-06 follow-up to the reliability blueprint):
-- "agent should remember who's in view if we add a photo in the knowledge
-- base with that person's name." Architecture is 100% client-side by
-- design (MediaPipe Face Detector + ArcFace ONNX, both running in-browser
-- via WebGPU/WASM) - the browser generates the 512-dim embedding locally
-- from a knowledge-base photo (enrollment) and from live camera frames
-- (matching); this table is pure storage so a person survives a page
-- reload / different device, never a compute step. Same reasoning as
-- knowledge_chunks (V10) for why this is raw JDBC, not Hibernate-mapped:
-- pgvector's `vector` type has no Hibernate mapping.
--
-- Deliberately its own table, not a column bolted onto knowledge_document:
-- one photo could reasonably enroll zero (no face found), one, or more than
-- one face, and a person's enrollment should survive the source photo being
-- deleted from the knowledge base without losing recognition - so this only
-- references document_id loosely (nullable, ON DELETE SET NULL) rather than
-- being owned by it.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_face_enrollment (
    id            UUID PRIMARY KEY,
    collection_id UUID NOT NULL,
    document_id   UUID REFERENCES knowledge_document(id) ON DELETE SET NULL,
    person_name   VARCHAR(200) NOT NULL,
    -- ArcFace ResNet100 (ONNX Model Zoo, Apache 2.0) - 512-dim, L2-normalized
    -- client-side before this is ever sent. See web/app/lib/edge/face.ts.
    embedding     vector(512) NOT NULL,
    -- Biometric data (special-category under GDPR Art.9 / a "sensitive
    -- personal data" trigger under India's DPDP Act) - every row exists
    -- because Agent.biometricConsentEnabled was true AND an explicit
    -- consent gesture happened client-side at enrollment time. Never
    -- backfilled/defaulted - see FaceEnrollmentService#enroll.
    consented_at  TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_face_enrollment_collection ON knowledge_face_enrollment (collection_id);

-- Same "off by default, explicit per-agent opt-in" shape as
-- knowledge_base_enabled (Stage 6) / share_memory_with_tenant (V78) - no
-- agent starts running face-matching on camera frames or offering
-- face-enrollment on knowledge-base photo uploads until a tenant-admin
-- explicitly turns this on for that agent, given the biometric-data
-- sensitivity above.
ALTER TABLE agent ADD COLUMN biometric_consent_enabled BOOLEAN NOT NULL DEFAULT false;
