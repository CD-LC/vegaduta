-- Fictional "Example" data for the pilot report and the Cost & ROI page, so they can be shown without a real
-- customer. Run by `scripts/vegaduta seed-demo` (psql -v schema=<tenant schema>), never by hand against a real tenant.
--
-- SAFETY
--   * Refuses a tenant that already has agents, LLM calls or resolution events and was not seeded before, so it cannot
--     bury a real customer's numbers under fake ones.
--   * Everything it writes is recognisable: agent names start with "Example - ", conversation keys and session ids
--     start with "demo:", the model is "claude-example-1", the price note says Example, and one demo_dataset row marks
--     the tenant (the Cost & ROI page, the Insights page and every pilot report print an "Example" watermark while that row exists).
--   * Re-running replaces the previous demo rows. scripts/demo/seed-demo-remove.sql removes them.
--
-- Nothing here is a measured or typical result. The counts, latencies and prices are arbitrary round numbers.
--
-- Expected demo ROI (hand-checked against the generators below, 28-day window, 420 conversations):
--   * Value: 70% of conversations resolve -> 84 + 63 + 42 + 105 resolutions for agents 1-4, at the example
--     value per resolution baseline_value / 10 = 1.00, 0.50, 2.00, 1.00 USD -> 304.50 USD.
--   * Cost: 2 calls per conversation, 1 in 25 fails and is not billed -> 810 calls of about 1,000 input and
--     200 output tokens at the invented example price 50 / 250 USD per million tokens -> about 80.90 USD.
--   * ROI = (value - cost) / cost, about 276%. The earlier values (10 USD per resolution, 3 / 15 USD per million,
--     about 1.4 to 5 USD of cost) printed an ROI of tens of thousands of percent, which no pilot would believe.
--   The example price is deliberately not a real rate; the Example watermark and the "Example price" note stay.

SET LOCAL search_path TO :"schema", public;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM demo_dataset)
     AND (EXISTS (SELECT 1 FROM agent) OR EXISTS (SELECT 1 FROM llm_call_log) OR EXISTS (SELECT 1 FROM resolution_event)) THEN
    RAISE EXCEPTION 'tenant schema % already holds real data (agents, calls or resolutions); seed-demo only runs on an empty tenant', current_schema();
  END IF;
END $$;

-- Re-seed: drop the previous demo rows first. Agents are matched by the ids of the seeded conversations (and the
-- "Example - " name), so a real agent that happens to share the naming pattern is never deleted.
DELETE FROM agent WHERE name LIKE 'Example - %'
  AND id IN (SELECT DISTINCT agent_id FROM resolution_event WHERE conversation_key LIKE 'demo:%');
DELETE FROM conversation_correction WHERE conversation_key LIKE 'demo:%';
DELETE FROM resolution_event WHERE conversation_key LIKE 'demo:%';
DELETE FROM llm_call_log WHERE session_id LIKE 'demo:%';
DELETE FROM llm_price WHERE model_prefix = 'claude-example' AND set_by = 'seed-demo';
DELETE FROM demo_dataset;

CREATE TEMP TABLE _demo_agent (idx int, id uuid, name text, job text, n int, baseline_volume int, baseline_minutes numeric, baseline_missed numeric, baseline_value numeric) ON COMMIT DROP;
INSERT INTO _demo_agent VALUES
  (1, gen_random_uuid(), 'Example - Missed-call recovery',  'MISSED_CALL_RECOVERY',  120, 400, 6.0, 0.35, 10),
  (2, gen_random_uuid(), 'Example - Appointment reminders', 'APPOINTMENT_REMINDERS',  90, 300, 3.0, 0.20, 5),
  (3, gen_random_uuid(), 'Example - Payment follow-up',     'PAYMENT_FOLLOW_UP',      60, 150, 8.0, 0.30, 20),
  (4, gen_random_uuid(), 'Example - Lead follow-up',        'LEAD_FOLLOW_UP',        150, 500, 5.0, 0.40, 10);

INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature, knowledge_base_enabled, created_at, updated_at)
SELECT id, name, 'Example agent. Fictional demo data, not a real deployment.', 'ANTHROPIC', 'claude-example-1', 1000, 0.2, false, now(), now()
FROM _demo_agent;

