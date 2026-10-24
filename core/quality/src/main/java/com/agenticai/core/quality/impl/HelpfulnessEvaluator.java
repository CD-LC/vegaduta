package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether a response actually helps answer the user's question.
 *
 * Uses a real LLM-as-judge call (via {@link JudgeChatClientFactory}, resolved
 * from the tenant's active BYOK provider) to score helpfulness on a scale of
 * 0-1. Looks for: relevance to the query, completeness, clarity,
 * actionability. Reports {@code UNAVAILABLE} - never an invented score -
 * when no judge verdict can be obtained (see {@link AbstractLlmJudgeEvaluator}).
 */
@Component
public class HelpfulnessEvaluator extends AbstractLlmJudgeEvaluator {

    /** Registry key of the judge prompt (default text: {@code prompts/en/eval.judge.helpfulness.md}). */
    static final String PROMPT_KEY = "eval.judge.helpfulness";

    public HelpfulnessEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.HELPFULNESS;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return prompts.text(
            PROMPT_KEY,
            context.agentId(),
            Map.of(
                "question", v(context.userMessage()),
                "response", v(context.agentResponse()),
                "system_prompt", v(context.systemPrompt())
            )
        );
    }
}
