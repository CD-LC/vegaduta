package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.http.AttachmentDisposition;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.audit.KeysetCursor;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import com.agenticai.core.workflow.diagram.WorkflowDiagramService;
import com.agenticai.core.workflow.dto.DiagramFormat;
import com.agenticai.core.workflow.dto.GenerateGraphRequest;
import com.agenticai.core.workflow.dto.GenerateGraphResponse;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.ValidationResult;
import com.agenticai.core.workflow.dto.WorkflowCostRoutingUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowCreateRequest;
import com.agenticai.core.workflow.dto.WorkflowDiagramResponse;
import com.agenticai.core.workflow.dto.WorkflowExecutionLimitsUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeTestRequest;
import com.agenticai.core.workflow.dto.WorkflowReasoningDepthUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowResponse;
import com.agenticai.core.workflow.dto.WorkflowRunRequest;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.dto.WorkflowScheduleRequest;
import com.agenticai.core.workflow.dto.WorkflowScheduleResponse;
import com.agenticai.core.workflow.dto.WorkflowStepCachingUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowTuningModeUpdateRequest;
import com.agenticai.core.workflow.dto.WorkflowUpdateRequest;
import com.agenticai.core.workflow.guidance.WorkflowInsightsService;
import com.agenticai.core.workflow.guidance.WorkflowLintService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Interactive Workflow Designer's backend surface: CRUD over saved graphs, a stateless {@code
 * /validate} the canvas can call as a user edits (before committing to Activate), and {@code /run}
 * - the canvas's "Play" button, invoking {@link WorkflowExecutionEngine} synchronously and
 * returning the full per-node trace in one response (see {@code WorkflowExecutionEngine}'s class
 * javadoc for why this is synchronous rather than Kafka-consumer-driven like a real channel trigger
 * will be). The CRUD/run/trace surface is open to any authenticated tenant member (then narrowed
 * per-workflow by {@link WorkflowService#requireAccess}), same posture as the MCP registry and
 * approvals endpoints.
 *
 * <p>The TRIGGER sub-resources are not: {@code SecurityConfig} gates every write under {@code
 * /webhook}, {@code /schedule} and {@code /trigger-channel} - plus the {@code /webhook} GET, which
 * returns the plaintext trigger secret - to {@code ROLE_tenant-admin}. The per-node ones live on
 * {@link WorkflowNodeTriggerController}; one run's read and control endpoints (trace, events, stop,
 * pause, resume) on {@link WorkflowRunControlController}. All URLs are unchanged by that split.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowGraphGeneratorService graphGeneratorService;
    private final WorkflowDiagramService diagramService;
    private final WorkflowDiagramPdfRenderer diagramPdfRenderer;
    private final WorkflowBackgroundRunService backgroundRunService;
    private final WorkflowNodeTestService nodeTestService;
    private final WorkflowLintService lintService;
    private final WorkflowInsightsService insightsService;

    // nlflow W7: dry-run of sends in draft runs. Field-injected so the constructor (driven directly
    // by the
    // controller tests) keeps its arity; a hand-built instance gets the same default as the
    // property.
    // Default OFF: a dry run persists RunMode.DRAFT_DRY_RUN on workflow_run.run_mode, an enum
    // constant the
    // previous image cannot read (expand/contract) - enable by env once this image is the rollback
    // target.
    @Value("${app.workflow.draft-run.simulate-sends.enabled:false}")
    private boolean simulateSendsEnabled = false;

    void setSimulateSendsEnabled(boolean enabled) {
        this.simulateSendsEnabled = enabled;
    }

    /**
     * Setter-injected (same reason {@code WorkflowService#pinStore} is): the
     * constructor's parameter list is a de-facto public API for tests that
     * build the controller positionally, and dedup is only needed on the
     * {@code Idempotency-Key} header path. Absent (a narrow test context)
     * the header is simply not honoured - same opt-in posture as {@code
     * InboundMessageDedupService}'s own null-id contract.
     */
    private InboundMessageDedupService runIdempotencyDedup;

    @Autowired
    void setRunIdempotencyDedup(InboundMessageDedupService dedupService) {
        this.runIdempotencyDedup = dedupService;
    }

    public WorkflowController(
            WorkflowService workflowService,
            WorkflowGraphGeneratorService graphGeneratorService,
            WorkflowDiagramService diagramService,
            WorkflowDiagramPdfRenderer diagramPdfRenderer,
            WorkflowBackgroundRunService backgroundRunService,
            WorkflowNodeTestService nodeTestService,
            WorkflowLintService lintService,
            WorkflowInsightsService insightsService) {
        this.workflowService = workflowService;
        this.graphGeneratorService = graphGeneratorService;
        this.diagramService = diagramService;
        this.diagramPdfRenderer = diagramPdfRenderer;
        this.backgroundRunService = backgroundRunService;
        this.nodeTestService = nodeTestService;
        this.lintService = lintService;
        this.insightsService = insightsService;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(
            @Valid @RequestBody WorkflowCreateRequest request) {
        WorkflowDefinition definition =
                workflowService.create(request.name(), request.description(), request.graph());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflowService.toResponse(definition));
    }

    @GetMapping
    public List<WorkflowResponse> list() {
        return workflowService.listAll().stream().map(workflowService::toResponse).toList();
    }

    @GetMapping("/{workflowId}")
    public WorkflowResponse get(@PathVariable UUID workflowId) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: reads full
        // config (graph, triggers).
        return workflowService.toResponse(
                workflowService.requireAccess(workflowId, PermissionLevel.VIEWER));
    }

    @PutMapping("/{workflowId}")
    public WorkflowResponse update(
            @PathVariable UUID workflowId, @Valid @RequestBody WorkflowUpdateRequest request) {
        WorkflowDefinition definition =
                workflowService.update(
                        workflowId,
                        request.name(),
                        request.description(),
                        request.graph(),
                        request.status());
        return workflowService.toResponse(definition);
    }

    @DeleteMapping("/{workflowId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workflowId) {
        workflowService.delete(workflowId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Subscription Plan &amp; GTM Strategy doc, Section 4, Decision 2 - a FREE tenant's only way to
     * change a locked (24h+) workflow: duplicate it into a fresh, editable DRAFT copy. See {@link
     * WorkflowService#duplicate}'s javadoc for why triggers aren't copied. Consumes a slot under
     * the same workflow-count cap {@link #create} enforces.
     */
    @PostMapping("/{workflowId}/duplicate")
    public ResponseEntity<WorkflowResponse> duplicate(@PathVariable UUID workflowId) {
        WorkflowDefinition copy = workflowService.duplicate(workflowId);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.toResponse(copy));
    }

    /** Body of {@link #lint}: the unsaved canvas graph, or omit it to lint the saved graph. */
    public record LintRequest(WorkflowGraphDto graph) {}

    /**
     * Canvas lint panel (phase 4): structured issues ({@code id, code, severity, nodeId, edgeId,
     * message, fix}) for the unsaved graph in the body, or the saved one. VIEWER - it reads, it
     * changes nothing. Rules and codes: {@link WorkflowLintService}.
     */
    @PostMapping("/{workflowId}/lint")
    public WorkflowLintService.LintResult lint(
            @PathVariable UUID workflowId, @RequestBody(required = false) LintRequest request) {
        workflowService.requireViewerAccess(workflowId);
        WorkflowGraphDto saved = workflowService.graph(workflowId);
        WorkflowGraphDto target =
                request != null && request.graph() != null ? request.graph() : saved;
        return lintService.lint(workflowId, target, saved);
    }

    /**
     * Canvas badges (phase 4): per-workflow and per-node run statistics over the last {@code days}
     * (default 30, max 90) plus agent eval scores. VIEWER, the same bar as run history. Shape:
     * {@link WorkflowInsightsService}.
     */
    @GetMapping("/{workflowId}/eval-summary")
    public Map<String, Object> evalSummary(
            @PathVariable UUID workflowId, @RequestParam(defaultValue = "30") int days) {
        workflowService.requireViewerAccess(workflowId);
        return insightsService.summary(workflowId, workflowService.graph(workflowId), days);
    }

    /** Structural governance check only - does not run anything. */
    @PostMapping("/{workflowId}/validate")
    public ValidationResult validate(@PathVariable UUID workflowId) {
        return workflowService.validate(workflowId);
    }

    /**
     * AI workflow generator (#392) - "customer should be able to type what he wants and
     * automatically it should get [built]". {@code workflowId} isn't actually read (this never
     * touches the saved definition or persists anything - see {@code
     * WorkflowGraphGeneratorService}'s javadoc), it just keeps this endpoint nested under the same
     * resource path as every other workflow-designer action (validate/run/webhook/ schedule) for
     * consistency. A structurally invalid generation surfaces as the same 400 + {@code errors}
     * shape the Validate Workflow button already produces (WorkflowValidationException), so the
     * canvas needs no new error-handling path for it.
     */
    @PostMapping("/{workflowId}/generate-graph")
    public GenerateGraphResponse generateGraph(
            @PathVariable UUID workflowId, @Valid @RequestBody GenerateGraphRequest request) {
        WorkflowDefinition definition = workflowService.get(workflowId);
        ReasoningDepth depth =
                definition.getReasoningDepth() != null
                        ? definition.getReasoningDepth()
                        : ReasoningDepth.FAST;
        return graphGeneratorService.generate(request.instruction(), depth, request.currentGraph());
    }

    /**
     * The canvas's "Play" button. Synchronous by default (returns the full node trace); {@code
     * background: true} returns the RUNNING row immediately instead - see {@code
     * WorkflowRunRequest#background}.
     *
     * <p>Cross-user resource sharing retrofit (Round 2) - RUNNER check HERE, deliberately not
     * inside {@link WorkflowService#run} itself: this controller method is only ever reached by a
     * real authenticated tenant member (web console / direct API call), unlike {@code
     * WorkflowService#run}, which is ALSO the shared primitive every autonomous trigger (schedule,
     * webhook, channel, sub-workflow node) calls with no human actor to check - see that method's
     * own javadoc for the full reasoning, same distinction {@code AgentController#chat} draws for
     * the sibling module.
     */
    @PostMapping("/{workflowId}/run")
    public WorkflowRunResponse run(
            @PathVariable UUID workflowId,
            @Valid @RequestBody(required = false) WorkflowRunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Principal principal) {
        RunMode mode = dryRunModeIfAsked(parseRunMode(request), request);
        // A draft run executes the live, unpublished graph (and any pins), so it is an editing
        // tool: RUNNER
        // (execute-only) keeps to the promoted PRODUCTION version, as before draft mode existed.
        workflowService.requireAccess(
                workflowId, mode.honoursPins() ? PermissionLevel.EDITOR : PermissionLevel.RUNNER);
        // Client-supplied idempotency (Stripe-style) for headless callers
        // retrying on timeouts: scoped to (workflow, caller, key) so a retry
        // with the same key 409s instead of starting a second run. Not stored
        // alongside the run - callers that need the original run id should
        // send clientRunId, which run() already dedupes on.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            if (idempotencyKey.length() > 128) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most 128 characters.");
            }
            String caller = principal != null ? principal.getName() : "anonymous";
            if (runIdempotencyDedup != null
                    && !runIdempotencyDedup.tryClaim(
                            "workflow-run", workflowId + ":" + caller + ":" + idempotencyKey.strip())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This Idempotency-Key was already used for this workflow run request.");
            }
        }
        String input = request != null ? request.input() : null;
        String requestedBy =
                request != null && StringUtils.hasText(request.requestedBy())
                        ? request.requestedBy()
                        : (principal != null ? principal.getName() : null);
        UUID clientRunId = request != null ? request.clientRunId() : null;
        // SE-14: a separate field, stored on the run; never concatenated into the node input.
        String comment = request != null && StringUtils.hasText(request.comment()) ? request.comment() : null;
        List<ChatInlineAttachment> attachments =
                request != null && request.attachments() != null
                        ? request.attachments()
                        : List.of();
        if (request != null && Boolean.TRUE.equals(request.background())) {
            // Fire-and-poll (see WorkflowRunRequest#background's javadoc):
            // returns the freshly persisted RUNNING row; the caller polls
            // GET /{workflowId}/runs/{runId} to completion. RUNNER was
            // already checked above - same gate as the blocking path.
            return comment == null
                    ? backgroundRunService.start(
                            workflowId, input, requestedBy, clientRunId, attachments, mode)
                    : backgroundRunService.start(
                            workflowId, input, requestedBy, clientRunId, attachments, mode, comment);
        }
        return comment == null
                ? workflowService.run(
                        workflowId, null, input, requestedBy, clientRunId, attachments, mode)
                : workflowService.run(
                        workflowId, null, input, requestedBy, clientRunId, attachments, mode, comment);
    }

    /**
     * The request's optional {@code mode}; absent means production. Only "draft" and "production"
     * are accepted.
     */
    private static RunMode parseRunMode(WorkflowRunRequest request) {
        try {
            return RunMode.fromRequest(request == null ? null : request.mode());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * {@code simulateSends: true} turns a draft run into {@link RunMode#DRAFT_DRY_RUN}. Asking for
     * it anywhere it cannot be honoured is refused instead of ignored: a caller who asked for "send
     * nothing" must never get a run that sends.
     */
    private RunMode dryRunModeIfAsked(RunMode mode, WorkflowRunRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.simulateSends())) {
            return mode;
        }
        if (mode != RunMode.DRAFT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "simulateSends is only available for draft runs - send mode \"draft\" with"
                            + " it.");
        }
        if (!simulateSendsEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Simulated sends are switched off on this server, so nothing was run.");
        }
        return RunMode.DRAFT_DRY_RUN;
    }

    /**
     * "Test this step" - runs ONE node of the saved graph with the given (or pinned upstream) input
     * and returns a single trace entry, persisting no run. RUNNER, same bar as {@link #run}: it
     * executes the workflow's own code path. 409 for node types with side effects and no dry-run
     * mode, for approval/human-input nodes, and for multi-node constructs - see {@link
     * WorkflowNodeTestService} for the exact list.
     */
    @PostMapping("/{workflowId}/nodes/{nodeId}/test")
    public NodeTraceEntry testNode(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @RequestBody(required = false) WorkflowNodeTestRequest request,
            Principal principal) {
        return nodeTestService.test(
                workflowId, nodeId, request, principal != null ? principal.getName() : null);
    }

    @GetMapping("/{workflowId}/runs")
    public ResponseEntity<List<WorkflowRunResponse>> runs(
            @PathVariable UUID workflowId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset,
            @RequestParam(required = false) String cursor) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: run history
        // is internal detail beyond plain RUNNER (execute-only) visibility,
        // same bar as AgentController's memories/tool-calls endpoints.
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        int size = ListPaging.limit(limit, WorkflowService.RUNS_DEFAULT_LIMIT, WorkflowService.RUNS_MAX_LIMIT);
        if (cursor != null) {
            // Keyset page (X-Next-Cursor from the previous page): no offset, no count query.
            return RunCursorPaging.page(
                    workflowService.listRunsAfter(workflowId, KeysetCursor.decode(cursor), size), size);
        }
        List<WorkflowRunResponse> rows = workflowService.listRuns(workflowId, limit, offset);
        // Truncation must be visible (rows carry their trace, so the cap is real).
        // The canvas polls this: the count query only runs for a full or paged-in page.
        return RunCursorPaging.withNextCursor(
                ListPaging.respond(
                        rows,
                        size,
                        ListPaging.offset(offset),
                        () -> workflowService.countRuns(workflowId)),
                limit != null);
    }

    // Schedule trigger (#166) - the other half of "how do customers trigger
    // it": a cron-based automatic run, handled entirely server-side by
    // WorkflowScheduler. Same authenticated/admin-gated posture as the
    // per-node webhook trigger on WorkflowNodeTriggerController.
    @GetMapping("/{workflowId}/schedule")
    public WorkflowScheduleResponse getSchedule(@PathVariable UUID workflowId) {
        return workflowService.getSchedule(workflowId);
    }

    @PutMapping("/{workflowId}/schedule")
    public WorkflowScheduleResponse enableSchedule(
            @PathVariable UUID workflowId, @Valid @RequestBody WorkflowScheduleRequest request) {
        return workflowService.enableSchedule(workflowId, request);
    }

    @DeleteMapping("/{workflowId}/schedule")
    public WorkflowScheduleResponse disableSchedule(@PathVariable UUID workflowId) {
        return workflowService.disableSchedule(workflowId);
    }


    // ---- Per-workflow tuning PATCH endpoints --------------------------------
    // All five follow the identical "EDITOR check + return updated response"
    // pattern as AgentController's analogous PATCH endpoints, and delegate to
    // WorkflowService methods that enforce requireAccess(EDITOR) + assertEditable
    // before mutating the definition.

    @PatchMapping("/{workflowId}/reasoning-depth")
    public WorkflowResponse setReasoningDepth(
            @PathVariable UUID workflowId,
            @Valid @RequestBody WorkflowReasoningDepthUpdateRequest request) {
        return workflowService.toResponse(
                workflowService.setReasoningDepth(workflowId, request.reasoningDepth()));
    }

    @PatchMapping("/{workflowId}/tuning-mode")
    public WorkflowResponse setTuningMode(
            @PathVariable UUID workflowId,
            @Valid @RequestBody WorkflowTuningModeUpdateRequest request) {
        return workflowService.toResponse(
                workflowService.setTuningMode(workflowId, request.tuningMode()));
    }

    @PatchMapping("/{workflowId}/step-caching")
    public WorkflowResponse setStepCaching(
            @PathVariable UUID workflowId, @RequestBody WorkflowStepCachingUpdateRequest request) {
        return workflowService.toResponse(
                workflowService.setStepCaching(workflowId, request.enabled()));
    }

    @PatchMapping("/{workflowId}/cost-routing")
    public WorkflowResponse setCostRouting(
            @PathVariable UUID workflowId,
            @Valid @RequestBody WorkflowCostRoutingUpdateRequest request) {
        return workflowService.toResponse(
                workflowService.setCostRouting(
                        workflowId,
                        request.enabled(),
                        request.economyProviderName(),
                        request.economyModel()));
    }

    @PatchMapping("/{workflowId}/execution-limits")
    public WorkflowResponse setExecutionLimits(
            @PathVariable UUID workflowId,
            @Valid @RequestBody WorkflowExecutionLimitsUpdateRequest request) {
        return workflowService.toResponse(
                workflowService.setExecutionLimits(
                        workflowId,
                        request.maxStepsPerRun(),
                        request.stepTimeoutSeconds(),
                        request.maxRetryAttempts()));
    }

    /**
     * "Customer should be able to generate a diagram of the existing workflow including agents,
     * tools and all configs" - returns Mermaid flowchart source (default) or PlantUML state-diagram
     * source for the current saved graph. Text response so the canvas can render it client-side (a
     * Mermaid preview) and/or offer it as a copyable/downloadable {@code .mmd}/ {@code .puml} file
     * - no need for a separate download endpoint per format the way the PDF variant below needs
     * one.
     */
    @GetMapping("/{workflowId}/diagram")
    public WorkflowDiagramResponse diagram(
            @PathVariable UUID workflowId,
            @RequestParam(
                            required = false,
                            defaultValue = "mermaid")
                    String format) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: renders the
        // full graph, same bar as a plain config read.
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        return diagramService.generate(workflowId, DiagramFormat.fromWire(format));
    }

    /**
     * Same diagram, rendered as a downloadable, actual boxes-and-arrows PDF - see {@link
     * WorkflowDiagramPdfRenderer}.
     */
    @GetMapping("/{workflowId}/diagram/pdf")
    public ResponseEntity<byte[]> diagramPdf(@PathVariable UUID workflowId) {
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        var resolved = diagramService.resolve(workflowId);
        byte[] bytes =
                Base64.getDecoder().decode(diagramPdfRenderer.renderToBase64(resolved));
        String fileName =
                (resolved.workflowName() == null ? "workflow" : resolved.workflowName())
                                .replaceAll("[^A-Za-z0-9-_]", "_")
                        + "-diagram.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, AttachmentDisposition.attachment(fileName))
                .body(bytes);
    }

    /**
     * Output node "file" destination (#152) - streams back whatever an Output(channel=file) node
     * produced during this run. 404s (via WorkflowRunNotFoundException-style handling) are not
     * distinguished from "this run has no file output" here since both cases mean "there is nothing
     * to download" from the caller's point of view.
     *
     * <p>File type selector (#390): a {@code .pdf} file name means {@code outputData} holds
     * Base64-encoded PDF bytes (see {@code PdfOutputRenderer}) rather than raw UTF-8 text - decoded
     * here and served as {@code application/pdf}. Every other extension keeps the original behavior
     * unchanged: the column's raw UTF-8 bytes, served as {@code text/plain}.
     */
    @GetMapping("/{workflowId}/runs/{runId}/download")
    public ResponseEntity<byte[]> downloadRunOutput(
            @PathVariable UUID workflowId, @PathVariable UUID runId) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER, gated on the
        // path's workflowId.
        //
        // Security audit 2026-07-29 (F3): the run is now loaded by BOTH ids,
        // so this can no longer stream back another user's run output file
        // (the highest-value leg of the three - a whole output document, not
        // just a trace) via a borrowed runId. 404 on mismatch, which this
        // endpoint already treats identically to "this run produced no file".
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        WorkflowRun run = workflowService.getRunEntity(workflowId, runId);
        if (run.getOutputFileName() == null || run.getOutputData() == null) {
            return ResponseEntity.notFound().build();
        }
        boolean isPdf = run.getOutputFileName().toLowerCase(Locale.ROOT).endsWith(".pdf");
        byte[] bytes =
                isPdf
                        ? Base64.getDecoder().decode(run.getOutputData())
                        : run.getOutputData().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(isPdf ? MediaType.APPLICATION_PDF : MediaType.TEXT_PLAIN)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        AttachmentDisposition.attachment(run.getOutputFileName()))
                .body(bytes);
    }

    /**
     * Keyset-paging responses for the run history list ({@code GET /api/workflows/{id}/runs}): the
     * cursor of the next page is the last row's (startedAt, id), matching the list's (startedAt DESC, id
     * DESC) order. See {@link KeysetCursor}.
     */
    static final class RunCursorPaging {

        private RunCursorPaging() {}

        /** A keyset page from {@code size + 1} fetched rows: trims the probe row and sets the headers. */
        static ResponseEntity<List<WorkflowRunResponse>> page(List<WorkflowRunResponse> rows, int size) {
            boolean more = rows.size() > size;
            List<WorkflowRunResponse> page = more ? rows.subList(0, size) : rows;
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok().header("X-Has-More", String.valueOf(more));
            if (more && hasKey(page.get(page.size() - 1))) {
                ok.header(KeysetCursor.NEXT_CURSOR_HEADER, cursorOf(page.get(page.size() - 1)));
            }
            return ok.body(page);
        }

        /**
         * Adds {@code X-Next-Cursor} to an offset page that has more rows, so a paged client ({@code
         * limit} given) can switch to keyset paging from the first page.
         */
        static ResponseEntity<List<WorkflowRunResponse>> withNextCursor(
                ResponseEntity<List<WorkflowRunResponse>> response, boolean paged) {
            List<WorkflowRunResponse> rows = response.getBody();
            boolean more = "true".equals(response.getHeaders().getFirst("X-Has-More"));
            if (!paged || !more || rows == null || rows.isEmpty() || !hasKey(rows.get(rows.size() - 1))) {
                return response;
            }
            return ResponseEntity.ok()
                    .headers(response.getHeaders())
                    .header(KeysetCursor.NEXT_CURSOR_HEADER, cursorOf(rows.get(rows.size() - 1)))
                    .body(rows);
        }

        private static boolean hasKey(WorkflowRunResponse row) {
            return row.startedAt() != null && row.id() != null;
        }

        private static String cursorOf(WorkflowRunResponse last) {
            return new KeysetCursor(last.startedAt(), last.id()).encode();
        }
    }
}
