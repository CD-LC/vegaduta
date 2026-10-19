package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BoundedRegexTest {

    @Test
    void ordinaryMatchesWork() {
        assertTrue(BoundedRegex.matchesWhole(Pattern.compile("[a-z]+"), "abc"));
        assertFalse(BoundedRegex.matchesWhole(Pattern.compile("[a-z]+"), "abc1"));
        assertTrue(BoundedRegex.matchesWhole(Pattern.compile(".*"), ""));
    }

    @Test
    void aMatchThatSpendsItsBudgetIsReportedNotCompleted() {
        // Linear pattern on a long input, but with a tiny budget: deterministic proof the counter is in force.
        assertThrows(BoundedRegex.BudgetExceeded.class,
            () -> BoundedRegex.matchesWhole(Pattern.compile("a*"), "a".repeat(1_000), 10));
    }

    @Test
    void theDefaultBudgetAllowsHonestUseOnTheLargestAllowedValue() {
        String value = "a".repeat(ToolArgPolicyEvaluator.MAX_PATTERN_INPUT_CHARS);
        assertTrue(BoundedRegex.matchesWhole(Pattern.compile("a*"), value));
    }
}
