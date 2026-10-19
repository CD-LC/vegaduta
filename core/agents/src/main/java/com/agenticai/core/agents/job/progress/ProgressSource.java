package com.agenticai.core.agents.job.progress;

/**
 * Who wrote a progress note.
 *
 * <p>Kept as a stored column rather than inferred, because the two carry
 * different TRUST. A {@link #RUNTIME} note is a fact the platform observed ("job
 * claimed by worker agent-job-3", "turn 1 of 1 starting"). An {@link #AGENT}
 * note is a claim the model made about its own work ("finished reconciling the
 * invoices") - useful, often the only human-readable signal a long job produces,
 * and exactly as reliable as the model is. An operator deciding whether to
 * cancel a job that has been quiet for six minutes needs to know which kind they
 * are reading; collapsing them into one undifferentiated stream would hide that.
 */
public enum ProgressSource {

    /** Written by the model calling {@code report_progress}. A claim, not an observation. */
    AGENT,

    /** Written by the platform at a lifecycle point. An observation. */
    RUNTIME
}
