package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.Map;
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

    /** Registry key of the judge prompt (default text: {@code prompts/en/eval.judge.tone.md}). */
    static final String PROMPT_KEY = "eval.judge.tone";

    public ToneEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.TONE;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return prompts.text(
            PROMPT_KEY,
            context.agentId(),
            Map.of(
                "system_prompt", v(context.systemPrompt()),
                "response", v(context.agentResponse())
            )
        );
    }
}
