-- Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) -
-- "variant tagging on usage/audit records" per the tech-stack audit's own
-- scoping note. Nullable free-text column (e.g. "flagA=treatment,flagB=control"),
-- same posture as llm_call_log's existing agent_id/session_id: populated
-- only for call sites that opt in via LlmMetricsAdvisor's advisor-param
-- mechanism (see EXPERIMENT_VARIANTS_PARAM), null everywhere else.
ALTER TABLE llm_call_log ADD COLUMN experiment_variants VARCHAR(500);
