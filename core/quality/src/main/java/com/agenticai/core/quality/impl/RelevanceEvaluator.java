package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
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

    public RelevanceEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.RELEVANCE;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        return String.format(
            """
            You are evaluating whether an agent's response is relevant to and stays on topic with the user's question.

            USER QUESTION:
            %s

            AGENT RESPONSE:
            %s

            Assess the relevance of the response:

            Consider:
            - Does the response directly address what was asked?
            - Does it stay on topic or drift to unrelated subjects?
            - Are there unnecessary tangents or off-topic information?
            - Does it include information the user didn't ask for that distracts from the main answer?

            Score on a scale of 0.0 to 1.0, where:
            - 1.0: Highly focused on the question, no drift
            - 0.75: Mostly relevant with minor tangents
            - 0.5: Some drift but core question is addressed
            - 0.25: Significant tangents, question partially addressed
            - 0.0: Not relevant, answers a different question

            Respond in this exact format:
            SCORE: <number from 0.0 to 1.0>
            FEEDBACK: <brief explanation of relevance issues if any>
            """,
            context.userMessage(),
            context.agentResponse()
        );
    }
}
