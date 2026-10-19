-- The generic per-agent capability flag table behind
-- com.agenticai.core.agents.capability.StoredAgentCapability - the storage
-- half of the plug-and-play capability SPI that replaced the
-- one-column-per-provider pattern (sarvam_enabled V63, e2b_enabled V97,
-- code_workspace_enabled V103, xai_server_tools_enabled V239) and its
-- per-provider PATCH endpoints with GET/PATCH
-- /api/agents/{id}/capabilities(/​{key}).
--
-- One row per (agent, capability key); a missing row reads as disabled.
-- New capabilities plug in with only an AgentCapability bean - no Flyway
-- migration, no endpoint, no frontend section.
--
-- The four pre-existing flags deliberately KEEP their agent columns (they
-- are round-tripped by AgentConfigSnapshotCodec/TemplateSnapshotService and
-- copied by AgentService#duplicate); their capability beans are column-
-- backed. This table is for capabilities added from here on.
CREATE TABLE agent_capability (
    agent_id       UUID        NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    capability_key VARCHAR(64) NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT false,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, capability_key)
);
