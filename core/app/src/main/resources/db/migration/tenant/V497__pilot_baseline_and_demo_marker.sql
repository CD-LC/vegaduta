-- Pilot kit (docs/pilots): the tenant's own pre-pilot numbers, and a marker for seeded demo data.
--
--   1. agent_pilot_baseline - what the TENANT says the chosen job looked like before the pilot, one
--      row per agent. Every column is entered by a person: nothing is measured, defaulted or
--      estimated by the platform, and every column is nullable (a half-filled baseline is allowed;
--      the pilot report shows a dash for what is missing). entered_by/entered_at record who typed it.
--      missed_rate is a fraction (0.25 = 25%).
--
--   2. demo_dataset - a single row that exists only in a tenant seeded by `scripts/vegaduta
--      seed-demo`. The console and the pilot report read it to print an "Example" watermark, so demo
--      numbers cannot be mistaken for a customer's results. A real tenant never has this row.
--
-- Additive and idempotent. Schema-per-tenant: no tenant_id column (see V52__llm_call_log.sql).

CREATE TABLE IF NOT EXISTS agent_pilot_baseline (
    agent_id                  UUID          PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
    job_type                  VARCHAR(32),
    baseline_period           VARCHAR(80),
    monthly_volume            NUMERIC(12,0),
    avg_handling_minutes      NUMERIC(10,2),
    missed_rate               NUMERIC(5,4),
    avg_value_per_conversion  NUMERIC(14,4),
    value_currency            CHAR(3),
    entered_by                VARCHAR(255),
    entered_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT agent_pilot_baseline_nonneg_chk CHECK (
        COALESCE(monthly_volume, 0) >= 0 AND COALESCE(avg_handling_minutes, 0) >= 0
        AND COALESCE(avg_value_per_conversion, 0) >= 0),
    CONSTRAINT agent_pilot_baseline_missed_chk CHECK (missed_rate IS NULL OR (missed_rate >= 0 AND missed_rate <= 1)),
    CONSTRAINT agent_pilot_baseline_currency_chk CHECK ((avg_value_per_conversion IS NULL) = (value_currency IS NULL)),
    CONSTRAINT agent_pilot_baseline_job_chk CHECK (job_type IS NULL OR job_type IN
        ('MISSED_CALL_RECOVERY', 'APPOINTMENT_REMINDERS', 'PAYMENT_FOLLOW_UP', 'LEAD_FOLLOW_UP'))
);

CREATE TABLE IF NOT EXISTS demo_dataset (
    id        SMALLINT     PRIMARY KEY DEFAULT 1,
    label     VARCHAR(120) NOT NULL,
    seeded_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT demo_dataset_single_chk CHECK (id = 1)
);
