package com.agenticai.core.agents.memorystore.decay;

import java.time.Duration;
import java.time.Instant;

/**
 * The importance the nightly consolidation feeds into the salience function for a stored fact
 * (phase 1 design 03, section 3). A stored {@code importance} is only a prior set at write time
 * (0.8 typed by a person, 0.6 saved by the agent, 0.5 default); with the default weights a prior of
 * 0.5 alone keeps salience at 0.225, above the forget threshold of 0.12, so a prior that is never
 * tested could never be forgotten. This makes the prior earn its keep:
 *
 * <pre>
 * earned     = min(1, ln(1 + uses) / ln(1 + F_SAT))            evidence the fact was useful
 * neglect    = 0.5 ^ (idleDays / H)                            idleDays since max(lastUsedAt, createdAt)
 * importance = prior * (earned + (1 - earned) * neglect)
 * </pre>
 *
 * A well-used fact keeps its whole prior however long it then sits idle; a never-used fact's prior
 * halves every half-life, so an unpinned fact nobody ever recalled reaches the forget band after
 * roughly three to four half-lives (about 100 to 130 days with the default 45), while one recalled
 * a handful of times or lately does not. Pinned and person-authored facts are not decided here: the
 * planner never touches them. Pure and unit-tested without a database.
 */
public final class FactImportance {

    /** The prior of a fact with no stored importance. */
    public static final double DEFAULT_PRIOR = 0.5;

    private static final double SECONDS_PER_DAY = 86_400d;

    private FactImportance() {}

    public static double effective(
            double prior,
            int uses,
            Instant createdAt,
            Instant lastUsedAt,
            Instant now,
            double halfLifeDays,
            double frequencySaturation) {
        double p = Double.isNaN(prior) ? DEFAULT_PRIOR : Math.max(0d, Math.min(1d, prior));
        double earned =
                Math.min(
                        1d,
                        Math.log1p(Math.max(0, uses))
                                / Math.log1p(Math.max(1d, frequencySaturation)));
        Instant anchor =
                lastUsedAt != null && lastUsedAt.isAfter(createdAt) ? lastUsedAt : createdAt;
        double idleDays =
                Math.max(0d, Duration.between(anchor, now).toMillis() / 1000d / SECONDS_PER_DAY);
        double neglect = Math.pow(0.5, idleDays / Math.max(1e-6, halfLifeDays));
        return Math.max(0d, Math.min(1d, p * (earned + (1d - earned) * neglect)));
    }
}
