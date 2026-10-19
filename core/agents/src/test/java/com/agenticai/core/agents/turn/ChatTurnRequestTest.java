package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatTurnRequestTest {

    @Test
    void builderDefaultsMatchTheMostCommonLegacyShape() {
        UUID agent = UUID.randomUUID();
        ChatTurnRequest r = ChatTurnRequest.builder(agent, "s", "subject", "hi").build();

        assertThat(r.agentId()).isEqualTo(agent);
        assertThat(r.sessionId()).isEqualTo("s");
        assertThat(r.subjectKey()).isEqualTo("subject");
        assertThat(r.userMessage()).isEqualTo("hi");
        assertThat(r.attachmentKey()).isNull();
        assertThat(r.attachmentMimeType()).isNull();
        assertThat(r.inlineAttachments()).isNull();
        assertThat(r.extraTools()).isEmpty();
        assertThat(r.turnRef()).isNull();
        assertThat(r.visionSessionId()).isNull();
        assertThat(r.forceFastDepth()).isFalse();
        assertThat(r.stepEvents().isEnabled()).isFalse();
        assertThat(r.suppressAgentTools()).isFalse();
    }

    @Test
    void builderCarriesEveryOptionalField() {
        ChatTurnRequest r =
                ChatTurnRequest.builder(UUID.randomUUID(), "s", "subject", "hi")
                        .attachment("k", "image/png")
                        .turnRef("ref")
                        .visionSessionId("v")
                        .forceFastDepth(true)
                        .suppressAgentTools(true)
                        .build();

        assertThat(r.attachmentKey()).isEqualTo("k");
        assertThat(r.attachmentMimeType()).isEqualTo("image/png");
        assertThat(r.turnRef()).isEqualTo("ref");
        assertThat(r.visionSessionId()).isEqualTo("v");
        assertThat(r.forceFastDepth()).isTrue();
        assertThat(r.suppressAgentTools()).isTrue();
    }
}
