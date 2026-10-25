package com.agenticai.core.chat;

import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * The machine-readable markers the tool loop reads out of tool responses: the budget-exhausted
 * result every loop-limit path writes and the approval_parked result the guard writes once a
 * turn parks on a human approval. Both are embedded as {@code "error":"<marker>"} in a JSON
 * tool result, so detection is a content match that survives the {@code {"result":...}} wrap
 * non-object outputs get. The {@code core/guardrails} module cannot depend on this module, so
 * the strings are repeated there ({@code ToolBudgetExhausted}, {@code ApprovalParked}) and a
 * test pins that they agree.
 */
final class ToolLoopMarkers {

    private ToolLoopMarkers() {}

    /** True when any response in the message carries the tool_budget_exhausted marker. */
    static boolean carriesBudgetMarker(ToolResponseMessage message) {
        return carries(message, ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
    }

    /** True when any response in the message carries the approval_parked marker. */
    static boolean carriesParkedMarker(ToolResponseMessage message) {
        return carries(message, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED);
    }

    private static boolean carries(ToolResponseMessage message, String marker) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = response.responseData();
            if (data != null && data.contains("\"" + marker + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The soft-stop JSON written by hand, for the case the serializer fails (unreachable for a map of strings). It
     * carries no English of its own: the instruction is the registry's text.
     */
    static String fallbackSoftStop(String instruction) {
        return "{\"error\":\"" + ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED + "\",\"result\":\""
            + new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(String.valueOf(instruction)))
            + "\"}";
    }
}
