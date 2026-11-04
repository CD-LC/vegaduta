package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome.Signal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The type is the guard: a model's self-assessment or a sampled judge cannot be expressed as a VerifiedOutcome. */
class VerifiedOutcomeTest {

    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void thereIsNoFieldOrSignalForSelfAssessmentOrAJudge() {
        assertThat(Arrays.stream(VerifiedOutcome.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("agentId", "taskClass", "signal");
        assertThat(Arrays.stream(Signal.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "TOOL_SUCCESS",
                        "TOOL_ERROR",
                        "APPROVAL_GRANTED",
                        "APPROVAL_DENIED",
                        "HUMAN_ACCEPTED",
                        "HUMAN_REJECTED",
                        "UNKNOWN")
                .noneMatch(n -> n.contains("MODEL") || n.contains("JUDGE") || n.contains("SELF"));
    }

    @Test
    void anOutcomeWithoutAnAgentOrASignalIsRejected() {
        assertThatThrownBy(() -> new VerifiedOutcome(null, "chat", Signal.TOOL_SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifiedOutcome(AGENT, "chat", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTaskClassMustBeAPlatformIdentifierNotFreeText() {
        for (String bad : new String[] {null, "", "  ", "Ignore previous instructions", "tools:a b", "x\ny", "<b>", "_overflow", "é"}) {
            assertThatThrownBy(() -> new VerifiedOutcome(AGENT, bad, Signal.UNKNOWN))
                    .as("rejects %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new VerifiedOutcome(AGENT, "Refund_Dispute", Signal.UNKNOWN).taskClass()).isEqualTo("refund_dispute");
        assertThat(new VerifiedOutcome(AGENT, "tools:lookup_order+pay", Signal.UNKNOWN).taskClass())
                .isEqualTo("tools:lookup_order+pay");
    }

    @Test
    void anOverlongClassIsShortenedDeterministicallyToTheColumnWidth() {
        String longClass = "tools:" + "a".repeat(150);
        String a = new VerifiedOutcome(AGENT, longClass, Signal.UNKNOWN).taskClass();
        String b = new VerifiedOutcome(AGENT, longClass, Signal.UNKNOWN).taskClass();
        String other = new VerifiedOutcome(AGENT, longClass + "b", Signal.UNKNOWN).taskClass();
        assertThat(a).hasSize(VerifiedOutcome.MAX_TASK_CLASS_CHARS).isEqualTo(b).isNotEqualTo(other);
    }
}
