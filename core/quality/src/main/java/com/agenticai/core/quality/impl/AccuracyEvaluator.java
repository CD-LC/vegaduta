package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import org.springframework.stereotype.Component;

/**
 * Evaluates the factual accuracy of the response.
 *
 * Uses a real LLM-as-judge call (via {@link JudgeChatClientFactory}, resolved
 * from the tenant's active BYOK provider) to score accuracy. Looks for:
 * factual correctness, proper citations/sources, logical consistency.
 * Reports {@code UNAVAILABLE} - never an invented score - when no judge
 * verdict can be obtained (see {@link AbstractLlmJudgeEvaluator}).
 *
 * Premium feature only.
 */
@Component
public class AccuracyEvaluator extends AbstractLlmJudgeEvaluator {

    public AccuracyEvaluator(JudgeChatClientFactory judgeChatClientFactory) {
        super(judgeChatClientFactory);
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.ACCURACY;
    }

    @Override
    protected String buildPrompt(EvaluationContext context) {
        // Offline dataset replays (eval-datasets workstream) can supply a
        // reference answer for the case being replayed. When present it is
        // handed to the judge as ground truth, turning "is this plausible?"
        // into "does this match what the tenant said is correct?" - the
        // single highest-signal upgrade a golden dataset buys. Online turns
        // never have one (EvaluationContext#expectedOutput is null there),
        // so the prompt below is byte-identical to before on every
        // pre-existing path.
        String referenceBlock = context.hasExpectedOutput()
            ? String.format(
                """

                REFERENCE ANSWER (known-good, authored by the agent's owner - treat as ground truth):
                %s
                """,
                context.expectedOutput())
            : "";

        return String.format(
            """
            You are an expert fact-checker evaluating the accuracy of an agent's response.

            USER QUESTION:
            %s

            AGENT RESPONSE:
            %s
            %s
            Evaluate the factual accuracy of this response. Consider:
            - Are the facts stated correct and verifiable?
            - Are any claims properly qualified or cited?
            - Is the response internally logically consistent?
            - Are there any false statements, misrepresentations, or unsupported claims?%s

            Score the accuracy on a scale of 0.0 to 1.0, where:
            - 1.0: All facts are correct, properly sourced and internally consistent
            - 0.75: Mostly accurate with minor issues or minor unsourced claims
            - 0.5: Significant accuracy issues, mixed correct and incorrect information
            - 0.25: Many inaccuracies, mostly unreliable
            - 0.0: Mostly or entirely false

            Respond in this exact format:
            SCORE: <number from 0.0 to 1.0>
            FEEDBACK: <brief explanation of accuracy issues found, if any>
            """,
            context.userMessage(),
            context.agentResponse(),
            referenceBlock,
            context.hasExpectedOutput()
                ? "\n- Does the response agree with the REFERENCE ANSWER on every material fact?"
                : ""
        );
    }
}
