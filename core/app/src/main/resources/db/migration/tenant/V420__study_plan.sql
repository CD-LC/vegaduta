-- Learn Anything tutor: one row per study plan a member created.
--
-- Tenant schema: this is tenant data, scoped to the member who owns it
-- (owner_subject = Keycloak subject, the same key /api/memory uses). Members
-- share the schema but never each other's plans - StudyPlanService refuses to
-- touch a row owned by someone else and answers 404.
--
-- curriculum: {"modules":[{"id","title","lessons":[{"id","title","objective",
--   "status","content"?,"quiz"?[{"id","question","options","correctIndex",
--   "explanation","answeredIndex"?}]}]}]} - lesson content and quizzes are
--   generated lazily and cached here.
-- review: {"cards":[{"id","lessonId","question","options","correctIndex",
--   "explanation","ease","intervalDays","reps","lapses","dueAt","lastResult"}],
--   "lastRemindedAt"?} - the spaced-repetition deck (mistakes come back).
--
-- Both documents are capped in size by the service (app.tutor.max-curriculum-bytes,
-- app.tutor.max-lessons-per-plan), so one JSONB row per plan stays small.
-- Additive only: a new table, nothing existing is altered.
CREATE TABLE IF NOT EXISTS study_plan (
    id             UUID PRIMARY KEY,
    owner_subject  VARCHAR(255) NOT NULL,
    topic          VARCHAR(300) NOT NULL,
    language       VARCHAR(16)  NOT NULL DEFAULT 'en',
    level          VARCHAR(32)  NOT NULL DEFAULT 'beginner',
    goal           VARCHAR(500),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    curriculum     JSONB        NOT NULL DEFAULT '{"modules":[]}'::jsonb,
    review         JSONB        NOT NULL DEFAULT '{"cards":[]}'::jsonb,
    tutor_agent_id UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_study_plan_owner ON study_plan (owner_subject, updated_at DESC);
