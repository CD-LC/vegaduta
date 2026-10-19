package com.agenticai.core.approval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link ApprovalActionExecutor} bean in the application
 * context, keyed by its declared {@link ApprovalActionExecutor#actionType()}.
 * At Omnichannel Stage 1, this list is empty - no channel adapters exist
 * yet, which is expected and covered by the Stage 1 verification prompt
 * (an unmatched action type logs a failure outcome rather than silently
 * doing nothing, see {@link HumanApprovalGateService#approve}).
 *
 * <p>Lookup is two-phase since the agent-tool replay executor arrived: an
 * exact {@code actionType()} match always wins; only when none exists does
 * {@link #find} fall back to asking each executor's {@link
 * ApprovalActionExecutor#supports} - the seam that lets ONE executor own
 * the whole dynamic {@code "mcp_tool.<name>"} family. The exact-first
 * ordering is deliberate: if a dedicated executor for one specific tool's
 * action type is ever registered, it takes that tool over from the family
 * executor with no further wiring. The uniqueness check below still only
 * covers declared {@code actionType()} values, not {@code supports}
 * overlap - two family executors claiming overlapping prefixes would
 * resolve by bean iteration order, which is why the fallback scan logs
 * nothing and stays simple: today there are exactly two family executors
 * ({@code "mcp_tool."} in {@code core/agents} and {@code "workflow.node."}
 * in {@code core/workflow}) whose prefixes are disjoint by construction, so
 * ordering can never matter between them; a new family whose prefix could
 * overlap an existing one should prompt revisiting this registry, not
 * silently relying on ordering.
 */
@Component
public class ApprovalActionExecutorRegistry {

    private final Map<String, ApprovalActionExecutor> executorsByActionType;
    private final List<ApprovalActionExecutor> executors;

    public ApprovalActionExecutorRegistry(List<ApprovalActionExecutor> executors) {
        Map<String, ApprovalActionExecutor> byActionType = new HashMap<>();
        for (ApprovalActionExecutor executor : executors) {
            ApprovalActionExecutor existing = byActionType.putIfAbsent(executor.actionType(), executor);
            if (existing != null) {
                throw new IllegalStateException(
                    "Two ApprovalActionExecutor beans registered for the same action type '"
                        + executor.actionType() + "': "
                        + existing.getClass().getSimpleName() + " and "
                        + executor.getClass().getSimpleName()
                );
            }
        }
        this.executorsByActionType = byActionType;
        this.executors = List.copyOf(executors);
    }

    public Optional<ApprovalActionExecutor> find(String actionType) {
        ApprovalActionExecutor exact = executorsByActionType.get(actionType);
        if (exact != null) {
            return Optional.of(exact);
        }
        return executors.stream()
            .filter(executor -> executor.supports(actionType))
            .findFirst();
    }
}
