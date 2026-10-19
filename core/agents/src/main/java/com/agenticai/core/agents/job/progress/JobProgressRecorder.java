package com.agenticai.core.agents.job.progress;

import java.util.UUID;

/**
 * Port through which code in {@code core/agents} writes a note onto an agent job's progress trail.
 *
 * <p>Jobs live in {@code core/agents-jobs} (ADR-0023), which depends on {@code core/agents}; the
 * tool layer here (deep_research phase notes) must not depend back on it. {@code
 * AgentJobProgressService} in {@code agents-jobs} is the implementation.
 *
 * <p>Best-effort by contract, like the implementation: {@link #record} never throws for a write
 * failure. Callers still guard it, since an absent bean or a proxy failure is outside that
 * contract.
 */
public interface JobProgressRecorder {

    /** Appends {@code note} (with an optional 0-100 {@code percent}) to job {@code jobId}. */
    void record(UUID jobId, String note, Integer percent, ProgressSource source);
}
