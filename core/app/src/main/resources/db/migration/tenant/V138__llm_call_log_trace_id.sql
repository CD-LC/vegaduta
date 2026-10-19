-- Infrastructure audit fix: Langfuse (self-hosted, wired via OTel/OTLP - see
-- application.yml's management.otlp.tracing.* and LlmMetricsAdvisor's javadoc)
-- was a bare span collector - no llm_call_log row could be traced back to the
-- Langfuse trace it belonged to. Nullable, same posture as every other
-- opt-in column on this table (agent_id/session_id/experiment_variants):
-- populated only when tracing is actually on (management.tracing.sampling.
-- probability > 0) AND a span was genuinely active on the calling thread at
-- the time LlmMetricsAdvisor#logCall ran - null otherwise ("tracing off" or
-- "no active span"), never a fabricated value.
--
-- trace_id is the 32-lowercase-hex-char OTel/W3C trace id (Langfuse's own
-- trace id space for OTLP-ingested traces is the same 32-hex format - see
-- LangfuseScoreClient's javadoc for the exact reasoning/caveat), span_id the
-- 16-hex-char OTel span id of the specific call.
ALTER TABLE llm_call_log ADD COLUMN trace_id VARCHAR(32);
ALTER TABLE llm_call_log ADD COLUMN span_id VARCHAR(16);

-- Supports LlmCallLogService#mostRecentTraceIdForSession - LlmQualityJudgeService
-- (core/agents) looks up the traceId of the turn it just sampled by session id
-- so it can attach its verdict to that exact Langfuse trace as a score.
CREATE INDEX idx_llm_call_log_trace_id ON llm_call_log (trace_id) WHERE trace_id IS NOT NULL;
