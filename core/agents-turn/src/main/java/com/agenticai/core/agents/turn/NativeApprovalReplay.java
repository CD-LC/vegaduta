package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.ToolLedgerReplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The replayed history of the turn that continues a task after a human decided its held tool call,
 * with that call given to the model the way providers carry one natively: an assistant tool call
 * followed by its tool result. The two text records the history holds for it (the parked turn and
 * the outcome note) are left out, so the continuation sends the first turn's prompt plus the call,
 * the result and a short note, and a provider's prompt cache reuses that prefix.
 *
 * <p>Applies only when the continuation message was written for this shape (see {@link
 * ApprovalContinuation#isNativeShapeMessage}) and the history holds the outcome note of that
 * request; anything else (no note, arguments that are not a JSON object) is replayed as text.
 */
final class NativeApprovalReplay {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code hasTextRecord}: a tool record is still replayed as text, so the trust note is needed.
     */
    record Replay(List<Message> messages, boolean hasTextRecord) {}

    private NativeApprovalReplay() {}

    static Replay of(
            List<ChatTurn> turns,
            ApprovalContinuation continuation,
            String userMessage,
            ToolLedgerProperties ledger) {
        int outcome =
                continuation != null && ApprovalContinuation.isNativeShapeMessage(userMessage)
                        ? outcomeIndex(turns, continuation, ledger)
                        : -1;
        if (outcome < 0 || !isJsonObject(continuation.argumentsJson())) {
            return asText(turns);
        }
        String requestId = String.valueOf(continuation.requestId());
        int parked =
                outcome > 0 && heldOnly(turns.get(outcome - 1), continuation, requestId)
                        ? outcome - 1
                        : -1;
        ToolLedger.Entry note = turns.get(outcome).tools().entries().get(0);
        String callId = "approval_" + requestId.replace("-", "");
        List<Message> out = new ArrayList<>(turns.size() + 1);
        boolean textRecord = false;
        for (int i = 0; i < turns.size(); i++) {
            if (i == parked) {
                continue;
            }
            if (i == outcome) {
                out.add(
                        AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        callId,
                                                        "function",
                                                        continuation.toolName(),
                                                        continuation.argumentsJson())))
                                .build());
                out.add(
                        ToolResponseMessage.builder()
                                .responses(
                                        List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        callId,
                                                        continuation.toolName(),
                                                        result(continuation, note))))
                                .build());
                continue;
            }
            ChatTurn turn = turns.get(i);
            textRecord |= turn.tools() != null;
            out.add(asMessage(turn));
        }
        return new Replay(List.copyOf(out), textRecord);
    }

    static Replay asText(List<ChatTurn> turns) {
        return new Replay(
                turns.stream().map(NativeApprovalReplay::asMessage).toList(),
                turns.stream().anyMatch(t -> t.tools() != null));
    }

    private static Message asMessage(ChatTurn turn) {
        return turn.role() == MessageRole.USER
                ? new UserMessage(turn.content())
                : new AssistantMessage(turn.content());
    }

    /**
     * The newest outcome note of this request: a record-only turn, replayed as its rendered record
     * alone.
     */
    private static int outcomeIndex(
            List<ChatTurn> turns, ApprovalContinuation continuation, ToolLedgerProperties ledger) {
        String requestId = String.valueOf(continuation.requestId());
        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatTurn turn = turns.get(i);
            ToolLedger.Entry entry = single(turn);
            if (entry != null
                    && entry.status() != ToolLedger.Status.AWAITING_APPROVAL
                    && entry.approval() != null
                    && entry.approval().startsWith(requestId)
                    && entry.tool().equals(continuation.toolName())
                    && ToolLedgerReplay.render(turn.tools(), ledger).equals(turn.content())) {
                return i;
            }
        }
        return -1;
    }

    /** The parked turn of this request when the held call is all it recorded. */
    private static boolean heldOnly(
            ChatTurn turn, ApprovalContinuation continuation, String requestId) {
        ToolLedger.Entry entry = single(turn);
        return entry != null
                && entry.status() == ToolLedger.Status.AWAITING_APPROVAL
                && requestId.equals(entry.approval())
                && entry.tool().equals(continuation.toolName());
    }

    private static ToolLedger.Entry single(ChatTurn turn) {
        if (turn == null
                || turn.role() != MessageRole.ASSISTANT
                || turn.tools() == null
                || turn.tools().omitted() != 0
                || turn.tools().entries().size() != 1) {
            return null;
        }
        return turn.tools().entries().get(0);
    }

    /**
     * The approved call's output for a completed call (already cut by {@code
     * ApprovalContinuationPrompt#forReplay}); the outcome note (denial reason, failure) otherwise.
     */
    private static String result(ApprovalContinuation continuation, ToolLedger.Entry note) {
        if (continuation.outcome() != ApprovalContinuation.Outcome.COMPLETED) {
            return note.result();
        }
        return continuation.result() == null ? "" : continuation.result();
    }

    private static boolean isJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            return JSON.readTree(json).isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
