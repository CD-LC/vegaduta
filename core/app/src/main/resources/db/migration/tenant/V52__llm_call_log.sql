-- Direct feedback: "there should be an option to see the llm errors and
-- success logs and also, customer should be able to evaluate the price
-- cost per call or a task, and an outstanding balance of activated llm."
--
-- Durable, queryable counterpart to LlmCallMetricsService's in-memory-only
-- counters (reset on every restart) - see LlmCallLog.java's javadoc. One
-- row per real LLM call, success or failure, written by LlmMetricsAdvisor
-- via LlmCallLogService - the same single chokepoint every ChatClient this
-- app builds already goes through (AnthropicAgentChatClientFactory).
CREATE TABLE llm_call_log (
    id                  UUID PRIMARY KEY,
    agent_id            UUID,
    session_id          VARCHAR(128),
    provider_name       VARCHAR(32) NOT NULL,
    model               VARCHAR(128) NOT NULL,
    prompt_tokens       BIGINT,
    completion_tokens   BIGINT,
    total_tokens        BIGINT,
    estimated_cost_usd  NUMERIC(12, 6),
    success             BOOLEAN NOT NULL,
    error_message       TEXT,
    latency_ms          BIGINT NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL
);

-- Recent-log listing (RECENT INBOUND-style "most recent first" pattern
-- already used elsewhere in this schema) and the provider-usage summary
-- aggregate both filter/group without an index-free full scan.
CREATE INDEX idx_llm_call_log_started_at ON llm_call_log (started_at DESC);
CREATE INDEX idx_llm_call_log_provider_name ON llm_call_log (provider_name);
CREATE INDEX idx_llm_call_log_session_id ON llm_call_log (session_id);
