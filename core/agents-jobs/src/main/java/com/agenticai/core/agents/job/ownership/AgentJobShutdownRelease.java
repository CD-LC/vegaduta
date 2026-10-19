package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rolling-deploy safety: when this replica is SIGTERMed, hand its running jobs to another replica
 * immediately instead of leaving them to expire.
 *
 * <h2>Sequence (runs on {@link ContextClosedEvent}, the very first thing a context close does)</h2>
 * <ol>
 *   <li>{@link JobOwnership#beginShutdown} - workers that get interrupted from now on leave their row alone
 *       (instead of today's behaviour of writing CANCELLED for an interrupt that was really a shutdown) and the
 *       executor refuses new local dispatches.</li>
 *   <li>{@link AgentJobExecutor#quiesceForShutdown} - stops the heartbeat tick (so nothing can renew a lease
 *       we are about to release) and interrupts every worker. Interrupt BEFORE release so the adopter cannot
 *       start while this replica's worker is still running the job.</li>
 *   <li>Release each owned row: one guarded UPDATE {@code heartbeat_at = epoch}. From here the row is expired
 *       for the lease sweep of ANY replica and for the legacy orphan sweep of a not-yet-upgraded replica. The
 *       executor first waits (bounded, a few seconds) for the interrupted workers to unwind; if the heartbeat
 *       scheduler had not fully terminated (a tick still inside a renewal could have written
 *       {@code heartbeat_at = now} after the release) the release is asserted a second time.</li>
 *   <li>Best-effort immediate resolution ({@link AgentJobLeaseSweep#resolveReleased}): the planner re-queues
 *       what is safe to resume so another replica's 10s dispatch poll claims it, rather than waiting for the
 *       next minute-cadence sweep.</li>
 * </ol>
 *
 * <h2>Termination grace period</h2>
 * Azure Container Apps sends SIGTERM and SIGKILLs after 30 seconds by default
 * (https://learn.microsoft.com/azure/container-apps/application-lifecycle-management). Steps 1-3 are a few
 * indexed single-row UPDATEs per owned job (at most {@code app.agent-jobs.max-concurrent}, default 4), well
 * inside that, and they run before Spring's web-server graceful drain and bean destruction so a long drain
 * cannot starve them. If the process is SIGKILLed anyway (OOM, grace exceeded), nothing is lost: the lease
 * simply expires and the sweep adopts the job after lease-seconds + skew grace + one sweep interval.
 *
 * <p>No-op unless {@code app.agent-jobs.ownership.enabled=true}. Every step is try/caught: shutdown must
 * never be blocked or failed by this class.
 */
@Component
public class AgentJobShutdownRelease {

    private static final Logger log = LoggerFactory.getLogger(AgentJobShutdownRelease.class);

    private static final String ACTOR = "system:agent-job-lease-release";

    private final JobOwnership ownership;
    private final AgentJobExecutor executor;
    private final JobLeaseStore store;
    private final AgentJobLeaseSweep sweep;
    private final MeterRegistry meterRegistry;
    private final ApplicationContext applicationContext;

    public AgentJobShutdownRelease(
        JobOwnership ownership,
        AgentJobExecutor executor,
        JobLeaseStore store,
        AgentJobLeaseSweep sweep,
        MeterRegistry meterRegistry,
        ApplicationContext applicationContext
    ) {
        this.ownership = ownership;
        this.executor = executor;
        this.store = store;
        this.sweep = sweep;
        this.meterRegistry = meterRegistry;
        this.applicationContext = applicationContext;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        // A ContextClosedEvent of a child/management context is also delivered here; only the context this
        // bean belongs to closing means THIS replica is going away.
        if (event != null && applicationContext != null && event.getApplicationContext() != applicationContext) {
            return;
        }
        if (!ownership.isEnabled() || !ownership.beginShutdown()) {
            return;
        }
        List<AgentJobExecutor.OwnedJob> owned;
        try {
            owned = executor.quiesceForShutdown();
        } catch (RuntimeException e) {
            log.warn("Could not quiesce the agent-job executor at shutdown; leases will expire on their own", e);
            return;
        }
        if (owned.isEmpty()) {
            return;
        }
        String me = ownership.ownerId();
        int released = releaseAll(owned, me);
        boolean heartbeatQuiesced;
        try {
            heartbeatQuiesced = executor.heartbeatQuiesced();
        } catch (RuntimeException e) {
            heartbeatQuiesced = false;
        }
        if (!heartbeatQuiesced) {
            // A heartbeat tick that was already inside renewLease when the scheduler was shut down may have
            // written heartbeat_at = now after the release above. Re-assert (guarded, idempotent).
            releaseAll(owned, me);
        }
        try {
            meterRegistry.counter("agent.job.lease.released").increment(released);
        } catch (RuntimeException ignored) {
            // metrics must never affect shutdown
        }
        log.info("Released {} of {} running agent job(s) for adoption by another replica (released at {})",
            released, owned.size(), Instant.now());
        try {
            sweep.resolveReleased(owned);
        } catch (RuntimeException e) {
            log.warn("Immediate resolution of released agent jobs failed; the lease sweep will handle them", e);
        }
    }

    private int releaseAll(List<AgentJobExecutor.OwnedJob> owned, String me) {
        int released = 0;
        for (AgentJobExecutor.OwnedJob job : owned) {
            Optional<TenantContext> previous = TenantContext.current();
            TenantContext.set(TenantContext.of(job.tenantId(), ACTOR, Set.of()));
            try {
                if (store.release(job.jobId(), me, JobLeasePolicy.RELEASED_AT)) {
                    released++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not release the lease of agent job {} at shutdown; it will expire on its own",
                    job.jobId(), e);
            } finally {
                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
            }
        }
        return released;
    }
}
