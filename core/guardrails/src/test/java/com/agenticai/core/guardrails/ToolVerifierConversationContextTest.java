package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The RIGOROUS verifier judged only the current user message, so "yes, send it" was judged against "yes".
 * These drive the real {@link ChatClientToolCallVerifierService} and the real {@link GuardedToolCallback} and
 * read the text the judge is actually sent.
 */
class ToolVerifierConversationContextTest {

    private static final String TAIL = "User: draft a refund email to Acme\nAssistant: Here is the draft. Shall I send it?";

    @AfterEach
    void resetFailMode() {
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
    }

    /** A chat client that answers {@code answer} and keeps the user text it was sent. */
    private static final class Judge {
        final ChatClient client = mock(ChatClient.class);
        private final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        private final ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);

        Judge(String answer) {
            ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
            when(client.prompt()).thenReturn(spec);
            when(spec.system(anyString())).thenReturn(spec);
            when(spec.user(anyString())).thenReturn(spec);
            when(spec.call()).thenReturn(call);
            when(call.content()).thenReturn(answer);
        }

        String sent() {
            verify(spec).user(user.capture());
            return user.getValue();
        }
    }

    @Test
    void theJudgeIsShownTheConversationTailBeforeTheCurrentMessage() {
        Judge judge = new Judge("APPROVED");
        AtomicReference<int[]> asked = new AtomicReference<>();

        new ChatClientToolCallVerifierService().verify(
            judge.client, "yes, send it",
            (messages, chars) -> {
                asked.set(new int[] {messages, chars});
                return TAIL;
            },
            "send_email", "{\"to\":\"acme\"}");

        String sent = judge.sent();
        assertThat(sent).contains(TAIL).contains("User's message: yes, send it");
        assertThat(sent.indexOf(TAIL)).isLessThan(sent.indexOf("User's message:"));
        assertThat(sent).contains(GuardrailPrompts.text(null, GuardrailPrompts.JUDGE_TOOL_VERIFIER_CONTEXT, null));
        assertThat(asked.get()[0]).isEqualTo(GuardrailTuningProperties.ToolVerifier.DEFAULT_CONTEXT_MESSAGES);
        assertThat(asked.get()[1]).isEqualTo(GuardrailTuningProperties.Judge.DEFAULT_MAX_INPUT_CHARS);
    }

    @Test
    void theSizeComesFromTheSettingAndZeroRestoresTheMessageAlone() {
        GuardrailTuningProperties tuning = new GuardrailTuningProperties();
        tuning.getToolVerifier().setContextMessages(3);
        AtomicInteger asked = new AtomicInteger();
        new ChatClientToolCallVerifierService(tuning).verify(
            new Judge("APPROVED").client, "yes", (messages, chars) -> {
                asked.set(messages);
                return TAIL;
            }, "t", "{}");
        assertThat(asked.get()).isEqualTo(3);

        tuning.getToolVerifier().setContextMessages(0);
        Judge off = new Judge("APPROVED");
        new ChatClientToolCallVerifierService(tuning).verify(off.client, "yes", (m, c) -> TAIL, "t", "{}");
        assertThat(off.sent()).doesNotContain(TAIL).startsWith("User's message: yes");
    }

    @Test
    void anUnreadableOrEmptyTailLeavesTheCallJudgedOnTheMessageAlone() {
        Judge broken = new Judge("APPROVED");
        VerificationResult result = new ChatClientToolCallVerifierService().verify(
            broken.client, "yes", (m, c) -> {
                throw new IllegalStateException("redis down");
            }, "t", "{}");
        assertThat(result.approved()).isTrue();
        assertThat(broken.sent()).startsWith("User's message: yes");

        Judge empty = new Judge("APPROVED");
        new ChatClientToolCallVerifierService().verify(empty.client, "yes", (m, c) -> " ", "t", "{}");
        assertThat(empty.sent()).startsWith("User's message: yes");
    }

    @Test
    void theContextLineIsAnOverridableRegistryText() {
        assertThat(GuardrailPrompts.text(null, GuardrailPrompts.JUDGE_TOOL_VERIFIER_CONTEXT, UUID.randomUUID()))
            .contains("Recent conversation").contains("yes, send it");
    }

    // ------------------------------------------------------------ the guard hands the tail to the verifier

    private static GuardedToolCallback guarded(ToolCallVerifierService verifier, TurnApprovalState state, ConversationTailSource source) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("send_email");
        when(tool.getToolDefinition()).thenReturn(def);
        when(tool.call(anyString())).thenReturn("sent");
        GuardedToolCallback cb = new GuardedToolCallback(
            tool, new AtomicInteger(), 10, mock(GuardrailService.class), null, null, UUID.randomUUID(), "s1",
            verifier, true, mock(ChatClient.class), "yes, send it", ApprovalGateMode.NEVER_GATE,
            null, null, null);
        cb.withTurnApproval(state);
        if (source != null) {
            cb.withConversationTail(source);
        }
        return cb;
    }

    @Test
    void theGuardAsksTheSourceForTheTurnsScopedConversationAndPassesItToTheVerifier() {
        AtomicReference<String> readMemoryId = new AtomicReference<>();
        AtomicReference<String> seenTail = new AtomicReference<>();
        ToolCallVerifierService verifier = new ToolCallVerifierService() {
            @Override
            public VerificationResult verify(ChatClient c, String m, String tool, String in) {
                seenTail.set("none");
                return VerificationResult.approved("ok");
            }

            @Override
            public VerificationResult verify(ChatClient c, String m, ConversationTail tail, String tool, String in) {
                seenTail.set(tail.get(6, 4000));
                return VerificationResult.approved("ok");
            }
        };
        ConversationTailSource source = (memoryId, messages, chars) -> {
            readMemoryId.set(memoryId);
            return TAIL;
        };

        guarded(verifier, new TurnApprovalState("c1:scoped-memory-id"), source).call("{}");

        assertThat(readMemoryId.get()).isEqualTo("c1:scoped-memory-id");
        assertThat(seenTail.get()).isEqualTo(TAIL);
    }

    @Test
    void aTurnWithoutAScopedMemoryIdOrWithoutASourceIsJudgedOnTheMessageAlone() {
        AtomicReference<String> seen = new AtomicReference<>();
        ToolCallVerifierService verifier = new ToolCallVerifierService() {
            @Override
            public VerificationResult verify(ChatClient c, String m, String tool, String in) {
                seen.set("message-only");
                return VerificationResult.approved("ok");
            }

            @Override
            public VerificationResult verify(ChatClient c, String m, ConversationTail tail, String tool, String in) {
                seen.set("with-tail");
                return VerificationResult.approved("ok");
            }
        };

        guarded(verifier, new TurnApprovalState(null), (id, m, c) -> TAIL).call("{}");
        assertThat(seen.get()).isEqualTo("message-only");

        seen.set(null);
        guarded(verifier, new TurnApprovalState("c1:x"), null).call("{}");
        assertThat(seen.get()).isEqualTo("message-only");
    }
}
