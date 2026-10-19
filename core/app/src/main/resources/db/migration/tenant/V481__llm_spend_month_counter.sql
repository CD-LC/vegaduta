-- Incrementally maintained BYOK spend counter: one row per (tenant schema, UTC month).
--
-- Before: LlmGatewayBudgetService ran SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM
-- llm_call_log WHERE started_at >= <start of month> on every BYOK chat turn. The cost of that
-- read grows with the number of calls the tenant made this month.
-- After: LlmCallLogService.record bumps this row in the SAME transaction that inserts the
-- llm_call_log row (one UPDATE ... SET cost_usd = cost_usd + :c, atomic and safe under
-- concurrency and across replicas), and the budget check reads this one row by primary key.
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
-- month_start is the first day of the UTC month of the call's started_at, the same boundary
-- the old SUM used.
CREATE TABLE IF NOT EXISTS llm_spend_month (
    month_start DATE PRIMARY KEY,
    cost_usd    NUMERIC(18, 6) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill the current UTC month from the rows that already exist. Idempotent (DO NOTHING).
-- Months other than the current one are never read by the budget check.
INSERT INTO llm_spend_month (month_start, cost_usd, updated_at)
SELECT m.month_start,
       COALESCE(SUM(l.estimated_cost_usd), 0),
       now()
FROM (SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date AS month_start) m
LEFT JOIN llm_call_log l
       ON l.started_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
      AND l.started_at <  ((m.month_start + INTERVAL '1 month')::timestamp AT TIME ZONE 'UTC')
GROUP BY m.month_start
ON CONFLICT (month_start) DO NOTHING;
