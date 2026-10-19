package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Where every hook decision lands: a step event (when the turn asked for them), an audit-log entry,
 * and a counter. Best-effort end to end - recording a decision must never change it or fail the
 * run.
 *
 * <p>Decision vocabulary (also the step event's {@code decision}): {@code deny}, {@code
 * require_approval}, {@code redacted}, {@code limited}, {@code note}, {@code allow}, {@code
 * logged}, {@code error}.
 */
final class HookDecisionSink {

    private static final Logger log = LoggerFactory.getLogger(HookDecisionSink.class);

    static final String AUDIT_ACTION = "agent.hook.decision";
    static final String METRIC = "agent.hook.decisions";
    private static final int MAX_DETAIL_CHARS = 600;

    private final ObjectProvider<AuditLogService> audit;
    private final ObjectProvider<MeterRegistry> meters;

    HookDecisionSink(ObjectProvider<AuditLogService> audit, ObjectProvider<MeterRegistry> meters) {
        this.audit = audit;
        this.meters = meters;
    }

    /**
     * @param tool the tool concerned, or {@code null} at a run-level point
     * @param reason a short, operator-facing note; never tool arguments or output
     */
    void record(HookCtx ctx, EffectiveHook hook, String decision, String tool, String reason) {
        HookDefinition def = hook.def();
        try {
            AgentStepEventEmitter emitter = ctx.emitterOrCurrent();
            if (emitter != null) {
                emitter.hook(
                        def.point().name(),
                        hook.key(),
                        def.action().wire(),
                        decision,
                        tool,
                        reason);
            }
        } catch (RuntimeException e) {
            log.debug("Could not emit the hook step event for {}", hook.key(), e);
        }
        try {
            MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
            if (registry != null) {
                registry.counter(
                                METRIC,
                                "point",
                                def.point().name(),
                                "action",
                                def.action().wire(),
                                "decision",
                                decision)
                        .increment();
            }
        } catch (RuntimeException e) {
            log.debug("Could not count the hook decision for {}", hook.key(), e);
        }
        try {
            AuditLogService service = audit == null ? null : audit.getIfAvailable();
            if (service != null) {
                String detail =
                        "point="
                                + def.point()
                                + " hook="
                                + hook.key()
                                + " action="
                                + def.action().wire()
                                + " decision="
                                + decision
                                + (tool != null ? " tool=" + tool : "")
                                + (reason != null && !reason.isBlank() ? " reason=" + reason : "");
                if (detail.length() > MAX_DETAIL_CHARS) {
                    detail = detail.substring(0, MAX_DETAIL_CHARS);
                }
                String detailToStore = detail;
                ctx.scope()
                        .run(
                                () ->
                                        service.record(
                                                ctx.actor(),
                                                AUDIT_ACTION,
                                                "agent",
                                                String.valueOf(ctx.agentId()),
                                                outcomeFor(decision),
                                                detailToStore));
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Could not write the audit entry for hook {} decision {}",
                    hook.key(),
                    decision,
                    e);
        }
    }

    static AuditOutcome outcomeFor(String decision) {
        return switch (decision) {
            case "deny", "limited" -> AuditOutcome.REJECTED;
            case "require_approval" -> AuditOutcome.PENDING_APPROVAL;
            case "error" -> AuditOutcome.FAILURE;
            default -> AuditOutcome.SUCCESS;
        };
    }
}
