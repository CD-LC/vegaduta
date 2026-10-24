package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.ownership.JobLeasePolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code app.agent-jobs.*} block (see {@code application.yml}).
 *
 * <p>Carries keys this PR does not read - {@code schedule.*} belongs to the SCHEDULES PR, {@code
 * output-schema.*} and {@code progress.*} to the OBSERVABILITY one. That is on purpose: {@code
 * application.yml} is a large shared file and this workstream opens it exactly ONCE, here, rather
 * than three times in three parallel sessions. Unused-here does not mean dead - each key is
 * documented at the point of its future use in the yml itself.
 *
 * <p>Registered by {@code @Component} + {@code @ConfigurationProperties} rather than a module
 * {@code @Configuration} class (the pattern {@code VisionProperties}/{@code ToolSandboxProperties}
 * use) because this needs no conditional wiring and this package is inside {@code
 * CoreApplication}'s scan root. There is no application-wide {@code @ConfigurationPropertiesScan}.
 */
@Component
@ConfigurationProperties(prefix = "app.agent-jobs")
public class AgentJobProperties {

    /**
     * Size of the bounded worker pool - see {@link AgentJobExecutor} for why the bound IS the
     * feature. Must stay comfortably under Hikari's {@code maximum-pool-size} (15) since every
     * worker holds a JPA connection intermittently across a multi-minute LLM call.
     */
    private int maxConcurrent = 4;

    /**
     * Ceiling on how many of those workers ONE tenant may occupy, so a single tenant submitting
     * twenty jobs cannot starve every other tenant on the replica.
     */
    private int maxConcurrentPerTenant = 2;

    /**
     * Hand-off queue depth in front of the pool. Overflow is NOT an error and NOT a 429: the row
     * simply stays QUEUED in Postgres and {@link AgentJobRunner} picks it up on its next tick.
     */
    private int queueCapacity = 8;

    private Duration defaultTimeout = Duration.ofMinutes(10);

    private Duration maxTimeout = Duration.ofMinutes(60);

    /**
     * A job left QUEUED longer than this is FAILED rather than run. Its requester authority was
     * frozen at submit time, and executing an hour-old snapshot of someone's roles means acting on
     * permissions nobody re-checked.
     */
    private Duration queueTtl = Duration.ofHours(1);

    /** Turns per job when the request does not say. 1: a job is a single turn by default. */
    private int maxTurns = 1;

    /**
     * The most turns a single job may be submitted with ({@code AgentJobStartRequest.maxTurns} is
     * clamped to it). Caps the per-job spend of the {@code continue_in_background} handoff and of
     * API callers: one job can never ask for more turns than this, whatever the caller writes.
     */
    private int maxTurnsCap = 8;

    private Duration pollInterval = Duration.ofSeconds(10);

    private Duration heartbeatInterval = Duration.ofSeconds(15);

    // app.agent-jobs.deadline-sweep-interval (15s) backs the deadline and orphan sweeps of
    // AgentJobRunner; it is read
    // by their @Scheduled placeholders alone, so it has no field here (a field would be a second,
    // unread copy).

    /**
     * How long a shutting-down replica waits for its interrupted job workers to unwind before it
     * releases their ownership leases ({@code AgentJobExecutor#quiesceForShutdown}). Keep it well
     * inside the container's SIGTERM grace period (30 s on the default deployment). A worker stuck
     * in non-interruptible I/O simply times out here; the release still happens.
     */
    private Duration quiesceWorkerWait = Duration.ofSeconds(3);

    /**
     * Where a freshly submitted job first runs. {@link RunNowMode#INLINE} (the default, and today's
     * behaviour) hands the row to this JVM's own worker pool the moment it is written. {@link
     * RunNowMode#ENQUEUE} only writes the durable QUEUED row and leaves it for the poller ({@code
     * AgentJobRunner}) of a different deployment shape - the API shape of a split deployment sets
     * this so no agent turn ever runs on an interactive-chat JVM.
     */
    private RunNowMode runNowMode = RunNowMode.INLINE;

    /**
     * When {@code true}, the heartbeat tick also interrupts a locally-running job whose row another
     * replica has already driven to a terminal status (the deadline sweep's TIMEOUT, the orphan
     * sweep's ORPHANED). Off by default: with one process the sweep that failed the row interrupts
     * the worker itself. Needed once sweeps and workers can live in different pods.
     */
    private boolean interruptOnExternalTerminal = false;

