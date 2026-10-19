package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
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

    public HelpfulnessEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.HELPFULNESS;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return String.format(
            """
            You are an AI evaluator assessing the helpfulness of an agent's response.

            USER QUESTION:
            %s

            AGENT RESPONSE:
            %s

            AGENT SYSTEM PROMPT (for context on the agent's role):
            %s

            Score the response's helpfulness on a scale of 0.0 to 1.0, where:
            - 1.0: Directly answers the question, complete and actionable
            - 0.75: Mostly helpful, minor gaps or unclear parts
            - 0.5: Partially helpful but has significant gaps or issues
            - 0.25: Minimally helpful, mostly irrelevant or confusing
            - 0.0: Not helpful at all, does not address the question

            Respond in this exact format:
            SCORE: <number from 0.0 to 1.0>
            FEEDBACK: <brief explanation of the score>
            """,
            context.userMessage(),
            context.agentResponse(),
            context.systemPrompt()
        );
    }
}
