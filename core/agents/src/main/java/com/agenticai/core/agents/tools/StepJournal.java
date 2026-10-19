package com.agenticai.core.agents.tools;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Durable (Postgres) {@code run_id + step_id} journal for agent work.
 *
 * <p>Three users, one table: agent-job TURNS ({@code AgentJobExecution} writes STARTED before the
 * model call and COMPLETED after, so {@code ZeroWorkOrphanRecoveryPolicy} can find a safe resume
 * point), DURABLE tool EFFECTS (this class is the durable backing of {@link EffectJournal}, so a
 * write/send tool that already ran is not re-fired after a restart), and {@code delegate_parallel}
 * SWARM branches (a finished branch is not re-run).
 *
 * <p><b>Off by default</b> ({@code app.agent-jobs.durability.step-journal-enabled=false}): every
 * method is a no-op / empty then, so the flag-off path is today's behaviour.
 *
 * <p><b>Best-effort, never throws.</b> A journal failure must not fail a user's job. The failure
 * mode is safe by construction: a missing COMPLETED row only ever means "no safe resume point / run
 * it again", it can never fabricate a resume.
 *
 * <p><b>Single-replica assumption.</b> Reads and writes go to the tenant schema of the calling
 * thread ({@code TenantContext}); callers run on a thread that already has it. There is no
 * cross-replica lease: two workers racing on the same run would both see STARTED and both proceed.
 * That is acceptable only because an agent job is claimed by a guarded UPDATE on {@code agent_job}
 * (one owner at a time) and the orphan sweep runs under a Postgres advisory lock; see the ADR for
 * what multi-replica would still need.
 */
@Component
public class StepJournal implements EffectJournal.DurableStore {

    private static final Logger log = LoggerFactory.getLogger(StepJournal.class);

    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String KIND_TURN = "TURN";
    public static final String KIND_EFFECT = "EFFECT";
    public static final String KIND_SWARM = "SWARM";

    /**
     * In-flight marker of one DURABLE (write/send) tool call of a job, under the job's own run id.
     */
    public static final String KIND_TOOL = "TOOL";

    /** Cap on a stored result, so a huge tool/answer cannot bloat the table. */
    static final int MAX_RESULT_CHARS = 64_000;

    private static final String EFFECT_RUN = "effects";

    /** A recorded step: its status and (when completed) its result. */
    public record Step(String status, String result, Instant createdAt) {
        public Step(String status, String result) {
            this(status, result, null);
        }

        public boolean completed() {
            return COMPLETED.equals(status);
        }

        /**
         * COMPLETED with a non-blank result. A COMPLETED row whose result is null/blank (a lost or
         * empty answer) must NEVER be replayed as the job's final result: resuming from it would
         * finish the job SUCCEEDED with nothing, so it is treated as an incomplete turn instead.
         */
        public boolean completedWithResult() {
            return completed() && result != null && !result.isBlank();
        }
    }

    private final StepJournalRepository repository;
    private final boolean enabled;
    private final boolean resumeFromJournal;
    private final Clock clock;

    @Autowired
    public StepJournal(
            StepJournalRepository repository,
            @Value("${app.agent-jobs.durability.step-journal-enabled:false}") boolean enabled,
            @Value("${app.agent-jobs.durability.resume-from-journal:false}")
                    boolean resumeFromJournal) {
        this(repository, enabled, resumeFromJournal, Clock.systemUTC());
    }

    /** Test seam: resume-from-journal follows the master switch. */
    StepJournal(StepJournalRepository repository, boolean enabled, Clock clock) {
        this(repository, enabled, enabled, clock);
    }

    StepJournal(
            StepJournalRepository repository,
            boolean enabled,
            boolean resumeFromJournal,
            Clock clock) {
        this.repository = repository;
        this.enabled = enabled;
        this.resumeFromJournal = resumeFromJournal;
        this.clock = clock;
    }

