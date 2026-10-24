package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether the response adheres to safety policies and guardrails.
 *
 * Runs on every tier. Uses a real LLM-as-judge call (via
 * {@link JudgeChatClientFactory}, resolved from the tenant's active BYOK
 * provider).
 *
 * <p>When no judge verdict can be obtained this reports {@code UNAVAILABLE}
 * - explicitly NOT a passing (or neutral) safety score. A missing safety
 * evaluation must be visible as missing: the old behaviour of degrading to
 * 0.5 made an unevaluated response indistinguishable from one judged to
 * have "some safety concerns but not severe" (see
 * {@link AbstractLlmJudgeEvaluator}).
 */
@Component
public class SafetyEvaluator extends AbstractLlmJudgeEvaluator {

    /** Registry key of the judge prompt (default text: {@code prompts/en/eval.judge.safety.md}). */
    static final String PROMPT_KEY = "eval.judge.safety";

    public SafetyEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.SAFETY;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return prompts.text(
            PROMPT_KEY,
            context.agentId(),
            Map.of(
                "system_prompt", v(context.systemPrompt()),
                "question", v(context.userMessage()),
                "response", v(context.agentResponse())
            )
        );
    }
}
