package com.agenticai.core.quality.impl;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationType;
import com.agenticai.core.quality.Evaluator;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether the response length is appropriate for the question.
 *
 * Premium feature only. Uses heuristic rules based on response length,
 * question complexity, and token usage.
 *
 * Does not require LLM-as-judge; runs locally.
 */
@Component
public class LengthEvaluator implements Evaluator {

    @Override
    public EvaluationType getType() {
        return EvaluationType.LENGTH;
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        String response = context.agentResponse();
        String question = context.userMessage();

        // Word counts
        int responseWords = response.split("\\s+").length;
        int questionWords = question.split("\\s+").length;

        // Character count
        int responseChars = response.length();

        // Heuristics
        double score = 0.5;
        String feedback = "";

        if (questionWords < 10) {
            // Short question - expect short answer
            if (responseWords < 10) {
                score = 0.25;
                feedback = "Response too brief for the question asked";
            } else if (responseWords < 50) {
                score = 0.9;
                feedback = "Appropriate length for a simple question";
            } else if (responseWords < 200) {
                score = 0.7;
                feedback = "Response is somewhat verbose for a simple question";
            } else {
                score = 0.3;
                feedback = "Response is too long for such a simple question";
            }
        } else if (questionWords < 30) {
            // Medium question
            if (responseWords < 20) {
                score = 0.4;
                feedback = "Response is too brief";
            } else if (responseWords < 300) {
                score = 0.9;
                feedback = "Good response length";
            } else if (responseWords < 800) {
                score = 0.6;
                feedback = "Response is somewhat long but acceptable";
            } else {
                score = 0.25;
                feedback = "Response is excessively long";
            }
        } else {
            // Complex question - allow longer response
            if (responseWords < 50) {
                score = 0.3;
                feedback = "Response is too brief for a complex question";
            } else if (responseWords < 500) {
                score = 0.85;
                feedback = "Appropriate length for complex question";
            } else if (responseWords < 1500) {
                score = 0.7;
                feedback = "Good coverage but could be more concise";
            } else {
                score = 0.4;
                feedback = "Response is too long and needs trimming";
            }
        }

        return EvaluationResult.of(getType(), score, feedback);
    }
}
