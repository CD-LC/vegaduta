package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import com.agenticai.core.sdlc.harness.HarnessBudget;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The per-task cost guard: model calls ("steps"), estimated tokens and wall-clock time, each with a
 * hard server-side cap (see {@link CodingTaskSettings}). Every model call goes through {@link
 * #beforeModelCall} / {@link #afterModelCall}, so the figures cover the fixer's file reads and the
 * reviewer too, not only the loop's own per-fix bookkeeping ({@link #record} therefore adds nothing:
 * counting it again would double the same text).
 *
 * <p>Tokens are an estimate (characters / 4), a runaway guard rather than billing - the same
 * limitation as {@code RunBudget}; the platform's per-user budget still applies inside each call.
 */
final class CodingTaskBudget implements HarnessBudget {

    /** Thrown before a model call that would start past a cap. */
    static final class ExhaustedException extends RuntimeException {
        ExhaustedException(String message) {
            super(message);
        }
    }

    private final Budget limits;
    private final LongSupplier clock;
    private final long startedAtMs;
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    CodingTaskBudget(Budget limits, LongSupplier clock) {
        this.limits = limits;
        this.clock = clock;
        this.startedAtMs = clock.getAsLong();
    }

    @Override
    public boolean exhausted() {
        return steps.get() >= limits.maxSteps()
            || tokens.get() >= limits.maxTokens()
            || elapsedMs() >= limits.maxWallSeconds() * 1000L;
    }

    /** Intentionally empty - see the class comment. */
    @Override
    public void record(String input, String output) {
    }

    @Override
    public String stopMessage() {
        if (steps.get() >= limits.maxSteps()) {
            return "step budget used up (" + limits.maxSteps() + " model calls)";
        }
        if (tokens.get() >= limits.maxTokens()) {
            return "token budget used up (about " + limits.maxTokens() + " tokens)";
        }
        return "time budget used up (" + limits.maxWallSeconds() + " seconds)";
    }

    /** @throws ExhaustedException when no further model call may start */
    void beforeModelCall() {
        if (exhausted()) {
            throw new ExhaustedException(stopMessage());
        }
    }

    void afterModelCall(int promptChars, int responseChars) {
        steps.incrementAndGet();
        tokens.addAndGet((Math.max(0, promptChars) + Math.max(0, responseChars) + 3) / 4);
    }

    long elapsedMs() {
        return Math.max(0, clock.getAsLong() - startedAtMs);
    }

    int steps() {
        return steps.get();
    }

    int tokens() {
        return tokens.get();
    }

    /** Seconds left on the wall clock, never below 1 (used to bound a single command). */
    int remainingWallSeconds() {
        return (int) Math.max(1, limits.maxWallSeconds() - elapsedMs() / 1000);
    }
}
