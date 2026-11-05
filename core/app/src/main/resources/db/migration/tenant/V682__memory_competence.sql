-- Unified agent memory, WP3 (ADR-0028 section 3): SELF_MODEL competence counters per agent and task class.
-- Numbers only, computed by a platform job from verified outcomes (tool status, approval decisions, explicit
-- human rating); a model's own claim of success is not a source, and UNKNOWN is counted apart, never as
-- success. task_class comes from a closed platform vocabulary (workflow id, skill id, tool family).
-- Nothing writes this table yet (the counter job is a later work package).

CREATE TABLE memory_competence (
    agent_id         UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    task_class       VARCHAR(96) NOT NULL,
    attempts         INT NOT NULL DEFAULT 0,
    verified_success INT NOT NULL DEFAULT 0,
    verified_failure INT NOT NULL DEFAULT 0,
    human_accepted   INT NOT NULL DEFAULT 0,
    human_rejected   INT NOT NULL DEFAULT 0,
    unknown_outcome  INT NOT NULL DEFAULT 0,
    last_outcome_at  TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, task_class)
);
