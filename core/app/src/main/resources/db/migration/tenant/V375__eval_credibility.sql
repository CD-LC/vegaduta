-- Eval credibility + reproducibility (docs/plans/Agentic-Platform-Gap-Analysis-2026-10-01.md, item 12).
--
--   1. eval_dataset_case.grader_spec - optional deterministic ("code") grader for one case, stored as the
--      raw JSON text {"type":"regex"|"exact"|"json_schema", ...}. Parsed/validated in Java
--      (com.agenticai.core.quality.grader.CodeGrader); NULL = no code grader, exactly today's behaviour.
--      It is part of the dataset content hash ONLY when present, so existing dataset hashes (and the runs
--      bound to them) stay valid.
--
--   2. eval_run agent snapshot - what was actually replayed, captured at trigger time:
--        agent_model, agent_temperature, agent_seed - the sampling configuration (NULL on older runs).
--        agent_config_json - canonical JSON of {model, temperature, topP, maxTokens, seed,
--            systemPromptSha256, tools[]} (the prompt is stored as a hash, never inline).
--        agent_config_hash - SHA-256 of agent_config_json. Two runs with the same hash replayed the same
--            prompt + tool allow-list + sampling settings.
--      All nullable: pre-V375 rows record nothing, and nothing reads them as failure.
--
-- VERSION NUMBER
-- ---------------
-- Shared controlplane/tenant Flyway namespace. Checked 2026-10-01: tenant/ highest was V374, controlplane/
-- highest is V37 (a different, much lower series); V375 is reserved for this workstream and this file is
-- the only V375 in the repository.

ALTER TABLE eval_dataset_case
    ADD COLUMN grader_spec TEXT;

ALTER TABLE eval_run
    ADD COLUMN agent_model       VARCHAR(128),
    ADD COLUMN agent_temperature DOUBLE PRECISION,
    ADD COLUMN agent_seed        INTEGER,
    ADD COLUMN agent_config_hash VARCHAR(64),
    ADD COLUMN agent_config_json TEXT;
