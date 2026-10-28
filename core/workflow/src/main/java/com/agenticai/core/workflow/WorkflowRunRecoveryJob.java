package com.agenticai.core.workflow;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crash recovery for the DEFAULT (synchronous, in-process) {@link
 * WorkflowExecutionEngine}. That engine persists a run as RUNNING up front
 * and only ever flips it to a terminal status from the same JVM - so a
 * deploy, OOM-kill, or crash mid-run leaves the row RUNNING forever: the
 * canvas polls it indefinitely, run lists show phantom in-flight work, and
 * nothing ever reaps it. (The Temporal path does not have this problem -
 * durable resumption is the whole reason it exists - but it is off by
 * default; see {@code app.workflow.execution-engine}.) This job is a REAPER,
 * not a resumer: the synchronous engine keeps all traversal state on the
 * request thread's stack, so a run interrupted by JVM death is genuinely
 * unresumable - the honest terminal state is FAILED with an explicit
 * "interrupted" reason, visible to the user, rather than RUNNING forever.
 *
 * <p><b>Why no heartbeat column</b>: staleness is derived, not stored. A
 * {@code last_progress_at} column would need a Flyway migration in the
 * SHARED tenant/controlplane version namespace while several parallel
 * sessions are allocating numbers from it - a collision risk that buys
 * nothing here, because the engine already persists the growing trace after
 * every node (see {@link WorkflowRun#updateTrace}), and each {@link
 * NodeTraceEntry} carries real per-node timestamps. So: candidates are
 * selected cheaply in SQL on {@code startedAt} older than a generous,
 * configurable threshold (default 2h - runs on this engine normally finish
 * in seconds to minutes), then each candidate's trace timestamps are checked
 * in-process and a run whose latest node activity is still inside the
 * threshold is left alone. The residual honest limitation: a run stuck
 * INSIDE one node (e.g. an LLM call hung for hours) looks identical to a
 * crashed one and will be reaped once its last node activity ages past the
 * threshold - acceptable, since a node genuinely running that long is
 * indistinguishable from a hang from the outside anyway.
 *
 * <p>Follows the {@link TenantSchemaIterator} idiom every other tenant-scoped
 * {@code @Scheduled} job uses ({@code RetryFailedActionsJob},
 * {@code KnowledgeIngestionJobRunner}) - querying {@link
 * WorkflowRunRepository} without it would silently sweep only the empty
 * {@code public} schema. Per-tenant isolation comes from the iterator;
 * per-row isolation from the try/catch in {@link #sweepTenant}.
 *
 * <p><b>Multi-instance guard</b>: wrapped in {@link PgAdvisoryJobLock} so
 * only one replica sweeps per tick - injected via {@link ObjectProvider}
 * rather than directly because that bean was authored in the same
 * multi-session pass as this class and this job must keep working (not fail
 * context startup) if the bean is ever conditionally absent. Running
 * lock-less is safe, just wasteful: the sweep is idempotent - a second
 * instance either finds no RUNNING rows left past the cutoff or writes the
 * same terminal state - so the lock is a duplicate-work optimization, not a
 * correctness requirement.
 */
@Component
public class WorkflowRunRecoveryJob {

    /** Exact user-visible reason written to {@code errorMessage} - asserted in tests, keep stable. */
    static final String FAILURE_REASON =
        "interrupted: instance stopped mid-run; marked failed by recovery job";

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunRecoveryJob.class);

    private static final String JOB_LOCK_KEY = "workflow-run-recovery";

    private final TenantSchemaIterator schemaIterator;
    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final PlatformIssueService issueService;
    private final WorkflowEventPublisher eventPublisher;
    private final ObjectProvider<PgAdvisoryJobLock> jobLock;
    private final Duration staleAfter;
    // Effect journal (V680): lets the reaper say whether the crash left an effect of unknown outcome.
    // Null when not wired (hand-built tests) - the reason is then the plain FAILURE_REASON.
    private ObjectProvider<WorkflowEffectJournal> effectJournal;
    private long journalRetentionDays = 30;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEffectJournal(
        ObjectProvider<WorkflowEffectJournal> effectJournal,
        @Value("${app.workflow.effects.journal.retention-days:30}") long retentionDays
    ) {
        this.effectJournal = effectJournal;
        this.journalRetentionDays = retentionDays;
    }

    public WorkflowRunRecoveryJob(
        TenantSchemaIterator schemaIterator,
        WorkflowRunRepository runRepository,
        ObjectMapper objectMapper,
        PlatformIssueService issueService,
        WorkflowEventPublisher eventPublisher,
        ObjectProvider<PgAdvisoryJobLock> jobLock,
        @Value("${app.workflow.recovery.stale-after-minutes:120}") long staleAfterMinutes
    ) {
        this.schemaIterator = schemaIterator;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.issueService = issueService;
        this.eventPublisher = eventPublisher;
        this.jobLock = jobLock;
        this.staleAfter = Duration.ofMinutes(staleAfterMinutes);
    }

    @Scheduled(
        fixedDelayString = "${app.workflow.recovery.interval-ms:600000}",
        initialDelayString = "${app.workflow.recovery.initial-delay-ms:120000}"
    )
    public void reapStaleRuns() {
        PgAdvisoryJobLock lock = jobLock.getIfAvailable();
        if (lock != null) {
            lock.tryRunExclusive(JOB_LOCK_KEY, this::sweepAllTenants);
        } else {
            sweepAllTenants();
        }
    }

    /** Package-private seam so tests exercise the sweep without the lock wrapper. */
    void sweepAllTenants() {
        schemaIterator.forEachActiveTenantSchema(this::sweepTenant);
    }

    private void sweepTenant(String schema) {
        purgeJournal(schema);
        Instant cutoff = Instant.now().minus(staleAfter);
        List<WorkflowRun> candidates = runRepository.findByStatusAndStartedAtBefore(WorkflowRunStatus.RUNNING, cutoff);
        for (WorkflowRun run : candidates) {
            try {
                Instant lastActivity = lastActivityAt(run);
                if (lastActivity.isAfter(cutoff)) {
                    // startedAt is old but the trace is still growing - a
                    // legitimately long run, not a crashed one. Leave it.
                    continue;
                }
                markInterrupted(run, lastActivity, schema);
            } catch (RuntimeException e) {
                // One unrecoverable row (corrupt trace JSON is handled softer
                // than this - see lastActivityAt/appendRecoveryTraceEntry -
                // so this is really a save/DB failure) must not stop the rest
                // of this tenant's batch, matching TenantSchemaIterator's own
                // per-tenant isolation at the per-row level.
                log.error("Failed to recover stale workflow run {} in schema '{}'", run.getId(), schema, e);
            }
        }
    }

    /** Retention sweep of the effect journal (best effort; 0 or less keeps rows forever). */
    private void purgeJournal(String schema) {
        WorkflowEffectJournal journal = effectJournal == null ? null : effectJournal.getIfAvailable();
        if (journal == null || !journal.isEnabled() || journalRetentionDays <= 0) {
            return;
        }
        try {
            journal.purgeOlderThan(Instant.now().minus(Duration.ofDays(journalRetentionDays)));
        } catch (RuntimeException e) {
            log.warn("Effect journal purge failed in schema '{}'", schema, e);
        }
    }

    /**
     * What the effect journal says about a crashed run: the user-visible reason (FAILURE_REASON plus
     * the precise recovery verdict) and extra trace lines. Plain FAILURE_REASON when the journal is
     * not wired or disabled, or cannot be read.
     */
    private Recovery assess(WorkflowRun run, List<NodeTraceEntry> trace) {
        WorkflowEffectJournal journal = effectJournal == null ? null : effectJournal.getIfAvailable();
        if (journal == null || !journal.isEnabled()) {
            return new Recovery(FAILURE_REASON, List.of());
        }
        try {
            journal.markRunEffectsUncertain(run.getId());
            List<WorkflowEffectJournalEntry> uncertain = journal.uncertainEffects(run.getId());
            if (!uncertain.isEmpty()) {
                StringBuilder nodes = new StringBuilder();
                for (WorkflowEffectJournalEntry e : uncertain) {
                    if (nodes.length() > 0) {
                        nodes.append(", ");
                    }
                    nodes.append(e.getNodeType()).append(" node ").append(e.getNodeId());
                    if (!"-".equals(e.getIterationKey())) {
                        nodes.append(" (iteration ").append(e.getIterationKey()).append(")");
                    }
                }
                String reason = FAILURE_REASON + " Effect uncertain: " + nodes
                    + " started its external side effect but never recorded completing; it may or may not"
                    + " have happened. NOT resumable until a human checks the destination and resolves the"
                    + " journal entry.";
                return new Recovery(reason, List.of("Effect journal: " + uncertain.size()
                    + " effect(s) of unknown outcome - " + nodes + "."));
            }
            NodeTraceEntry last = lastCompleted(trace);
            if (last != null) {
                String reason = FAILURE_REASON + " Resumable from the node after '" + last.label() + "' ("
                    + last.nodeId() + "): the effect journal has no uncertain effect, and completed effects"
                    + " are replayed from the journal, never re-executed.";
                return new Recovery(reason, List.of("Effect journal: no uncertain effects; last completed node is '"
                    + last.label() + "' (" + last.nodeId() + ")."));
            }
            return new Recovery(FAILURE_REASON + " No node had completed; the effect journal has no uncertain effect.",
                List.of("Effect journal: no uncertain effects."));
        } catch (RuntimeException e) {
            log.warn("Effect journal could not be read for stale run {}", run.getId(), e);
            return new Recovery(FAILURE_REASON, List.of());
        }
    }

    private static NodeTraceEntry lastCompleted(List<NodeTraceEntry> trace) {
        if (trace == null) {
            return null;
        }
        for (int i = trace.size() - 1; i >= 0; i--) {
            NodeTraceEntry e = trace.get(i);
            if (e.nodeId() != null && !"recovery".equals(e.nodeId()) && !"engine".equals(e.nodeId())
                && ("OK".equals(e.status()) || "SKIPPED".equals(e.status()) || "PINNED".equals(e.status()))) {
                return e;
            }
        }
        return null;
    }

    private record Recovery(String reason, List<String> traceLines) { }

    /**
     * The run's de-facto heartbeat: the latest timestamp across {@code
     * startedAt} and every trace entry's own start/completion instants. Falls
     * back to bare {@code startedAt} when the trace is empty or unparseable -
     * for a RUNNING row already past the SQL cutoff that means "reap it",
     * which is the right call: no readable evidence of progress exists.
     */
    private Instant lastActivityAt(WorkflowRun run) {
        Instant last = run.getStartedAt();
        List<NodeTraceEntry> trace = readTraceOrNull(run);
        for (NodeTraceEntry entry : trace == null ? List.<NodeTraceEntry>of() : trace) {
            if (entry.startedAt() != null && entry.startedAt().isAfter(last)) {
                last = entry.startedAt();
            }
            if (entry.completedAt() != null && entry.completedAt().isAfter(last)) {
                last = entry.completedAt();
            }
        }
        return last;
    }

    /**
     * {@code null} means "stored but unparseable" - distinct from an empty
     * list (no trace stored yet, or a genuinely empty {@code []}), because
     * {@link #appendRecoveryTraceEntry} must preserve unparseable bytes
     * as-is but is free to append to an empty trace.
     */
    private List<NodeTraceEntry> readTraceOrNull(WorkflowRun run) {
        if (run.getTraceJson() == null || run.getTraceJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() { });
        } catch (Exception unparseable) {
            return null;
        }
    }

    private void markInterrupted(WorkflowRun run, Instant lastActivity, String schema) {
        log.warn("Marking stale workflow run {} ('{}', schema '{}') FAILED - RUNNING since {}, no progress since {}.",
            run.getId(), run.getWorkflowName(), schema, run.getStartedAt(), lastActivity);

        // complete() re-writes the output columns too, so the existing values
        // are passed through - a crash after an Output(file) node already
        // produced a download-able artifact must not erase it.
        Recovery recovery = assess(run, readTraceOrNull(run));
        run.complete(WorkflowRunStatus.FAILED, appendRecoveryTraceEntry(run, lastActivity, recovery),
            recovery.reason(), run.getOutputFileName(), run.getOutputData());
        runRepository.save(run);

        // Same best-effort posture as WorkflowExecutionEngine#recordFailureIssue,
        // and the same workflow-keyed (not run-keyed) fingerprint so repeated
        // interruptions of one workflow accumulate onto one triage row.
        try {
            issueService.record(IssueSource.WORKFLOW_RUN, run.getWorkflowId().toString(), IssueSeverity.ERROR,
                "Workflow run failed", "Run " + run.getId() + ": " + recovery.reason());
        } catch (RuntimeException recordingFailure) {
            log.warn("Failed to record platform issue for recovered workflow run {}", run.getId(), recordingFailure);
        }
        eventPublisher.publish("workflow.run.failed", run.getId().toString(),
            Map.of("workflowId", run.getWorkflowId().toString(), "error", recovery.reason()));
    }

    /**
     * Appends a terminal ERROR entry so the run-trace overlay shows WHY the
     * run is FAILED, exactly like an engine-caught failure would. If the
     * stored trace cannot be parsed it is preserved byte-for-byte rather than
     * replaced - losing the partial trace of a crashed run to make room for
     * one bookkeeping entry would be a bad trade; {@code errorMessage}
     * carries the reason regardless.
     */
    private String appendRecoveryTraceEntry(WorkflowRun run, Instant lastActivity, Recovery recovery) {
        List<NodeTraceEntry> parsed = readTraceOrNull(run);
        if (parsed == null) {
            log.warn("Trace of stale workflow run {} is unparseable - leaving it untouched.", run.getId());
            return run.getTraceJson();
        }
        List<NodeTraceEntry> trace = new ArrayList<>(parsed);
        Instant now = Instant.now();
        List<String> lines = new ArrayList<>(List.of("Run had been RUNNING since " + run.getStartedAt()
            + " with no recorded progress since " + lastActivity + ".",
            "The executing instance stopped mid-run; this synchronous run cannot be resumed automatically."));
        lines.addAll(recovery.traceLines());
        trace.add(new NodeTraceEntry("recovery", "engine", "Crash recovery", "ERROR", recovery.reason(), now, now,
            lines));
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            // Same silent best-effort fallback shape as WorkflowExecutionEngine#writeTrace.
            return run.getTraceJson();
        }
    }
}
