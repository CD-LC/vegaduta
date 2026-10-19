package com.agenticai.core.common;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Diagnostics build-out (2026-08) - turns two specific failure shapes that
 * previously either recorded no {@code PlatformIssue} at all, or all
 * collapsed into one indistinguishable row, into durable, admin-visible,
 * per-tool/per-provider signals. See {@code IssueSource#GUARDRAIL_FALSE_POSITIVE}/
 * {@code #UPSTREAM_PROVIDER_FAILURE}'s javadoc for the full incident that
 * motivated this (a Vertex AI service account returning 403 on every call,
 * invisible as a distinct, recurring problem until now).
 *
 * <p>Deliberately lives here (core/app, alongside {@code
 * GlobalExceptionHandler}, its only caller) rather than in core/audit itself:
 * core/audit's own {@code pom.xml} documents it as a leaf module with "No
 * dependency on any other core module", and core/guardrails already depends
 * ON core/audit (for {@code AuditLogService}, see {@code
 * GuardrailViolationLogger}/{@code ModerationService}) - giving core/audit a
 * reverse dependency on core/guardrails just for {@link
 * GuardrailViolationException} would create a circular module dependency,
 * which Maven's reactor rejects outright. core/app already has a compile
 * dependency on core/audit, core/chat, AND core/guardrails (it is the
 * top-level composition module), so this is the lowest-friction home that
 * doesn't touch that leaf-module boundary.
 *
 * <p>Both {@code record*} methods are best-effort, matching the "never let an
 * observability write break the primary operation" posture {@code
 * GlobalExceptionHandler#recordIssue} already documents - a bug in issue
 * recording itself must never turn into a second failure on top of the
 * original exception being handled.
 */
@Service
public class DiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticService.class);

    private final PlatformIssueService issueService;

    public DiagnosticService(PlatformIssueService issueService) {
        this.issueService = issueService;
    }

    /**
     * Called from {@code GlobalExceptionHandler#handleGuardrailViolation}. WARNING
     * severity (not ERROR): a blocked tool output isn't necessarily a platform bug -
     * it may be a correct block, or (per the incident that motivated this class) the
     * semantic judge false-positiving on an upstream error message's own wording -
     * either way it is a signal worth an admin's attention, not an outage.
     *
     * <p>Returns the saved/bumped {@link PlatformIssue} (or {@code null} if recording itself
     * failed) so {@code GlobalExceptionHandler} can hand its id to {@code
     * AiDiagnosticAnalysisService#analyzeAsync} for the async LLM enrichment step - see that
     * class's javadoc. This method's own best-effort posture is unchanged: a recording failure
     * is still only logged, never propagated, so the original exception is always still returned
     * to the caller regardless of what happens here.
     */
    public PlatformIssue recordGuardrailViolation(GuardrailViolationException e) {
        try {
            String toolName = e.getToolName() == null || e.getToolName().isBlank() ? "unknown-tool" : e.getToolName();
            return issueService.record(
                IssueSource.GUARDRAIL_FALSE_POSITIVE,
                toolName,
                IssueSeverity.WARNING,
                "Guardrail blocked output from tool '" + toolName + "'",
                e.getMessage()
            );
        } catch (Exception recordingFailure) {
            log.warn("Failed to record platform issue for guardrail violation (original exception still returned to caller)", recordingFailure);
            return null;
        }
    }

    /**
     * Called from {@code GlobalExceptionHandler#handleUpstreamProvider}, replacing
     * that handler's previous generic {@code recordIssue(Exception)} call - see
     * {@code IssueSource#UPSTREAM_PROVIDER_FAILURE}'s javadoc for why the generic
     * catch-all fingerprint was wrong for this exception type specifically. ERROR
     * severity: an upstream LLM call actually failed and the caller got no answer.
     *
     * <p>Returns the saved/bumped {@link PlatformIssue} (or {@code null} if recording itself
     * failed) for the same reason {@link #recordGuardrailViolation}'s javadoc explains.
     */
    public PlatformIssue recordUpstreamProviderFailure(UpstreamProviderException e) {
        try {
            String providerName = e.getProviderName() == null || e.getProviderName().isBlank()
                ? "unknown-provider" : e.getProviderName();
            return issueService.record(
                IssueSource.UPSTREAM_PROVIDER_FAILURE,
                providerName,
                IssueSeverity.ERROR,
                "Upstream provider failure: " + providerName,
                e.getMessage()
            );
        } catch (Exception recordingFailure) {
            log.warn("Failed to record platform issue for upstream provider failure (original exception still returned to caller)", recordingFailure);
            return null;
        }
    }
}
