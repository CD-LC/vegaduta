-- Simulation leg on the promote gate ("staging environment for agents") -
-- workstream "agent ops: fleet supervisor". V344 already binds every
-- simulation_run to the agent_version row it replayed; this migration lets a
-- gate REQUIRE a judged simulation pass on the candidate configuration in
-- addition to the gated dataset's eval pass.
--
--   1. agent_eval_gate.require_simulation - opt-in (default FALSE so existing
--      gates are untouched): when TRUE, EvalGateService additionally consults
--      the latest simulation_session for this agent whose judged runs were
--      bound to a version with BYTE-IDENTICAL config_json (the same content
--      binding eval runs use). Any FAILED persona blocks; INCONCLUSIVE
--      blocks rather than passes (honest absence - the sims measured
--      nothing); only an all-PASSED judged session qualifies.
--
--   2. agent_eval_gate.max_simulation_age_hours - optional freshness window
--      on that simulation evidence, mirroring max_run_age_hours. Null = any
--      age qualifies (the config binding already proves WHAT was tested).
--
-- VERSION NUMBER
-- --------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders.
-- Checked at authoring time: tenant/ tops out at V393 (agent_updated_by),
-- controlplane/ at V369 (ai_content_report); V394 is the next free slot and
-- this file is the only V394 in the repository.

ALTER TABLE agent_eval_gate
    ADD COLUMN require_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN max_simulation_age_hours INTEGER;

COMMENT ON COLUMN agent_eval_gate.require_simulation IS
    'When TRUE, promote additionally requires the latest judged simulation session on this exact configuration to be all-PASSED (V394).';
COMMENT ON COLUMN agent_eval_gate.max_simulation_age_hours IS
    'Optional freshness window on the qualifying simulation evidence; NULL = any age.';
