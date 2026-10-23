package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether the response stays on topic and addresses the user's query.
 *
 * Premium feature only. Uses a real LLM-as-judge call (via
 * {@link JudgeChatClientFactory}, resolved from the tenant's active BYOK
 * provider) to detect topic drift or responses that don't directly address
 * what was asked. Reports {@code UNAVAILABLE} - never an invented score -
 * when no judge verdict can be obtained (see {@link AbstractLlmJudgeEvaluator}).
 */
@Component
public class RelevanceEvaluator extends AbstractLlmJudgeEvaluator {

    /** Registry key of the judge prompt (default text: {@code prompts/en/eval.judge.relevance.md}). */
    static final String PROMPT_KEY = "eval.judge.relevance";

    public RelevanceEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.RELEVANCE;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return prompts.text(
            PROMPT_KEY,
            context.agentId(),
            Map.of(
                "question", v(context.userMessage()),
                "response", v(context.agentResponse())
            )
        );
    }
}
