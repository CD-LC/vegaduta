-- Template standard (2026-10-12): every agent template ships with at least the jailbreak guardrail.
-- Ten platform-seeded agent templates were published with enabledGuardrails = [] (found by auditing the
-- 86 live templates). Scoped by exact title AND by "guardrails currently empty", so it never touches a
-- community submission and never overwrites a guardrail set someone chose on purpose. Idempotent.
UPDATE control_plane.community_template
SET config_json = jsonb_set(config_json::jsonb, '{enabledGuardrails}', '["jailbreak"]'::jsonb, true)::text,
    updated_at  = now()
WHERE kind = 'AGENT'
  AND status = 'APPROVED'
  AND jsonb_typeof(config_json::jsonb -> 'enabledGuardrails') = 'array'
  AND jsonb_array_length(config_json::jsonb -> 'enabledGuardrails') = 0
  AND title IN (
    'Marketing Performance Analyst',
    'RCM Eligibility Verifier',
    'SEO Content Strategist',
    'Budget & Bid Steward',
    'RCM Appeal & Statement Drafter',
    'RCM Denial Triage Analyst',
    'VegaDuta Demo Concierge',
    'Procurement Quote Analyst',
    'Invoice Match Auditor',
    'Quality & Downtime Analyst'
  );