    private final OutputSchema outputSchema = new OutputSchema();
    private final Progress progress = new Progress();
    private final Schedule schedule = new Schedule();
    private final AgentJobTriggerProperties trigger = new AgentJobTriggerProperties();
    private final Approval approval = new Approval();
    private final AgentJobDurabilityProperties durability = new AgentJobDurabilityProperties();
    private final Ownership ownership = new Ownership();

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxConcurrentPerTenant() {
        return maxConcurrentPerTenant;
    }

    public void setMaxConcurrentPerTenant(int maxConcurrentPerTenant) {
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    public void setMaxTimeout(Duration maxTimeout) {
        this.maxTimeout = maxTimeout;
    }

    public Duration getQueueTtl() {
        return queueTtl;
    }

    public void setQueueTtl(Duration queueTtl) {
        this.queueTtl = queueTtl;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getMaxTurnsCap() {
        return maxTurnsCap;
    }

    public void setMaxTurnsCap(int maxTurnsCap) {
        this.maxTurnsCap = maxTurnsCap;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getQuiesceWorkerWait() {
        return quiesceWorkerWait;
    }

    public void setQuiesceWorkerWait(Duration quiesceWorkerWait) {
        if (quiesceWorkerWait != null && !quiesceWorkerWait.isNegative()) {
            this.quiesceWorkerWait = quiesceWorkerWait;
        }
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public RunNowMode getRunNowMode() {
        return runNowMode;
    }

    public void setRunNowMode(RunNowMode runNowMode) {
        this.runNowMode = runNowMode == null ? RunNowMode.INLINE : runNowMode;
    }

    public boolean isInterruptOnExternalTerminal() {
        return interruptOnExternalTerminal;
    }

    public void setInterruptOnExternalTerminal(boolean interruptOnExternalTerminal) {
        this.interruptOnExternalTerminal = interruptOnExternalTerminal;
    }

    /** {@code app.agent-jobs.run-now-mode} - see {@link #runNowMode}. */
    public enum RunNowMode {
        INLINE,
        ENQUEUE
    }

    public OutputSchema getOutputSchema() {
        return outputSchema;
    }

    public Progress getProgress() {
        return progress;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public AgentJobTriggerProperties getTrigger() {
        return trigger;
    }

    public Approval getApproval() {
        return approval;
    }

    public AgentJobDurabilityProperties getDurability() {
        return durability;
    }

    /** {@code app.agent-jobs.ownership.*} - cross-replica lease/adoption, default OFF. */
    public Ownership getOwnership() {
        return ownership;
    }

    /**
     * The interval the in-process heartbeat tick actually runs at. With ownership OFF (the default)
     * this is exactly {@link #getHeartbeatInterval()}, i.e. today's behaviour; with it ON the
     * validated {@code ownership.heartbeat-seconds} wins so the lease is renewed several times per
     * lease period.
     */
    public Duration effectiveHeartbeatInterval() {
        if (!ownership.isEnabled()) {
            return heartbeatInterval;
        }
        return Duration.ofSeconds(ownership.effectiveTiming(heartbeatInterval).heartbeatSeconds());
    }

    /**
     * How long a RUNNING row may go without a heartbeat before the orphan sweep treats it as
     * abandoned. Derived rather than configured: it is only ever meaningful as a multiple of the
     * heartbeat interval, and a separate knob would let an operator set the two into a combination
     * that reaps live jobs. Floored at two minutes so a GC pause or a slow tick can never reap a
     * job that is genuinely running.
     */
    public Duration staleHeartbeatAfter() {
        Duration derived = heartbeatInterval.multipliedBy(5);
        return derived.compareTo(Duration.ofMinutes(2)) < 0 ? Duration.ofMinutes(2) : derived;
    }

    /** {@code app.agent-jobs.output-schema.*} - read by the OBSERVABILITY PR. */
    public static class OutputSchema {

        private int maxRepairAttempts = 2;
        private int maxSchemaBytes = 32768;

        public int getMaxRepairAttempts() {
            return maxRepairAttempts;
        }

        public void setMaxRepairAttempts(int maxRepairAttempts) {
            this.maxRepairAttempts = maxRepairAttempts;
        }

        public int getMaxSchemaBytes() {
            return maxSchemaBytes;
        }

        public void setMaxSchemaBytes(int maxSchemaBytes) {
            this.maxSchemaBytes = maxSchemaBytes;
        }
    }

    /** {@code app.agent-jobs.progress.*} - read by the OBSERVABILITY PR. */
    public static class Progress {

        private int maxNotesPerJob = 200;
        private int maxNoteChars = 500;
        private int dedupeWindowSeconds = 30;

        public int getMaxNotesPerJob() {
            return maxNotesPerJob;
        }

        public void setMaxNotesPerJob(int maxNotesPerJob) {
            this.maxNotesPerJob = maxNotesPerJob;
        }

        public int getMaxNoteChars() {
            return maxNoteChars;
        }

        public void setMaxNoteChars(int maxNoteChars) {
            this.maxNoteChars = maxNoteChars;
        }

        public int getDedupeWindowSeconds() {
            return dedupeWindowSeconds;
        }

        public void setDedupeWindowSeconds(int dedupeWindowSeconds) {
            this.dedupeWindowSeconds = dedupeWindowSeconds;
        }
    }

    /** {@code app.agent-jobs.schedule.*} - read by the SCHEDULES PR. */
    public static class Schedule {

        private int skipNotificationThreshold = 3;
        private Duration blockedAlertAfter = Duration.ofHours(4);
        private int maxPerTenant = 25;
        private int minIntervalMinutes = 5;

        public int getSkipNotificationThreshold() {
            return skipNotificationThreshold;
        }

        public void setSkipNotificationThreshold(int skipNotificationThreshold) {
            this.skipNotificationThreshold = skipNotificationThreshold;
        }

        public Duration getBlockedAlertAfter() {
            return blockedAlertAfter;
        }

        public void setBlockedAlertAfter(Duration blockedAlertAfter) {
            this.blockedAlertAfter = blockedAlertAfter;
        }

        public int getMaxPerTenant() {
            return maxPerTenant;
        }

        public void setMaxPerTenant(int maxPerTenant) {
            this.maxPerTenant = maxPerTenant;
        }

        public int getMinIntervalMinutes() {
            return minIntervalMinutes;
        }

        public void setMinIntervalMinutes(int minIntervalMinutes) {
            this.minIntervalMinutes = minIntervalMinutes;
        }
    }

    /** {@code app.agent-jobs.approval.*} - read by the APPROVAL-PARKING PR. */
    public static class Approval {

        /**
         * How long a parked job waits for a human before it is FAILED with {@code
         * APPROVAL_TIMEOUT}. A day, because the whole point of parking is that a job may
         * legitimately wait for someone's working hours - but bounded, because a job parked forever
         * holds its schedule's overlap guard shut forever.
         */
        private Duration parkTimeout = Duration.ofHours(24);

        /**
         * How many times ONE job may park. Each park buys exactly one more turn (see {@code
         * AgentJobExecution#runTurns}) and each resumed turn charges the requester's budget again,
         * so this is a real spend bound, not bookkeeping.
         */
        private int maxParksPerJob = 3;

        /**
         * The sweep is the GUARANTEE that an approved job resumes. There is deliberately no
         * in-process fast path: the {@code mcp_tool.} action-type family is already owned by {@code
         * AgentToolCallApprovalExecutor} and {@code ApprovalActionExecutorRegistry#find} resolves
         * exactly one executor per action type, so this tick is the ONLY resume mechanism and this
         * value is the upper bound on resume latency.
         */
        private Duration sweepInterval = Duration.ofSeconds(60);

        /**
         * How long a decided-but-not-yet-executed approval is left alone before the sweep resumes
         * the job anyway. {@code HumanApprovalGateService#approve} commits the decision and only
         * then replays the gated tool, outside any transaction, so for a while the request is
         * APPROVED with no result; resuming inside that window costs a billed turn that re-asks for
         * the same gate or races the replay into a duplicate side effect. Waiting forever would
         * strand a job whose replay died with its JVM, so the wait is bounded. Raise it for tools
         * that replay slowly (long builds).
         */
        private Duration replayGrace = Duration.ofSeconds(90);

        /**
         * The failure message of a job whose blocking approval request expired undecided ({@code
         * app.approvals.expiry}) while {@code
         * app.approvals.continuation.jobs-continue-after-denial} is off. {@code {label}} is the
         * {@code requested_by} label to look the request up under.
         */
        private String expiredFailureMessage =
                "An approval request this job stopped on expired without a decision, "
                        + "so the job cannot continue. See the approval request recorded against '{label}' for the reason.";

        public Duration getReplayGrace() {
            return replayGrace;
        }

        public void setReplayGrace(Duration replayGrace) {
            if (replayGrace != null && !replayGrace.isNegative()) {
                this.replayGrace = replayGrace;
            }
        }

        public String getExpiredFailureMessage() {
            return expiredFailureMessage;
        }

        public void setExpiredFailureMessage(String expiredFailureMessage) {
            if (expiredFailureMessage != null && !expiredFailureMessage.isBlank()) {
                this.expiredFailureMessage = expiredFailureMessage;
            }
        }

        public Duration getParkTimeout() {
            return parkTimeout;
        }

        public void setParkTimeout(Duration parkTimeout) {
            this.parkTimeout = parkTimeout;
        }

        public int getMaxParksPerJob() {
            return maxParksPerJob;
        }

        public void setMaxParksPerJob(int maxParksPerJob) {
            this.maxParksPerJob = maxParksPerJob;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }
    }

    /**
     * {@code app.agent-jobs.ownership.*} - cross-replica ownership lease + adoption of jobs whose
     * replica died or was replaced. Default OFF: with {@code enabled=false} nothing here changes
     * behaviour.
     *
     * <p>With {@code enabled=true} the lease sweep REPLACES the legacy heartbeat orphan sweep
     * ({@code AgentJobRunner#reapOrphans} is skipped), so one planner makes every orphan decision:
     * the bound is {@code resume-max-attempts} (not {@code durability.max-attempts}) and {@code
     * durability.requeue-zero-work-orphans=false} still means "never re-queue an orphan, fail it".
     */
    public static class Ownership {

        /** Master switch. OFF = today's behaviour (the 2-minute heartbeat orphan sweep only). */
        private boolean enabled = false;

        /**
         * A RUNNING job whose heartbeat is older than this (plus a small skew grace) is adoptable.
         * Floored.
         */
        private int leaseSeconds = 60;

        /** How often a running job's lease is renewed. The lease is floored at three of these. */
        private int heartbeatSeconds = 15;

        /**
         * Total claims of one job (including the first) after which the lease sweep stops
         * re-queuing it and FAILS it with {@code RESUME_ATTEMPTS_EXHAUSTED}: a poison job must not
         * loop forever.
         */
        private int resumeMaxAttempts = 3;

        /** Cadence of the lease sweep (every replica, advisory-lock guarded). */
        private Duration sweepInterval = Duration.ofSeconds(60);

        /** Max expired leases handled per tenant per sweep tick. */
        private int sweepBatch = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public int getResumeMaxAttempts() {
            return resumeMaxAttempts;
        }

        public void setResumeMaxAttempts(int resumeMaxAttempts) {
            this.resumeMaxAttempts = resumeMaxAttempts;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }

        public int getSweepBatch() {
            return sweepBatch;
        }

        public void setSweepBatch(int sweepBatch) {
            this.sweepBatch = sweepBatch;
        }

        /** The validated lease/heartbeat pair, given the legacy heartbeat interval. */
        public JobLeasePolicy.Timing effectiveTiming(Duration legacyHeartbeatInterval) {
            long legacy =
                    legacyHeartbeatInterval == null
                            ? 15L
                            : Math.max(1L, legacyHeartbeatInterval.toSeconds());
            return JobLeasePolicy.effectiveTiming(
                    leaseSeconds, heartbeatSeconds, (int) Math.min(legacy, 3600L));
        }

        /** Attempts bound, never below 1. */
        public int effectiveResumeMaxAttempts() {
            return Math.max(1, resumeMaxAttempts);
        }

        public int effectiveSweepBatch() {
            return Math.max(1, Math.min(sweepBatch, 500));
        }
    }
}
