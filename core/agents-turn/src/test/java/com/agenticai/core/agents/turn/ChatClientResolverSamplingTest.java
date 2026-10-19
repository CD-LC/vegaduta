package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.chat.SamplingOptions;
import com.agenticai.core.providers.ProviderName;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The resolver half of {@code AgentSamplingParamsTest} (agents.core): sampling provider options. */
class ChatClientResolverSamplingTest {

    private static Agent agent() {
        return new Agent("A", "prompt", ProviderName.ANTHROPIC, "claude-sonnet-5", 1024, 0.7, 1.0, false,
            null, ReasoningDepth.FAST, TuningMode.MANUAL);
    }

    @Test
    void resolverProviderOptionsCarrySamplingOnlyWhenSet() {
        Agent a = agent();
        assertThat(ChatClientResolver.providerOptions(ProviderName.ANTHROPIC, a)).isEmpty();
        a.setSamplingParams(40, List.of("END"), null, null, null);
        Object s = ChatClientResolver.providerOptions(ProviderName.ANTHROPIC, a).get(SamplingOptions.OPTION_KEY);
        assertThat(s).isEqualTo(new SamplingOptions(40, List.of("END"), null, null, null));
    }
}
