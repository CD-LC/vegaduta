package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * The studio's workflow runner: a small, honest, sequential engine that runs the visitor's REAL
 * agents step by step and streams what happens.
 *
 * <p>Why not the production workflow engine ({@code core/workflow})? Three reasons, all checked
 * before this was written: the demo package cannot depend on it (the module graph runs workflow
 * -&gt; agents); its approval nodes auto-approve unless an approval-policy override row exists in
 * the tenant, which would make the one step this page exists to demonstrate a no-op; and its
 * scheduler, webhook triggers and admin failure notifications all assume a real tenant with real
 * admins. The step vocabulary here (trigger / agent / condition / approval / output) is the
 * production designer's own, so a design made on /try maps 1:1 onto a real workflow after signup.
 *
 * <p>What is real: every agent step is a real chat turn with the real tool trace ({@link
 * AgentStepEventEmitter}); the approval step genuinely parks the run until a human answers (or ten
 * minutes pass); every agent step spends one message of the sandbox's allowance, exactly like the
 * chat did.
 */
@Service
public class DemoStudioWorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioWorkflowRunner.class);

    public static final int MAX_STEPS = 8;
    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(10);
    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final int MAX_STEP_OUTPUT_CHARS = 12_000;

    private final DemoStudioService studio;
    private final DemoSessionService demoSessionService;
    private final DemoStudioRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final DemoStudioModerationService moderation;
    private final DemoStudioRunEvents out;

    /**
     * Runs parked on an approval step, by run id. The future lives in the replica that streams the
     * run; with {@code app.demo.studio.approval-store=redis} a decision POSTed to another replica
     * reaches it through {@link DemoStudioApprovalBridge}.
     */
    private final Map<UUID, CompletableFuture<Boolean>> pendingApprovals =
            new ConcurrentHashMap<>();

    /**
     * {@code memory} (default, today) or {@code redis}; field-injected so the test-built
     * constructor keeps the default.
     */
    @Value("${app.demo.studio.approval-store:memory}")
    private String approvalStore = "memory";

    private volatile ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile DemoStudioApprovalBridge approvalBridge;

    /**
     * Setter-injected and optional so the constructor stays unchanged; only consulted when {@code
     * approval-store=redis}.
     */
    @Autowired(required = false)
    void setRedisProvider(ObjectProvider<StringRedisTemplate> provider) {
        this.redisProvider = provider;
    }

    /** Test seam: use this bridge regardless of the approval-store property. */
    void useApprovalBridge(DemoStudioApprovalBridge bridge) {
        this.approvalBridge = bridge;
    }

    /**
     * The Redis bridge when approval-store=redis and a template exists, else null (in-memory only).
     */
    private DemoStudioApprovalBridge bridge() {
        DemoStudioApprovalBridge ready = approvalBridge;
        if (ready != null) {
            return ready;
        }
        if (!"redis".equalsIgnoreCase(approvalStore == null ? "" : approvalStore.strip())) {
            return null;
        }
        ObjectProvider<StringRedisTemplate> provider = redisProvider;
        StringRedisTemplate template = provider == null ? null : provider.getIfAvailable();
        if (template == null) {
            return null;
        }
        approvalBridge = new DemoStudioApprovalBridge(template);
        return approvalBridge;
    }

    public DemoStudioWorkflowRunner(
            DemoStudioService studio,
            DemoSessionService demoSessionService,
            DemoStudioRunRepository runRepository,
            ObjectMapper objectMapper,
            DemoStudioModerationService moderation) {
        this.studio = studio;
        this.demoSessionService = demoSessionService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.moderation = moderation;
        this.out = new DemoStudioRunEvents(objectMapper);
    }

    // ------------------------------------------------------------------
    // Definition
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Definition(List<Step> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String id, String type, String label, Map<String, Object> config) {
        String str(String key) {
            Object v = config == null ? null : config.get(key);
            return v == null ? null : String.valueOf(v);
        }
    }

    public Definition parse(String json) {
        try {
            Definition def = objectMapper.readValue(json, Definition.class);
            return def == null || def.steps() == null ? new Definition(List.of()) : def;
        } catch (JsonProcessingException e) {
            throw new DemoStudioService.StudioException(
                    "invalid", 400, "The workflow definition isn't valid.");
        }
    }

    public String serialize(Definition def) {
        try {
            return objectMapper.writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new DemoStudioService.StudioException(
                    "invalid", 400, "The workflow definition can't be stored.");
        }
    }

    /**
     * Shape rules, enforced at save AND at run (a stored definition may reference an agent that was
     * deleted since): a trigger first, at most {@link #MAX_STEPS} steps, at least one agent step,
     * every agent step pointing at an agent this session may see, condition steps carrying a
     * non-empty needle.
     */
    public Definition validate(DemoSession session, Definition def) {
        return DemoStudioWorkflowSteps.validate(session, def, studio);
    }

    /**
     * Content moderation for everything a visitor typed into a workflow: its name, every step label
     * and every free-text config value (instructions, approval messages, condition text). Called on
     * create and update, before anything is stored. {@code agentId} values are ids, not prose, and
     * are skipped.
     */
    public void moderate(DemoSession session, String workflowName, Definition def) {
        moderation.check(session, DemoStudioWorkflowSteps.moderationTexts(workflowName, def));
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    /**
     * Human decision on a parked run. False when nothing is waiting under that id (already decided,
     * timed out, or never existed).
     */
    public boolean decide(UUID runId, boolean approved) {
        CompletableFuture<Boolean> waiting = pendingApprovals.remove(runId);
        if (waiting == null) {
            // Not parked here: with the Redis bridge the run may be streaming from another replica.
            DemoStudioApprovalBridge shared = bridge();
            return shared != null && shared.offer(runId, approved);
        }
        waiting.complete(approved);
        return true;
    }

    /**
     * Executes the workflow on a worker thread and streams JSON events (one per SSE {@code data:}
     * line). The caller has already admitted the run (session live, editable, at least one message
     * left).
     */
    public Flux<String> run(DemoSession session, DemoStudioWorkflow workflow, String input) {
        Definition def = validate(session, parse(workflow.getDefinitionJson()));
        String cleanInput = input == null ? "" : input.strip();
        moderation.check(session, Map.of("workflow input", cleanInput));
        DemoStudioRun run =
                runRepository.save(
                        new DemoStudioRun(
                                workflow.getId(), session.getId(), cleanInput, Instant.now()));
        return Flux.<String>create(
                sink -> {
                    TenantAwareSchedulers.boundedElastic()
                            .schedule(() -> execute(session, def, run, cleanInput, sink));
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    private void execute(
            DemoSession session,
            Definition def,
            DemoStudioRun run,
            String input,
            FluxSink<String> sink) {
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> trace = new ArrayList<>();
        int limit = demoSessionService.messageLimit();
        int charged = 0;
        String previous = input;
        String output = null;
        boolean markedPending =
                false; // true only after this run parked on an approval step (and marked it in
        // Redis)
        DemoStudioRun.Status status = DemoStudioRun.Status.COMPLETED;
        out.emit(
                sink,
                Map.of(
                        "type",
                        "run_started",
                        "runId",
                        run.getId().toString(),
                        "steps",
                        def.steps().size()));
        try {
            for (int i = 0; i < def.steps().size(); i++) {
                Step step = def.steps().get(i);
                long stepStart = System.currentTimeMillis();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("stepId", step.id());
                entry.put("type", step.type());
                entry.put("label", step.label());
                out.emit(
                        sink,
                        Map.of(
                                "type",
                                "step_started",
                                "stepId",
                                step.id(),
                                "stepType",
                                step.type(),
                                "label",
                                step.label(),
                                "index",
                                i));
                switch (step.type()) {
                    case "trigger" -> {
                        entry.put("status", "ok");
                        entry.put(
                                "summary",
                                input.isEmpty()
                                        ? "Started with no input"
                                        : "Started with: " + preview(input, 160));
                    }
                    case "agent" -> {
                        if (session.getMessageCount() + charged >= limit) {
                            entry.put("status", "budget");
                            entry.put("summary", "Message allowance used up");
                            out.emit(
                                    sink,
                                    Map.of(
                                            "type",
                                            "error",
                                            "stepId",
                                            step.id(),
                                            "message",
                                            "This sandbox has used all "
                                                    + limit
                                                    + " messages - create a free account to keep running."));
                            status = DemoStudioRun.Status.STOPPED;
                            out.finishStep(sink, entry, stepStart, trace);
                            i = def.steps().size();
                            continue;
                        }
                        demoSessionService.recordMessage(session);
                        charged++;
                        out.emit(
                                sink,
                                Map.of(
                                        "type",
                                        "budget",
                                        "used",
                                        session.getMessageCount() + charged,
                                        "limit",
                                        limit));
                        String instruction = render(step.str("instruction"), input, previous);
                        UUID agentId = UUID.fromString(step.str("agentId").trim());
                        AgentStepEventEmitter events = AgentStepEventEmitter.active(HEARTBEAT);
                        var forwarding =
                                events.asFlux()
                                        .map(
                                                line ->
                                                        line.startsWith(
                                                                        AgentStepEventEmitter
                                                                                .STEP_EVENT_MARKER)
                                                                ? line.substring(
                                                                        AgentStepEventEmitter
                                                                                .STEP_EVENT_MARKER
                                                                                .length())
                                                                : line)
                                        .subscribe(
                                                json -> sink.next(out.withStepId(json, step.id())),
                                                err -> {});
                        String reply;
                        try {
                            reply =
                                    studio.runMissionBlocking(
                                            session,
                                            agentId,
                                            instruction,
                                            "studio:" + run.getId() + ":" + step.id(),
                                            events);
                        } finally {
                            events.complete();
                            forwarding.dispose();
                        }
                        if (reply == null || reply.isBlank()) {
                            throw new IllegalStateException("The agent returned an empty reply");
                        }
                        if (reply.length() > MAX_STEP_OUTPUT_CHARS) {
                            reply = reply.substring(0, MAX_STEP_OUTPUT_CHARS);
                        }
                        previous = reply;
                        output = reply;
                        entry.put("status", "ok");
                        entry.put("summary", preview(reply, 240));
                        entry.put("output", reply);
                        out.emit(
                                sink,
                                Map.of("type", "step_output", "stepId", step.id(), "text", reply));
                    }
                    case "condition" -> {
                        String needle = step.str("contains").strip();
                        boolean matched =
                                previous != null
                                        && previous.toLowerCase(Locale.ROOT)
                                                .contains(needle.toLowerCase(Locale.ROOT));
                        String onFalse =
                                step.str("onFalse") == null
                                        ? "stop"
                                        : step.str("onFalse").toLowerCase(Locale.ROOT);
                        entry.put("status", "ok");
                        entry.put("matched", matched);
                        entry.put(
                                "summary",
                                (matched ? "Found \"" : "Did not find \"")
                                        + needle
                                        + "\" in the previous output");
                        out.emit(
                                sink,
                                Map.of(
                                        "type",
                                        "condition",
                                        "stepId",
                                        step.id(),
                                        "matched",
                                        matched,
                                        "needle",
                                        needle,
                                        "onFalse",
                                        onFalse));
                        if (!matched) {
                            if ("skip".equals(onFalse) && i + 1 < def.steps().size()) {
                                Step skipped = def.steps().get(i + 1);
                                Map<String, Object> skippedEntry = new LinkedHashMap<>();
                                skippedEntry.put("stepId", skipped.id());
                                skippedEntry.put("type", skipped.type());
                                skippedEntry.put("label", skipped.label());
                                skippedEntry.put("status", "skipped");
                                skippedEntry.put("summary", "Skipped - the condition wasn't met");
                                out.emit(
                                        sink,
                                        Map.of(
                                                "type",
                                                "step_skipped",
                                                "stepId",
                                                skipped.id(),
                                                "label",
                                                skipped.label()));
                                out.finishStep(sink, entry, stepStart, trace);
                                trace.add(skippedEntry);
                                i++;
                                continue;
                            }
                            status = DemoStudioRun.Status.STOPPED;
                            out.finishStep(sink, entry, stepStart, trace);
                            i = def.steps().size();
                            continue;
                        }
                    }
                    case "approval" -> {
                        String message =
                                step.str("message") == null || step.str("message").isBlank()
                                        ? "A human needs to approve this before the workflow continues."
                                        : step.str("message").strip();
                        CompletableFuture<Boolean> decision = new CompletableFuture<>();
                        pendingApprovals.put(run.getId(), decision);
                        DemoStudioApprovalBridge sharedApprovals = bridge();
                        if (sharedApprovals != null) {
                            sharedApprovals.markPending(run.getId());
                            markedPending = true;
                        }
                        run.progress(DemoStudioRun.Status.AWAITING_APPROVAL, out.toJson(trace));
                        runRepository.save(run);
                        out.emit(
                                sink,
                                Map.of(
                                        "type",
                                        "approval_required",
                                        "stepId",
                                        step.id(),
                                        "runId",
                                        run.getId().toString(),
                                        "message",
                                        message,
                                        "preview",
                                        previous == null ? "" : preview(previous, 600)));
                        Boolean approved = awaitDecision(run.getId(), decision, sink);
                        if (approved == null) {
                            entry.put("status", "timeout");
                            entry.put(
                                    "summary",
                                    "Nobody answered within "
                                            + APPROVAL_TIMEOUT.toMinutes()
                                            + " minutes");
                            out.emit(
                                    sink,
                                    Map.of(
                                            "type",
                                            "approval",
                                            "stepId",
                                            step.id(),
                                            "approved",
                                            false,
                                            "timedOut",
                                            true));
                            status = DemoStudioRun.Status.STOPPED;
                            out.finishStep(sink, entry, stepStart, trace);
                            i = def.steps().size();
                            continue;
                        }
                        out.emit(
                                sink,
                                Map.of(
                                        "type",
                                        "approval",
                                        "stepId",
                                        step.id(),
                                        "approved",
                                        approved));
                        entry.put("status", approved ? "ok" : "rejected");
                        entry.put(
                                "summary",
                                approved
                                        ? "Approved by you"
                                        : "Rejected by you - workflow stopped");
                        if (!approved) {
                            status = DemoStudioRun.Status.REJECTED;
                            out.finishStep(sink, entry, stepStart, trace);
                            i = def.steps().size();
                            continue;
                        }
                    }
                    case "output" -> {
                        output = previous;
                        entry.put("status", "ok");
                        entry.put("summary", "Delivered to your screen");
                    }
                    default -> throw new IllegalStateException("Unknown step type " + step.type());
                }
                out.finishStep(sink, entry, stepStart, trace);
                run.progress(DemoStudioRun.Status.RUNNING, out.toJson(trace));
                runRepository.save(run);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Studio workflow run {} failed for sandbox {}",
                    run.getId(),
                    session.getId(),
                    e);
            status = DemoStudioRun.Status.FAILED;
            out.emit(sink, Map.of("type", "error", "message", describe(e)));
        } finally {
            pendingApprovals.remove(run.getId());
            // Only a run that parked on an approval step has keys to clear: no Redis traffic for
            // the others.
            DemoStudioApprovalBridge sharedApprovals = markedPending ? bridge() : null;
            if (sharedApprovals != null) {
                sharedApprovals.clear(run.getId());
            }
            long ms = System.currentTimeMillis() - startedAt;
            try {
                run.finish(status, output, out.toJson(trace), Instant.now());
                runRepository.save(run);
            } catch (RuntimeException e) {
                log.warn("Could not persist studio run {}", run.getId(), e);
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "run_finished");
            done.put("runId", run.getId().toString());
            done.put("status", status.name());
            done.put("output", output == null ? "" : output);
            done.put("ms", ms);
            done.put("messagesUsed", session.getMessageCount() + charged);
            done.put("messageLimit", limit);
            out.emit(sink, done);
            sink.complete();
        }
    }

    private Boolean awaitDecision(
            UUID runId, CompletableFuture<Boolean> decision, FluxSink<String> sink) {
        DemoStudioApprovalBridge shared = bridge();
        if (shared != null) {
            return awaitDecisionShared(runId, decision, sink, shared);
        }
        long deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return decision.get(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                out.emit(sink, Map.of("type", "heartbeat", "waitingFor", "approval"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
        pendingApprovals.remove(runId);
        return null;
    }

    /**
     * The Redis-bridged wait: as {@link #awaitDecision} but wakes about once a second to look for a
     * decision another replica recorded, and still emits the heartbeat every {@link #HEARTBEAT}.
     */
    private Boolean awaitDecisionShared(
            UUID runId,
            CompletableFuture<Boolean> decision,
            FluxSink<String> sink,
            DemoStudioApprovalBridge shared) {
        long deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT.toMillis();
        long nextHeartbeat = System.currentTimeMillis() + HEARTBEAT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return decision.get(1000L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Boolean remote = shared.takeDecision(runId);
                if (remote != null) {
                    pendingApprovals.remove(runId);
                    return remote;
                }
                if (System.currentTimeMillis() >= nextHeartbeat) {
                    out.emit(sink, Map.of("type", "heartbeat", "waitingFor", "approval"));
                    nextHeartbeat = System.currentTimeMillis() + HEARTBEAT.toMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
        pendingApprovals.remove(runId);
        return null;
    }

    static String render(String template, String input, String previous) {
        String t = template == null || template.isBlank() ? "{{previous}}" : template;
        String rendered =
                t.replace("{{input}}", input == null ? "" : input)
                        .replace("{{previous}}", previous == null ? "" : previous);
        // A template that mentions neither still needs the data to act on -
        // append the previous step's output so the agent isn't told to
        // "summarise" thin air.
        if (!t.contains("{{input}}")
                && !t.contains("{{previous}}")
                && previous != null
                && !previous.isBlank()) {
            rendered = rendered.strip() + "\n\n---\n" + previous;
        }
        return rendered.strip();
    }

    static String preview(String text, int max) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof DemoStudioService.StudioException se) {
            return se.getMessage();
        }
        return "That step didn't complete - the model or a tool was unavailable. Try again in a moment.";
    }
}
