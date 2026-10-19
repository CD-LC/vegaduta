-- Direct feedback ("Log all LLM provider exceptions and application errors.
-- Store sufficient context for future analysis, including: Timestamp,
-- User/request ID, Provider name, Request correlation ID, Exception type,
-- Full error message, Stack trace. Ensure these logs are accessible for
-- future review and troubleshooting.") - llm_call_log (V52) already has
-- started_at (timestamp), provider_name, agent_id/session_id (user/request
-- attribution), and error_message (full technical detail, since
-- ProviderErrorTranslator's cause-chain walk - see that class's javadoc -
-- now puts the real detail there instead of a generic wrapper string). This
-- migration adds the three fields that were still missing: which request
-- this failure happened during (request_id, tied to the same value
-- RequestCorrelationFilter stamps into every log line via MDC, so a support
-- engineer can go from one failed row here straight to every server log
-- line for that same request), what kind of exception it was
-- (exception_type, the simple class name - e.g. "UpstreamProviderException",
-- "ApiException" - a quick filter/group-by column without parsing
-- error_message text), and the full stack trace (stack_trace) so the
-- underlying cause chain is available for review without needing the
-- original server log line to still exist (log files rotate/age out; this
-- row doesn't).
ALTER TABLE llm_call_log ADD COLUMN request_id VARCHAR(64);
ALTER TABLE llm_call_log ADD COLUMN exception_type VARCHAR(255);
ALTER TABLE llm_call_log ADD COLUMN stack_trace TEXT;

-- Supports "show me every failure for this request" / "how often does this
-- exception type happen" triage queries without a sequential scan.
CREATE INDEX idx_llm_call_log_request_id ON llm_call_log (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX idx_llm_call_log_exception_type ON llm_call_log (exception_type) WHERE exception_type IS NOT NULL;
