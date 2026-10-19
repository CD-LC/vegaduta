package com.agenticai.core.audit;

/**
 * Admin Issues portal (#425/#426) - which failure path produced a {@link PlatformIssue}. The first
 * three sources are wired synchronously at the point of failure (see PlatformIssueService's javadoc
 * for why sync, not the Kafka outbox): an unhandled exception reaching GlobalExceptionHandler's
 * catch-all, a WorkflowRun transitioning to FAILED, and a ToolCallLog recorded with a non-null
 * errorMessage.
 *
 * <p>{@link #LLM_QUALITY} (Gap 2, Reliability/Governance/SDLC build plan) is different in kind from
 * the other three - it is not a failure at all in the exception/error sense, but a SAMPLED,
 * ASYNCHRONOUS quality signal: {@code LlmQualityJudgeService} occasionally asks a second LLM call
 * to judge whether a just-produced response actually addressed the user, and records a
 * WARNING-severity issue here when it didn't. It is recorded from a background thread strictly
 * after the real response has already been returned to the user (see that service's javadoc), never
 * synchronously on the request path the way the other three sources are.
 *
 * <p>{@link #EVENT_OUTBOX} (#913/#914, Kafka/queue reliability hardening) is the first
 * BACKGROUND/SCHEDULED-JOB-sourced issue type - unlike the three request-path sources above, or
 * {@link #LLM_QUALITY}'s post-response-but- still-per-request framing, this one is raised by {@code
 * WorkflowEventOutboxRelay}'s unattended 5s poll loop, with no request or response in flight at
 * all, once a single outbox row's publish attempts exhaust the relay's retry cap and the row is
 * excluded from all future polls (dead-lettered).
 *
 * <p>{@link #PROVIDER_FALLBACK} (fallback observability follow-up) is raised by {@code
 * AgentChatService} whenever an agent's primary LLM provider call fails and it retries on the
 * agent's configured {@code fallbackProviderName}/ {@code fallbackModel} - both when that retry
 * succeeds (WARNING: primary is degraded but the user still got a response) and when the fallback
 * itself also fails (WARNING: same title/fingerprint bucket, since either way the signal an admin
 * needs is "this agent's primary provider keeps failing"). {@code sourceId} is the agent id,
 * matching the {@link #TOOL_CALL}/{@link #LLM_QUALITY} convention of keying on the stable resource
 * rather than the transient session/run.
 *
 * <p>{@link #TENANT_REPORTED} (#974) is the only HUMAN-sourced issue - a tenant admin self-filing a
 * support ticket from the Billing & usage page, not a failure this platform detected on its own.
 * Unlike the five sources above, rows from this source are never deduplicated by recurrence (see
 * {@code PlatformIssueService#createTicket}) - two tickets with the same title are two distinct
 * tickets, not one problem happening twice - and carry a non-null {@link IssueCategory} chosen by
 * the tenant at submission.
 *
 * <p>{@link #ABUSE_ANOMALY} (Phase P5, Fraud & Anomaly detection) is raised by {@code
 * AbuseSignalService} when a (tenant, user) principal's computed risk score crosses a
 * WATCH/THROTTLE/SUSPEND threshold. Like {@link #TOOL_CALL}/ {@link #PROVIDER_FALLBACK} it keys
 * {@code sourceId} on the stable principal ({@code tenantId:userId}) so a principal that keeps
 * tripping the same signal collapses into one recurring row (bumped {@code occurrenceCount}) rather
 * than a firehose - the fingerprint dedup is exactly what a triage view of "who is being flagged"
 * wants. Recorded from the anomaly-scoring path (a scheduled sweep or an explicit evaluate call),
 * never on the user request hot path.
 *
 * <p>{@link #GUARDRAIL_FALSE_POSITIVE} and {@link #UPSTREAM_PROVIDER_FAILURE} (Diagnostics
 * build-out, 2026-08) close a gap the Vertex AI 403 incident exposed: {@code
 * GlobalExceptionHandler#handleGuardrailViolation} previously recorded no {@link PlatformIssue} at
 * all for a blocked tool output, and {@code handleUpstreamProvider} recorded one through the
 * generic {@code recordIssue(Exception)} catch-all - {@code source=EXCEPTION}, {@code
 * sourceId=null}, {@code title="UpstreamProviderException"} always - which collapsed EVERY
 * provider's EVERY failure into one identical fingerprint (see {@code
 * PlatformIssueService#fingerprint}), hiding a persistent, single-provider outage (e.g. a Vertex AI
 * service account returning 403 on every call) behind a firehose of unrelated upstream errors
 * bumping the same row. Both are now recorded by {@code DiagnosticService} (core/app, alongside
 * {@code GlobalExceptionHandler} - see that class's javadoc for why this couldn't live in this leaf
 * module) with a source-specific {@code sourceId}: the tool name for {@link
 * #GUARDRAIL_FALSE_POSITIVE} (from {@code GuardrailViolationException#getToolName()}), the provider
 * name (e.g. {@code "GOOGLE_VERTEX"}) for {@link #UPSTREAM_PROVIDER_FAILURE} (from {@code
 * UpstreamProviderException#getProviderName()}) - so a repeating failure on the SAME tool/provider
 * now collapses into one recurring row (bumped {@code occurrenceCount}) while a different
 * tool/provider gets its own distinct, triageable row.
 */
public enum IssueSource {
    EXCEPTION,
    WORKFLOW_RUN,
    TOOL_CALL,
    LLM_QUALITY,
    EVENT_OUTBOX,
    PROVIDER_FALLBACK,
    TENANT_REPORTED,
    ABUSE_ANOMALY,
    GUARDRAIL_FALSE_POSITIVE,
    UPSTREAM_PROVIDER_FAILURE,
    /** Monthly hosted-pool spend cap crossed 80% or 100% (SpendCapAlertNotifier, core/platform). */
    SPEND_CAP
}