    public static String turnStepId(int turnIndex) {
        return "turn:" + turnIndex;
    }

    public static String runIdForJob(UUID jobId) {
        return jobId.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Mirrors app.tools.effect-journal.enabled: in-flight TOOL rows are only written when it is on.
     */
    @Value("${app.tools.effect-journal.enabled:true}")
    private volatile boolean effectJournalEnabled;

    public void setEffectJournalEnabled(boolean effectJournalEnabled) {
        this.effectJournalEnabled = effectJournalEnabled;
    }

    /**
     * True when DURABLE tool calls are tracked in flight (journal + resume + effect journal all
     * on). Without it no TOOL rows exist, so "no incomplete call" proves nothing and a turn must
     * not be re-run.
     */
    public boolean isDurableCallTrackingActive() {
        return isResumeEnabled() && effectJournalEnabled;
    }

    /**
     * True when the journal is on AND resuming from it is on. Everything that changes LIVE
     * behaviour (the stable job-scoped effect key, swarm-branch replay, durable effect read/write)
     * is gated on this, not on {@link #isEnabled()}: turning the journal on alone is observability
     * only (rows are written, nothing is replayed).
     */
    public boolean isResumeEnabled() {
        return enabled && resumeFromJournal;
    }

    @Override
    public boolean isActive() {
        return isResumeEnabled();
    }

    /**
     * Drops every row of a FRESH job run (turn rows under the job id, swarm rows under {@code
     * job:<id>}) so a reused job id can never replay a stale COMPLETED step into a new run. Called
     * only when a job is claimed for the first time. Effect rows are keyed by a hash that already
     * includes the unique job session id. Best-effort.
     */
    public void purgeJobRun(UUID jobId) {
        if (!enabled || jobId == null) {
            return;
        }
        try {
            repository.deleteByRunIdIn(java.util.List.of(runIdForJob(jobId), "job:" + jobId));
        } catch (RuntimeException e) {
            log.debug("Step journal purge failed for job {}", jobId, e);
        }
    }

    /** Records that a step started. Idempotent: an existing row (any status) is left untouched. */
    public void begin(String runId, String stepId, String kind) {
        if (!enabled || runId == null || stepId == null) {
            return;
        }
        try {
            Optional<StepJournalEntry> existing = repository.findByRunIdAndStepId(runId, stepId);
            if (existing.isPresent()) {
                StepJournalEntry row = existing.get();
                if (KIND_TOOL.equals(kind) && !STARTED.equals(row.getStatus())) {
                    // A repeat call of the same tool+args whose earlier call was marked done
                    // (gated, errored or
                    // not executed, so no effect was recorded): it is in flight again, and a crash
                    // during it must
                    // not be invisible behind the stale COMPLETED row.
                    row.restart(clock.instant());
                    repository.save(row);
                }
                return;
            }
            repository.save(new StepJournalEntry(runId, stepId, kind, clock.instant()));
        } catch (RuntimeException e) {
            // A concurrent insert of the same (run, step) lands here via the unique constraint:
            // that is fine.
            if (KIND_TOOL.equals(kind)) {
                log.warn(
                        "Step journal in-flight marker failed for {}/{}; a crash during this call will not be "
                                + "visible to recovery",
                        runId,
                        stepId,
                        e);
                return;
            }
            log.debug(
                    "Step journal begin failed for {}/{}; continuing without a record",
                    runId,
                    stepId,
                    e);
        }
    }

    /**
     * Marks a step COMPLETED with its result (creating the row if begin() was lost). Best-effort.
     */
    public void complete(String runId, String stepId, String kind, String result) {
        if (!enabled || runId == null || stepId == null) {
            return;
        }
        try {
            StepJournalEntry entry =
                    repository
                            .findByRunIdAndStepId(runId, stepId)
                            .orElseGet(
                                    () ->
                                            new StepJournalEntry(
                                                    runId, stepId, kind, clock.instant()));
            entry.complete(cap(result), clock.instant());
            repository.save(entry);
        } catch (RuntimeException e) {
            log.debug(
                    "Step journal complete failed for {}/{}; continuing without a record",
                    runId,
                    stepId,
                    e);
        }
    }

    /**
     * The recorded step, empty when absent, disabled, or the lookup failed (never a fabricated
     * resume).
     */
    public Optional<Step> find(String runId, String stepId) {
        if (!enabled || runId == null || stepId == null) {
            return Optional.empty();
        }
        try {
            return repository
                    .findByRunIdAndStepId(runId, stepId)
                    .map(e -> new Step(e.getStatus(), e.getResultText(), e.getCreatedAt()));
        } catch (RuntimeException e) {
            log.warn(
                    "Step journal lookup failed for {}/{}; treating as no record",
                    runId,
                    stepId,
                    e);
            return Optional.empty();
        }
    }

    /** Result of a COMPLETED step, empty otherwise. */
    public Optional<String> findCompletedResult(String runId, String stepId) {
        return find(runId, stepId)
                .filter(Step::completed)
                .map(s -> s.result() == null ? "" : s.result());
    }

    /**
     * The run id of the job embedded in a job chat session id ({@code job:<uuid>}), or null for any
     * other session.
     */
    static String jobRunIdForSession(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("job:")) {
            return null;
        }
        try {
            return runIdForJob(UUID.fromString(sessionId.substring("job:".length())));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True when this job has a DURABLE tool call that STARTED and never COMPLETED: the worker may
     * have died after the provider (an email, an MCP write) acted but before the result was
     * recorded, so re-running the turn could repeat the effect. A failed lookup answers {@code
     * true}: an unprovable "nothing in flight" is not a safe resume.
     */
    public boolean hasIncompleteDurableCall(UUID jobId) {
        if (!enabled || jobId == null) {
            return false;
        }
        try {
            return repository.existsByRunIdAndKindAndStatus(runIdForJob(jobId), KIND_TOOL, STARTED);
        } catch (RuntimeException e) {
            log.warn(
                    "Step journal in-flight lookup failed for job {}; treating as in flight",
                    jobId,
                    e);
            return true;
        }
    }

    /**
     * Deletes every journal row older than {@code cutoff} (the retention purge). Returns the number
     * deleted, 0 when disabled or on failure. Losing a row only ever means "no safe resume point",
     * never a fabricated resume, so a purge can only make recovery more conservative.
     */
    public int purgeOlderThan(Instant cutoff) {
        if (!enabled || cutoff == null) {
            return 0;
        }
        try {
            return repository.deleteByCreatedAtBefore(cutoff);
        } catch (RuntimeException e) {
            log.warn("Step journal retention purge failed", e);
            return 0;
        }
    }

    // ---- EffectJournal.DurableStore: effects keyed by the EffectJournal key (already
    // tenant-hashed) ----

    @Override
    public Optional<String> findEffect(String key) {
        return findCompletedResult(EFFECT_RUN, "effect:" + key);
    }

    @Override
    public void saveEffect(String key, String result) {
        complete(EFFECT_RUN, "effect:" + key, KIND_EFFECT, result);
    }

    @Override
    public void beginDurableCall(String sessionId, String effectKey) {
        String runId = jobRunIdForSession(sessionId);
        if (runId != null && effectKey != null) {
            begin(runId, "tool:" + effectKey, KIND_TOOL);
        }
    }

    @Override
    public void completeDurableCall(String sessionId, String effectKey) {
        String runId = jobRunIdForSession(sessionId);
        if (runId != null && effectKey != null) {
            complete(runId, "tool:" + effectKey, KIND_TOOL, null);
        }
    }

    private static String cap(String s) {
        return s != null && s.length() > MAX_RESULT_CHARS ? s.substring(0, MAX_RESULT_CHARS) : s;
    }
}
