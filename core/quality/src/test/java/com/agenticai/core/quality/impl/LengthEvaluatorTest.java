package com.agenticai.core.quality.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LengthEvaluatorTest {

    private LengthEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new LengthEvaluator();
    }

    @Test
    void testShortQuestionShortAnswer() {
        EvaluationContext context = new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "You are a helpful assistant",
            "What is 2+2?",
            "The answer is 4.",
            List.of(),
            0,
            0,
            "claude-3-sonnet"
        );

        EvaluationResult result = evaluator.evaluate(context);

        assertEquals(EvaluationType.LENGTH, result.type());
        assertTrue(result.score() >= 0.0 && result.score() <= 1.0);
    }

    @Test
    void testComplexQuestionShortAnswer() {
        EvaluationContext context = new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "You are a helpful assistant",
            "Please explain in detail how machine learning models are trained, including the concepts of gradient descent, " +
                "backpropagation, and different optimization algorithms, and how these relate to real-world applications.",
            "ML models are trained.",
            List.of(),
            0,
            0,
            "claude-3-sonnet"
        );

        EvaluationResult result = evaluator.evaluate(context);

        assertEquals(EvaluationType.LENGTH, result.type());
        // Short answer to complex question should score lower
        assertTrue(result.score() < 0.5);
    }

    @Test
    void testShortQuestionLongAnswer() {
        EvaluationContext context = new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "You are a helpful assistant",
            "What is water?",
            "Water is a chemical substance with the molecular formula H2O. " +
                "It is a vital element for all known forms of life and is one of the most important compounds on Earth. " +
                "Water covers about 71% of Earth's surface. The molecular structure consists of two hydrogen atoms bonded " +
                "to one oxygen atom through covalent bonds. Water exists in three states: solid (ice), liquid (water), and " +
                "gas (water vapor). It has unique properties including high specific heat capacity, high heat of vaporization, " +
                "high surface tension, and the ability to dissolve many substances, earning it the title of 'universal solvent'. " +
                "These properties make water essential for life and countless industrial and biological processes.",
            List.of(),
            0,
            0,
            "claude-3-sonnet"
        );

        EvaluationResult result = evaluator.evaluate(context);

        assertEquals(EvaluationType.LENGTH, result.type());
        // Long answer to simple question should score lower than perfect
        assertTrue(result.score() < 0.9);
    }

    @Test
    void testScoreIsAlwaysNormalized() {
        EvaluationContext context = new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "System prompt",
            "Question?",
            "Answer.",
            List.of(),
            0,
            0,
            "claude-3-sonnet"
        );

        EvaluationResult result = evaluator.evaluate(context);

        assertTrue(result.score() >= 0.0, "Score should not be below 0");
        assertTrue(result.score() <= 1.0, "Score should not be above 1");
    }
}
