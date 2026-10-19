package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.ownership.JobLeasePolicy.RenewOutcome;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This replica's view of agent-job ownership: its owner id, whether the lease mechanism is on, whether the
 * process is shutting down, and the two checks the worker paths need (renew my lease; may I write this job's
 * terminal state).
 *
 * <p><b>With {@code app.agent-jobs.ownership.enabled=false} (the default) every method answers "legacy":</b>
 * {@link #ownerId()} is the old {@code host:pid}, {@link #mayWriteTerminal} is always true and {@link #renew}
 * is never called by the executor. The bean is always created so the executor and execution can take it as an
 * optional setter dependency without constructor changes.
 */
@Component
public class JobOwnership {

    private static final Logger log = LoggerFactory.getLogger(JobOwnership.class);

    private final AgentJobProperties properties;
    private final JobLeaseStore store;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean renewFailureLogged = new AtomicBoolean(false);

    public JobOwnership(AgentJobProperties properties, JobLeaseStore store) {
        this.properties = properties;
        this.store = store;
    }

    @PostConstruct
    void logConfiguration() {
        if (!isEnabled()) {
            return;
        }
        JobLeasePolicy.Timing timing = timing();
        log.info(
            "Agent-job ownership leases ON: owner id '{}', lease {}s, heartbeat {}s, resume attempts <= {}{}",
            ownerId(), timing.leaseSeconds(), timing.heartbeatSeconds(),
            properties.getOwnership().effectiveResumeMaxAttempts(),
            timing.adjusted() ? " (configured values were unsafe and were corrected)" : "");
    }

    public boolean isEnabled() {
        return properties.getOwnership().isEnabled();
    }

    public JobLeasePolicy.Timing timing() {
        return properties.getOwnership().effectiveTiming(properties.getHeartbeatInterval());
    }

    /** Written to {@code owner_instance} at claim. Legacy {@code host:pid} unless ownership is enabled. */
    public String ownerId() {
        return isEnabled() ? JobReplicaId.current() : AgentJobExecutor.instanceId();
    }

    /** True once this process has begun shutting down with ownership on; workers then leave their rows alone. */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** @return true for the first caller only. */
    public boolean beginShutdown() {
        return shuttingDown.compareAndSet(false, true);
    }

    /**
     * Renews this replica's lease on {@code jobId} (one UPDATE) and classifies a miss. Never throws: a DB blip
     * answers {@link RenewOutcome#UNKNOWN}, which makes the caller do nothing (fail safe: never fence a healthy
     * job because one query failed).
     */
    public RenewOutcome renew(UUID jobId, Instant now) {
        String me = ownerId();
        try {
            if (store.renew(jobId, me, now)) {
                return RenewOutcome.RENEWED;
            }
            Optional<JobLeaseStore.LeaseView> view = store.view(jobId);
            if (view.isEmpty()) {
                return RenewOutcome.UNKNOWN;
            }
            return JobLeasePolicy.classifyFailedRenewal(view.get().status(), view.get().owner(), me);
        } catch (RuntimeException e) {
            if (renewFailureLogged.compareAndSet(false, true)) {
                log.warn("Agent-job lease renewal failed (logged once; the job continues and is not fenced)", e);
            }
            return RenewOutcome.UNKNOWN;
        }
    }

    /**
     * Whether this replica may write {@code fresh}'s terminal state. With ownership off: always (today's
     * behaviour). On: only while the row is still RUNNING and owned by this replica, so a zombie worker whose
     * job was adopted elsewhere can never overwrite the adopter's row.
     */
    public boolean mayWriteTerminal(AgentJob fresh) {
        if (!isEnabled()) {
            return true;
        }
        return JobLeasePolicy.holdsLease(fresh.getStatus(), fresh.getOwnerInstance(), ownerId());
    }
}
