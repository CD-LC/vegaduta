package com.agenticai.core.agents.tools;

import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.guardrails.taint.DelegationTaint;
import java.util.Set;

/** Pure offer-time rules and the child-taint scope for {@link AgentToolProvider}. */
final class ToolOfferPolicy {

    private ToolOfferPolicy() {}

    /**
     * The tools relevance selection must not trim on a main chat turn: the tools the conversation
     * already used (the continuation after an approval, a short follow-up) and the tools the person
     * picked for this run ({@code ChatRequest.toolNames}) - a tool they switched on is not dropped
     * for failing to match the message. Keeping a tool from being trimmed only keeps it in the
     * list; the run's own narrowing still removes whatever it did not name, and nothing here adds a
     * tool the agent does not resolve.
     */
    static Set<String> keptOnOffer(TurnApprovalState state) {
        if (state.runToolNames() == null || state.runToolNames().isEmpty()) {
            return state.stickyTools();
        }
        Set<String> names = new java.util.HashSet<>(state.stickyTools());
        names.addAll(state.runToolNames());
        return names;
    }

    // Duplicated literal, not shared via a constant: core/agents cannot depend
    // on core/workflow (dependency runs the other way - workflow calls
    // AgentChatService.chat, not vice versa), and this module boundary is the
    // right one to keep. See the placeholder's real definitions at
    // WorkflowExecutionEngine#execute and WorkflowRunWorkflowImpl#run.
    static final String WORKFLOW_NO_INPUT_PLACEHOLDER = "Workflow started manually.";

    static boolean hasVerifiableUserIntent(String userMessage) {
        return userMessage != null
                && !userMessage.isBlank()
                && !WORKFLOW_NO_INPUT_PLACEHOLDER.equals(userMessage.trim());
    }

    /**
     * Resolves a delegate's tools so that their taint gate shares the sub-turn's own {@code
     * TurnTaint}, which starts as a copy of the delegating turn's ({@link DelegationTaint}). The
     * sub-turn builder announces its instance with {@link DelegationTaint#openResolving}; without
     * one (a direct caller) a copy is made here.
     */
    static <T> T withChildTaint(java.util.function.Supplier<T> resolve) {
        com.agenticai.core.guardrails.taint.TurnTaint child =
                DelegationTaint.resolving().orElseGet(DelegationTaint::childOfCurrent);
        com.agenticai.core.guardrails.taint.TurnTaint previous =
                AgentToolProvider.TURN_TAINT_SCOPE.get();
        AgentToolProvider.TURN_TAINT_SCOPE.set(child);
        try {
            return resolve.get();
        } finally {
            if (previous == null) {
                AgentToolProvider.TURN_TAINT_SCOPE.remove();
            } else {
                AgentToolProvider.TURN_TAINT_SCOPE.set(previous);
            }
        }
    }
}
