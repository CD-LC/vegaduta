package com.agenticai.core.brief;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable log of an apply ({@code brief_apply_step}) and its ref to entity map
 * ({@code brief_binding}). Every method is one short transaction of its own - the apply engine
 * writes a step BEFORE it calls the real service and updates it AFTER, so a crash between the two
 * leaves a PENDING row naming exactly what may exist. {@link JpaBriefApplyLedger} is the real
 * implementation; tests use an in-memory one.
 *
 * <p>A job is a row of kind {@link #JOB_KIND} (seq 0) whose status is the job's status; its steps
 * are the rows that follow. A step that was skipped or degraded is DONE with no entity id and its
 * reason in {@code error} (a note, not an error - the reason is prefixed {@code SKIPPED:}).
 */
public interface BriefApplyLedger {

    String JOB_KIND = "JOB";

    void startJob(UUID briefId, int version, UUID jobId);

    void finishJob(UUID briefId, UUID jobId, boolean succeeded, String message);

    UUID begin(UUID briefId, int version, UUID jobId, int seq, String kind, String ref);

    /** The real service returned an entity; record it now so a failure later in the step can still undo it. */
    void created(UUID stepId, String entityId);

    void done(UUID stepId, String entityId, String note);

    void failed(UUID stepId, String error);

    void compensated(UUID stepId);

    void compensationFailed(UUID stepId, String error);

    Map<String, Binding> bindings(UUID briefId);

    void bind(UUID briefId, String ref, String kind, String entityId);

    void unbind(UUID briefId, String ref);

    /** Steps of one job (excluding the job row), in seq order. */
    List<StepView> steps(UUID briefId, UUID jobId);

    Optional<JobView> job(UUID briefId, UUID jobId);

    /** The newest job that finished successfully - the version currently applied. */
    Optional<JobView> latestSuccessfulJob(UUID briefId);

    /** A job still PENDING that started after {@code notBefore} (older ones are treated as crashed). */
    Optional<JobView> runningJob(UUID briefId, Instant notBefore);

    record Binding(String kind, String entityId) {
    }

    record StepView(int seq, String kind, String ref, String entityId, String status, String error) {
    }

    record JobView(UUID jobId, int version, String status, String message, Instant startedAt) {
    }
}
