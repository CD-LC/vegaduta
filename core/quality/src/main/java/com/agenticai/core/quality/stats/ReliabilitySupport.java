package com.agenticai.core.quality.stats;

import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.IntervalResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Small pure helpers shared by {@link EvalReliabilityService} and {@link EvalNoiseFloors}. */
final class ReliabilitySupport {

    private ReliabilitySupport() {
    }

    /**
     * Case identity across runs. The result row's {@code caseId} is the real
     * identity and is what makes two runs' results the same case; the
     * position-based fallback exists because {@code caseId} is deliberately not
     * an FK (cases are deletable and results are permanent snapshots), so a
     * legacy or hand-inserted row could carry null.
     */
    static String caseKey(EvalRunCaseResult result) {
        return result.getCaseId() != null
            ? result.getCaseId().toString()
            : "pos:" + result.getPosition();
    }

    /**
     * Derives a bootstrap seed from the IDENTITY of the data being summarised.
     *
     * <p>{@link Objects#hash(Object...)} over {@link UUID}s and {@link List}s of
     * them is stable across JVMs and restarts (UUID, String and List hash codes
     * are all specified), which is the property that matters: the same runs must
     * always produce the same interval, and different runs should produce a
     * different resampling stream so two datasets do not share a coincidence.
     */
    static long seedFrom(Object... identity) {
        return Objects.hash(identity);
    }

    static IntervalResponse toDto(ConfidenceInterval interval) {
        if (interval == null) {
            // Null propagates deliberately: "not computable from this data",
            // never a zero-width placeholder.
            return null;
        }
        return new IntervalResponse(
            interval.low(), interval.high(), interval.confidenceLevel(), interval.method());
    }
}
