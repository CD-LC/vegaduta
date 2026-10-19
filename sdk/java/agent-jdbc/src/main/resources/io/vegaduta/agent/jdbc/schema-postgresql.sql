-- VegaDuta Agent SDK schema for PostgreSQL. Idempotent; safe to run on every start.
-- Copy into your own migration tool (Flyway, Liquibase) for production deployments.

CREATE TABLE IF NOT EXISTS vegaduta_approval (
  id               VARCHAR(36)   PRIMARY KEY,
  action_type      VARCHAR(200)  NOT NULL,
  summary          TEXT          NOT NULL,
  payload          TEXT          NOT NULL,
  tenant_id        VARCHAR(200),
  principal        VARCHAR(200),
  agent_id         VARCHAR(200),
  session_id       VARCHAR(200),
  run_id           VARCHAR(200)  NOT NULL,
  correlation_id   VARCHAR(200),
  attributes       TEXT,
  requested_by     VARCHAR(200)  NOT NULL,
  status           VARCHAR(20)   NOT NULL,
  created_at       TIMESTAMPTZ   NOT NULL,
  expires_at       TIMESTAMPTZ   NOT NULL,
  decided_at       TIMESTAMPTZ,
  decided_by       VARCHAR(200),
  decision_reason  TEXT,
  result           TEXT
);
CREATE INDEX IF NOT EXISTS ix_vegaduta_approval_status ON vegaduta_approval (status, tenant_id, created_at);
CREATE INDEX IF NOT EXISTS ix_vegaduta_approval_run ON vegaduta_approval (run_id);

CREATE TABLE IF NOT EXISTS vegaduta_effect (
  effect_key   VARCHAR(64)  PRIMARY KEY,
  status       VARCHAR(10)  NOT NULL,
  result       TEXT,
  created_at   TIMESTAMPTZ  NOT NULL,
  expires_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_vegaduta_effect_expires ON vegaduta_effect (expires_at);

CREATE TABLE IF NOT EXISTS vegaduta_audit (
  id              VARCHAR(36)   PRIMARY KEY,
  created_at      TIMESTAMPTZ   NOT NULL,
  actor_id        VARCHAR(200)  NOT NULL,
  action_type     VARCHAR(200)  NOT NULL,
  target_type     VARCHAR(100)  NOT NULL,
  target_id       VARCHAR(500)  NOT NULL,
  outcome         VARCHAR(30)   NOT NULL,
  detail          TEXT,
  tenant_id       VARCHAR(200),
  principal       VARCHAR(200),
  agent_id        VARCHAR(200),
  session_id      VARCHAR(200),
  run_id          VARCHAR(200)  NOT NULL,
  correlation_id  VARCHAR(200),
  risks           VARCHAR(100),
  attributes      TEXT
);
CREATE INDEX IF NOT EXISTS ix_vegaduta_audit_run ON vegaduta_audit (run_id, created_at);
CREATE INDEX IF NOT EXISTS ix_vegaduta_audit_tenant ON vegaduta_audit (tenant_id, created_at);
