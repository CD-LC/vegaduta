package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.CompetenceRecorder;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The SELF_MODEL competence writer (ADR-0028 section 3). It counts per (agent, task class) and accepts exactly one
 * input type, {@link VerifiedOutcome}, which has no way to carry the model's self-assessment or a sampled judge's
 * verdict. Off unless {@code app.memory.competence.enabled} AND the tenant/agent setting allow it; when off, {@link
 * #record} does nothing at all. Never throws into its caller.
 *
 * <p>The number of distinct task classes per agent is capped ({@link CompetenceProperties#getMaxTaskClassesPerAgent});
 * the rest share one {@link CompetenceStorage#OVERFLOW_CLASS} row, which the summary never names.
 */
public class CompetenceTracker implements CompetenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(CompetenceTracker.class);

    private final CompetenceProperties properties;
    private final CompetenceGate gate;
    private final CompetenceStorage storage;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;

    public CompetenceTracker(
            CompetenceProperties properties,
            CompetenceGate gate,
            CompetenceStorage storage,
            ObjectProvider<MeterRegistry> meters,
            Clock clock) {
        this.properties = properties;
        this.gate = gate;
        this.storage = storage;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public void record(VerifiedOutcome outcome) {
        if (outcome == null || !gate.enabled(outcome.agentId())) {
            return;
        }
        try {
            storage.add(outcome, properties.getMaxTaskClassesPerAgent(), clock.instant());
            count("recorded");
        } catch (RuntimeException e) {
            count("failed");
            log.warn("Competence outcome not recorded for agent {}: {}", outcome.agentId(), e.getMessage());
        }
    }

    /** The agent's rows, most attempts first. Empty when the flag is off or the store fails. */
    public List<CompetenceRow> rows(UUID agentId) {
        if (!gate.enabled(agentId)) {
            return List.of();
        }
        try {
            return storage.rows(agentId);
        } catch (RuntimeException e) {
            log.warn("Competence rows unreadable for agent {}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    private void count(String result) {
        try {
            MeterRegistry registry = meters.getIfAvailable();
            if (registry != null) {
                registry.counter("memory.competence.outcome", "result", result).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics must never matter
        }
    }
}
