-- V152 declared overall_score/score as DECIMAL(3,2), but the JPA entities
-- (AgentQualityRecord.overallScore, .scores) map to `double`/`Double`, which
-- Hibernate validates against DOUBLE PRECISION, not NUMERIC. This never
-- surfaced because core/app never depended on the quality module until now,
-- so these tables were never reachable and are guaranteed empty - a plain
-- type change, no data migration needed. V152's CHECK constraint on
-- overall_score survives an ALTER COLUMN TYPE unchanged (Postgres
-- re-validates it against the new type in place), so it is not re-declared
-- here.

ALTER TABLE agent_quality_record
    ALTER COLUMN overall_score TYPE DOUBLE PRECISION USING overall_score::double precision;

ALTER TABLE agent_quality_record_scores
    ALTER COLUMN score TYPE DOUBLE PRECISION USING score::double precision;
