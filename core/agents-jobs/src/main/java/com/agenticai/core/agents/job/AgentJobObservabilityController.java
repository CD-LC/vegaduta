package com.agenticai.core.agents.job;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobProgressNoteView;
import com.agenticai.core.agents.job.dto.AgentResultSchemaRequest;
import com.agenticai.core.agents.job.dto.JobTraceEntry;
import com.agenticai.core.agents.job.output.JobResultSchemaValidator;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of "is this long job alive, and what did it actually do" - plus the agent-level
 * result-schema configuration that drives structured output.
 *
 * <p><b>Deliberately a SEPARATE controller from {@link AgentJobController}</b>, which owns
 * submit/get/list/cancel. The split is by lifecycle, not by arbitrary size: those are the job's
 * control plane and this is its observation plane, and they change for different reasons. Note the
 * one thing that stayed next door on purpose - the tenant-wide operator list lives in {@code
 * AgentJobController}, because two controllers both serving "list jobs" would inevitably drift in
 * their filtering and their access rules.
 *
 * <p>Every handler runs {@code requireAccess} FIRST and then scopes the job by the path's {@code
 * agentId}, so a job id belonging to another agent 404s exactly as a non-existent one does. Reads
 * are VIEWER (a trace is conversation content: tool inputs and the agent's own commentary); the
 * result-schema writes are EDITOR (they are agent configuration, and they change the behaviour of
 * every job submitted afterwards).
 *
 * <p>No {@code SecurityConfig} change is needed for any of these: everything under {@code
 * /api/agents/**} already falls through to {@code anyRequest().authenticated()}, and the real
 * per-resource decision is the {@code requireAccess} call in each handler, not a URL matcher.
 */
@RestController
public class AgentJobObservabilityController {

    private static final Logger log =
            LoggerFactory.getLogger(AgentJobObservabilityController.class);

    /**
     * Live-trace poll interval. One second, matching {@code QuickTaskService}'s trace stream - fast
     * enough to feel live, slow enough that a handful of open streams is not a meaningful query
     * load.
     */
    private static final Duration TRACE_POLL_INTERVAL = Duration.ofSeconds(1);

    /**
     * Hard ceiling on how long one stream stays open, independent of the job. Slightly over {@code
     * app.agent-jobs.max-timeout} (60m) so a job that runs to its own maximum is still fully
     * observed, while a browser tab left open over a weekend cannot hold a polling subscription
     * forever.
     */
    private static final Duration MAX_STREAM_DURATION = Duration.ofMinutes(65);

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentJobRepository jobRepository;
    private final AgentJobProgressService progressService;
    private final AgentJobTraceService traceService;
    private final JobResultSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public AgentJobObservabilityController(
            AgentService agentService,
            AgentRepository agentRepository,
            AgentJobRepository jobRepository,
            AgentJobProgressService progressService,
            AgentJobTraceService traceService,
            JobResultSchemaValidator schemaValidator,
            ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.progressService = progressService;
        this.traceService = traceService;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------- progress

    /**
     * @param after exclusive cursor - pass the {@code createdAt} of the last note already seen.
     *     Absent means "from the beginning". Annotated with {@code @DateTimeFormat} to match {@code
     *     AuditLogController}'s existing {@code Instant} request params: without it the binding
     *     depends on whichever default converter happens to be registered, and the failure mode is
     *     a 400 on a perfectly valid ISO-8601 timestamp.
     */
    @GetMapping("/api/agents/{agentId}/jobs/{jobId}/progress")
    public List<AgentJobProgressNoteView> progress(
            @PathVariable UUID agentId,
            @PathVariable UUID jobId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant after) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        requireJob(agentId, jobId);
        return progressService.notesAfter(jobId, after);
    }

    // ---------------------------------------------------------------- trace

    @GetMapping("/api/agents/{agentId}/jobs/{jobId}/trace")
    public List<JobTraceEntry> trace(
            @PathVariable UUID agentId,
            @PathVariable UUID jobId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant after) {
        return traceService.traceAfter(agentId, jobId, after);
    }

    /**
     * The same trace, pushed as it happens.
     *
     * <p>Modeled directly on {@code QuickTaskService#runStream}'s trace poller, including the two
     * things that make that one correct and are easy to leave out:
     *
     * <ul>
     *   <li>the calling thread's {@link TenantContext} is captured HERE, on the request thread, and
     *       re-applied explicitly on every poll. The poll runs on a Reactor scheduler thread, which
     *       never inherits a plain {@code ThreadLocal}; without this it would query the empty
     *       {@code public} schema and stream nothing forever while reporting no error at all - the
     *       exact bug {@code TenantContextPropagatingToolCallback}'s javadoc documents.
     *   <li>a failed poll is swallowed and the stream continues. A transient query failure must not
     *       tear down an operator's live view of a job that is still running perfectly well.
     * </ul>
     *
     * <p>Access is checked ONCE, synchronously, before the stream opens - so an unauthorised caller
     * gets a normal 404 rather than a stream that opens and then reports an error. Each poll then
     * goes through {@link AgentJobTraceService}, which re-checks anyway.
     *
     * <p>The stream closes when the job reaches a terminal state, after one final catch-up poll.
     * That last poll matters: it closes the race between the worker's terminal write and the next
     * tick, so "done" can never arrive before the entries that led up to it.
     */
    @GetMapping(
            value = "/api/agents/{agentId}/jobs/{jobId}/trace/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> traceStream(@PathVariable UUID agentId, @PathVariable UUID jobId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        requireJob(agentId, jobId);

        TenantContext capturedContext = TenantContext.current().orElse(null);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Identity-based dedupe, not a moving timestamp cursor: two entries can
        // share an instant (see AgentJobProgressNoteRepository on why the order
        // needs an id tiebreak), and a cursor that advanced past one of them
        // would drop it silently.
        Set<String> seen = new HashSet<>();
        Disposable poller =
                Flux.interval(TRACE_POLL_INTERVAL)
                        .publishOn(TenantAwareSchedulers.boundedElastic())
                        .take(MAX_STREAM_DURATION)
                        .subscribe(tick -> pollTrace(capturedContext, agentId, jobId, seen, sink));

        return sink.asFlux().doFinally(signal -> poller.dispose());
    }

    /**
     * One poll: re-apply the captured context, emit anything new, and close the stream once the job
     * is finished. Never throws - see {@link #traceStream}.
     */
    private void pollTrace(
            TenantContext capturedContext,
            UUID agentId,
            UUID jobId,
            Set<String> seen,
            Sinks.Many<String> sink) {
        Optional<TenantContext> previous = TenantContext.current();
        if (capturedContext != null) {
            TenantContext.set(capturedContext);
        }
        try {
            for (JobTraceEntry entry : traceService.trace(agentId, jobId)) {
                // at + type + label identifies an entry without either source
                // having to expose its primary key through the trace DTO.
                if (seen.add(entry.at() + "|" + entry.type() + "|" + entry.label())) {
                    emit(sink, entry);
                }
            }
            boolean finished =
                    jobRepository
                            .findById(jobId)
                            .map(job -> job.getStatus().isTerminal())
                            .orElse(true);
            if (finished) {
                sink.tryEmitComplete();
            }
        } catch (RuntimeException e) {
            log.warn("Agent job trace poll failed for job {}", jobId, e);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void emit(Sinks.Many<String> sink, JobTraceEntry entry) {
        try {
            sink.tryEmitNext(objectMapper.writeValueAsString(entry));
        } catch (Exception e) {
            // A single unserializable entry must not kill the stream; skip it and
            // keep the rest of the trace flowing.
            log.warn("Could not serialize an agent job trace entry", e);
        }
    }

    // -------------------------------------------------------- result schema

    /**
     * Sets the JSON Schema every JOB of this agent must produce.
     *
     * <p>Validated BEFORE it is stored, so an uncompilable schema is a 400 the moment someone
     * writes one rather than a run of jobs that all fail ten minutes in, pointing at the agent
     * instead of at the configuration that actually broke.
     *
     * <p>Saved through {@link AgentRepository} rather than an {@code AgentService} update method on
     * purpose: those methods call {@code AgentSchemaService#refresh}, which regenerates {@code
     * output_schema_json} and bumps {@code schema_version}. Neither has anything to do with a
     * tenant-authored RESULT schema, and triggering them here would make an unrelated column churn
     * on every save of this one.
     */
    @PutMapping("/api/agents/{agentId}/result-schema")
    public ResultSchemaResponse setResultSchema(
            @PathVariable UUID agentId, @Valid @RequestBody AgentResultSchemaRequest request) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        schemaValidator.requireUsableSchema(request.schemaJson());
        agent.setResultSchemaJson(request.schemaJson());
        agentRepository.save(agent);
        return new ResultSchemaResponse(agent.getResultSchemaJson());
    }

    /** 404 when this agent has no result schema - "unset" is an absence, not an empty string. */
    @GetMapping("/api/agents/{agentId}/result-schema")
    public ResponseEntity<ResultSchemaResponse> getResultSchema(@PathVariable UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        String schemaJson = agent.getResultSchemaJson();
        if (schemaJson == null || schemaJson.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ResultSchemaResponse(schemaJson));
    }

    /**
     * Clears it. 204 whether or not one was set - removing something already absent is not an
     * error, and a 404 here would make "make sure this agent has no result schema" require a read
     * first.
     *
     * <p>Jobs already in flight are UNAFFECTED: each one snapshotted the schema onto its own row at
     * enqueue (see {@code agent_job.result_schema_json}), so clearing the agent-level default can
     * never retroactively change what a running job is being held to.
     */
    @DeleteMapping("/api/agents/{agentId}/result-schema")
    public ResponseEntity<Void> clearResultSchema(@PathVariable UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        if (agent.getResultSchemaJson() != null) {
            agent.setResultSchemaJson(null);
            agentRepository.save(agent);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The schema as a raw JSON STRING, matching {@link AgentResultSchemaRequest} on the way in.
     * Round-tripping it through Jackson as a nested object would change key order and formatting,
     * so what a tenant stored is not what they read back - which makes "did my change save?"
     * needlessly hard to answer.
     */
    public record ResultSchemaResponse(String schemaJson) {}

    // --------------------------------------------------------------- internals

    /**
     * Path-scoped existence check. Its return value is unused by most callers - it is here for the
     * 404, which must fire before any progress or trace data is read for a job that belongs to a
     * different agent.
     */
    private AgentJob requireJob(UUID agentId, UUID jobId) {
        return jobRepository
                .findByIdAndAgentId(jobId, agentId)
                .orElseThrow(() -> new AgentJobNotFoundException(jobId));
    }
}
