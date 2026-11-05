package com.agenticai.core.agents.memory.spi;

/**
 * Write port for the SELF_MODEL competence counters (ADR-0028 section 3). The only input is a
 * {@link VerifiedOutcome}; an implementation is a no-op while its flag is off, and never throws
 * into the caller (the episode path, a background worker with the tenant already applied).
 */
public interface CompetenceRecorder {

    void record(VerifiedOutcome outcome);
}
