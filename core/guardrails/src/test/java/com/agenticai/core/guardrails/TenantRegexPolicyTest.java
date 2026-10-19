package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** SEC-028: authoring-time policy for tenant guardrail regexes. */
class TenantRegexPolicyTest {

    @Test
    void seededDefaultTemplatesAndOrdinaryPatternsAreAccepted() {
        for (String ok : new String[] {
            "ignore\\s+(all\\s+)?(the\\s+)?(above|previous)\\s+instructions",
            "AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z\\-_]{35}|gh[pousr]_[A-Za-z0-9]{36,}",
            "-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}",
            "project\\s+bluejay", "4[0-9]{12}", "\\bfoo\\b", "[0-9]{1,1000}", "(a+)+$",
        }) {
            assertThatCode(() -> TenantRegexPolicy.validateForAuthoring(ok)).as(ok).doesNotThrowAnyException();
        }
    }

    @Test
    void backtrackingOnlyConstructsAreRejectedWithAClearMessage() {
        for (String bad : new String[] { "(a)\\1", "(?=a)b", "(?<!x)y", "a++", "(?>ab)" }) {
            assertThatThrownBy(() -> TenantRegexPolicy.validateForAuthoring(bad))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RE2");
        }
    }

    @Test
    void nestedCountedRepetitionIsRejectedBeforeRe2jCanExpandItOutOfHeap() {
        // ((a{1000}){1000}){1000} runs RE2/J out of heap at compile time; the
        // estimator must refuse it quickly without ever handing it to RE2/J.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> TenantRegexPolicy.validateForAuthoring("((a{1000}){1000}){1000}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expands too much");
            assertThatThrownBy(() -> TenantRegexPolicy.compileLinearOrNull("((a{1000}){1000}){1000}"))
                .isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(TenantRegexPolicy.estimateProgramSize("(a{100}){100}")).isGreaterThan(TenantRegexPolicy.MAX_PROGRAM_SIZE);
        assertThat(TenantRegexPolicy.estimateProgramSize("[0-9]{1,1000}")).isLessThan(2_000L);
    }

    @Test
    void overlongPatternIsRejected() {
        assertThatThrownBy(() -> TenantRegexPolicy.validateForAuthoring("a".repeat(TenantRegexPolicy.MAX_PATTERN_LENGTH + 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too long");
    }

    @Test
    void estimatorTerminatesOnMalformedInput() {
        for (String weird : new String[] { ")", "((", "[", "\\", "a{", "a{1,", "a{1,2", "[[:alpha:", "\\Qabc", "\\p{L" }) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> TenantRegexPolicy.estimateProgramSize(weird));
        }
    }
}
