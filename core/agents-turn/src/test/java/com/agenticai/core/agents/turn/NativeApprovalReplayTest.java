package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.ToolLedgerReplay;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class NativeApprovalReplayTest {

    private static final UUID REQUEST = UUID.fromString("c888f5cf-c6cf-498c-a0f1-9938f8a33f5b");
    private static final String ARGS = "{\"to\":\"ops@example.invalid\"}";
    private static final String GOAL = "Send the status email";
    private static final String NATIVE_MESSAGE =
            "<approval_result request=\""
                    + REQUEST
                    + "\" tool=\"send_email\" outcome=\"completed\" shape=\"native\">\n"
                    + "note\n</approval_result>";
    private static final String TEXT_MESSAGE =
            "<approval_result request=\""
                    + REQUEST
                    + "\" tool=\"send_email\" outcome=\"completed\">\n"
                    + "note\n</approval_result>";

    private final ToolLedgerProperties ledger = new ToolLedgerProperties();

    @Test
    void theDecidedCallReplacesTheParkedTurnAndTheNoteAsANativeCallAndResult() {
        NativeApprovalReplay.Replay replay =
                NativeApprovalReplay.of(
                        history(
                                ToolLedger.Status.OK,
                                "[approved action 'send_email' completed: queued]"),
                        continuation(Outcome.COMPLETED, ARGS, "queued email"),
                        NATIVE_MESSAGE,
                        ledger);

        List<Message> messages = replay.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        AssistantMessage call = (AssistantMessage) messages.get(1);
        assertThat(call.getToolCalls())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.id())
                                    .isEqualTo("approval_c888f5cfc6cf498ca0f19938f8a33f5b");
                            assertThat(c.name()).isEqualTo("send_email");
                            assertThat(c.arguments()).isEqualTo(ARGS);
                        });
        ToolResponseMessage result = (ToolResponseMessage) messages.get(2);
        assertThat(result.getResponses())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.id()).isEqualTo(call.getToolCalls().get(0).id());
                            assertThat(r.responseData()).isEqualTo("queued email");
                        });
        assertThat(replay.hasTextRecord()).isFalse();
    }

    @Test
    void aDeniedCallIsAnsweredWithTheDenialNote() {
        String note = "[action 'send_email' was DENIED by admin: not today - it did not run]";
        NativeApprovalReplay.Replay replay =
                NativeApprovalReplay.of(
                        history(ToolLedger.Status.NOT_RUN, note),
                        continuation(Outcome.DENIED, ARGS, "not today"),
                        NATIVE_MESSAGE,
                        ledger);

        ToolResponseMessage result = (ToolResponseMessage) replay.messages().get(2);
        assertThat(result.getResponses().get(0).responseData()).isEqualTo(note);
    }

    @Test
    void aTextContinuationMessageKeepsTheTextRecords() {
        NativeApprovalReplay.Replay replay =
                NativeApprovalReplay.of(
                        history(
                                ToolLedger.Status.OK,
                                "[approved action 'send_email' completed: queued]"),
                        continuation(Outcome.COMPLETED, ARGS, "queued email"),
                        TEXT_MESSAGE,
                        ledger);

        assertThat(replay.messages())
                .hasSize(3)
                .allSatisfy(m -> assertThat(m).isNotInstanceOf(ToolResponseMessage.class));
        assertThat(replay.hasTextRecord()).isTrue();
    }

    @Test
    void argumentsThatAreNotAJsonObjectKeepTheTextRecords() {
        NativeApprovalReplay.Replay replay =
                NativeApprovalReplay.of(
                        history(
                                ToolLedger.Status.OK,
                                "[approved action 'send_email' completed: queued]"),
                        continuation(Outcome.COMPLETED, "{\"to\":\"ops@exa...", "queued email"),
                        NATIVE_MESSAGE,
                        ledger);

        assertThat(replay.messages()).noneMatch(m -> m instanceof ToolResponseMessage);
    }

    @Test
    void anotherRequestsNoteIsLeftAsText() {
        UUID other = UUID.randomUUID();
        List<ChatTurn> turns =
                List.of(
                        new ChatTurn(MessageRole.USER, GOAL, Instant.now(), null),
                        folded(
                                ChatTurn.toolRecord(
                                        new ToolLedger(
                                                List.of(
                                                        new ToolLedger.Entry(
                                                                "send_email",
                                                                ARGS,
                                                                "[approved action 'send_email' completed: queued]",
                                                                ToolLedger.Status.OK,
                                                                other + ", decided by admin")),
                                                0))));

        NativeApprovalReplay.Replay replay =
                NativeApprovalReplay.of(
                        turns,
                        continuation(Outcome.COMPLETED, ARGS, "queued email"),
                        NATIVE_MESSAGE,
                        ledger);

        assertThat(replay.messages()).noneMatch(m -> m instanceof ToolResponseMessage);
    }

    private List<ChatTurn> history(ToolLedger.Status status, String note) {
        ChatTurn user = new ChatTurn(MessageRole.USER, GOAL, Instant.now(), null);
        ChatTurn parked =
                folded(
                        new ChatTurn(
                                MessageRole.ASSISTANT,
                                "Waiting for your approval.",
                                Instant.now(),
                                new ToolLedger(
                                        List.of(
                                                new ToolLedger.Entry(
                                                        "send_email",
                                                        ARGS,
                                                        null,
                                                        ToolLedger.Status.AWAITING_APPROVAL,
                                                        REQUEST.toString())),
                                        0)));
        ChatTurn outcome =
                folded(
                        ChatTurn.toolRecord(
                                new ToolLedger(
                                        List.of(
                                                new ToolLedger.Entry(
                                                        "send_email",
                                                        ARGS,
                                                        note,
                                                        status,
                                                        REQUEST + ", decided by admin")),
                                        0)));
        return List.of(user, parked, outcome);
    }

    private ChatTurn folded(ChatTurn turn) {
        return ToolLedgerReplay.modelView(turn, ledger, true).orElseThrow();
    }

    private static ApprovalContinuation continuation(
            Outcome outcome, String arguments, String result) {
        return new ApprovalContinuation(
                REQUEST,
                outcome,
                "send_email",
                arguments,
                result,
                "admin",
                GOAL,
                UUID.randomUUID(),
                "sess-1",
                "user-1",
                "tenant-a",
                "user-1",
                "c1:scoped",
                List.of("send_email"),
                List.of());
    }
}
