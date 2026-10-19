-- In-core RL subsystem - multi-dimension arm selection per episode.
--
-- rl_episode.policy_arm_id (V109) can only remember ONE selected arm per
-- turn. That was fine while MODEL_ROUTE was the only dimension the
-- orchestrator ever consulted, but wiring REASONING_DEPTH/GEN_PARAMS/
-- TOOL_SUBSET/PROMPT_VARIANT into AgentChatService means a single turn can
-- now select up to 5 arms (one per dimension) - and the LATER human-reward
-- path (FeedbackService#applyHumanReward, matched by turn_ref) needs to be
-- able to fold that one observed reward back into every arm that
-- contributed to the turn, not just whichever one happened to occupy the
-- single policy_arm_id column.
--
-- Additive, not a replacement: rl_episode.policy_arm_id keeps being set from
-- the MODEL_ROUTE selection specifically (existing readers of that column
-- are unaffected). This table is the full record - one row per
-- (episode, dimension) actually selected that turn, empty for a turn where
-- no policy was active for any dimension.
CREATE TABLE rl_episode_policy_arm (
    id             UUID PRIMARY KEY,
    episode_id     UUID NOT NULL REFERENCES rl_episode (id),
    policy_arm_id  UUID NOT NULL REFERENCES policy_arm (id),
    dimension      VARCHAR(32) NOT NULL,
    CONSTRAINT uq_rl_episode_policy_arm_episode_dimension UNIQUE (episode_id, dimension)
);

CREATE INDEX idx_rl_episode_policy_arm_episode_id ON rl_episode_policy_arm (episode_id);
CREATE INDEX idx_rl_episode_policy_arm_arm_id ON rl_episode_policy_arm (policy_arm_id);
