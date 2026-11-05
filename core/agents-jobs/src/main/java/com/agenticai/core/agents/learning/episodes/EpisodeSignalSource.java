package com.agenticai.core.agents.learning.episodes;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional port for the human-decided signals of a turn that the tool-call log does not carry:
 * approval decisions and an explicit rating. No implementation ships with WP4b, so without a bean
 * these are simply absent (the outcome then rests on tool status alone, or is UNKNOWN). An
 * implementation must return only what a person decided, never a model's claim.
 */
public interface EpisodeSignalSource {

    /** A person's explicit verdict on the turn, if any. */
    enum HumanVerdict {
        ACCEPTED,
        REJECTED
    }

    /**
     * Approval decisions taken during the turn, and the human rating if one was given (null =
     * none).
     */
    record Signals(int approved, int rejected, HumanVerdict verdict) {
        public static final Signals NONE = new Signals(0, 0, null);

        public Signals {
            approved = Math.max(0, approved);
            rejected = Math.max(0, rejected);
        }
    }

    Signals signalsFor(UUID agentId, String sessionId, Instant since);
}
