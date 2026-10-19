-- Per-agent run cap. NULL = use the platform cap (app.guardrails.run-budget.usd),
-- which is the exact behaviour before this column existed, so there is no
-- backfill. The value can only lower the platform cap (RunBudgetCaps); it
-- covers delegation tools and deep_research, and is an estimate, not billing.
ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS run_budget_usd NUMERIC(10,4);
