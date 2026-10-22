package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The resume half of the Wait node: wakes a parked run when its wake time has passed ({@link
 * #sweepDue}, called per tenant by {@link WorkflowWaitWakeJob}) or when its signed resume URL is
 * called ({@link #resumeByWebhook}). Both funnel through {@link #wake}, which claims the {@code
 * workflow_wait} row first (WAITING -> RESUMED, atomic, so a sweep racing the URL - or two replicas
 * sweeping - resumes the run exactly once) and then hands the run to {@link
 * WorkflowBackgroundRunService#resumeRun}, the same PAUSED -> RUNNING CAS + re-entry an operator
 * Resume uses. A run that is no longer parked at the wait's checkpoint (cancelled, resumed by an
 * operator) retires the row instead of being woken.
 *
 * <p>The caller must have set the {@code TenantContext} (the sweep via {@code
 * TenantSchemaIterator}, the public controller from the token's tenant claim).
 */
@Service
public class WorkflowWaitResumeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWaitResumeService.class);

    /** Rows handled per tenant per sweep; the remainder is picked up on the next tick. */
    static final int SWEEP_BATCH = 50;

    /**
     * A WAITING row whose run never became PAUSED within this long is an orphan (crash between
     * register and park).
     */
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(10);

    /** What a resume-URL call did. */
    public enum ResumeOutcome {
        RESUMED,
        NOT_WAITING
    }

    private enum Reason {
        TIME_REACHED,
        WEBHOOK
    }

    private final WorkflowWaitRepository waits;
    private final WorkflowRunRepository runs;
    private final WorkflowBackgroundRunService backgroundRuns;
    private final ObjectMapper objectMapper;

    public WorkflowWaitResumeService(
            WorkflowWaitRepository waits,
            WorkflowRunRepository runs,
            WorkflowBackgroundRunService backgroundRuns,
            ObjectMapper objectMapper) {
        this.waits = waits;
        this.runs = runs;
        this.backgroundRuns = backgroundRuns;
        this.objectMapper = objectMapper;
    }

    /** Wakes every due wait of the current tenant; returns how many runs were resumed or failed. */
    public int sweepDue(Instant now) {
        List<WorkflowWait> due =
                waits.findByStatusAndWakeAtLessThanEqualOrderByWakeAtAsc(
                        WorkflowWait.Status.WAITING, now, PageRequest.of(0, SWEEP_BATCH));
        int handled = 0;
        for (WorkflowWait wait : due) {
            try {
                if (wake(wait, Reason.TIME_REACHED, null, now)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                // One bad row must not stop the rest of the batch; it is retried on the next tick.
                log.error(
                        "Failed to wake workflow wait {} (run {})",
                        wait.getId(),
                        wait.getRunId(),
                        e);
            }
        }
        return handled;
    }

    /** The public resume URL was called with a valid token for {@code runId}/{@code nodeId}. */
    public ResumeOutcome resumeByWebhook(UUID runId, String nodeId, Map<String, Object> body) {
        WorkflowWait wait =
                waits.findFirstByRunIdAndStatus(runId, WorkflowWait.Status.WAITING).orElse(null);
        if (wait == null
                || (!WorkflowWaitResumeTokens.ANY_NODE.equals(nodeId)
                        && !wait.getNodeId().equals(nodeId))
                || !WorkflowWaitService.Mode.WEBHOOK.name().equals(wait.getMode())) {
            return ResumeOutcome.NOT_WAITING;
        }
        return wake(wait, Reason.WEBHOOK, body == null ? Map.of() : body, Instant.now())
                ? ResumeOutcome.RESUMED
                : ResumeOutcome.NOT_WAITING;
    }

    /**
     * @return true when this call resumed (or failed) the run; false when it left the run alone.
     */
    private boolean wake(WorkflowWait wait, Reason reason, Object payload, Instant now) {
        WorkflowRun run = runs.findById(wait.getRunId()).orElse(null);
        if (run == null) {
            retire(wait, now);
            return false;
        }
        if (run.getStatus() == WorkflowRunStatus.RUNNING) {
            // The row is written just BEFORE the run is parked, so RUNNING is normal for a moment.
            // Only a row that
            // stays unparked for ORPHAN_AFTER is a crash leftover.
            if (wait.getCreatedAt() != null
                    && wait.getCreatedAt().isBefore(now.minus(ORPHAN_AFTER))) {
                retire(wait, now);
            }
            return false;
        }
        if (run.getStatus() != WorkflowRunStatus.PAUSED
                || !wait.getResumeNodeId().equals(run.getPausedNodeId())) {
            // Cancelled, finished, or resumed by an operator (and possibly parked somewhere else
            // since).
            retire(wait, now);
            return false;
        }
        boolean timedOut =
                reason == Reason.TIME_REACHED
                        && WorkflowWaitService.Mode.WEBHOOK.name().equals(wait.getMode());
        if (waits.claim(wait.getId(), WorkflowWait.Status.WAITING, WorkflowWait.Status.RESUMED, now)
                == 0) {
            return false; // the sweep or the URL (or another replica) got there first
        }
        if (timedOut && wait.isFailOnTimeout()) {
            failTimedOut(run, wait);
            return true;
        }
        String resumeInput = resumeInput(wait, run.getPausedNodeInput(), reason, timedOut, payload);
        String trace = traceWithFinishedEntry(run, wait, finishedSummary(reason, timedOut), now);
        if (runs.updateParkedCheckpoint(run.getId(), WorkflowRunStatus.PAUSED, trace, resumeInput)
                == 0) {
            return false; // an operator cancelled/resumed between the read and here; their claim
            // stands
        }
        backgroundRuns.resumeRun(run.getWorkflowId(), run.getId());
        return true;
    }

    private void retire(WorkflowWait wait, Instant now) {
        waits.claim(wait.getId(), WorkflowWait.Status.WAITING, WorkflowWait.Status.CANCELLED, now);
    }

    private void failTimedOut(WorkflowRun run, WorkflowWait wait) {
        int claimed =
                runs.compareAndSetStatus(
                        run.getId(), WorkflowRunStatus.PAUSED, WorkflowRunStatus.FAILED);
        if (claimed == 0) {
            return;
        }
        WorkflowRun fresh = runs.findById(run.getId()).orElse(run);
        String message =
                "Wait node timed out: no call to the resume URL arrived before the timeout.";
        String trace = traceWithFinishedEntry(fresh, wait, message, Instant.now(), "ERROR");
        fresh.clearPauseEnvelope();
        fresh.complete(
                WorkflowRunStatus.FAILED,
                trace,
                message,
                fresh.getOutputFileName(),
                fresh.getOutputData());
        runs.save(fresh);
        log.info(
                "Workflow run {} failed: its webhook-mode Wait node '{}' timed out.",
                run.getId(),
                wait.getNodeId());
    }

    /**
     * Webhook resume: {@code {"resume": <posted JSON>, "input": "<upstream input>"}}.
     * Timeout-and-continue: {@code {"timedOut": true, "input": "<upstream input>"}}. Duration /
     * until: the upstream input untouched.
     */
    private String resumeInput(
            WorkflowWait wait,
            String pausedInput,
            Reason reason,
            boolean timedOut,
            Object payload) {
        if (!WorkflowWaitService.Mode.WEBHOOK.name().equals(wait.getMode())) {
            return pausedInput;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        if (timedOut) {
            envelope.put("timedOut", true);
        } else {
            envelope.put("resume", payload == null ? Map.of() : payload);
        }
        envelope.put("input", pausedInput == null ? "" : pausedInput);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return pausedInput;
        }
    }

    private static String finishedSummary(Reason reason, boolean timedOut) {
        if (reason == Reason.WEBHOOK) {
            return "Resumed by a call to the resume URL.";
        }
        return timedOut
                ? "Webhook wait timed out - continuing the run (onTimeout=continue)."
                : "Wait finished - wake time reached.";
    }

    private String traceWithFinishedEntry(
            WorkflowRun run, WorkflowWait wait, String summary, Instant now) {
        return traceWithFinishedEntry(run, wait, summary, now, "OK");
    }

    /**
     * The run's trace plus one entry saying the wait is over. An unreadable stored trace is
     * returned untouched - history is never destroyed to add a bookkeeping row.
     */
    private String traceWithFinishedEntry(
            WorkflowRun run, WorkflowWait wait, String summary, Instant now, String status) {
        String stored = run.getTraceJson();
        List<NodeTraceEntry> trace;
        try {
            trace =
                    stored == null || stored.isBlank()
                            ? new ArrayList<>()
                            : new ArrayList<>(
                                    objectMapper.readValue(
                                            stored, new TypeReference<List<NodeTraceEntry>>() {}));
            // The node's own label, taken from the AWAITING_WAIT row the engine wrote when it
            // parked the run.
            String label = "Wait";
            for (NodeTraceEntry entry : trace) {
                if (wait.getNodeId().equals(entry.nodeId())
                        && entry.label() != null
                        && !entry.label().isBlank()) {
                    label = entry.label();
                }
            }
            trace.add(
                    new NodeTraceEntry(
                            wait.getNodeId(), "wait", label, status, summary, now, now, List.of()));
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            return stored;
        }
    }
}
