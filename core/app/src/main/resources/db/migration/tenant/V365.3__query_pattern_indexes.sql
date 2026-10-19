-- Indexes for real query patterns that had none (JAVA-020). Each one was
-- checked against the repository query it serves:
--
--   llm_call_log (agent_id, started_at DESC)
--     LlmCallLogRepository#statsForAgent (WHERE agent_id = ?),
--     #findTop5ByAgentIdAndSuccessOrderByStartedAtDesc, and
--     ExperimentMetricsReader#queryRecentErrorSample
--     (WHERE agent_id = ? ORDER BY started_at DESC LIMIT n). Only
--     (started_at), (session_id), (provider_name) existed.
--   audit_log_entry (actor_id, created_at)
--     AuditLogRepository#countByActorId...AndCreatedAtAfter (the abuse-signal
--     counts, run per actor per tenant). Only single-column indexes on
--     action_type / target_type / created_at existed.
--   workflow_run (status, started_at)
--     WorkflowRunRepository#findByStatusAndStartedAtBefore (the stale-run
--     recovery sweep). Only (workflow_id, started_at) existed.
--   rl_episode (session_id, started_at DESC)
--     EpisodeRepository#findFirstBySessionIdOrderByStartedAtDesc. The existing
--     (agent_id, session_id, turn_ref) index cannot serve a session-only
--     lookup (session_id is not its leading column).
--   channel_session (session_id)
--     ChannelSessionRepository#findFirstBySessionId (handoff escalation).
--   channel_contact (group_id)
--     ChannelContactRepository#findByGroupIdOrderByCreatedAtDesc /
--     #countByGroupId, and the FK's ON DELETE SET NULL (deleting a
--     contact_group scanned every contact without it).
--
-- No CONCURRENTLY: Flyway runs each migration inside a transaction, and
-- CREATE INDEX CONCURRENTLY cannot run in one - the same convention as every
-- other index in this folder (see V211/V215). Per-tenant tables are small
-- enough that the brief SHARE lock is acceptable.
--
-- VERSION NUMBER: V365.3 - OSS-local dotted minor version (see
-- docs/engineering/FLYWAY-VERSIONING.md).
CREATE INDEX IF NOT EXISTS idx_llm_call_log_agent_started
    ON llm_call_log (agent_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_entry_actor_created
    ON audit_log_entry (actor_id, created_at);
CREATE INDEX IF NOT EXISTS idx_workflow_run_status_started
    ON workflow_run (status, started_at);
CREATE INDEX IF NOT EXISTS idx_rl_episode_session_started
    ON rl_episode (session_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_channel_session_session_id
    ON channel_session (session_id);
CREATE INDEX IF NOT EXISTS idx_channel_contact_group_id
    ON channel_contact (group_id);
