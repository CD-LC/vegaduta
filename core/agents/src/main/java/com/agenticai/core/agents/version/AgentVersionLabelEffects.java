package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What every agent-version label move does besides moving the label: the PRODUCTION eval gate, the
 * promote audit row, and the label-event / resolution-cache / outbox bookkeeping. Not a Spring
 * bean: {@link AgentVersionService} builds it from its own (optional) collaborators, and the
 * transaction boundaries stay on that service's methods.
 */
final class AgentVersionLabelEffects {

    private static final Logger log = LoggerFactory.getLogger(AgentVersionService.class);

    private final ObjectProvider<AgentPromotionGate> promotionGate;
    private final AuditLogService auditLogService;
    private final ObjectProvider<VersionLabelEventRepository> labelEvents;
    private final ObjectProvider<AgentVersionResolutionService> resolution;
    private final ObjectProvider<WorkflowEventPublisher> eventPublisher;

    /** Any provider may be null (the hand-built 8-arg service constructor), meaning "absent". */
    AgentVersionLabelEffects(
            ObjectProvider<AgentPromotionGate> promotionGate,
            AuditLogService auditLogService,
            ObjectProvider<VersionLabelEventRepository> labelEvents,
            ObjectProvider<AgentVersionResolutionService> resolution,
            ObjectProvider<WorkflowEventPublisher> eventPublisher) {
        this.promotionGate = promotionGate;
        this.auditLogService = auditLogService;
        this.labelEvents = labelEvents;
        this.resolution = resolution;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The bookkeeping every label move shares: append the {@code version_label_event} audit row,
     * evict the resolution cache for this agent (same-JVM AND the shared Redis key, so every
     * replica's next run re-resolves), and emit the outbox event cross-instance consumers subscribe
     * to. All three are best-effort-in-isolation: the label row itself is already flushed, and a
     * missing event/eviction degrades to "stale for up to the cache TTL", never a failed promote.
     */
    void afterLabelMove(
            UUID agentId,
            AgentVersion target,
            AgentVersionLabel label,
            LabelEventAction action,
            String detail) {
        VersionLabelEventRepository events =
                labelEvents == null ? null : labelEvents.getIfAvailable();
        if (events != null) {
            try {
                events.save(
                        VersionLabelEvent.of(
                                VersionEntityType.AGENT,
                                agentId,
                                label.name(),
                                target.getId(),
                                action,
                                TenantContext.current().map(TenantContext::getSubject).orElse(null),
                                detail));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not record label event for agent {} -> v{}",
                        agentId,
                        target.getVersionNumber(),
                        e);
            }
        }
        AgentVersionResolutionService resolver =
                resolution == null ? null : resolution.getIfAvailable();
        if (resolver != null) {
            resolver.evict(agentId);
        }
        WorkflowEventPublisher publisher =
                eventPublisher == null ? null : eventPublisher.getIfAvailable();
        if (publisher != null) {
            String type =
                    action == LabelEventAction.ROLLBACK
                            ? "version.rolled_back"
                            : "version.promoted";
            try {
                publisher.publish(
                        type,
                        agentId.toString(),
                        Map.of(
                                "entityType", "AGENT",
                                "entityId", agentId.toString(),
                                "versionId", target.getId().toString(),
                                "versionNumber", target.getVersionNumber(),
                                "label", label.name()));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not publish {} for agent {} v{}",
                        type,
                        agentId,
                        target.getVersionNumber(),
                        e);
            }
        }
    }

    /**
     * The gate check extracted so {@link AgentVersionService#setLabel} stays readable. Only runs
     * for PRODUCTION; STAGING is the under-test pointer and stays free (see {@link
     * AgentPromotionGate}'s javadoc for why gating it would invert the workflow). No provider bean
     * means no gate is possible at all - the SPI is satisfied by core/app's bridge, absent only in
     * hand-built tests.
     *
     * @return null when the gate allowed (or no gate exists); the override detail string when a
     *     human FORCED past a block, so {@link AgentVersionService#setLabel} can write it into the
     *     SUCCESS audit row - one row reading "promoted, gate had refused for reason X, overridden
     *     by Y because Z".
     */
    String enforcePromotionGate(Agent agent, AgentVersion target, boolean force, String reason) {
        AgentPromotionGate gate = promotionGate.getIfAvailable();
        if (gate == null) {
            return null;
        }
        AgentPromotionGate.GateDecision decision =
                gate.evaluatePromotion(agent.getId(), target.getId());
        if (decision.allowed()) {
            return null;
        }
        if (!force) {
            auditPromote(
                    agent,
                    target,
                    "BLOCKED by eval gate [" + decision.reason() + "]",
                    AuditOutcome.FAILURE);
            throw new PromotionGateBlockedException(decision);
        }
        if (!isForceOverrideAllowed()) {
            auditPromote(
                    agent,
                    target,
                    "BLOCKED by eval gate ["
                            + decision.reason()
                            + "]; force denied - caller lacks MANAGE_AGENTS",
                    AuditOutcome.FAILURE);
            throw new PromotionGateForceDeniedException(
                    "Force-promoting past the eval gate requires the MANAGE_AGENTS scope.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "force=true requires a reason - the override is written to the audit log.");
        }
        log.warn(
                "Agent {} PRODUCTION promote of v{} FORCED past eval gate [{}] by {} ({})",
                LogSafe.clean(agent.getId()),
                LogSafe.clean(target.getVersionNumber()),
                LogSafe.clean(decision.reason()),
                LogSafe.clean(TenantContext.current().map(TenantContext::getSubject).orElse("?")),
                LogSafe.clean(reason));
        return "FORCED past gate [" + decision.reason() + "]: " + reason.trim();
    }

    /**
     * The force-override scope check: the granular-RBAC domain permission for agents, or
     * platform-admin (which carries no tenant grants by design). Same pair {@code
     * AgentService#listAll}'s sees-whole-tenant check uses.
     */
    private static boolean isForceOverrideAllowed() {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole("PERM_MANAGE_AGENTS") || ctx.hasRole("ROLE_platform-admin"))
                .orElse(false);
    }

    void auditPromote(Agent agent, AgentVersion target, String detail, AuditOutcome outcome) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        String description =
                "version="
                        + target.getId()
                        + " (v"
                        + target.getVersionNumber()
                        + ")"
                        + (detail != null && !detail.isBlank() ? " - " + detail.trim() : "");
        try {
            auditLogService.record(
                    actor,
                    "agent.version.promote",
                    "agent",
                    agent.getId().toString(),
                    outcome,
                    description);
        } catch (RuntimeException e) {
            // Audit must never block or break the promote itself - the row is
            // evidence, not state. Same fail-open posture as the snapshot write.
            log.warn(
                    "Could not write audit row for promote of agent {} to v{}",
                    agent.getId(),
                    target.getVersionNumber(),
                    e);
        }
    }
}
