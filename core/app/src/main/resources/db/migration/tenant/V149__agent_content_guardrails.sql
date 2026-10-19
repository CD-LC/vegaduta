-- Content guardrails for agents (input/output filtering)
--
-- Adds per-agent guardrail configuration. Guardrails are opt-in safety filters
-- for content moderation. Backfill: empty set (no guardrails) for all pre-existing
-- agents, matching today's unchanged behavior. Future agents can opt into any combination
-- of: profanity, jailbreak, pii, policy_violation, toxicity, custom.
--
-- Availability by plan (enforced in AgentService):
-- - FREE: profanity, jailbreak
-- - PREMIUM: + pii, policy_violation
-- - UNLIMITED: + toxicity, custom

-- Table for agent -> guardrail_type many-to-many relationship
create table agent_enabled_guardrail (
    agent_id uuid not null,
    guardrail_type varchar(32) not null,
    primary key (agent_id, guardrail_type),
    foreign key (agent_id) references agent(id) on delete cascade
);

-- Index for efficient lookup of agents by guardrail type
create index idx_guardrail_type on agent_enabled_guardrail(guardrail_type);
