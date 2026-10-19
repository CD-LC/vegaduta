package com.agenticai.core.guardrails;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TenantGuardrailRuleEvaluator} - the read side that was
 * missing entirely, which is why a tenant-authored {@link GuardrailRule} used
 * to be stored and then never consulted by anything.
 */
class TenantGuardrailRuleEvaluatorTest {

    private GuardrailRuleRepository repository;
    private TenantGuardrailRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        repository = mock(GuardrailRuleRepository.class);
        // TTL 0 - every call re-reads, so a test can change the rule list
        // between calls without fighting the cache. The cache itself has its
        // own test below.
        evaluator = new TenantGuardrailRuleEvaluator(repository, 0L, true);
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static GuardrailRule rule(String name, String pattern, GuardrailRuleAction action, boolean enabled) {
        return new GuardrailRule("acme", GuardrailRuleType.CUSTOM, name, null, pattern, action, enabled);
    }

    @Test
    @DisplayName("a BLOCK rule blocks matching content and names itself")
    void blockRuleBlocks() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "project\\s+bluejay", GuardrailRuleAction.BLOCK, true)));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("ship Project Bluejay next week", true);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.blockedByRuleName()).isEqualTo("No codenames");
    }

    @Test
    @DisplayName("patterns match case-insensitively, like the platform's own compiled-in lists")
    void matchingIsCaseInsensitive() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, true)));

        assertThat(evaluator.apply("BLUEJAY", true).blocked()).isTrue();
    }

    @Test
    @DisplayName("a REDACT rule rewrites the match and leaves the rest of the text alone")
    void redactRuleRewrites() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Internal ids", "EMP-\\d{4}", GuardrailRuleAction.REDACT, true)));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("owner EMP-1234 and EMP-5678 approved", true);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.redacted()).isTrue();
        assertThat(outcome.text()).isEqualTo("owner [REDACTED] and [REDACTED] approved");
    }

    @Test
    @DisplayName("REDACT rules are skipped entirely when the caller cannot use a rewrite")
    void redactionSkippedWhenNotAllowed() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Internal ids", "EMP-\\d{4}", GuardrailRuleAction.REDACT, true)));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("owner EMP-1234", false);

        assertThat(outcome.redacted()).isFalse();
        assertThat(outcome.text()).isEqualTo("owner EMP-1234");
    }

    @Test
    @DisplayName("BLOCK still applies when redaction is not allowed")
    void blockAppliesEvenWhenRedactionDisallowed() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, true)));

        assertThat(evaluator.apply("bluejay", false).blocked()).isTrue();
    }

    @Test
    @DisplayName("a disabled rule does nothing")
    void disabledRuleIsIgnored() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, false)));

        assertThat(evaluator.apply("bluejay", true).blocked()).isFalse();
    }

    @Test
    @DisplayName("a rule whose pattern does not compile is skipped, not fatal for the others")
    void uncompilablePatternIsSkipped() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
            rule("Broken", "([unclosed", GuardrailRuleAction.BLOCK, true),
            rule("Good", "bluejay", GuardrailRuleAction.BLOCK, true)
        ));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("bluejay", true);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.blockedByRuleName()).isEqualTo("Good");
    }

    @Test
    @DisplayName("a repository failure leaves the content untouched (fail-open)")
    void repositoryFailureFailsOpen() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenThrow(new IllegalStateException("db down"));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("anything at all", true);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.text()).isEqualTo("anything at all");
    }

    @Test
    @DisplayName("no tenant context means no rules and no query")
    void noTenantContextSkipsEverything() {
        TenantContext.clear();

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("bluejay", true);

        assertThat(outcome.blocked()).isFalse();
        verify(repository, never()).findAllByOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("a catastrophically backtracking pattern is abandoned rather than hanging the turn")
    void runawayPatternIsAbandoned() {
        // The textbook ReDoS case. Without the matcher's read budget this call
        // does not return in any practical amount of time; the rule must be
        // skipped and the content allowed through instead.
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Evil", "(a+)+$", GuardrailRuleAction.BLOCK, true)));
        String hostile = "a".repeat(60) + "!";

        long startedAt = System.currentTimeMillis();
        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply(hostile, true);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(outcome.blocked()).isFalse();
        assertThat(elapsed).isLessThan(5_000L);
    }

    @Test
    @DisplayName("rules are cached per tenant for the configured TTL")
    void rulesAreCached() {
        TenantGuardrailRuleEvaluator cached = new TenantGuardrailRuleEvaluator(repository, 60L, true);
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, true)));

        assertThat(cached.apply("bluejay", true).blocked()).isTrue();
        assertThat(cached.apply("bluejay", true).blocked()).isTrue();

        verify(repository, times(1)).findAllByOrderByCreatedAtAsc();

        // ...and a write invalidates it, so "I just turned that rule off"
        // takes effect immediately rather than up to a TTL later.
        cached.invalidateCurrentTenant();
        assertThat(cached.apply("bluejay", true).blocked()).isTrue();
        verify(repository, times(2)).findAllByOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("one tenant's cached rules are never served to another tenant")
    void cacheIsPerTenant() {
        TenantGuardrailRuleEvaluator cached = new TenantGuardrailRuleEvaluator(repository, 60L, true);
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, true)));
        assertThat(cached.apply("bluejay", true).blocked()).isTrue();

        // A different tenant on the same JVM: the repository is re-queried (and
        // in production is routed to that tenant's own schema), never answered
        // from the first tenant's cached list.
        TenantContext.set(TenantContext.of("globex", "user-2", Set.of()));
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());

        assertThat(cached.apply("bluejay", true).blocked()).isFalse();
        verify(repository, times(2)).findAllByOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("the off switch skips every tenant rule and never queries at all")
    void offSwitchSkipsEverything() {
        // app.guardrails.tenant-rules.enabled=false is the emergency lever for a
        // capability that had never executed in production before it shipped -
        // see the constructor's javadoc. Pinned so it cannot be quietly removed
        // as dead code: with a rule that WOULD block, nothing is blocked, and
        // the repository is never even consulted.
        TenantGuardrailRuleEvaluator disabled = new TenantGuardrailRuleEvaluator(repository, 0L, false);
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("No codenames", "bluejay", GuardrailRuleAction.BLOCK, true)));

        TenantGuardrailRuleEvaluator.Outcome outcome = disabled.apply("bluejay", true);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.redacted()).isFalse();
        assertThat(outcome.text()).isEqualTo("bluejay");
        verify(repository, never()).findAllByOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("a REDACT rule is equally inert when the switch is off")
    void offSwitchSkipsRedaction() {
        TenantGuardrailRuleEvaluator disabled = new TenantGuardrailRuleEvaluator(repository, 0L, false);
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Internal ids", "EMP-\\d{4}", GuardrailRuleAction.REDACT, true)));

        assertThat(disabled.apply("owner EMP-1234", true).text()).isEqualTo("owner EMP-1234");
    }

    @Test
    @DisplayName("null and empty content short-circuit without touching the database")
    void emptyContentShortCircuits() {
        assertThat(evaluator.apply(null, true).text()).isNull();
        assertThat(evaluator.apply("", true).text()).isEmpty();
        verify(repository, never()).findAllByOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("the outcome names the tenant's own rule, which is what both call sites surface")
    void outcomeCarriesRuleName() {
        // Guards the contract CustomRuleContentGuardrail and GuardedToolCallback
        // both depend on: the user-facing message names the rule, so "which of
        // my rules stopped this" is answerable without log access.
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Card numbers", "4[0-9]{12}", GuardrailRuleAction.BLOCK, true)));

        assertThat(evaluator.apply("4111111111111", true).blockedByRuleName()).isEqualTo("Card numbers");
    }

    // --- SEC-028: ReDoS ------------------------------------------------------

    /** assertTimeoutPreemptively runs on another thread; TenantContext is thread-local, so re-establish it there. */
    private static <T> T inTenant(java.util.function.Supplier<T> body) {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        try {
            return body.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("SEC-028: (a+)+$ runs on the linear-time engine - finishes fast with the correct (no-match) answer")
    void catastrophicPatternRunsInLinearTime() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Evil", "(a+)+$", GuardrailRuleAction.BLOCK, true)));
        String hostile = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

        TenantGuardrailRuleEvaluator.Outcome outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> inTenant(() -> evaluator.apply(hostile, true)));

        // RE2/J evaluates it exactly: the text does not end in a run of a's, so no match, no block.
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.text()).isEqualTo(hostile);

        // And a long hostile input is still linear.
        String longHostile = "a".repeat(50_000) + "!";
        TenantGuardrailRuleEvaluator.Outcome longOutcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> inTenant(() -> evaluator.apply(longHostile, true)));
        assertThat(longOutcome.blocked()).isFalse();
    }

    @Test
    @DisplayName("SEC-028: a legacy backtracking-only BLOCK rule that exhausts its budget fails CLOSED")
    void legacyRunawayBlockRuleFailsClosed() {
        // The backreference makes RE2/J refuse this, so it runs on the budgeted
        // java.util.regex fallback, where (a|a)+ backtracks exponentially
        // (the backreference also defeats java.util.regex's loop memoisation).
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Legacy evil", "(x?)(?:a|a)+\\1$", GuardrailRuleAction.BLOCK, true)));
        String hostile = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

        TenantGuardrailRuleEvaluator.Outcome outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> inTenant(() -> evaluator.apply(hostile, true)));

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.blockedByRuleName()).isEqualTo("Legacy evil");
    }

    @Test
    @DisplayName("SEC-028: a legacy runaway REDACT rule is skipped, text left untouched")
    void legacyRunawayRedactRuleIsSkipped() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Legacy evil", "(x?)(?:a|a)+\\1$", GuardrailRuleAction.REDACT, true)));
        String hostile = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

        TenantGuardrailRuleEvaluator.Outcome outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> inTenant(() -> evaluator.apply(hostile, true)));

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.redacted()).isFalse();
        assertThat(outcome.text()).isEqualTo(hostile);
    }

    @Test
    @DisplayName("SEC-028: the step budget also applies on the RE2/J path (BLOCK fails closed)")
    void stepBudgetAppliesToLinearEngineToo() {
        TenantGuardrailRuleEvaluator tight = new TenantGuardrailRuleEvaluator(repository, 0L, true, 1_000L, 10_000L);
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Needle", "needle", GuardrailRuleAction.BLOCK, true)));

        assertThat(tight.apply("x".repeat(5_000), true).blocked()).isTrue();
        assertThat(tight.apply("short needle", true).blocked()).isTrue();
        assertThat(tight.apply("short hay", true).blocked()).isFalse();
    }

    @Test
    @DisplayName("SEC-028: a stored rule whose repetition would blow up RE2/J compilation is skipped, not compiled")
    void oversizedStoredPatternIsSkipped() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
            rule("Bomb", "((a{1000}){1000}){1000}", GuardrailRuleAction.BLOCK, true),
            rule("Good", "bluejay", GuardrailRuleAction.BLOCK, true)
        ));

        TenantGuardrailRuleEvaluator.Outcome outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> inTenant(() -> evaluator.apply("bluejay aaaa", true)));

        assertThat(outcome.blockedByRuleName()).isEqualTo("Good");
    }

    @Test
    @DisplayName("SEC-028: RE2/J redaction still rewrites matches")
    void re2jRedactionWorks() {
        when(repository.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(rule("Keys", "AKIA[0-9A-Z]{16}", GuardrailRuleAction.REDACT, true)));

        TenantGuardrailRuleEvaluator.Outcome outcome = evaluator.apply("key akiaABCDEFGHIJKLMNOP end", true);

        assertThat(outcome.redacted()).isTrue();
        assertThat(outcome.text()).isEqualTo("key [REDACTED] end");
    }
}
