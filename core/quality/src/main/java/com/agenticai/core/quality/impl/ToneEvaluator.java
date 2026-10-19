package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether the response tone matches the agent's configured persona.
 *
 * Premium feature only. Uses a real LLM-as-judge call (via
 * {@link JudgeChatClientFactory}, resolved from the tenant's active BYOK
 * provider). Reports {@code UNAVAILABLE} - never an invented score - when no
 * judge verdict can be obtained (see {@link AbstractLlmJudgeEvaluator}).
 */
@Component
public class ToneEvaluator extends AbstractLlmJudgeEvaluator {

    public ToneEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.TONE;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return String.format(
            """
            You are evaluating whether an agent's response tone is appropriate for its configured persona.

            AGENT SYSTEM PROMPT (defines the persona):
            %s

            AGENT RESPONSE:
            %s

            Analyze the tone and style of the response. Does it match what the system prompt defines?

            Consider:
            - Is the tone consistent with the persona described?
            - Is the language/vocabulary level appropriate?
            - Is the formality/informality level correct?
            - Does it avoid contradicting the persona?

            Score on a scale of 0.0 to 1.0, where:
            - 1.0: Tone perfectly matches the persona
            - 0.75: Tone is mostly consistent with minor deviations
            - 0.5: Tone is partially consistent with some mismatches
            - 0.25: Tone frequently conflicts with the persona
            - 0.0: Tone completely contradicts the persona

            Respond in this exact format:
            SCORE: <number from 0.0 to 1.0>
            FEEDBACK: <brief explanation of tone consistency>
            """,
            context.systemPrompt(),
            context.agentResponse()
        );
    }
}
