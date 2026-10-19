package com.agenticai.core.agents.job.durability;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRunner;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The boot-time reconciliation of orphaned RUNNING rows, as a REPORT.
 *
 * <p><b>It transitions nothing, and that is the design, not a shortcut.</b> The
 * sweeps in {@link AgentJobRunner} already reconcile - {@code dispatchQueued} is
 * the de-facto boot sweep for QUEUED rows (its first tick picks up everything a
 * previous process left behind), and {@code reapOrphans} terminalises RUNNING
 * rows whose heartbeat has gone stale, starting 60 seconds after boot. What was
 * missing was not a mechanism but EVIDENCE: an operator restarting core had no
 * way to see, at the moment of the restart, how much work the previous process
 * had been holding. This class is that line in the log, and the {@code
 * agent.job.recovery.orphans_at_boot} counter behind it.
 *
 * <h2>Why a blanket boot reclaim by status is forbidden here</h2>
 * The obvious-looking version of this class - "on boot, every RUNNING row is an
 * orphan, fail or re-queue them all" - is a worse bug than the one PR-4 fixes.
 * {@code core} is single-replica today only because {@code container_name:
 * agentic-core} in {@code deploy/docker-compose.yml} blocks {@code --scale}; the
 * whole job design is written multi-replica-safe, and a rolling deploy is
 * transiently two processes by construction. A fresh replica cannot tell, from
 * status alone, a row abandoned by a dead process from one a SIBLING is actively
 * executing right now. Killing a healthy sibling's live job would burn the
 * tenant's budget and lose their work, silently.
 *
 * <p>Heartbeat staleness is the only signal that CAN tell those apart, and it is
 * already the orphan sweep's decision rule ({@code heartbeat_at} older than
 * {@link AgentJobProperties#staleHeartbeatAfter()}, AND not {@code
 * executor.isActiveHere}). So this class reports and the sweep decides, ~60s
 * later, per row.
 *
 * <h2>Why {@code owner_instance} is reported but not trusted</h2>
 * {@link AgentJobExecutor#instanceId()} is {@code host:pid}. In a container the
 * JVM is usually PID 1 and the hostname is fixed by {@code container_name}, so
 * a restarted container produces the IDENTICAL instance id as the process that
 * died. Rows the report attributes to "this instance" at boot are therefore
 * almost always the PREVIOUS incarnation's abandoned work, not live work - which
 * is why both breakdowns are logged and neither is used to decide anything. The
 * orphan sweep is unaffected by this collision: it discriminates on {@code
 * heartbeat_at} plus the in-memory {@code isActiveHere} map, which is empty on a
 * fresh JVM, and only ever LOGS {@code owner_instance}.
 */
@Component
public class AgentJobBootReconciliation {

    private static final Logger log = LoggerFactory.getLogger(AgentJobBootReconciliation.class);

    /**
     * Named for the question an operator asks after a deploy ("did the restart
     * strand anything?"). Counts every row still RUNNING at {@code
     * ApplicationReadyEvent}, which this process is by construction not
     * executing - a SUPERSET of true orphans in a multi-replica deployment,
     * because a sibling may legitimately own some of them. Read it next to
     * {@code agent.job.recovery.verdict} to see how the sweep resolved them.
     */
    private static final String BOOT_ORPHAN_METRIC = "agent.job.recovery.orphans_at_boot";

    private final AgentJobRecoveryLookup lookup;
    private final TenantSchemaIterator schemaIterator;
    private final AgentJobProperties properties;
    private final MeterRegistry meterRegistry;

    public AgentJobBootReconciliation(
        AgentJobRecoveryLookup lookup,
        TenantSchemaIterator schemaIterator,
        AgentJobProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.lookup = lookup;
        this.schemaIterator = schemaIterator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Runs once, on {@link ApplicationReadyEvent}.
     *
     * <p>Not {@code @PostConstruct} and not a {@code @Scheduled} tick: the
     * tenant list and the JPA infrastructure must both be up before the first
     * {@link TenantSchemaIterator} call, and this must happen exactly once per
     * process. It deliberately carries no {@code PgAdvisoryJobLock} - unlike a
     * periodic sweep, every replica genuinely wants to report its own view of
     * what it inherited, and this write-free report cannot double-anything.
     *
     * <p>Every failure is swallowed. A boot report that cannot read the database
     * must never stop core from starting, and the sweeps that do the actual
     * recovery are entirely independent of it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportOrphansAtBoot() {
        String instanceId = AgentJobExecutor.instanceId();
        AtomicLong runningTotal = new AtomicLong();
        AtomicLong sameInstanceTotal = new AtomicLong();

        try {
            // The iterator sets TenantContext per schema; without it every count
            // below would silently read the empty `public` schema and this report
            // would cheerfully announce "nothing stranded" for every tenant.
            schemaIterator.forEachActiveTenantSchema(schema -> {
                long running = lookup.countByStatus(AgentJobStatus.RUNNING);
                if (running == 0) {
                    return;
                }
                long sameInstance = lookup.countByStatusAndOwnerInstance(AgentJobStatus.RUNNING, instanceId);
                runningTotal.addAndGet(running);
                sameInstanceTotal.addAndGet(sameInstance);

                log.warn(
                    "Boot reconciliation: schema '{}' has {} agent_job row(s) still RUNNING that this process ({}) "
                        + "is not executing - {} carry this instance id (after a container restart that is the "
                        + "PREVIOUS process on the same host+pid, not live work) and {} carry another instance id. "
                        + "Nothing is transitioned here: the orphan sweep decides each row individually once its "
                        + "heartbeat is older than {}, and a row a sibling replica is genuinely still running is "
                        + "skipped rather than reaped.",
                    schema, running, instanceId, sameInstance, running - sameInstance,
                    properties.staleHeartbeatAfter()
                );

                List<AgentJob> sample = lookup.findTop20ByStatusOrderByCreatedAtAsc(AgentJobStatus.RUNNING);
                for (AgentJob job : sample) {
                    log.warn(
                        "  RUNNING at boot in '{}': job {} (agent {}) owner '{}' last heartbeat {} "
                            + "turns_used {} attempt {} created {}",
                        schema, job.getId(), job.getAgentId(), job.getOwnerInstance(), job.getHeartbeatAt(),
                        job.getTurnsUsed(), job.getAttemptCount(), job.getCreatedAt()
                    );
                }
                if (running > sample.size()) {
                    // The cap lives in the finder's name (findTop20...), so a
                    // pathological schema cannot flood startup logs.
                    log.warn(
                        "  ... and {} further RUNNING row(s) in '{}' not listed (detail capped at {})",
                        running - sample.size(), schema, sample.size()
                    );
                }
            });

            long total = runningTotal.get();
            meterRegistry.counter(BOOT_ORPHAN_METRIC).increment(total);
            if (total == 0) {
                log.info(
                    "Boot reconciliation: no agent_job rows were left RUNNING by a previous process (instance {})",
                    instanceId
                );
            } else {
                log.warn(
                    "Boot reconciliation: {} agent_job row(s) across all tenant schemas were RUNNING at startup "
                        + "({} carrying this instance id). Expect each to be re-queued or FAILED with ORPHANED "
                        + "within roughly {} of its last heartbeat.",
                    total, sameInstanceTotal.get(), properties.staleHeartbeatAfter()
                );
            }
        } catch (RuntimeException e) {
            log.warn(
                "Agent-job boot reconciliation report failed; startup and the orphan sweep are unaffected", e);
        }
    }
}
