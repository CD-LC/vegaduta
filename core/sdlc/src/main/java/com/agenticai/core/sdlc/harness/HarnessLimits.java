package com.agenticai.core.sdlc.harness;

/** Hard bounds for one harness run. Values are clamped, never trusted. */
public record HarnessLimits(int maxFixIterations, int testTimeoutSeconds, int maxOutputChars) {

    public static final int MAX_ITERATIONS_CEILING = 10;

    public HarnessLimits {
        maxFixIterations = Math.max(0, Math.min(MAX_ITERATIONS_CEILING, maxFixIterations));
        testTimeoutSeconds = Math.max(5, Math.min(1800, testTimeoutSeconds));
        maxOutputChars = Math.max(500, Math.min(50_000, maxOutputChars));
    }

    public static HarnessLimits defaults() {
        return new HarnessLimits(3, 300, 8_000);
    }
}
