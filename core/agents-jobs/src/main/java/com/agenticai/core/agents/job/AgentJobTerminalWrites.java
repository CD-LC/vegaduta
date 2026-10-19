package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.chat.LlmCallLogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The terminal-write half of {@link AgentJobExecution}: re-load-then-write of a terminal state,
 * the ownership fence, outcome counting, the cooperative cancel read and the job's observed cost.
 * Split out of that class to keep it readable; not a bean - one instance is owned by each {@link
 * AgentJobExecution}, whose optional setters forward the ownership, outcome and call-log
 * collaborators here. Logs under {@link AgentJobExecution}'s logger so log routing is unchanged.
 */
final class AgentJobTerminalWrites {

    private static final Logger log = LoggerFactory.getLogger(AgentJobExecution.class);

    private final AgentJobRepository repository;
    private final MeterRegistry meterRegistry;

    private volatile JobOwnership ownership;
    private volatile AgentJobOutcomes outcomes;
    private volatile LlmCallLogService llmCallLogService;

    AgentJobTerminalWrites(AgentJobRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    void setOwnership(JobOwnership ownership) {
        this.ownership = ownership;
    }

    void setOutcomes(AgentJobOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    void setLlmCallLogService(LlmCallLogService llmCallLogService) {
        this.llmCallLogService = llmCallLogService;
    }

    /**
     * What this job's model calls cost, summed from {@code llm_call_log}: the rows logged for this
     * job's agent under this job's session since the job was created. Every provider path - a
     * workspace's own key as much as the platform pool - writes its estimated cost there, so this
     * is the one figure that is right for both.
     *
     * <p>{@code agent_job.cost_usd} has existed since V207 but {@link AgentJob#succeed} was always
     * handed {@code null}, so every consumer that reads it - a scheduled task's run history first
     * of all - showed no cost for a run that had spent tokens (prod, 2026-10-05).
     *
     * <p>Null, not zero, when nothing is known: no log service, no call logged, or only calls on a
     * model the pricing catalog cannot price. "Unknown" must not read as "free". Never throws -
     * accounting must not turn a finished job into a failed one.
     */
    BigDecimal observedCostUsd(AgentJob job) {
        LlmCallLogService callLog = llmCallLogService;
        if (callLog == null
                || job == null
                || job.getSessionId() == null
                || job.getAgentId() == null) {
            return null;
        }
        try {
            Instant from = job.getCreatedAt() != null ? job.getCreatedAt() : Instant.EPOCH;
            BigDecimal total = null;
            for (LlmCallLog call :
                    callLog.forSessionAgentWindow(
                            job.getSessionId(),
                            job.getAgentId(),
                            from,
                            Instant.now().plusSeconds(1))) {
                BigDecimal cost = call.getEstimatedCostUsd();
                if (cost != null) {
                    total = total == null ? cost : total.add(cost);
                }
            }
            return total == null ? null : total.setScale(6, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the cost of agent job {} from the call log ({})",
                    job.getId(),
                    e.toString());
            return null;
        }
    }

    /**
     * The value written to {@code owner_instance} at claim: the legacy host:pid unless ownership is
     * on.
     */
    String ownerId() {
        JobOwnership own = ownership;
        return own != null ? own.ownerId() : AgentJobExecutor.instanceId();
    }

    /**
     * True when the interrupt this worker just observed came from a graceful shutdown of THIS
     * replica (ownership mode only), not from an operator cancel or a deadline. The row must then
     * be left RUNNING (its lease is released) for another replica to adopt, instead of being
     * written CANCELLED.
     */
    boolean interruptedByShutdown() {
        JobOwnership own = ownership;
        return own != null && own.isEnabled() && own.isShuttingDown();
    }

    /**
     * Writes a terminal state by RE-LOADING the row first.
     *
     * <p>The worker has held a detached {@link AgentJob} across a call that may have run for
     * minutes, during which an operator may have set {@code cancel_requested} on the real row.
     * Saving the stale in-memory copy would silently reset that column and erase the record that a
     * human asked for this to stop. Re-reading costs one query at the end of a multi-minute job and
     * removes the entire class of "the worker clobbered a concurrent write".
     */
    boolean finish(UUID jobId, Consumer<AgentJob> transition) {
        AgentJob fresh = repository.findById(jobId).orElse(null);
        if (fresh == null) {
            log.warn("Agent job {} vanished before its terminal state could be written", jobId);
            return false;
        }
        if (fresh.getStatus().isTerminal()) {
            // A sweep (deadline or orphan) already terminated it. Its verdict
            // stands - the row is the record, and a second opinion written after
            // the fact would only be confusing.
            return false;
        }
        JobOwnership own = ownership;
        if (own != null && !own.mayWriteTerminal(fresh)) {
            // Ownership mode: this worker no longer holds the row (its lease expired and the job
            // was re-queued
            // or adopted). Writing now would overwrite the adopter's state with a zombie's result.
            log.warn(
                    "Agent job {} is no longer owned by this replica (status {}, owner '{}'); not"
                            + " writing its terminal state",
                    jobId,
                    fresh.getStatus(),
                    fresh.getOwnerInstance());
            return false;
        }
        transition.accept(fresh);
        repository.save(fresh);
        AgentJobOutcomes notifier = outcomes;
        if (notifier != null) {
            notifier.terminal(jobId);
        }
        return true;
    }

    /**
     * Whether a terminal outcome is counted. With ownership off (default) every outcome is counted
     * exactly as before, written or not. With ownership on, a fenced zombie that wrote nothing is
     * not counted, so the adopter's outcome is not double-counted.
     */
    boolean countable(boolean written) {
        JobOwnership own = ownership;
        return written || own == null || !own.isEnabled();
    }

    void failWith(UUID jobId, String errorCode, String message) {
        if (interruptedByShutdown()) {
            // The shutdown interrupt can surface as any of the typed failures below (budget,
            // tenant, tool
            // limit, a wrapped exception without the interrupt flag...). Writing a terminal FAILED
            // row for it
            // would defeat adoption: leave the row RUNNING (it is released at shutdown) for the
            // lease sweep.
            log.info(
                    "Agent job {} stopped with {} while this replica is shutting down; leaving it"
                            + " RUNNING for adoption",
                    jobId,
                    errorCode);
            return;
        }
        if (countable(finish(jobId, j -> j.fail(errorCode, message, Instant.now())))) {
            meterRegistry.counter("agent.job.completed", "outcome", "FAILED").increment();
        }
    }

    /**
     * Cooperative checkpoint. Reads the flag, not the loaded entity, because the cancel may have
     * been written by another replica after this worker loaded its copy.
     */
    boolean observedCancel(UUID jobId) {
        try {
            return Boolean.TRUE.equals(repository.isCancelRequested(jobId).orElse(false));
        } catch (RuntimeException e) {
            // Fail-closed would mean cancelling a healthy job because one query
            // blipped. Fail-open here just means the cancel is observed at the
            // next checkpoint or by the deadline sweep.
            log.warn("Could not read the cancel flag for agent job {}", jobId, e);
            return false;
        }
    }
}
