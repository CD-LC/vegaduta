package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationType;
import java.util.Map;
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

    /** Registry keys of the judge prompt and its two optional blocks (default text: {@code prompts/en/eval.judge.accuracy*.md}). */
    static final String PROMPT_KEY = "eval.judge.accuracy";

    static final String REFERENCE_KEY = "eval.judge.accuracy.reference";
    static final String REFERENCE_CHECK_KEY = "eval.judge.accuracy.reference_check";

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
        // so the prompt is byte-identical to before on every pre-existing path.
        String referenceBlock = context.hasExpectedOutput()
            ? prompts.text(REFERENCE_KEY, context.agentId(), Map.of("reference", v(context.expectedOutput())))
            : "";
        String referenceCheck = context.hasExpectedOutput() ? prompts.text(REFERENCE_CHECK_KEY, context.agentId()) : "";

        return prompts.text(
            PROMPT_KEY,
            context.agentId(),
            Map.of(
                "question", v(context.userMessage()),
                "response", v(context.agentResponse()),
                "reference_block", referenceBlock,
                "reference_check", referenceCheck
            )
        );
    }
}
