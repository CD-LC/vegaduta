package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence of the competence counters (table {@code memory_competence}, V682). */
public interface CompetenceStorage {

    /** Task class that absorbs outcomes once an agent holds its maximum number of classes. */
    String OVERFLOW_CLASS = "_overflow";

    /**
     * Atomically adds one outcome: inserts the (agent, class) row or increments it in a single
     * statement, so concurrent writers never lose a count. When the agent already has {@code
     * maxClasses} classes (not counting the overflow row) and {@code outcome.taskClass()} is not
     * one of them, the outcome lands on {@link #OVERFLOW_CLASS}.
     */
    void add(VerifiedOutcome outcome, int maxClasses, Instant now);

    /** All rows of one agent (at most maxClasses + 1), most attempts first. */
    List<CompetenceRow> rows(UUID agentId);
}
