package com.agenticai.core.brief;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test double for {@link ProofSource} - the shape CORE 3's real implementation must satisfy. Holds one settable
 * snapshot; the factory methods build the interesting ones.
 */
class InMemoryProofSource implements ProofSource {

    volatile ProofSnapshot snapshot;
    volatile RuntimeException failure;

    InMemoryProofSource(ProofSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public ProofSnapshot snapshot(UUID briefId, Instant now) {
        if (failure != null) {
            throw failure;
        }
        return snapshot;
    }

    /** Everything the contract asks for: 9 days observed, scores above 0.7, no strikes, 12 rated turns 10 up. */
    static ProofSnapshot strong(Instant now) {
        return new ProofSnapshot(true, null, now.minus(Duration.ofDays(9)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(6)), 0.78), new ScorePoint(now.minus(Duration.ofDays(2)), 0.82)),
            8, 0, 12, 10, null, 0.75, 0.04);
    }
}
