-- Platform-admin alert threshold follow-up (#956) - singleton row, same
-- id=1L convention as control_plane.platform_kill_switch (V79) and
-- control_plane.hosted_llm_pool_settings. V84 because this project's Flyway
-- setup merges controlplane/ and tenant/ into ONE combined version sequence
-- (see application.yml's flyway.locations comment) and tenant/ had already
-- claimed V83 (agent_fallback_counters.sql) by the time this was written -
-- same collision-avoidance reasoning V79's own header comment documents.
CREATE TABLE control_plane.platform_alert_settings (
    id                          BIGINT PRIMARY KEY,
    fallback_failure_threshold  INT NOT NULL DEFAULT 10,
    updated_by                  VARCHAR(255),
    updated_at                  TIMESTAMPTZ
);

INSERT INTO control_plane.platform_alert_settings (id, fallback_failure_threshold) VALUES (1, 10);
