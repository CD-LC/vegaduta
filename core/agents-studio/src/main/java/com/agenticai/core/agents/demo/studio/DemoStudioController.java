package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.demo.studio.DemoStudioService.AgentUpdate;
import com.agenticai.core.agents.demo.studio.DemoStudioService.AgentView;
import com.agenticai.core.agents.demo.studio.DemoStudioService.Palette;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP surface of the /try Agent Studio - {@code /api/demo/studio/**}, unauthenticated like the
 * rest of {@code /api/demo/**} (SecurityConfig), identified by the sandbox's {@code X-Demo-Token}.
 *
 * <p>Error posture: refusals are JSON {@code {code, message}} with a real status (404 unknown
 * sandbox, 410 expired, 403 view-only, 429 caps), so the page can render each as content. Streams
 * (missions, workflow runs) are {@code text/event-stream} of JSON objects - one per {@code data:}
 * line. Mission streams re-use the Playground's step-event vocabulary (tool_call / tool_result /
 * plan / guardrail / run_finished) plus {@code token} for the reply text, so the trace the visitor
 * sees is the same trace a customer's console shows.
 */
@RestController
@RequestMapping("/api/demo/studio")
public class DemoStudioController {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioController.class);
    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    /**
     * Whole-run ceiling for a workflow: several agent steps plus the visitor's own approval pause
     * (10 min).
     */
    private static final java.time.Duration WORKFLOW_TIMEOUT = java.time.Duration.ofMinutes(12);

    private final DemoSessionService demoSessionService;
    private final DemoStudioService studio;
    private final DemoStudioWorkflowRunner runner;
    private final DemoStudioWorkflowRepository workflowRepository;
    private final DemoStudioRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final DemoStudioDocumentService documents;
    private final DemoStudioConnectorService connectors;
    private final DemoStudioFairUse fairUse;
    private final int maxWorkflowsPerSession;
    private final DemoStudioStreams streams;

    public DemoStudioController(
            DemoSessionService demoSessionService,
            DemoStudioService studio,
            DemoStudioWorkflowRunner runner,
            DemoStudioWorkflowRepository workflowRepository,
            DemoStudioRunRepository runRepository,
            ObjectMapper objectMapper,
            DemoStudioDocumentService documents,
            DemoStudioConnectorService connectors,
            DemoStudioFairUse fairUse,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.demo.studio.max-workflows-per-session:10}")
                    int maxWorkflowsPerSession) {
        this.demoSessionService = demoSessionService;
        this.studio = studio;
        this.runner = runner;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.documents = documents;
        this.connectors = connectors;
        this.fairUse = fairUse;
        this.maxWorkflowsPerSession = maxWorkflowsPerSession;
        this.streams = new DemoStudioStreams(objectMapper, fairUse);
    }

    // ------------------------------------------------------------------
    // Wire shapes
    // ------------------------------------------------------------------

    public record AgentRequest(
            @Size(max = 80) String name,
            @Size(max = 6000) String mission,
            String reasoningDepth,
            String autonomy,
            List<String> tools,
            List<String> guardrails,
            Double temperature,
            Integer maxTokens) {
        AgentUpdate toUpdate() {
            return new AgentUpdate(
                    name,
                    mission,
                    reasoningDepth,
                    autonomy,
                    tools,
                    guardrails,
                    temperature,
                    maxTokens);
        }
    }

    public record MissionRequest(
            @NotBlank @Size(max = 2000) String mission, @Size(max = 64) String sessionId) {}

    public record WorkflowRequest(
            @NotBlank @Size(max = 120) String name,
            DemoStudioWorkflowRunner.Definition definition) {}

    public record WorkflowView(
            UUID id,
            String name,
            DemoStudioWorkflowRunner.Definition definition,
            boolean mine,
            Instant createdAt,
            Instant updatedAt) {}

    public record RunView(
            UUID id,
            String status,
            String input,
            String output,
            Object trace,
            Instant startedAt,
            Instant finishedAt) {}

    public record RunRequest(@Size(max = 2000) String input) {}

    public record DecisionRequest(boolean approved) {}

    public record TextDocumentRequest(
            @Size(max = 120) String title, @NotBlank @Size(max = 200_000) String text) {}

    public record ConnectRequest(@NotBlank @Size(max = 200) String name) {}

    /**
     * {@code messageLimit} is 0 = no lifetime allowance (kept for older pages that read it). {@code
     * clientIp} is the address recorded with this sandbox, shown back to the visitor on purpose.
     */
    public record StudioStatus(
            int messagesUsed,
            int messageLimit,
            boolean readOnly,
            boolean expired,
            int agentsMax,
            int workflowsMax,
            boolean unlimited,
            String clientIp,
            int missionTimeoutSeconds,
            int workflowTimeoutMinutes,
            DemoStudioFairUse.FairUseView fairUse) {}

    // ------------------------------------------------------------------
    // Admission
    // ------------------------------------------------------------------

    private DemoSession require(String token) {
        Optional<DemoSession> found = demoSessionService.resolve(token);
        if (found.isEmpty()) {
            throw new StudioException(
                    "not_found", 404, "This sandbox isn't available any more. Start a new one.");
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now())) {
            throw new StudioException(
                    "expired",
                    410,
                    "This sandbox has ended. Everything you built is kept for your account - or"
                            + " start a new one.");
        }
        return session;
    }

    private DemoSession requireEditable(String token) {
        DemoSession session = require(token);
        if (demoSessionService.isReadOnly(session, Instant.now())) {
            throw new StudioException(
                    "read_only",
                    403,
                    "This sandbox's 24-hour hands-on window has ended, so it's view-only now."
                            + " Create a free account to keep building.");
        }
        return session;
    }

    /**
     * No lifetime allowance: an unlimited sandbox is guarded by speed limits, in-flight caps and
     * cool-downs instead (see DemoStudioFairUse).
     */
    private DemoSession requireRunnable(String token) {
        return requireEditable(token);
    }

    /**
     * Cheap, non-run actions (an upload, a connection) still spend a burst token so they cannot be
     * hammered.
     */
    private void touch(DemoSession session, String what) {
        fairUse.admit(session, what).release();
    }

    @ExceptionHandler(StudioException.class)
    public ResponseEntity<Map<String, String>> onStudioException(StudioException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    // ------------------------------------------------------------------
    // Status + palette
    // ------------------------------------------------------------------

    @GetMapping("/status")
    public StudioStatus status(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token) {
        DemoSession session = require(token);
        Instant now = Instant.now();
        return new StudioStatus(
                session.getMessageCount(),
                0,
                demoSessionService.isReadOnly(session, now),
                false,
                studio.palette(session, null).maxAgentsPerSession(),
                maxWorkflowsPerSession,
                true,
                DemoStudioFairUse.isPublicAddress(session.getCreatedIp())
                        ? session.getCreatedIp()
                        : null,
                studio.missionTimeoutSeconds(),
                (int) WORKFLOW_TIMEOUT.toMinutes(),
                fairUse.view(session));
    }

    @GetMapping("/palette")
    public Palette palette(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @RequestParam(value = "agentId", required = false) UUID agentId) {
        DemoSession session = require(token);
        UUID probe = agentId != null ? agentId : session.getAgentId();
        return studio.palette(session, probe);
    }

    // ------------------------------------------------------------------
    // Agents
    // ------------------------------------------------------------------

    @GetMapping("/agents")
    public List<AgentView> listAgents(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token) {
        return studio.listAgents(require(token));
    }

    @PostMapping("/agents")
    public ResponseEntity<AgentView> createAgent(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody AgentRequest request) {
        DemoSession session = requireEditable(token);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(studio.createAgent(session, request.toUpdate()));
    }

    @GetMapping("/agents/{agentId}")
    public AgentView getAgent(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId) {
        return studio.getAgent(require(token), agentId);
    }

    @PatchMapping("/agents/{agentId}")
    public AgentView updateAgent(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentRequest request) {
        return studio.updateAgent(requireEditable(token), agentId, request.toUpdate());
    }

    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Void> deleteAgent(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId) {
        studio.deleteAgent(requireEditable(token), agentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agents/{agentId}/live")
    public AgentView makeLive(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId) {
        return studio.makeLive(requireEditable(token), agentId);
    }

    /**
     * A mission: one real agent turn, streamed. Pre-charged before the call (a turn that fails
     * upstream is not free - see DemoChatController), and never an HTTP error once the stream has
     * opened: failures arrive as an {@code error} event so the page can render them as content.
     */
    @PostMapping(value = "/agents/{agentId}/mission", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> mission(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @Valid @RequestBody MissionRequest request,
            HttpServletRequest http) {
        DemoSession session = requireRunnable(token);
        DemoStudioFairUse.Permit permit = fairUse.admit(session, "mission");
        String chatSessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : "studio:" + UUID.randomUUID();
        demoSessionService.recordMessage(session);
        int used = session.getMessageCount() + 1;
        int limit = 0;
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean anyToken = new AtomicBoolean(false);

        Flux<String> body;
        try {
            body = studio.runMission(session, agentId, request.mission(), chatSessionId);
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        body =
                streams.missionEvents(
                        body.takeUntilOther(
                                        reactor.core.publisher.Mono.delay(
                                                        java.time.Duration.ofSeconds(
                                                                studio.missionTimeoutSeconds()))
                                                .doOnNext(x -> timedOut.set(true)))
                                .doFinally(signal -> permit.release()),
                        session,
                        agentId,
                        anyToken);
        Flux<String> stream =
                streams.missionStream(
                        chatSessionId, used, limit, body, timedOut, studio.missionTimeoutSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    // ------------------------------------------------------------------
    // Documents (real RAG, one collection per agent)
    // ------------------------------------------------------------------

    @GetMapping("/agents/{agentId}/documents")
    public List<DemoStudioDocumentService.DocumentView> listDocuments(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId) {
        return documents.list(require(token), agentId);
    }

    @PostMapping(
            value = "/agents/{agentId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DemoStudioDocumentService.DocumentView> uploadDocument(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        DemoSession session = requireEditable(token);
        touch(session, "upload");
        if (file.getSize() > documents.limits().maxBytes()) {
            throw new StudioException(
                    "invalid",
                    413,
                    "Sandbox documents are limited to "
                            + (documents.limits().maxBytes() / 1024)
                            + " KB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new StudioException("invalid", 400, "We could not read that file. Try again.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documents.upload(session, agentId, file.getOriginalFilename(), bytes));
    }

    /** Paste-in notes: same pipeline as a file, for visitors with nothing to hand. */
    @PostMapping("/agents/{agentId}/documents/text")
    public ResponseEntity<DemoStudioDocumentService.DocumentView> uploadText(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @Valid @RequestBody TextDocumentRequest request) {
        DemoSession session = requireEditable(token);
        touch(session, "upload-text");
        String title =
                request.title() == null || request.title().isBlank()
                        ? "Pasted notes"
                        : request.title().strip();
        if (!title.contains(".")) {
            title = title + ".txt";
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        documents.upload(
                                session,
                                agentId,
                                title,
                                request.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @DeleteMapping("/agents/{agentId}/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @PathVariable UUID documentId) {
        documents.delete(requireEditable(token), agentId, documentId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // MCP connectors (remote, no-credential servers from the public registry)
    // ------------------------------------------------------------------

    @GetMapping("/mcp/search")
    public DemoStudioConnectorService.RegistryPage searchMcp(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "cursor", required = false) String cursor) {
        DemoSession session = require(token);
        return connectors.search(session, q, cursor);
    }

    @GetMapping("/agents/{agentId}/connectors")
    public List<DemoStudioConnectorService.ConnectorView> listConnectors(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId) {
        return connectors.list(require(token), agentId);
    }

    @PostMapping("/agents/{agentId}/connectors")
    public ResponseEntity<DemoStudioConnectorService.ConnectorView> connect(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @Valid @RequestBody ConnectRequest request) {
        DemoSession session = requireEditable(token);
        touch(session, "connect");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(connectors.connect(session, agentId, request.name()));
    }

    @DeleteMapping("/agents/{agentId}/connectors/{server}")
    public ResponseEntity<Void> disconnect(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID agentId,
            @PathVariable String server) {
        connectors.disconnect(requireEditable(token), agentId, server);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Workflows
    // ------------------------------------------------------------------

    @GetMapping("/workflows")
    public List<WorkflowView> listWorkflows(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token) {
        DemoSession session = require(token);
        // Own workflows only - never another sandbox's, even from the same network address.
        return workflowRepository
                .findBySessionIdInOrderByCreatedAtAsc(List.of(session.getId()))
                .stream()
                .map(w -> toView(w, session))
                .toList();
    }

    @PostMapping("/workflows")
    public ResponseEntity<WorkflowView> createWorkflow(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody WorkflowRequest request) {
        DemoSession session = requireEditable(token);
        if (workflowRepository.countBySessionId(session.getId()) >= maxWorkflowsPerSession) {
            throw new StudioException(
                    "workflow_cap",
                    429,
                    "This sandbox can hold "
                            + maxWorkflowsPerSession
                            + " workflows. Delete one, or create a free account for more.");
        }
        DemoStudioWorkflowRunner.Definition def =
                runner.validate(
                        session,
                        request.definition() == null
                                ? new DemoStudioWorkflowRunner.Definition(List.of())
                                : request.definition());
        runner.moderate(session, request.name(), def);
        DemoStudioWorkflow saved =
                workflowRepository.save(
                        new DemoStudioWorkflow(
                                session.getId(),
                                session.getCreatedIp(),
                                request.name().strip(),
                                runner.serialize(def),
                                Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved, session));
    }

    @PutMapping("/workflows/{workflowId}")
    public WorkflowView updateWorkflow(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID workflowId,
            @Valid @RequestBody WorkflowRequest request) {
        DemoSession session = requireEditable(token);
        DemoStudioWorkflow workflow = requireWorkflow(session, workflowId);
        DemoStudioWorkflowRunner.Definition def =
                runner.validate(
                        session,
                        request.definition() == null
                                ? new DemoStudioWorkflowRunner.Definition(List.of())
                                : request.definition());
        runner.moderate(session, request.name(), def);
        workflow.update(request.name().strip(), runner.serialize(def), Instant.now());
        return toView(workflowRepository.save(workflow), session);
    }

    @DeleteMapping("/workflows/{workflowId}")
    public ResponseEntity<Void> deleteWorkflow(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID workflowId) {
        DemoSession session = requireEditable(token);
        workflowRepository.delete(requireWorkflow(session, workflowId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workflows/{workflowId}/runs")
    public List<RunView> listRuns(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID workflowId) {
        DemoSession session = require(token);
        requireWorkflow(session, workflowId);
        return runRepository.findTop10ByWorkflowIdOrderByStartedAtDesc(workflowId).stream()
                .map(this::toView)
                .toList();
    }

    @PostMapping(
            value = "/workflows/{workflowId}/run",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> runWorkflow(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID workflowId,
            @Valid @RequestBody(required = false) RunRequest request) {
        DemoSession session = requireRunnable(token);
        DemoStudioWorkflow workflow = requireWorkflow(session, workflowId);
        DemoStudioFairUse.Permit permit = fairUse.admit(session, "workflow");
        AtomicBoolean capped = new AtomicBoolean(false);
        Flux<String> started;
        try {
            // Moderates the input (and validates the stored definition) before anything is
            // persisted or sent to a model.
            started = runner.run(session, workflow, request == null ? "" : request.input());
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        Flux<String> stream =
                started.takeUntilOther(
                                reactor.core.publisher.Mono.delay(WORKFLOW_TIMEOUT)
                                        .doOnNext(x -> capped.set(true)))
                        .concatWith(streams.workflowCapNotice(capped, WORKFLOW_TIMEOUT))
                        .doFinally(signal -> permit.release())
                        .onErrorResume(
                                e -> streams.workflowFailure(e, session, workflowId, WORKFLOW_TIMEOUT));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    @PostMapping("/runs/{runId}/decision")
    public ResponseEntity<Map<String, Object>> decide(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String token,
            @PathVariable UUID runId,
            @RequestBody DecisionRequest request) {
        DemoSession session = require(token);
        DemoStudioRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new StudioException(
                                                "not_found",
                                                404,
                                                "That run isn't in your studio."));
        if (!session.getId().equals(run.getSessionId())) {
            throw new StudioException("not_found", 404, "That run isn't in your studio.");
        }
        boolean accepted = runner.decide(runId, request.approved());
        return ResponseEntity.ok(Map.of("accepted", accepted, "approved", request.approved()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private DemoStudioWorkflow requireWorkflow(DemoSession session, UUID workflowId) {
        DemoStudioWorkflow workflow =
                workflowRepository
                        .findById(workflowId)
                        .orElseThrow(
                                () ->
                                        new StudioException(
                                                "not_found",
                                                404,
                                                "That workflow isn't in your studio."));
        if (!session.getId().equals(workflow.getSessionId())) {
            throw new StudioException("not_found", 404, "That workflow isn't in your studio.");
        }
        return workflow;
    }

    private WorkflowView toView(DemoStudioWorkflow w, DemoSession session) {
        return new WorkflowView(
                w.getId(),
                w.getName(),
                runner.parse(w.getDefinitionJson()),
                w.getSessionId().equals(session.getId()),
                w.getCreatedAt(),
                w.getUpdatedAt());
    }

    private RunView toView(DemoStudioRun r) {
        return streams.toView(r);
    }
}
