-- V669: remove an agent's setting overrides when the agent is deleted (core/settings, follow-up to V668).
--
-- setting_override.scope_id is a plain string (a catalog key can also be set at tenant scope, where there is no agent),
-- so it cannot be a foreign key to agent(id) and the ON DELETE CASCADE the other agent-owned tables use does not
-- apply. Without this trigger an override of a deleted agent would stay in the table for good: harmless to the runtime
-- (nothing resolves for an agent that no longer exists) but it would show up in exports and counts forever.
--
-- A row trigger on agent, so every way an agent can disappear is covered (the delete endpoint, a cascade from a tenant
-- purge, a manual DELETE by an operator), not just the one code path that happens to exist today.
--
--   setting_override          the agent's current override rows are DELETED.
--   setting_override_history  stays (it is immutable by design) and gets one RESET row per removed override, with the value
--                             that was in force as previous_value, reason 'agent deleted' and actor 'system:agent-deleted',
--                             so the record of what the agent was running with, and when it stopped, is complete.
--
-- The per-key advisory lock is the one JdbcSettingsStore#latestVersion takes ('setting:agent:<agent id>:<key>'), so a
-- write that is in flight for the same key finishes first and the next version number is computed after it.
CREATE OR REPLACE FUNCTION setting_override_remove_for_deleted_agent()
RETURNS TRIGGER AS $$
DECLARE
    o RECORD;
BEGIN
    FOR o IN
        SELECT scope_id, setting_key, value
        FROM setting_override
        WHERE scope = 'agent' AND scope_id = OLD.id::text
        ORDER BY setting_key
    LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended('setting:agent:' || o.scope_id || ':' || o.setting_key, 0));
        INSERT INTO setting_override_history
            (scope, scope_id, setting_key, version, action, value, previous_value, reason, actor, created_at)
        SELECT 'agent', o.scope_id, o.setting_key, COALESCE(MAX(h.version), 0) + 1, 'RESET', NULL, o.value,
               'agent deleted', 'system:agent-deleted', now()
        FROM setting_override_history h
        WHERE h.scope = 'agent' AND h.scope_id = o.scope_id AND h.setting_key = o.setting_key;
    END LOOP;
    DELETE FROM setting_override WHERE scope = 'agent' AND scope_id = OLD.id::text;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_agent_delete_setting_override ON agent;
CREATE TRIGGER trg_agent_delete_setting_override
    AFTER DELETE ON agent
    FOR EACH ROW
    EXECUTE FUNCTION setting_override_remove_for_deleted_agent();
