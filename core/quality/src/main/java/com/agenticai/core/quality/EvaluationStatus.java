package com.agenticai.core.quality;

/**
 * Whether an evaluation actually happened.
 *
 * <p>Exists because the previous contract had no way to say "we couldn't
 * measure this": every failure path (judge not wired, provider outage,
 * malformed verdict) was silently converted into a fabricated neutral 0.5
 * that callers could not distinguish from a real measurement. Fabricated
 * data is worse than absent data - this enum makes absence explicit.
 */
public enum EvaluationStatus {

    /** The evaluator genuinely ran and produced a real measurement. */
    EVALUATED,

    /**
     * The evaluator could not produce a measurement (LLM judge unavailable,
     * provider failure, unparseable verdict). {@code score} is {@code null}
     * and MUST NOT be counted in any aggregate or persisted as a score.
     */
    UNAVAILABLE
}