-- One conversation per (agent, g). Outcome mix is a fixed pattern: 70% resolved, 20% handed over, 10% abandoned.
CREATE TEMP TABLE _demo_conv ON COMMIT DROP AS
SELECT a.id AS agent_id, a.idx, g,
       'demo:' || a.idx || ':' || g AS key,
       now() - (((g * 37 + a.idx * 11) % (28 * 24 * 60)) || ' minutes')::interval AS at,
       CASE WHEN (g + a.idx) % 10 < 7 THEN 'RESOLVED_AI' WHEN (g + a.idx) % 10 < 9 THEN 'ESCALATED' ELSE 'ABANDONED' END AS outcome
FROM _demo_agent a, LATERAL generate_series(1, a.n) AS g;

INSERT INTO resolution_event (id, conversation_key, agent_id, channel, outcome, evidence, confidence, billable, billable_reason, resolved_at, dedupe_key)
SELECT gen_random_uuid(), key, agent_id, 'WHATSAPP', outcome,
       CASE outcome WHEN 'RESOLVED_AI' THEN 'EXPLICIT_API' WHEN 'ESCALATED' THEN 'ESCALATION_OPEN' ELSE 'OPERATOR_CLOSE' END,
       1.0, false, 'Example data', at, key
FROM _demo_conv;

-- Two model calls per conversation; roughly one in 25 fails.
INSERT INTO llm_call_log (id, agent_id, session_id, provider_name, model, prompt_tokens, completion_tokens, total_tokens, success, latency_ms, started_at)
SELECT gen_random_uuid(), c.agent_id, c.key, 'ANTHROPIC', 'claude-example-1', t.p, t.o, t.p + t.o,
       (c.g + k) % 25 <> 0, 1500 + (c.g * 137 + k * 211) % 3000, c.at - (k || ' minutes')::interval
FROM _demo_conv c, generate_series(1, 2) AS k,
     LATERAL (SELECT 800 + (c.g * 13 + k * 101) % 400 AS p, 150 + (c.g * 7 + k * 31) % 100 AS o) t;

-- A price the "tenant" typed, clearly an example; without it the cost would be a lower bound.
INSERT INTO llm_price (id, provider_name, model_prefix, input_per_million, output_per_million, effective_from, set_by, note)
VALUES (gen_random_uuid(), 'ANTHROPIC', 'claude-example', 50, 250, current_date - 60, 'seed-demo', 'Example price, not a real rate');

-- A few corrected conversations (about 1 in 17 of the resolved ones).
INSERT INTO conversation_correction (id, conversation_key, agent_id, marked_by, note)
SELECT gen_random_uuid(), key, agent_id, 'seed-demo', 'Example data'
FROM _demo_conv WHERE outcome = 'RESOLVED_AI' AND g % 17 = 0;

-- The value assumption and baseline, as if the tenant had typed them.
INSERT INTO agent_value_assumption (agent_id, value_per_resolution, value_currency, minutes_saved_per_resolution, updated_by)
SELECT id, baseline_value / 10, 'USD', baseline_minutes, 'seed-demo' FROM _demo_agent
ON CONFLICT (agent_id) DO UPDATE SET value_per_resolution = EXCLUDED.value_per_resolution, value_currency = EXCLUDED.value_currency,
  minutes_saved_per_resolution = EXCLUDED.minutes_saved_per_resolution, updated_by = EXCLUDED.updated_by, updated_at = now();

INSERT INTO agent_pilot_baseline (agent_id, job_type, baseline_period, monthly_volume, avg_handling_minutes, missed_rate, avg_value_per_conversion, value_currency, entered_by)
SELECT id, job, 'Example baseline', baseline_volume, baseline_minutes, baseline_missed, baseline_value, 'USD', 'seed-demo' FROM _demo_agent
ON CONFLICT (agent_id) DO UPDATE SET job_type = EXCLUDED.job_type, baseline_period = EXCLUDED.baseline_period,
  monthly_volume = EXCLUDED.monthly_volume, avg_handling_minutes = EXCLUDED.avg_handling_minutes, missed_rate = EXCLUDED.missed_rate,
  avg_value_per_conversion = EXCLUDED.avg_value_per_conversion, value_currency = EXCLUDED.value_currency, updated_at = now();

INSERT INTO demo_dataset (id, label) VALUES (1, 'Example - fictional data');
