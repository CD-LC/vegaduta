package com.agenticai.core.agents.memory.spi;

/**
 * The turn path's view of unified recall: merge every {@link MemoryRecallSource} under the prompt budget. Lives in the
 * kernel spi so {@code agents-turn} can call it without depending on the store module (which implements it). Returns
 * an empty result with a {@code degradedReason} when the feature is off or a source failed; implementations must
 * never hand back an item that is not TRUSTED.
 */
public interface UnifiedMemoryRecall {

    /** Reason an implementation returns when the unified-memory gate is off for the agent. */
    String DISABLED = "disabled";

    /** The platform's token cap for ALL memory text in one prompt (ADR-0028 section 9). */
    default int totalTokens() {
        return 800;
    }

    MemoryRecallResult compose(MemoryRecallRequest request);
}
