package com.agenticai.core.agents.crew;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writer/reader for the durable handoff audit trail - migration V305's {@code
 * agent_handoff_audit}. See that migration's header for why a dedicated table exists alongside
 * the pre-existing ephemeral {@code AgentStepEventEmitter#handoff} step-event trace.
 *
 * <p>Every write here is best-effort and fail-open, matching this codebase's established posture
 * for observability writes that must never be able to fail the turn they describe (same as {@code
 * AgentStepEventEmitter#emit}'s own javadoc): a row that fails to persist is logged and swallowed,
 * never propagated to the caller.
 */
@Service
public class AgentHandoffAuditService {

    private static final Logger log = LoggerFactory.getLogger(AgentHandoffAuditService.class);

    private final AgentHandoffAuditRepository repository;

    public AgentHandoffAuditService(AgentHandoffAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Records the START of a hop that is ABOUT to be dispatched (status {@link
     * HandoffStatus#PENDING}), before the specialist's turn actually runs - so a hop that never
     * returns (a hung provider, a JVM crash mid-call) still leaves a row rather than silently
     * vanishing from the trail. Returns the row id to pass to {@link #recordOutcome}, or {@code
     * null} if the write itself failed (fail-open - the caller proceeds with dispatch regardless).
     */
    public UUID recordStart(HandoffChain chain, UUID toAgentId, String capabilityTag, String subTask) {
        try {
            AgentHandoffAudit row = new AgentHandoffAudit(
                chain.turnSessionId(), chain.rootAgentId(), chain.currentAgentId(), toAgentId,
                capabilityTag, chain.depth() + 1, subTask, HandoffStatus.PENDING
            );
            return repository.save(row).getId();
        } catch (RuntimeException e) {
            log.warn("Failed to record handoff-start audit row for turn {} ({} -> {})",
                chain.turnSessionId(), chain.currentAgentId(), toAgentId, e);
            return null;
        }
    }

    /** Closes out a row {@link #recordStart} opened, with the hop's final status. A no-op if {@code auditId} is null (the start write itself failed) or no longer resolves. */
    public void recordOutcome(UUID auditId, HandoffStatus status, String errorMessage) {
        if (auditId == null) {
            return;
        }
        try {
            repository.findById(auditId).ifPresent(row -> {
                row.setStatus(status);
                row.setErrorMessage(errorMessage);
                row.setCompletedAt(java.time.Instant.now());
                repository.save(row);
            });
        } catch (RuntimeException e) {
            log.warn("Failed to record handoff outcome for audit row {}", auditId, e);
        }
    }

    /**
     * Records a hop that was REFUSED before dispatch ({@link HandoffStatus#DEPTH_EXCEEDED}, {@link
     * HandoffStatus#CYCLE_DETECTED} or {@link HandoffStatus#NO_SPECIALIST_FOUND}) - a single
     * already-terminal row, since there is no "in-flight" state for something that never ran.
     * {@code toAgentId} may be {@code null} for {@code NO_SPECIALIST_FOUND} (nothing matched the
     * requested tag, so there is no target agent to name).
     */
    public void recordRefusal(
        HandoffChain chain, UUID toAgentId, String capabilityTag, String subTask, HandoffStatus status, String reason
    ) {
        try {
            AgentHandoffAudit row = new AgentHandoffAudit(
                chain.turnSessionId(), chain.rootAgentId(), chain.currentAgentId(),
                toAgentId == null ? chain.currentAgentId() : toAgentId,
                capabilityTag, chain.depth() + 1, subTask, status
            );
            row.setErrorMessage(reason);
            row.setCompletedAt(row.getStartedAt());
            repository.save(row);
        } catch (RuntimeException e) {
            log.warn("Failed to record handoff refusal ({}) for turn {}", status, chain.turnSessionId(), e);
        }
    }

    /** Every hop of one turn's handoff chain(s), in dispatch order - the full trail for a trace/debug view. */
    public List<AgentHandoffAudit> findChainForTurn(String turnSessionId) {
        return repository.findByTurnSessionIdOrderByDepthAscCreatedAtAsc(turnSessionId);
    }
}
