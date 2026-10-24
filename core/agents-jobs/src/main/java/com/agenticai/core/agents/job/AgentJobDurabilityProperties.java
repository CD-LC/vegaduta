package com.agenticai.core.agents.job;

/**
 * {@code app.agent-jobs.durability.*} - read by the DURABILITY PR. Bound through
 * {@link AgentJobProperties#getDurability()}; the property names are the field names here.
 */
public class AgentJobDurabilityProperties {

    /**
     * Total claims of one job, INCLUDING the first. 2 means "one recovery
     * attempt". This is compared against {@code agent_job.attempt_count},
     * which both {@code claim} and {@code resumeClaim} increment.
     */
    private int maxAttempts = 2;

    /**
     * Whether an orphan that provably did ZERO chargeable work (no turns
     * used, no tool calls logged for its session) may be re-queued instead of
     * failed. Safe only under exactly that condition: there is no job-level
     * cost ledger, spend is metered per {@code AgentChatService.chat} call,
     * and tool side effects are not idempotent - so a job that had already
     * STARTED a turn must always fail rather than re-run.
     */
    private boolean requeueZeroWorkOrphans = true;

    /**
     * L1 step journal master switch (default ON since 2026-10-23): job turns, DURABLE tool effects and delegate_parallel
     * branches are journaled in Postgres ({@code agent_step_journal}, V376). Read by {@code StepJournal}
     * itself via {@code @Value}; mirrored here for documentation and the policy.
     */
    private boolean stepJournalEnabled = true;

    /**
     * With the journal on, an orphan that already STARTED turns may be re-queued (instead of ORPHANED) when
     * the journal proves its last turn COMPLETED - the resumed attempt reuses that result and charges
     * nothing. Default OFF.
     */
    private boolean resumeFromJournal = false;

    /**
     * Also re-queue an orphan whose last turn STARTED but never COMPLETED, re-running that turn. This can
     * re-bill one model call (there is no cost ledger); DURABLE tool effects are de-duplicated only when
     * {@code app.tools.effect-journal.enabled} is on. Default OFF; requires {@link #resumeFromJournal}.
     */
    private boolean resumeRerunIncompleteTurn = false;

    /**
     * Retention of {@code agent_step_journal} rows, in days (floored at 1). Every DURABLE tool call and turn
     * writes a row; the purge deletes rows older than this. Only runs when the journal is enabled.
     */
    private int stepJournalRetentionDays = 14;

    // app.agent-jobs.durability.step-journal-purge-interval (6h), how often the retention purge runs, is read by the
    // @Scheduled placeholder of StepJournalRetentionPurge alone; a field here would be a second, unread copy.

    public int getStepJournalRetentionDays() {
        return stepJournalRetentionDays;
    }

    public void setStepJournalRetentionDays(int stepJournalRetentionDays) {
        this.stepJournalRetentionDays = stepJournalRetentionDays;
    }

    public boolean isStepJournalEnabled() {
        return stepJournalEnabled;
    }

    public void setStepJournalEnabled(boolean stepJournalEnabled) {
        this.stepJournalEnabled = stepJournalEnabled;
    }

    public boolean isResumeFromJournal() {
        return resumeFromJournal;
    }

    public void setResumeFromJournal(boolean resumeFromJournal) {
        this.resumeFromJournal = resumeFromJournal;
    }

    public boolean isResumeRerunIncompleteTurn() {
        return resumeRerunIncompleteTurn;
    }

    public void setResumeRerunIncompleteTurn(boolean resumeRerunIncompleteTurn) {
        this.resumeRerunIncompleteTurn = resumeRerunIncompleteTurn;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isRequeueZeroWorkOrphans() {
        return requeueZeroWorkOrphans;
    }

    public void setRequeueZeroWorkOrphans(boolean requeueZeroWorkOrphans) {
        this.requeueZeroWorkOrphans = requeueZeroWorkOrphans;
    }
}
