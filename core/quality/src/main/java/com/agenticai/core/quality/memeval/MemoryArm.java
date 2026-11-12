package com.agenticai.core.quality.memeval;

/**
 * The three arms of the memory A/B (docs/architecture/phase1/04-measurement-scorecard.md section
 * 5). Arms differ in exactly one switch, the memory seam; everything else is held constant.
 */
public enum MemoryArm {
    /** Baseline: memory off (no recall, no extraction, no writes). */
    B,
    /** Candidate: memory on. */
    M,
    /**
     * Ceiling: the ledger's currently valid facts are injected directly, bypassing the retriever.
     */
    ORACLE
}
