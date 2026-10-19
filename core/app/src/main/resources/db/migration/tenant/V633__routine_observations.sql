-- V633: routine_observations - what the "routines from habits" learning loop remembers about repeated requests.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- One row per request the person made (a chat message or a Quick Task goal), kept only when
-- app.learning.routines.enabled=true (on in the personal profile, off elsewhere). raw_text has already been
-- scrubbed of secrets and personal data, and a request that needed scrubbing is never stored. norm_text is the
-- sorted set of content words used to cluster similar requests. Rows older than the retention window are deleted.
-- Nothing here reaches a prompt or a schedule: the loop only files a ROUTINE row into learning_proposals, which
-- the person approves.

CREATE TABLE IF NOT EXISTS routine_observations (
    id          UUID         PRIMARY KEY,
    subject     VARCHAR(255) NOT NULL,
    agent_id    UUID         NOT NULL,
    source      VARCHAR(8)   NOT NULL,
    norm_text   VARCHAR(600) NOT NULL,
    raw_text    VARCHAR(500) NOT NULL,
    observed_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_routine_observations_subject
    ON routine_observations (subject, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_routine_observations_age
    ON routine_observations (observed_at);
