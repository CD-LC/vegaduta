package com.agenticai.core.tenancy.demo;

/**
 * Answers "can the platform image pool actually serve a generation right
 * now?" for the tenancy module, which cannot see the pool itself (module
 * graph: imagegen -> tenancy, never the reverse — see {@link
 * DemoImageService}'s class javadoc).
 *
 * <p>The implementation lives in {@code core/imagegen}, the one module that
 * can see both sides, and delegates to {@code
 * GeminiImageGenerationClient#isAvailable} - the same cheap, non-throwing
 * pre-check that module's orchestrator already uses.
 *
 * <p>When no implementation bean exists (a deployment without the imagegen
 * module), callers treat availability as {@code true} - an absent SPI must
 * degrade to today's behaviour, not silently switch the feature off on a
 * deployment that never had the check.
 */
public interface DemoImageAvailability {

    /** True when a usable provider row exists behind the demo image endpoint. */
    boolean available();
}
