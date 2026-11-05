package com.agenticai.core.agents.memorystore.decay;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-fact salience {@code S} in [0,1] (phase 1 design 03, section 3):
 *
 * <pre>
 * S = clamp(wI*importance + wR*0.5^(age/H) + wF*min(1, ln(1+uses)/ln(1+F_SAT)), 0, 1)   pinned: S = 1
 * S_eff = S * (0.5 if QUARANTINED else 1)
 * </pre>
 *
 * where age is the days since {@code max(lastUsedAt, createdAt)}. The arithmetic is a pure static
 * method; the instance form only supplies {@link DecayProperties} and a {@link Clock} so a caller
 * never passes "now" by hand and tests control time. Salience is recomputed, never stored.
 */
public final class MemorySalience {

    private static final double SECONDS_PER_DAY = 86_400d;

    private final DecayProperties props;
    private final Clock clock;

    public MemorySalience(DecayProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * Pure formula. {@code lastUsedAt} may be null; a clock earlier than the row clamps age to 0.
     */
    public static double score(
            double importance,
            int uses,
            Instant createdAt,
            Instant lastUsedAt,
            Instant now,
            double halfLifeDays,
            boolean pinned,
            double wImportance,
            double wRecency,
            double wFrequency,
            double frequencySaturation) {
        if (pinned) {
            return 1.0;
        }
        Instant anchor =
                lastUsedAt != null && lastUsedAt.isAfter(createdAt) ? lastUsedAt : createdAt;
        double ageDays =
                Math.max(0d, Duration.between(anchor, now).toMillis() / 1000d / SECONDS_PER_DAY);
        double recency = Math.pow(0.5, ageDays / halfLifeDays);
        double frequency =
                Math.min(1d, Math.log1p(Math.max(0, uses)) / Math.log1p(frequencySaturation));
        double imp = Double.isNaN(importance) ? 0d : Math.max(0d, Math.min(1d, importance));
        double s = wImportance * imp + wRecency * recency + wFrequency * frequency;
        return Math.max(0d, Math.min(1d, s));
    }

    /** {@code S} for a snapshot at the injected clock's now. */
    public double score(ItemSnapshot item) {
        return score(item, clock.instant());
    }

    /** {@code S} for a snapshot at an explicit instant (the planner pins one instant per plan). */
    public double score(ItemSnapshot item, Instant now) {
        DecayProperties.Weights w = props.getWeights();
        return score(
                item.importance(),
                item.uses(),
                item.createdAt(),
                item.lastUsedAt(),
                now,
                props.halfLifeFor(item.kind()),
                item.pinned(),
                w.getImportance(),
                w.getRecency(),
                w.getFrequency(),
                props.getFrequencySaturation());
    }

    /** {@code S_eff = S * trust_factor}. */
    public double effective(ItemSnapshot item, Instant now) {
        double s = score(item, now);
        return item.trust() == MemoryTrustLevel.QUARANTINED ? s * props.getQuarantineFactor() : s;
    }

    public double effective(ItemSnapshot item) {
        return effective(item, clock.instant());
    }
}
