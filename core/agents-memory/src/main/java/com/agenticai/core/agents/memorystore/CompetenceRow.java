package com.agenticai.core.agents.memorystore;

import java.time.Instant;

/** One {@code memory_competence} row: numbers only. */
public record CompetenceRow(
        String taskClass,
        int attempts,
        int verifiedSuccess,
        int verifiedFailure,
        int humanAccepted,
        int humanRejected,
        int unknownOutcome,
        Instant lastOutcomeAt) {

    /** Outcomes that count as success: verified tool or approval success, or a person's acceptance. */
    public long successes() {
        return (long) verifiedSuccess + humanAccepted;
    }

    /** Outcomes that count as failure. {@code unknownOutcome} is in neither. */
    public long failures() {
        return (long) verifiedFailure + humanRejected;
    }

    /** The sample the rate rests on: verified outcomes only, UNKNOWN excluded. */
    public long verifiedSample() {
        return successes() + failures();
    }

    public double successRate() {
        long n = verifiedSample();
        return n == 0 ? 0.0 : (double) successes() / n;
    }

    /** 95% Wilson lower bound of the success rate: what the agent can be said to achieve at least. */
    public double lowerBound() {
        return CompetenceStats.wilsonLowerBound(successes(), verifiedSample());
    }
}
