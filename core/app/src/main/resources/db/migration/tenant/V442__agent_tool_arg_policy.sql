-- Per-agent tool ARGUMENT policies (lane "guardtraj"; docs: ToolArgPolicySet in core/guardrails).
--
-- One row per agent holding the validated JSON policy document ({"policies":[{"tool":..., "rules":[...]}]}).
-- No row = no policies = exactly the behaviour before this table existed. The feature is additionally gated by
-- app.tool-arg-policies.enabled (default false); with the flag off nothing reads this table.
--
-- Why a table and not an agent column: there is no per-agent settings JSON to put it in (agent.* columns are
-- individually typed), and keeping it out of agent keeps the agent row, agent_version snapshots and the
-- agent edit paths untouched. TEXT not JSONB: it is read whole, never queried into.
--
-- agent_id IS a foreign key (unlike eval_dataset.agent_id): the policy is meaningless without its agent and
-- should vanish with it.
--
-- VERSION NUMBER: V442 was assigned to this workstream by the fan-out lead (tenant/ highest on disk is V425;
-- controlplane/ highest is V415); this is the only V442 in the repository.
CREATE TABLE IF NOT EXISTS agent_tool_arg_policy (
    agent_id      UUID         PRIMARY KEY REFERENCES agent (id) ON DELETE CASCADE,
    policies_json TEXT         NOT NULL,
    updated_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
