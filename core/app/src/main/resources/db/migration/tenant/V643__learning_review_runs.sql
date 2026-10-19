-- V643: learning_review_runs - which people the daily memory review has already looked at today.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- The review (app.learning.review.enabled) wakes up every hour so a machine that was asleep at night still
-- catches up, and claims (subject, run_date) here before it reads anyone's memory. The primary key makes the
-- claim atomic, so two replicas or two ticks never review the same person twice in a day. Holds no memory text.

CREATE TABLE IF NOT EXISTS learning_review_runs (
    subject     VARCHAR(255) NOT NULL,
    run_date    DATE         NOT NULL,
    filed       INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (subject, run_date)
);
