-- Short, tightly-capped AI video generation inside the no-signup sandbox
-- (core/tenancy/demo + core/imagegen/demo). Same "counter on the session
-- row + capability-URL table" shape V127 (demo_generated_image) already
-- established - see that migration's header comment for the full "why a
-- counter AND a table" reasoning, which applies unchanged here.
--
-- Video's allowance is far tighter than image's for the same reason
-- DemoVideoService's javadoc gives: Veo-class generation is billed per
-- SECOND of output, not per call.

ALTER TABLE control_plane.demo_session
    ADD COLUMN video_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE control_plane.demo_generated_video (
    id                UUID PRIMARY KEY,
    session_id        UUID         NOT NULL,
    object_key        VARCHAR(512) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    prompt            TEXT         NOT NULL,
    model             VARCHAR(128) NOT NULL,
    duration_seconds  INTEGER      NOT NULL,
    created_ip        VARCHAR(45),
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_demo_generated_video_session ON control_plane.demo_generated_video (session_id);

-- Time-ordered sweep AND the platform-wide daily $ budget check
-- (DemoVideoService#isGlobalBudgetAvailable) - unlike images, every
-- anonymous visitor draws from ONE shared daily counter here, so this index
-- is load-bearing for every single video request, not just an admin/purge
-- convenience.
CREATE INDEX idx_demo_generated_video_created_at ON control_plane.demo_generated_video (created_at);
