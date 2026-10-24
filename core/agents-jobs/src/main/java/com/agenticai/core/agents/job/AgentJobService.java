package com.agenticai.core.agents.job;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import com.agenticai.core.agents.job.output.InvalidResultSchemaException;
import com.agenticai.core.agents.job.output.JobResultSchemaValidator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The request-facing half of agent jobs: submit, read, list, cancel - plus the {@link
 * AgentJobLauncher} seam the scheduler PR enqueues through.
 *
 * <p><b>Access is checked here, at every entry point, before anything else.</b> {@code submit} and
 * {@code cancel} require RUNNER, mirroring {@code AgentController}'s existing chat gate exactly: a
 * job IS an agent execution, so it must not be easier to start than the chat turn it wraps. Reads
 * require VIEWER, because a job's goal, result and error are the agent's actual conversation
 * content.
 *
 * <p>Nothing here blocks. {@code submit} writes a row, hands it to a worker best-effort, and
 * returns 202 - the HTTP request never waits on an LLM, which is the entire point of the feature.
 */
@Service
public class AgentJobService implements AgentJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(AgentJobService.class);

    private final AgentJobRepository repository;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final AgentJobExecutor executor;
    private final AgentJobProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Compiles a result schema at the moment it ENTERS the system, so an unusable one is a 400 the
     * caller sees immediately rather than a job that is accepted with a 202 and dies ten minutes
     * later pointing at the agent.
     */
    private final JobResultSchemaValidator schemaValidator;

    /**
     * The existing per-TURN tool ceiling ({@code GuardedToolCallback}). Read here only to derive a
     * sensible per-JOB default; the per-turn limit itself is untouched and still enforced by the
     * guardrail layer exactly as it is for a normal chat turn.
     */
    private final int maxToolCallsPerTurn;

    @Autowired
    public AgentJobService(
            AgentJobRepository repository,
            AgentRepository agentRepository,
            AgentService agentService,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            JobResultSchemaValidator schemaValidator,
            @Value("${app.guardrails.max-tool-calls-per-turn:15}") int maxToolCallsPerTurn) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.executor = executor;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
    }

    /**
     * Validator-free constructor, for the unit suite (which builds this service directly with plain
     * Mockito mocks and no Spring context). Builds the same validator the container would inject -
     * it is a pure function of the object mapper and the properties, with no collaborators of its
     * own - so this shortcut cannot make a test disagree with production about which schemas are
     * usable.
     */
    public AgentJobService(
            AgentJobRepository repository,
            AgentRepository agentRepository,
            AgentService agentService,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            int maxToolCallsPerTurn) {
        this(
                repository,
                agentRepository,
                agentService,
                executor,
                properties,
                meterRegistry,
                objectMapper,
                new JobResultSchemaValidator(objectMapper, properties),
                maxToolCallsPerTurn);
    }

    /**
     * Enqueues a job on behalf of the authenticated caller and returns immediately.
     *
     * <p>{@code subjectKey} and {@code requestedBy} are BOTH the caller's own subject, and a test
     * asserts the value threaded into {@code chat()} equals {@code requested_by}. This is
     * load-bearing, not tidiness: {@code PerUserBudgetService} keys its spend window on (tenant,
     * subject, modality), so a synthetic per-job subject would mint a brand-new daily dollar
     * ceiling on every submission - a job would literally BE the way to escape the budget that a
     * chat turn obeys.
     */
    public AgentJobResponse submit(UUID agentId, AgentJobStartRequest request) {
        return submit(agentId, request, false);
    }

    /**
     * The one resolver of the per-tenant / per-agent runtime limits: a job that names no tool-call
     * budget gets {@code maxTurns} times what one turn of ITS agent may make. Null (unit tests,
     * slim contexts) = the single global {@code app.guardrails.max-tool-calls-per-turn}.
     */
    private volatile com.agenticai.core.agents.runtime.AgentRuntime runtimeSettings;

    @Autowired(required = false)
    void setRuntimeSettings(com.agenticai.core.agents.runtime.AgentRuntime resolver) {
        this.runtimeSettings = resolver;
    }

    /**
     * What one turn of {@code agentId} may make: its runtime ceiling, else the global per-turn
     * ceiling.
     */
    private int toolCallsPerTurn(UUID agentId) {
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtimeSettings;
        if (resolver != null) {
            try {
                return Math.max(1, resolver.forAgent(agentId).maxToolSteps());
            } catch (RuntimeException e) {
                log.warn(
                        "Could not resolve the runtime ceiling of agent {}; the job's default tool budget uses the"
                                + " global per-turn ceiling: {}",
                        agentId,
                        e.toString());
            }
        }
        return maxToolCallsPerTurn;
    }

    private AgentJobOutcomes outcomes;

    @Autowired(required = false)
    void setOutcomes(AgentJobOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    // Optional per-feature run meter (deep research), setter-injected so the constructors stay as
    // they are.
    private ObjectProvider<AgentJobSubmitGate> submitGate;

    @Autowired(required = false)
    void setSubmitGate(ObjectProvider<AgentJobSubmitGate> submitGate) {
        this.submitGate = submitGate;
    }

    /**
     * @param runMeterAlreadyReserved true when the caller (the research facade) has already counted
     *     this run against the feature's own cap, so the {@link AgentJobSubmitGate} must not count
     *     it a second time.
     */
    public AgentJobResponse submit(
            UUID agentId, AgentJobStartRequest request, boolean runMeterAlreadyReserved) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);

        TenantContext context =
                TenantContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "An agent job cannot be submitted without an authenticated tenant context"));

        int timeoutSeconds = resolveTimeoutSeconds(request.timeoutSeconds());
        // A request may ask for more turns than the global default (the continue_in_background
        // handoff does), never for more than the per-job cap.
        int maxTurns =
                request.maxTurns() != null
                        ? Math.min(
                                Math.max(1, request.maxTurns()),
                                Math.max(1, properties.getMaxTurnsCap()))
                        : Math.max(1, properties.getMaxTurns());
        int maxToolCalls =
                request.maxToolCalls() != null && request.maxToolCalls() > 0
                        ? request.maxToolCalls()
                        : maxTurns * toolCallsPerTurn(agentId);

        // Per-job schema if supplied, else the agent's own default. Snapshotted
        // onto the row either way, so editing the agent mid-flight can never
        // retroactively invalidate a job already running.
        String resultSchemaJson = effectiveSchema(agentId, request.resultSchemaJson());
        if (resultSchemaJson != null) {
            // Compiled BEFORE the row is written. A malformed, oversized or
            // remote-$ref schema must be a 400 the caller sees now, not a job
            // accepted with a 202 that dies ten minutes later on a worker.
            schemaValidator.requireUsableSchema(resultSchemaJson);
        }

        // A feature that meters its own runs (deep research) is metered here too, so submitting a
        // job
        // straight to its agent cannot dodge the cap. Reserved AFTER the cheap validation above so
        // a
        // rejected request counts nothing; given back if anything below fails before the job is
        // durable.
        Runnable releaseRunMeter = null;
        if (!runMeterAlreadyReserved) {
            ObjectProvider<AgentJobSubmitGate> gateProvider = submitGate;
            AgentJobSubmitGate gate = gateProvider == null ? null : gateProvider.getIfAvailable();
            if (gate != null) {
                releaseRunMeter =
                        gate.reserve(agentId, context.getTenantId(), context.getSubject());
            }
        }
        try {
            AgentJobResponse accepted =
                    enqueueApiJob(
                            agentId,
                            request,
                            context,
                            timeoutSeconds,
                            maxTurns,
                            maxToolCalls,
                            resultSchemaJson);
            // Routines-from-habits loop (off by default): a Quick Task the person submitted and the
            // platform accepted is a request they made. Rejected submissions are never recorded.
            com.agenticai.core.agents.skills.RoutineObservationGateway.observeCurrent(
                    agentId,
                    request.goal(),
                    com.agenticai.core.agents.skills.RoutineObservationGateway.JOB);
            return accepted;
        } catch (RuntimeException | Error e) {
            if (releaseRunMeter != null) {
                try {
                    releaseRunMeter.run();
                } catch (RuntimeException releaseFailure) {
                    log.warn(
                            "Could not give back the run reservation for agent {} after a failed submit",
                            agentId,
                            releaseFailure);
                }
            }
            throw e;
        }
    }

    private AgentJobResponse enqueueApiJob(
            UUID agentId,
            AgentJobStartRequest request,
            TenantContext context,
            int timeoutSeconds,
            int maxTurns,
            int maxToolCalls,
            String resultSchemaJson) {
        UUID jobId = UUID.randomUUID();
        AgentJob job =
                AgentJob.queued(
                        jobId,
                        context.getTenantId(),
                        agentId,
                        request.goal(),
                        context.getSubject(),
                        context.getSubject(),
                        rolesOf(context),
                        AgentJobOrigin.API,
                        null,
                        timeoutSeconds,
                        maxTurns,
                        maxToolCalls,
                        resultSchemaJson);

        // Durable row FIRST, dispatch second - see AgentJobExecutor's javadoc.
        // If this process dies between the two, the poller finds the row.
        repository.save(job);
        meterRegistry
                .counter("agent.job.submitted", "origin", AgentJobOrigin.API.name())
                .increment();

        // run-now-mode=enqueue: this JVM is not the one that runs jobs (API shape
        // of a split deployment) - the durable row IS the hand-off and the jobs
        // shape's poller claims it. Not a rejection, so no counter either.
        // The row is durable, so a failure to hand it to a worker is never a failed submit: the
        // poller will
        // find it. Throwing here would refund the run meter for a job that still runs.
        try {
            if (!enqueueOnly() && !executor.tryDispatch(jobId, context.getTenantId())) {
                meterRegistry.counter("agent.job.rejected").increment();
            }
        } catch (RuntimeException dispatchFailure) {
            log.warn(
                    "Agent job {} was saved but could not be dispatched; the poller will pick it up",
                    jobId,
                    dispatchFailure);
            meterRegistry.counter("agent.job.rejected").increment();
        }
        return AgentJobResponse.from(job, objectMapper);
    }

    /**
     * {@code app.agent-jobs.run-now-mode=enqueue} - see {@link AgentJobProperties#getRunNowMode}.
     */
    private boolean enqueueOnly() {
        return properties.getRunNowMode() == AgentJobProperties.RunNowMode.ENQUEUE;
    }

    public AgentJobResponse get(UUID agentId, UUID jobId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return AgentJobResponse.from(require(agentId, jobId), objectMapper);
    }

    /**
     * @param statuses an EMPTY (or {@code null}) set means "no filter". A SET rather than a single
     *     nullable status because the operator screens ask real multi-status questions -
     *     "everything still live", "everything that ended badly" - and answering those with N round
     *     trips, or with a client-side union of single-status pages, gets the paging wrong.
     */
    public List<AgentJobSummaryResponse> listForAgent(
            UUID agentId, Set<AgentJobStatus> statuses, int limit, int offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        Pageable pageable = AgentJobListSupport.pageOf(limit, offset);
        Page<AgentJob> page =
                AgentJobListSupport.isUnfiltered(statuses)
                        ? repository.findByAgentIdOrderByCreatedAtDesc(agentId, pageable)
                        : repository.findByAgentIdAndStatusInOrderByCreatedAtDesc(
                                agentId, statuses, pageable);
        return AgentJobListSupport.summarize(agentRepository, page.getContent());
    }

    /**
     * Tenant-wide operator list.
     *
     * <p>Filtered PER ROW rather than by pre-computing the set of agents the caller may see: {@code
     * requireAccess} is the one implementation of "may this caller see this agent", and
     * reimplementing its logic as a query predicate here is exactly how the two drift apart. The
     * cost is bounded - at most {@code limit} (capped at 200) checks, against a service that is
     * already the per-request path for every agent screen in the console.
     */
    public List<AgentJobSummaryResponse> listForTenant(
            Set<AgentJobStatus> statuses, UUID agentIdOrNull, int limit, int offset) {
        if (agentIdOrNull != null) {
            return listForAgent(agentIdOrNull, statuses, limit, offset);
        }
        Pageable pageable = AgentJobListSupport.pageOf(limit, offset);
        Page<AgentJob> page =
                AgentJobListSupport.isUnfiltered(statuses)
                        ? repository.findAllByOrderByCreatedAtDesc(pageable)
                        : repository.findByStatusInOrderByCreatedAtDesc(statuses, pageable);

        Map<UUID, Boolean> visibility = new HashMap<>();
        List<AgentJob> visible = new ArrayList<>();
        for (AgentJob job : page.getContent()) {
            boolean allowed = visibility.computeIfAbsent(job.getAgentId(), this::canView);
            if (allowed) {
                visible.add(job);
            }
        }
        return AgentJobListSupport.summarize(agentRepository, visible);
    }

    /**
     * Total rows matching {@link #listForTenant}'s filters, BEFORE the per-row visibility filter
     * (that filter runs after paging, so the visible total is not knowable without loading every
     * row). Backs {@code X-Total-Count}; a caller with partial visibility may therefore see a total
     * larger than what they can page through.
     */
    public long countForTenant(Set<AgentJobStatus> statuses, UUID agentIdOrNull) {
        if (agentIdOrNull != null) {
            agentService.requireAccess(agentIdOrNull, PermissionLevel.VIEWER);
            return AgentJobListSupport.isUnfiltered(statuses)
                    ? repository.countByAgentId(agentIdOrNull)
                    : repository.countByAgentIdAndStatusIn(agentIdOrNull, statuses);
        }
        return AgentJobListSupport.isUnfiltered(statuses)
                ? repository.count()
                : repository.countByStatusIn(statuses);
    }

    /**
     * Idempotent by contract: cancelling an already-terminal job returns its current row rather
     * than a 409. An operator hitting cancel twice, or hitting it as the job finishes, is not
     * making a mistake worth an error - and the deadline sweep uses the same path, so a 409 here
     * would turn a normal race into a logged failure.
     *
     * <p>Ordered so that the DURABLE effect happens first and the best-effort interrupt second.
     * Even if this replica does not own the job (or crashes immediately after), the flag is written
     * and the owning worker observes it at its next heartbeat tick.
     *
     * <p>Who may cancel: the job's requester (for a scheduled or triggered job, the person who set
     * it up) with RUNNER, or anyone with EDITOR on the agent (its owner, an admin, an editor
     * grant). Another runner cannot stop someone else's job. The job is looked up first, so an
     * unknown id is still a 404 for everyone.
     */
    public AgentJobResponse cancel(UUID agentId, UUID jobId) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        AgentJob job = require(agentId, jobId);
        String caller = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (caller == null || !caller.equals(job.getRequestedBy())) {
            agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        }
        if (job.getStatus().isTerminal()) {
            return AgentJobResponse.from(job, objectMapper);
        }

        String by = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        Instant now = Instant.now();

        // Three branches, tried in order, each a guarded UPDATE that loses
        // cleanly with rowcount 0:
        //   1. QUEUED - nobody has claimed it, so it dies outright with no
        //      worker to interrupt. A worker that claimed it a microsecond ago
        //      wins and we fall through.
        //   2. AWAITING_APPROVAL - a parked job holds NO worker, so the
        //      cooperative path below would write the flag, find nothing to
        //      interrupt, and leave the row parked forever with
        //      cancel_requested = true, holding its schedule's overlap guard
        //      shut. It has to be terminated here or not at all.
        //   3. anything else (RUNNING) - durable flag first, best-effort local
        //      interrupt second.
        if (repository.cancelIfStillQueued(jobId, by, now) > 0
                || repository.cancelIfAwaitingApproval(jobId, by, now) > 0) {
            meterRegistry.counter("agent.job.cancelled").increment();
            if (outcomes != null) {
                outcomes.terminal(jobId);
            }
        } else {
            repository.requestCancel(jobId, by, now);
            executor.stopLocally(jobId);
            // No metric here on purpose. The job is not cancelled YET - it is
            // cancel-REQUESTED, and the worker records agent.job.cancelled when
            // it actually stops. Counting here too would double-count every
            // cancel of a running job and make the metric useless for "how many
            // jobs actually ended this way".
        }
        return AgentJobResponse.from(require(agentId, jobId), objectMapper);
    }

    // ------------------------------------------------------- AgentJobLauncher

    /**
     * {@inheritDoc}
     *
     * <p>No access check here, deliberately and unlike {@link #submit}: the caller is a scheduler
     * tick, not a human, and the authority question was already answered when the schedule was
     * CONFIGURED. The identity carried in {@link AgentJobLaunch} is that configuring human's,
     * frozen - which is why every identity field on the record is mandatory.
     *
     * <p>{@code DataIntegrityViolationException} propagates untouched - see this method's
     * declaration on {@link AgentJobLauncher}.
     */
    @Override
    public UUID enqueue(AgentJobLaunch launch) {
        UUID jobId = UUID.randomUUID();
        String tenantId =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "enqueue() must run inside a TenantContext - schedulers iterate schemas via TenantSchemaIterator"));

        int timeoutSeconds = resolveTimeoutSeconds(launch.timeoutSecondsOrNull());
        int maxTurns = Math.max(1, properties.getMaxTurns());

        // A non-HTTP launch has no per-job schema, so this is purely the agent's
        // own default. Before this, the hard-coded null here was half of why the
        // agent-level result-schema API was write-only: a scheduled or triggered
        // job silently ignored the schema its agent declared.
        String resultSchemaJson = effectiveSchema(launch.agentId(), null);
        String schemaDefect =
                resultSchemaJson == null ? null : schemaDefectOrNull(resultSchemaJson);

        AgentJob job =
                AgentJob.queued(
                        jobId,
                        tenantId,
                        launch.agentId(),
                        launch.goal(),
                        launch.subjectKey(),
                        launch.requestedBy(),
                        launch.requestedByRoles(),
                        launch.origin(),
                        launch.originRef(),
                        timeoutSeconds,
                        maxTurns,
                        maxTurns * toolCallsPerTurn(launch.agentId()),
                        resultSchemaJson);
        if (launch.scheduleId() != null) {
            // Must be part of the same INSERT: V208's two unique indexes (one live
            // job per schedule; one job per occurrence) are what actually prevent
            // a double firing, and they can only fire on the insert itself.
            job.bindToSchedule(launch.scheduleId(), launch.occurrenceAt());
        }
        if (launch.triggerId() != null) {
            // Same INSERT, same reason as the schedule binding above: a job whose
            // trigger is recorded only afterwards is unattributable in between,
            // and "which trigger produced this?" is the first question asked when
            // one misbehaves.
            job.bindToTrigger(launch.triggerId(), launch.triggerDepth());
        }
        repository.save(job);
        meterRegistry.counter("agent.job.submitted", "origin", launch.origin().name()).increment();

        if (schemaDefect != null) {
            // Enqueue-then-fail rather than throw. This method runs inside a
            // scheduler tick and an event-dispatch listener; throwing there
            // would kill the batch (and, for the trigger listener, poison the
            // caller's transaction) over one agent's bad configuration. The row
            // exists, so the operator can SEE the failure and its reason instead
            // of watching a schedule silently do nothing.
            repository.failIfStillQueued(jobId, "SCHEMA_VIOLATION", schemaDefect, Instant.now());
            log.warn(
                    "Agent job {} (agent {}) failed at enqueue - the agent's result schema is unusable: {}",
                    jobId,
                    launch.agentId(),
                    schemaDefect);
            return jobId;
        }

        // Dispatch AFTER the enqueuing transaction commits, when there is one.
        // The event-trigger path calls this inside AgentEventTriggerFiring's
        // REQUIRES_NEW transaction, and an in-transaction dispatch hands the id
        // to a pool thread that can look the row up BEFORE the insert commits -
        // a spurious "vanished before it could be claimed" WARN plus up to a
        // full poll interval of added latency on every triggered job. The
        // schedule ticker and the HTTP path run with no active transaction and
        // keep the immediate dispatch, byte-for-byte.
        Runnable dispatch =
                () -> {
                    if (!enqueueOnly() && !executor.tryDispatch(jobId, tenantId)) {
                        meterRegistry.counter("agent.job.rejected").increment();
                    }
                };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatch.run();
                        }
                    });
        } else {
            dispatch.run();
        }
        return jobId;
    }

    @Override
    public Optional<AgentJobView> findLiveForSchedule(UUID scheduleId) {
        return repository
                .findFirstByScheduleIdAndStatusInOrderByCreatedAtDesc(
                        scheduleId, AgentJobStatus.live())
                .map(AgentJobView::of);
    }

    // ------------------------------------------------------------- internals

    private AgentJob require(UUID agentId, UUID jobId) {
        return repository
                .findByIdAndAgentId(jobId, agentId)
                .orElseThrow(() -> new AgentJobNotFoundException(jobId));
    }

    /**
     * Which result schema a job actually runs under: the per-job one when the caller supplied a
     * non-blank value, otherwise the agent's own {@code result_schema_json}.
     *
     * <p>This precedence is the whole point - without the agent-level fallback, {@code
     * agent.result_schema_json} is a column an admin can set through the API and that nothing ever
     * reads, so a job "succeeds" with prose for an agent explicitly configured to return structured
     * output.
     *
     * @return {@code null} when neither level declares one, which is the common case and means "no
     *     structured output for this job".
     */
    private String effectiveSchema(UUID agentId, String perJobSchemaOrNull) {
        if (perJobSchemaOrNull != null && !perJobSchemaOrNull.isBlank()) {
            return perJobSchemaOrNull;
        }
        return agentRepository
                .findById(agentId)
                .map(Agent::getResultSchemaJson)
                .filter(schema -> !schema.isBlank())
                .orElse(null);
    }

    /**
     * Compiles a schema and returns the defect message instead of throwing - for the non-HTTP
     * enqueue path, where there is no caller to hand a 400 to and throwing would abort a scheduler
     * batch.
     *
     * @return {@code null} when the schema is usable.
     */
    private String schemaDefectOrNull(String schemaJson) {
        try {
            schemaValidator.requireUsableSchema(schemaJson);
            return null;
        } catch (InvalidResultSchemaException unusable) {
            return unusable.getMessage();
        }
    }

    /**
     * Clamps rather than rejects. A client asking for four hours on a platform that allows one is
     * asking for "as long as you'll give me"; a 400 there teaches nothing and just makes
     * integrators hard-code the current maximum.
     */
    private int resolveTimeoutSeconds(Integer requested) {
        long max = properties.getMaxTimeout().toSeconds();
        if (requested == null || requested <= 0) {
            return (int) Math.min(properties.getDefaultTimeout().toSeconds(), max);
        }
        return (int) Math.min(requested, max);
    }

    private boolean canView(UUID agentId) {
        try {
            agentService.requireAccess(agentId, PermissionLevel.VIEWER);
            return true;
        } catch (RuntimeException notVisible) {
            return false;
        }
    }

    /**
     * One page of a list plus whether more rows follow it (fetched as limit + 1, so it is exact).
     */
    public record SummarySlice(List<AgentJobSummaryResponse> items, boolean hasMore) {}

    /**
     * Newest-first jobs across several of the caller's agents whose goal starts with {@code
     * goalPrefix}. Access is checked per agent (VIEWER) like every other read; an agent the caller
     * cannot view is dropped rather than failing the whole list.
     */
    public SummarySlice listForAgentsWithGoalPrefix(
            java.util.Collection<UUID> agentIds, String goalPrefix, int limit, int offset) {
        List<UUID> visible = new ArrayList<>();
        for (UUID agentId : agentIds) {
            try {
                agentService.requireAccess(agentId, PermissionLevel.VIEWER);
                visible.add(agentId);
            } catch (RuntimeException notVisible) {
                log.debug(
                        "Skipping agent {} in a job history list: {}",
                        agentId,
                        notVisible.toString());
            }
        }
        if (visible.isEmpty()) {
            return new SummarySlice(List.of(), false);
        }
        int size = Math.min(Math.max(limit, 1), 200);
        List<AgentJob> rows =
                repository.findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        visible, goalPrefix, new OffsetLimitRequest(Math.max(offset, 0), size + 1));
        boolean hasMore = rows.size() > size;
        List<AgentJob> page = hasMore ? rows.subList(0, size) : rows;
        return new SummarySlice(AgentJobListSupport.summarize(agentRepository, page), hasMore);
    }

    private static Set<String> rolesOf(TenantContext context) {
        return context.getRoles() == null ? Set.of() : Set.copyOf(context.getRoles());
    }
}
