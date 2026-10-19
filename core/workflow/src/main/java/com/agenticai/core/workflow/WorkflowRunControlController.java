package com.agenticai.core.workflow;

import com.agenticai.core.chat.LlmRequestSnapshot;
import com.agenticai.core.chat.LlmRequestSnapshotStore;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One run of a workflow, split out of {@link WorkflowController} with its URLs unchanged: the run with its trace,
 * the model requests its agent nodes sent, the live event stream, and the Stop / Pause / Resume controls. The
 * access checks are per method, exactly as before (VIEWER to read, EDITOR for the model requests, RUNNER to
 * control the run), always against the path's {@code workflowId}, and every lookup is scoped by both ids.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/runs/{runId}")
public class WorkflowRunControlController {

    private final WorkflowService workflowService;
    private final WorkflowBackgroundRunService backgroundRunService;
    private final WorkflowRunStreamHub streamHub;

    public WorkflowRunControlController(
            WorkflowService workflowService,
            WorkflowBackgroundRunService backgroundRunService,
            WorkflowRunStreamHub streamHub) {
        this.workflowService = workflowService;
        this.backgroundRunService = backgroundRunService;
        this.streamHub = streamHub;
    }

    @GetMapping
    public WorkflowRunResponse getRun(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER, gated on the
        // path's workflowId.
        //
        // Security audit 2026-07-29 (F3): the run is now loaded BY BOTH ids
        // (WorkflowService#getRun -> findByIdAndWorkflowId), so the id this
        // check ran against is necessarily the id the run belongs to. Before,
        // the run was fetched by runId alone, so passing your own workflowId
        // plus someone else's runId passed the check and returned their run -
        // trace included. 404 on mismatch.
        //
        // What the trace exposes: per-node status, summary, timings and log
        // lines, and (since 2026-09-29) each node's input/output - capped at
        // 16 KB per field and secret-redacted by NodeTracePayloads before it
        // is stored, because VIEWER is enough to read it - plus tokens/cost
        // for agent/supervisor nodes (null on FREE plans). Runs recorded
        // before that date carry no inputs/outputs.
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        return workflowService.getRun(workflowId, runId);
    }

    /**
     * Every model request an agent/supervisor node of this run sent (final prompt, options, tools),
     * oldest first - the run viewer's "What was sent" tab. EDITOR, not VIEWER like {@link #getRun}:
     * prompts carry system instructions, recalled memory and user data, more than the redacted node
     * payloads do. In-memory and short-lived (see {@link LlmRequestSnapshotStore}); empty after a restart or for steps served
     * from the step cache. {@code from}/{@code to} narrow to one node's time window.
     */
    @GetMapping("/llm-requests")
    public List<LlmRequestSnapshot> runLlmRequests(
            @PathVariable UUID workflowId,
            @PathVariable UUID runId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
        workflowService.getRun(workflowId, runId); // 404 unless the run belongs to this workflow
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return LlmRequestSnapshotStore.INSTANCE.list(
                tenantId, runId.toString(), null, from, to);
    }

    /**
     * Live run events (phase 3, agent-native canvas) as Server-Sent Events: a {@code snapshot}
     * first, then node.started / node.token / node.tool_call / node.tool_result / node.completed
     * (with per-node and running cost) / run.awaiting / run.finished - the full vocabulary is on
     * {@link WorkflowRunStreamHub}. VIEWER, same bar as {@link #getRun}; the streamed payloads
     * carry nothing the stored trace does not (tokens are redacted before they stream).
     *
     * <p>May be opened BEFORE the run starts: POST {@code /run} with a {@code clientRunId}, open
     * this with that id first, and every event of a synchronous run arrives live. Reconnect with
     * {@code Last-Event-ID} to replay missed events. Runs not executing in this process (Temporal
     * engine, another replica, already finished) degrade to a snapshot per stored change.
     */
    @GetMapping(
            value = "/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runEvents(
            @PathVariable UUID workflowId,
            @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        Optional<TenantContext> tenant = TenantContext.current();
        Supplier<WorkflowRunResponse> loader =
                () -> {
                    try {
                        return workflowService.getRun(workflowId, runId);
                    } catch (WorkflowRunNotFoundException e) {
                        return null;
                    }
                };
        Supplier<WorkflowRunResponse> poller =
                () -> {
                    // Runs on the hub's scheduler thread: re-establish the caller's
                    // tenant so the lookup hits the right schema.
                    tenant.ifPresent(TenantContext::set);
                    try {
                        return loader.get();
                    } finally {
                        TenantContext.clear();
                    }
                };
        Long lastSeq = null;
        if (StringUtils.hasText(lastEventId)) {
            try {
                lastSeq = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                // Not one of ours - replay everything.
            }
        }
        SseEmitter emitter =
                streamHub.subscribe(
                        tenant.map(TenantContext::getTenantId).orElse(null),
                        workflowId.toString(),
                        runId.toString(),
                        lastSeq,
                        loader.get(),
                        poller);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                // Proxies (nginx, and Cloudflare honours it too) must not buffer an event stream.
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /**
     * Stop Run - the canvas's "Stop" button. Best-effort cancellation signal, not a guaranteed
     * instant stop: see {@link WorkflowService#cancelRun}'s javadoc for the exact mechanism (a
     * Redis flag for the synchronous engine, Temporal's native cancellation for the Temporal
     * engine) and its documented limitations (checked only between node executions, fails open -
     * meaning "the stop didn't take effect" - if Redis is unreachable). Cancelling a run that isn't
     * RUNNING (already finished, or paused awaiting approval) is a no-op, returning the run's
     * unchanged current status rather than an error.
     */
    @PostMapping("/cancel")
    public WorkflowRunResponse cancelRun(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        // Cross-user resource sharing retrofit (Round 2) - RUNNER: stopping a
        // run is execution control, not a config edit, same bar as run().
        //
        // Security audit 2026-07-29 (F3): scoped by workflowId as well, so
        // this can no longer kill an arbitrary running workflow elsewhere in
        // the tenant via a borrowed runId. 404 on mismatch.
        workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
        return workflowService.cancelRun(workflowId, runId);
    }

    /**
     * Pause Run (V261) - parks a RUNNING run at the next node boundary; the returned row still
     * reads RUNNING until the traversal observes the flag (a node mid-flight finishes first - same
     * between-nodes semantics as Stop). Resumed via {@link #resumeRun}; still cancellable while
     * parked ({@link #cancelRun} flips PAUSED to CANCELLED directly). RUNNER - pausing is a gentler
     * form of the same "stop this run" operator power.
     */
    @PostMapping("/pause")
    public WorkflowRunResponse pauseRun(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
        return workflowService.pauseRun(workflowId, runId);
    }

    /**
     * Resume Run (V261) - re-enters a PAUSED run's traversal at the node it parked on, on the
     * background-run pool (the resumed leg can run for minutes - same reason {@code background:
     * true} exists). RUNNER - resuming is execute privilege, same as the Run button itself.
     */
    @PostMapping("/resume")
    public WorkflowRunResponse resumeRun(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
        return backgroundRunService.resumeRun(workflowId, runId);
    }
}
