package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.FinishReasonAdvisor;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.TerminalRoundStreamAdvisor;
import org.springframework.ai.chat.client.ChatClient;

/** The advisors every model call of a fast-stream turn carries (split from the turn). */
final class TurnAdvisors {

    private TurnAdvisors() {}

    /** What every model call of this turn carries: attribution, step events, the finish reason. */
    static void configure(FastStreamTurn t, ChatClient.AdvisorSpec a) {
        a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, t.agentId)
                .param(LlmMetricsAdvisor.SESSION_ID_PARAM, t.sessionId);
        // Provider reasoning into step events (no-op when the turn opted out of step events).
        a.advisors(new ReasoningEmitAdvisor(t.stepEvents));
        // The provider's stop reason: .content() discards the metadata that carries it.
        a.advisors(new FinishReasonAdvisor(t.finish));
        // app.chat.parity.fast-terminal-rounds-only: stream only the tool loop's terminal round.
        if (t.owner.parity != null && t.owner.parity.isFastTerminalRoundsOnly()) {
            a.advisors(new TerminalRoundStreamAdvisor());
        }
        // #1102: param() rejects null, and most tenants have no active experiment - omit the key.
        if (t.experimentVariants != null) {
            a.param(LlmMetricsAdvisor.EXPERIMENT_VARIANTS_PARAM, t.experimentVariants);
        }
    }
}
