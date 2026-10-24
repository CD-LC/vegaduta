package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.ToolLoopTranscript;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tool loop writes text of its own (the "tool budget used up" result, the closing reply, the answer to a tool name
 * that was never offered). It looks that text up in the prompt registry for the agent and tenant of the TURN, which the
 * turn hands to the loop through the transcript every turn path creates ({@code newTranscript}): the stream, the
 * buffered call and the continuation passes all go through it.
 */
class TurnToolLoopPromptScopeTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final TenantContext TENANT = TenantContext.of("t1", "owner", Set.of());

    private final ChatTurnExecutor executor =
            new ChatTurnExecutor(null, null, null, null, null, null, null, null, null, Duration.ofSeconds(30));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTranscriptOfATurnCarriesItsAgentAndItsTenant() {
        TurnContext context = new TurnContext(TENANT, AGENT, "s1", "r1", Instant.now(), null, null);

        ToolLoopTranscript transcript = executor.newTranscript(context);

        assertThat(transcript.promptAgentId()).isEqualTo(AGENT);
        assertThat(transcript.promptTenant().context()).contains(TENANT);
    }

    @Test
    void theTenantIsReappliedOnAThreadThatCarriesNone() {
        TurnContext context = new TurnContext(TENANT, AGENT, "s1", "r1", Instant.now(), null, null);
        ToolLoopTranscript transcript = executor.newTranscript(context);
        TenantContext.clear();

        String seen = transcript.promptTenant().get(() -> TenantContext.current().map(TenantContext::getTenantId).orElse("none"));

        assertThat(seen).isEqualTo("t1");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aTurnWithoutAContextGetsATranscriptWithoutAScope() {
        ToolLoopTranscript transcript = executor.newTranscript(null);

        assertThat(transcript.promptAgentId()).isNull();
        assertThat(transcript.promptTenant()).isNull();
    }
}
