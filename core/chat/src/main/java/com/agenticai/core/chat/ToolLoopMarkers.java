package com.agenticai.core.chat;

import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * The machine-readable markers the tool loop reads out of tool responses: the budget-exhausted
 * result every loop-limit path writes and the approval_parked result the guard writes once a turn
 * parks on a human approval. Both are written as exactly {@code {"error":"<marker>","result":"<text>"}}
 * (the result is optional) and are matched by that whole shape, never by a substring (see {@link
 * #isMarkerDocument}). The {@code core/guardrails} module cannot depend on this module, so the strings are repeated
 * there ({@code ToolBudgetExhausted}, {@code ApprovalParked}) and a test pins that they agree.
 */
final class ToolLoopMarkers {

    private ToolLoopMarkers() {}

    /** The platform's control results are a sentence or two; anything larger is a tool's output. */
    private static final int MAX_MARKER_CHARS = 16_384;

    /** Rejects anything after the first JSON value: a marker followed by text is a tool's output. */
    private static final com.fasterxml.jackson.databind.ObjectReader STRICT =
            com.agenticai.core.json.JsonMappers.plain()
                    .reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolLoopMarkers.class);

    /** Logged once per process: a run read its control state from tool text because nothing out-of-band was wired. */
    private static final java.util.concurrent.atomic.AtomicBoolean FALLBACK_WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whether the run's turn is parked on a human approval. With {@link ToolLoopSignals} wired this is the signal and
     * only the signal: a tool response shaped like the marker is plain tool output (logged once per run). Without
     * signals it is the content fallback - any response of {@code messages} that is exactly the marker document - which
     * a tool CAN forge; that path logs once per process so a deployment that never wired the signal is visible.
     */
    static boolean parked(ToolLoopTranscript run, List<Message> messages) {
        ToolLoopSignals signals = run == null ? null : run.signals();
        if (signals != null) {
            boolean parked = signals.parked();
            if (!parked) {
                noteIfForged(run, messages, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED);
            }
            return parked;
        }
        return contentFallback(messages, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED);
    }

    /**
     * Whether the run has been told its tool budget is spent: the loop's own record of the soft stop it wrote, or the
     * guard's signal. Same signal-or-fallback rule as {@link #parked}.
     */
    static boolean budgetSpent(ToolLoopTranscript run, List<Message> messages) {
        ToolLoopSignals signals = run == null ? null : run.signals();
        if (signals != null) {
            boolean spent = run.softStopSent() || signals.budgetExhausted();
            if (!spent) {
                noteIfForged(run, messages, ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
            }
            return spent;
        }
        return contentFallback(messages, ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
    }

    /** True when any response in {@code message} is exactly the approval_parked document (a LOCATOR, never a decision when signals are wired). */
    static boolean carriesParkedMarker(ToolResponseMessage message) {
        return carries(message, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED);
    }

    /**
     * Adds to {@code out} the tool names of every response carrying the {@code tool_unavailable}
     * marker - the names the model was already told are not offered to it this invocation.
     */
    static void collectUnavailableNames(ToolResponseMessage message, Set<String> out) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = response.responseData();
            if (data != null
                    && data.contains(
                            "\"" + ArgumentSanitizingToolCallingManager.TOOL_UNAVAILABLE + "\"")) {
                out.add(response.name() == null ? "" : response.name().trim());
            }
        }
    }

    /**
     * The first {@code calls} name already refused as unavailable, or null (a repeat asks to be
     * stopped).
     */
    static String firstUnavailableRepeat(
            List<AssistantMessage.ToolCall> calls, Set<String> refusedNames) {
        if (refusedNames.isEmpty()) {
            return null;
        }
        for (AssistantMessage.ToolCall call : calls) {
            String name = call.name() == null ? "" : call.name().trim();
            if (refusedNames.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** True when any response in {@code message} is exactly the tool_budget_exhausted document (content shape only). */
    static boolean carriesBudgetMarker(ToolResponseMessage message) {
        return carries(message, ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
    }

    private static boolean contentFallback(List<Message> messages, String marker) {
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && carries(tool, marker)) {
                if (FALLBACK_WARNED.compareAndSet(false, true)) {
                    log.warn("Tool loop has no out-of-band ToolLoopSignals for this run; reading the '{}' control marker from "
                        + "tool output text, which any tool can forge. Wire ToolLoopTranscript.signals(...).", marker);
                }
                return true;
            }
        }
        return false;
    }

    private static void noteIfForged(ToolLoopTranscript run, List<Message> messages, String marker) {
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && carries(tool, marker) && run.firstForgery()) {
                log.warn("A tool output imitated the '{}' control marker while the turn's own state says it is not; "
                    + "treated as plain tool output (a tool, MCP server or endpoint is trying to emit a control marker)", marker);
                return;
            }
        }
    }

    private static boolean carries(ToolResponseMessage message, String marker) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            if (isMarkerDocument(response.responseData(), marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True only when {@code data} is exactly the platform's control result {@code
     * {"error":"<marker>","result":"<text>"}}. A substring match let any tool output that contained
     * the marker (a fetched page, a third-party MCP reply) end the turn as "waiting for approval" or
     * count as a spent budget.
     */
    static boolean isMarkerDocument(String data, String marker) {
        if (data == null || data.length() > MAX_MARKER_CHARS || !data.contains(marker)) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = STRICT.readTree(data);
            if (root == null || !root.isObject() || root.size() > 2) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode error = root.get("error");
            if (error == null || !error.isTextual() || !marker.equals(error.asText())) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode result = root.get("result");
            return (root.size() == 1 && result == null)
                    || (root.size() == 2 && result != null && result.isTextual());
        } catch (Exception notJson) {
            return false;
        }
    }

    /**
     * The soft-stop JSON written by hand, for the case the serializer fails (unreachable for a map
     * of strings). It carries no English of its own: the instruction is the registry's text.
     */
    static String fallbackSoftStop(String instruction) {
        return "{\"error\":\""
                + ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED
                + "\",\"result\":\""
                + new String(
                        com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance()
                                .quoteAsString(String.valueOf(instruction)))
                + "\"}";
    }
}
