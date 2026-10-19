package com.agenticai.core.quality;

/**
 * Result of a single evaluation.
 *
 * <p>Two honest shapes, discriminated by {@link #status()}:
 * <ul>
 *   <li>{@link EvaluationStatus#EVALUATED} - {@code score} is a real
 *       measurement in [0.0, 1.0] and {@code feedback} explains it.</li>
 *   <li>{@link EvaluationStatus#UNAVAILABLE} - the evaluation could not run;
 *       {@code score} is {@code null} and {@code feedback} carries the
 *       reason. Never a made-up mid-range number.</li>
 * </ul>
 *
 * @param type the evaluation type that was run
 * @param score normalized score from 0.0 (worst) to 1.0 (best), or
 *     {@code null} when the evaluation was unavailable
 * @param feedback human-readable explanation of the score (or of why the
 *     evaluation was unavailable)
 * @param status whether this is a real measurement or an explicit absence
 */
public record EvaluationResult(
    EvaluationType type,
    Double score,
    String feedback,
    EvaluationStatus status
) {
    public EvaluationResult {
        if (status == EvaluationStatus.EVALUATED) {
            if (score == null || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException(
                    "EVALUATED result requires a score between 0.0 and 1.0, got " + score);
            }
        } else if (score != null) {
            throw new IllegalArgumentException(
                "UNAVAILABLE result must not carry a score (got " + score
                    + ") - a number here is exactly the fabrication this type exists to prevent");
        }
    }

    /** A real measurement. */
    public static EvaluationResult of(EvaluationType type, double score, String feedback) {
        return new EvaluationResult(type, score, feedback, EvaluationStatus.EVALUATED);
    }

    /** An explicit non-measurement: the evaluation could not run. */
    public static EvaluationResult unavailable(EvaluationType type, String reason) {
        return new EvaluationResult(type, null, reason, EvaluationStatus.UNAVAILABLE);
    }

    /**
     * Feedback prefix of an UNAVAILABLE result whose cause is the LLM judge
     * itself not answering (no usable provider, provider error, empty
     * completion) - as opposed to a dimension that simply does not apply to
     * the turn, or a verdict that came back but could not be parsed.
     */
    public static final String JUDGE_UNAVAILABLE_PREFIX = "Not evaluated - LLM judge unavailable: ";

    /** An explicit non-measurement because the LLM judge could not be reached or did not answer. */
    public static EvaluationResult judgeUnavailable(EvaluationType type, String reason) {
        return unavailable(type, JUDGE_UNAVAILABLE_PREFIX + reason);
    }

    /**
     * True for a {@link #judgeUnavailable} result. Static (not an accessor) so
     * it adds no property to this record's JSON form. Callers use it to tell
     * "the judge is down" - which must fail a run loudly - from the ordinary
     * per-dimension absences an otherwise healthy run can contain.
     */
    public static boolean isJudgeUnavailable(EvaluationResult result) {
        return result != null && result.status() == EvaluationStatus.UNAVAILABLE
            && result.feedback() != null && result.feedback().startsWith(JUDGE_UNAVAILABLE_PREFIX);
    }

    public boolean isEvaluated() {
        return status == EvaluationStatus.EVALUATED;
    }
}
