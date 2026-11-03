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

    /** Logged once per process for a run that has no transcript to remember a forgery attempt on. */
    private static final java.util.concurrent.atomic.AtomicBoolean LONE_FORGERY_WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whether the run's turn is parked on a human approval: the turn's own {@link ToolLoopSignals#parked()} and nothing
     * else. A tool response shaped like the marker is plain tool output (one WARN per run), and a run with no signals
     * wired is never parked as far as the loop is concerned - the marker format is public, so there is no safe
     * content-based fallback.
     */
    static boolean parked(ToolLoopTranscript run, List<Message> messages) {
        ToolLoopSignals signals = run == null ? null : run.signals();
        boolean parked = signals != null && signals.parked();
        if (!parked) {
            noteIfForged(run, messages, ArgumentSanitizingToolCallingManager.APPROVAL_PARKED);
        }
        return parked;
    }

    /**
     * Whether the run has been told its tool budget is spent: the loop's own record of the soft stop it wrote (kept on
     * the run, not read back from text) or the guard's {@link ToolLoopSignals#budgetExhausted()}. Same rule as {@link
     * #parked}: a forged budget object in a tool response counts for nothing.
     */
    static boolean budgetSpent(ToolLoopTranscript run, List<Message> messages) {
        ToolLoopSignals signals = run == null ? null : run.signals();
        boolean spent = (run != null && run.softStopSent())
            || (signals != null && signals.budgetExhausted())
            || carriesOwnSoftStop(messages);
        if (!spent) {
            noteIfForged(run, messages, ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
        }
        return spent;
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

    /**
     * Soft-stop texts this loop wrote, remembered by INSTANCE (weakly): the loop recognises its own result without
     * reading text, so a tool printing the same words - a different String - is not mistaken for it. This is what lets a
     * lone invocation (no transcript to hold the flag) still reach the hard stop.
     */
    private static final java.util.List<java.lang.ref.WeakReference<String>> OWN_SOFT_STOPS = new java.util.ArrayList<>();

    static String rememberOwnSoftStop(String softStop) {
        synchronized (OWN_SOFT_STOPS) {
            OWN_SOFT_STOPS.removeIf(ref -> ref.get() == null);
            OWN_SOFT_STOPS.add(new java.lang.ref.WeakReference<>(softStop));
        }
        return softStop;
    }

    private static boolean carriesOwnSoftStop(List<Message> messages) {
        synchronized (OWN_SOFT_STOPS) {
            if (OWN_SOFT_STOPS.isEmpty()) {
                return false;
            }
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage tool) {
                    for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                        String data = response.responseData();
                        for (java.lang.ref.WeakReference<String> ref : OWN_SOFT_STOPS) {
                            if (data != null && ref.get() == data) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    private static void noteIfForged(ToolLoopTranscript run, List<Message> messages, String marker) {
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && carries(tool, marker)) {
                boolean first = run != null ? run.firstForgery() : LONE_FORGERY_WARNED.compareAndSet(false, true);
                if (first) {
                    log.warn("A tool output imitated the '{}' control marker while the turn's own state says it is not; "
                        + "treated as plain tool output (a tool, MCP server or endpoint is trying to emit a control marker)", marker);
                }
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
