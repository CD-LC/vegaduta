package com.agenticai.core.guardrails;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Logs guardrail violations for observability and compliance monitoring.
 * Records all guardrail blocks and near-blocks to enable tracking of:
 * - Which guardrails are most frequently triggered
 * - False positive rates
 * - Patterns in blocked content
 * - Potential abuse or attack attempts
 */
@Service
public class GuardrailViolationLogger {

    private static final Logger log = LoggerFactory.getLogger(GuardrailViolationLogger.class);

    private static final String ACTION_GUARDRAIL_INPUT_BLOCK = "guardrail.input.blocked";
    private static final String ACTION_GUARDRAIL_OUTPUT_BLOCK = "guardrail.output.blocked";
    private static final String TARGET_TYPE = "guardrail_violation";

    private final AuditLogService auditLogService;

    /** Optional (setter-injected so the constructor callers and tests keep working); null means "publish nothing". */
    private ApplicationEventPublisher eventPublisher;

    public GuardrailViolationLogger(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Announces a block as a {@link GuardrailViolationEvent}; never lets a listener break the chat turn. */
    private void publish(UUID agentId, String sessionId, boolean inputPath, ContentGuardrailCheckResult result) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new GuardrailViolationEvent(agentId, sessionId, inputPath,
                result.getBlockedByGuardrail() == null ? null : result.getBlockedByGuardrail().name(),
                result.getBlockReason(), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("A guardrail-violation listener failed for agent {} session {}", agentId, sessionId, e);
        }
    }

    /**
     * Logs a guardrail violation on the input path (user message).
     *
     * @param agentId the agent being called
     * @param sessionId the chat session
     * @param result the guardrail check result (must indicate failure)
     */
    public void logInputViolation(
        UUID agentId,
        String sessionId,
        ContentGuardrailCheckResult result
    ) {
        if (result.isPassed()) {
            return; // Only log failures
        }

        String detail = "path=input guardrail=" + result.getBlockedByGuardrail()
            + " confidence=" + result.getConfidence()
            + " reason=" + result.getBlockReason();

        auditLogService.record(
            null, // No user context for input violations
            ACTION_GUARDRAIL_INPUT_BLOCK,
            TARGET_TYPE,
            sessionId,
            AuditOutcome.REJECTED,
            detail
        );

        log.warn("Input guardrail violation: agent={}, session={}, {}",
            agentId, sessionId, detail);
        publish(agentId, sessionId, true, result);
    }

    /**
     * Logs a guardrail violation on the output path (agent response).
     *
     * @param agentId the agent that generated the response
     * @param sessionId the chat session
     * @param result the guardrail check result (must indicate failure)
     */
    public void logOutputViolation(
        UUID agentId,
        String sessionId,
        ContentGuardrailCheckResult result
    ) {
        if (result.isPassed()) {
            return; // Only log failures
        }

        String detail = "path=output guardrail=" + result.getBlockedByGuardrail()
            + " confidence=" + result.getConfidence()
            + " reason=" + result.getBlockReason();

        auditLogService.record(
            null, // No user context for output violations
            ACTION_GUARDRAIL_OUTPUT_BLOCK,
            TARGET_TYPE,
            sessionId,
            AuditOutcome.REJECTED,
            detail
        );

        log.warn("Output guardrail violation: agent={}, session={}, {}",
            agentId, sessionId, detail);
        publish(agentId, sessionId, false, result);
    }
}
