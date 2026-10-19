package com.agenticai.core.workflow.expression;

import java.util.function.LongSupplier;

/**
 * Safety limits enforced on every evaluation (spec 3.5). {@code nanoTime} is injectable so
 * wall-time behaviour is testable without sleeping.
 */
public record Limits(
        int maxSourceLength,
        int maxNodes,
        int maxSteps,
        long maxWallNanos,
        int maxResultChars,
        int maxRegexLength,
        int maxDepth,
        boolean strict,
        LongSupplier nanoTime) {

    public static Limits defaults() {
        return new Limits(
                2_000, 500, 20_000, 50_000_000L, 256 * 1024, 200, 64, false, System::nanoTime);
    }

    public Limits withMaxSourceLength(int v) {
        return new Limits(
                v,
                maxNodes,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxNodes(int v) {
        return new Limits(
                maxSourceLength,
                v,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxSteps(int v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                v,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxWallNanos(long v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                v,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxResultChars(int v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                maxWallNanos,
                v,
                maxRegexLength,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxRegexLength(int v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                v,
                maxDepth,
                strict,
                nanoTime);
    }

    public Limits withMaxDepth(int v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                v,
                strict,
                nanoTime);
    }

    public Limits withStrict(boolean v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                v,
                nanoTime);
    }

    public Limits withNanoTime(LongSupplier v) {
        return new Limits(
                maxSourceLength,
                maxNodes,
                maxSteps,
                maxWallNanos,
                maxResultChars,
                maxRegexLength,
                maxDepth,
                strict,
                v);
    }
}
