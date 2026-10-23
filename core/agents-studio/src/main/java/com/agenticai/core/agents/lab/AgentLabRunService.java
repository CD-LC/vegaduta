package com.agenticai.core.agents.lab;

import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_CANCELLED;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_FAILED;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_QUEUED;
import static com.agenticai.core.agents.lab.AgentLabDtos.isTerminal;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCancelResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCapabilitiesResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the Agent Lab's two money-spending instruments - Prompt Knockout and
 * the Consistency check - as one generic thing: a list of cells (one model
 * call each, on the agent's model, under a chosen system prompt), optionally
 * a judge score per answer, optionally a same-meaning verdict per pair of
 * answers. Which cells mean what is the web's business; this service knows
 * only keys.
 *
 * <h2>Fire and poll</h2>
 * A run is up to 120 serial model calls - minutes, far past any proxy's
 * request timeout - so {@link #start} validates on the request thread,
 * answers at once, and a bounded worker ({@link AgentLabExecutor}) does the
 * calls. State is one document in {@link AgentLabRunStore}, rewritten before
 * every model call; the client polls {@link #get}.
 *
 * <h2>Spend control</h2>
 * Hard caps on cells and calls, one live run per user (a lock that stores the
 * run id), a 20-minute deadline, cooperative cancel, and an immediate stop on
 * any limit-type failure (quota, budget, rate limit, tenant stopped) instead
 * of burning the remaining calls against the same wall. Every call goes
 * through {@link AgentLabCompletionService#complete}, which builds its client
 * per call so the budget and rate-limit gates fire each time.
 *
 * <p>The plan gate reads the STORED plan ({@link TenantLimits#storedPlan}),
 * not the effective one the quality controllers' gate reads. The maintainers decided
 * this on 2026-09-30, once the Lab was live: with platform free mode on the
 * effective plan is UNLIMITED for every tenant, which would open a
 * 120-call fan-out to every stored-FREE tenant on the hosted pool. Stored
 * PREMIUM/UNLIMITED (including an active complimentary grant, which is
 * written onto the row) pass at full caps; stored FREE gets the tighter
 * {@link #FREE_CAPS} while {@code app.agent-lab.free-plan-enabled} is on
 * (the 2026-10-02 research ungating - a bounded fan-out, not the 403 the
 * gate originally returned), and 403s when the flag is off. The gate is
 * still not a guarantee that a run stays off the hosted pool: a
 * paid tenant that never chose its own provider is served by the pool by
 * default, bounded by the caps above plus the pool's token quota and the
 * per-user budget, both enforced inside the completion service.
 *
 * <h2>Dead or just slow</h2>
 * A run is dead when the JVM that holds it is gone, and that is not something
 * the time since its last document write can show: a run waiting in the queue
 * writes nothing for as long as both workers are busy, and one model call may
 * legitimately block for ten minutes. So every run this JVM has queued or is
 * running is re-marked alive in the store on a timer ({@link
 * #refreshLiveness}, every {@value #HEARTBEAT_SECONDS} seconds, with a TTL of
 * three periods). A live run whose mark has lapsed lost its JVM; {@link #get}
 * and the lock self-heal in {@link #start} fail it and free the user's lock.
 *
 * <h2>Who may write the document</h2>
 * The worker owns it while the run is live. Its every write first re-reads
 * the stored document and stops if that is missing or already terminal, and
 * after every non-terminal write it checks the cancel flag before doing
 * anything else.
 *
 * <p>Anything else may only <em>end</em> a run, and only through {@link
 * AgentLabRunStore#replaceIfUnchanged} - a compare-and-set against the
 * document it read, so it can never overwrite newer progress or a final
 * SUCCEEDED with an older copy. There are three such writes: failing a run
 * whose liveness mark lapsed, failing a run that waited in the queue past the
 * deadline, and cancelling a run that is still queued. Where a worker could
 * be racing (the first and the last), the cancel flag is raised
 * <em>first</em> - so even a worker that was mid-write and overwrote the
 * terminal document stops at its very next step, with a terminal status.
 */
@Service
public class AgentLabRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentLabRunService.class);

    static final int MAX_CELLS = 60;
    static final int MAX_CALLS = 120;
    static final int MAX_SYSTEM_PROMPTS = 16;
    static final int MAX_SIMILARITY_CELLS = 8;

    /**
     * Stored-FREE caps when {@code app.agent-lab.free-plan-enabled} opens the
     * lab to research users (2026-10-02 ungating): the 2026-09-30 stored-plan
     * gate exists because a 120-call fan-out on the hosted pool is real money,
     * not because the feature should be Premium-only. 12 cells / 24 calls /
     * 4 prompts is enough for a meaningful knockout or consistency study while
     * staying inside what the pool's token quota and the per-user budget
     * already bound anyway.
     */
    static final int FREE_MAX_CELLS = 12;
    static final int FREE_MAX_CALLS = 24;
    static final int FREE_MAX_SYSTEM_PROMPTS = 4;
    static final int FREE_MAX_SIMILARITY_CELLS = 4;

    private static final LabRunPlanner.LabCaps PAID_CAPS = new LabRunPlanner.LabCaps(
        MAX_CELLS, MAX_CALLS, MAX_SYSTEM_PROMPTS, MAX_SIMILARITY_CELLS);
    private static final LabRunPlanner.LabCaps FREE_CAPS = new LabRunPlanner.LabCaps(
        FREE_MAX_CELLS, FREE_MAX_CALLS, FREE_MAX_SYSTEM_PROMPTS, FREE_MAX_SIMILARITY_CELLS);

    static final int MAX_PROMPT_CHARS = 20000;
    static final int MAX_MESSAGE_CHARS = 8000;
    static final int MAX_EXPECTED_CHARS = 4000;
    static final int MAX_HISTORY = 20;
    static final int MAX_HISTORY_CHARS = 4000;
    static final int MAX_ANSWER_CHARS = 6000;
    static final int MAX_KEY_CHARS = 120;
    static final int MAX_CELL_ERROR_CHARS = 300;

    static final int RUN_TTL_MINUTES = 60;
    static final int RUN_DEADLINE_MINUTES = 20;

    /**
     * How often every run this JVM holds is re-marked alive. A third of the
     * mark's TTL ({@code RedisAgentLabRunStore.ALIVE_TTL}), so two refreshes
     * in a row can be lost before a healthy run reads as dead.
     */
    static final int HEARTBEAT_SECONDS = 30;

    static final String PLAN_MESSAGE =
        "Agent Lab knockout and consistency runs are a Premium feature - upgrade your plan to use them.";
    static final String CONFLICT_MESSAGE =
        "A lab run is already in progress. Wait for it to finish or cancel it.";
    static final String SAVE_FAILED_MESSAGE = "The lab could not save the run. Try again in a minute.";
    static final String READ_FAILED_MESSAGE = "The lab could not read the run. Try again in a minute.";
    static final String BUSY_MESSAGE = "The lab is busy. Try again in a minute.";
    static final String NOT_FOUND_MESSAGE = "Lab run not found or expired.";
    static final String DEADLINE_MESSAGE = "The 20-minute time limit was reached. Results so far are kept.";
    static final String STALE_MESSAGE =
        "The run stopped responding (the server may have restarted). Start it again.";
    static final String QUEUE_TIMEOUT_MESSAGE =
        "The lab was busy and the run waited past the 20-minute time limit without starting. Start it again.";
    static final String EMPTY_ANSWER_MESSAGE = "The model returned an empty answer.";
    static final String TENANT_STOPPED_MESSAGE =
        "This workspace is not usable right now, so the run was stopped. Results so far are kept.";
    static final String INTERNAL_FAILURE_MESSAGE =
        "The run stopped because of an internal error. Results so far are kept.";
    static final String GENERIC_CALL_FAILURE = "The model call failed. Try again.";

    private final AgentService agentService;
    private final TenantLimits tenantLimits;
    private final TenantStatusGuard tenantStatusGuard;
    private final AgentLabCompletionService completionService;
    private final AgentLabJudge judge;
    private final AgentLabRunStore store;
    private final AgentLabExecutor executor;
    private final LongSupplier clock;
    private final AgentLabRunWorker worker;
    /**
     * {@code app.agent-lab.free-plan-enabled} - when true, stored-FREE tenants
     * may start lab runs under {@link #FREE_CAPS}; when false the pre-ungating
     * 403 applies. Default on (the explicit 2026-10-02 ungating decision).
     */
    private final boolean freePlanEnabled;

    /**
     * A run this JVM has queued or is running, with the task it handed to
     * the executor - kept so the run can be re-marked alive on the timer and
     * so a run that is ended while still queued can be taken out of the queue.
     */
    private record LiveRun(String tenantId, Runnable task) {}

    private final ConcurrentMap<String, LiveRun> liveRuns = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    /** The 400 for a run that asks for the agent's tools where the mode is off (app.agent-lab.with-tools.enabled). */
    static final String WITH_TOOLS_UNAVAILABLE_MESSAGE =
        "Running the lab with the agent's tools is turned off on this install (app.agent-lab.with-tools.enabled)."
            + " Run it without tools, or ask an administrator to enable it.";

    @Autowired
    public AgentLabRunService(
        AgentService agentService,
        TenantLimits tenantLimits,
        TenantStatusGuard tenantStatusGuard,
        AgentLabCompletionService completionService,
        AgentLabJudge judge,
        AgentLabRunStore store,
        AgentLabExecutor executor,
        @Value("${app.agent-lab.free-plan-enabled:true}") boolean freePlanEnabled
    ) {
        this(agentService, tenantLimits, tenantStatusGuard, completionService, judge, store, executor,
            System::currentTimeMillis, freePlanEnabled);
    }

    /**
     * Test seam: the run deadline and the queue-wait limit run on {@code
     * clock} (epoch milliseconds). No heartbeat thread is started here - that
     * is {@link #startHeartbeat}, which only the container calls.
     */
    AgentLabRunService(
        AgentService agentService,
        TenantLimits tenantLimits,
        TenantStatusGuard tenantStatusGuard,
        AgentLabCompletionService completionService,
        AgentLabJudge judge,
        AgentLabRunStore store,
        AgentLabExecutor executor,
        LongSupplier clock,
        boolean freePlanEnabled
    ) {
        this.agentService = agentService;
        this.tenantLimits = tenantLimits;
        this.tenantStatusGuard = tenantStatusGuard;
        this.completionService = completionService;
        this.judge = judge;
        this.store = store;
        this.executor = executor;
        this.clock = clock;
        this.freePlanEnabled = freePlanEnabled;
        this.worker = new AgentLabRunWorker(
            tenantStatusGuard, completionService, judge, store, clock, liveRuns::remove);
    }

    // ------------------------------------------------------------------
    // Liveness heartbeat
    // ------------------------------------------------------------------

    /** Started by Spring only; tests drive {@link #refreshLiveness} by hand. */
    @PostConstruct
    public synchronized void startHeartbeat() {
        if (heartbeat != null) {
            return;
        }
        heartbeat = TenantAwareExecutors.newSingleThreadScheduledExecutor("agent-lab-heartbeat", true);
        heartbeat.scheduleWithFixedDelay(
            this::refreshLiveness, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    /**
     * Re-marks every run this JVM holds - queued or running - as alive. Must
     * never throw: one escaped exception would silently cancel the schedule,
     * and every healthy run would read as dead a minute and a half later.
     */
    void refreshLiveness() {
        for (Map.Entry<String, LiveRun> held : liveRuns.entrySet()) {
            try {
                store.markAlive(held.getValue().tenantId(), held.getKey());
            } catch (RuntimeException e) {
                log.warn("Could not refresh the liveness mark of lab run {} - retrying in {}s",
                    held.getKey(), HEARTBEAT_SECONDS, e);
            }
        }
    }

    /** Stops the heartbeat for a run and, if it never started, takes it out of the executor's queue. */
    private void forget(String runId) {
        LiveRun held = liveRuns.remove(runId);
        if (held != null) {
            executor.remove(held.task());
        }
    }

    /** How many runs this JVM is keeping alive. Test visibility. */
    int liveRunCount() {
        return liveRuns.size();
    }

    // ------------------------------------------------------------------
    // Request-thread API
    // ------------------------------------------------------------------

    /**
     * What this tenant may run and the limits the client mirrors in its own
     * estimate. Caps are plan-scoped: stored-FREE tenants (allowed when {@code
     * app.agent-lab.free-plan-enabled}) get the tighter {@link #FREE_CAPS}
     * values so their own estimate matches what {@link #start} would accept.
     */
    public LabCapabilitiesResponse capabilities(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        TenantPlan stored = tenantLimits.storedPlan(requireTenantId());
        boolean allowed = stored != TenantPlan.FREE || freePlanEnabled;
        LabRunPlanner.LabCaps caps = stored == TenantPlan.FREE ? FREE_CAPS : PAID_CAPS;
        return new LabCapabilitiesResponse(
            allowed, allowed ? null : "PLAN",
            caps.cells(), caps.calls(), caps.systemPrompts(), caps.similarityCells(), RUN_TTL_MINUTES,
            completionService.withToolsAvailable());
    }

    /**
     * Validates, takes the caller's one-live-run lock, stores the QUEUED run
     * and hands it to the worker pool.
     *
     * @throws TenantLimitExceededException FREE plan (403)
     * @throws IllegalArgumentException a request the caps or shape rules reject (400)
     * @throws ResponseStatusException 409 the caller already has a live run, 429 the pool is
     *     full, 503 the run could not be stored
     */
    public LabRunResponse start(UUID agentId, LabRunRequest request, String subject) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        TenantContext context = TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("A lab run needs a tenant context."));
        String tenantId = context.getTenantId();
        String requester = requireSubject(subject, context);
        // Same exception type as the quality controllers' assertPremiumPlan, but on
        // the stored plan so free mode cannot open this fan-out to everyone (class doc).
        // Stored-FREE is allowed when freePlanEnabled, at the tighter FREE_CAPS bounds.
        TenantPlan storedPlan = tenantLimits.storedPlan(tenantId);
        if (storedPlan == TenantPlan.FREE && !freePlanEnabled) {
            throw new TenantLimitExceededException(PLAN_MESSAGE);
        }
        LabRunPlanner.LabCaps caps = storedPlan == TenantPlan.FREE ? FREE_CAPS : PAID_CAPS;

        String runId = UUID.randomUUID().toString();
        LabRunPlanner.RunPlan plan = LabRunPlanner.validate(request, runId, agentId, tenantId, requester, clock.getAsLong(), caps);
        // Asked for the agent's tools but this install does not offer that: refused up front with a sentence,
        // not run as a tool-less lab (the answers would not mean what the person asked) or failed cell by cell.
        if (plan.withTools() && !completionService.withToolsAvailable()) {
            throw new IllegalArgumentException(WITH_TOOLS_UNAVAILABLE_MESSAGE);
        }

        boolean locked;
        try {
            locked = acquireLock(tenantId, requester, runId);
        } catch (RuntimeException e) {
            throw storeUnavailable(SAVE_FAILED_MESSAGE, e);
        }
        if (!locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, CONFLICT_MESSAGE);
        }

        LabRunDocument queued = new AgentLabRunWorker.RunState(plan).snapshot(plan.createdAtMs());
        try {
            // The mark before the document: whoever can read the run must
            // also find it alive, or the first poll would fail it as dead.
            store.markAlive(tenantId, runId);
            store.save(tenantId, queued);
        } catch (RuntimeException e) {
            releaseLockQuietly(tenantId, requester, runId);
            // A real fault (Redis is down), so the 5xx Issues-portal record is wanted.
            throw storeUnavailable(SAVE_FAILED_MESSAGE, e);
        }

        // Snapshot on the request thread: TenantContext is a plain ThreadLocal
        // and the worker thread has none of its own.
        TenantContext snapshot = TenantContext.of(tenantId, context.getSubject(), context.getRoles());
        Runnable task = () -> worker.run(plan, snapshot);
        // Registered before it is submitted: the worker deregisters itself
        // when it ends, and with a free thread that can be before trySubmit
        // returns - registering afterwards would leave a finished run on the
        // heartbeat for good.
        liveRuns.put(runId, new LiveRun(tenantId, task));
        if (!executor.trySubmit(task)) {
            liveRuns.remove(runId);
            try {
                store.delete(tenantId, runId);
            } catch (RuntimeException e) {
                log.warn("Could not delete rejected lab run {} - it will expire", runId, e);
            }
            releaseLockQuietly(tenantId, requester, runId);
            // 429, not 503: saturation is an expected state of a 2-thread pool,
            // and a 5xx here would file an Issues-portal record per rejection.
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, BUSY_MESSAGE);
        }
        return queued.run();
    }

    /**
     * The caller's own run on this agent. A live run that lost its JVM (its
     * liveness mark lapsed), or that waited in the queue past the deadline,
     * is failed here and its lock freed, so the user is not shut out until
     * the lock's own TTL. A run that is merely queued, or inside a long model
     * call, is reported as it is.
     *
     * @throws ResponseStatusException 404 when the run is unknown, expired, another user's or
     *     another agent's - one answer for all four, so a run id cannot be probed
     */
    public LabRunResponse get(UUID agentId, String runId, String subject) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        String tenantId = requireTenantId();
        LabRunDocument doc = lookup(tenantId, agentId, runId, subject);
        if (isTerminal(doc.run().status())) {
            return doc.run();
        }
        LabRunDocument current;
        boolean cancelFlagged = false;
        try {
            current = settle(tenantId, doc);
            // cancel() on a running run only raises the flag; the worker folds
            // it into the document at its next write. Until then the poll reports it.
            if (current != null && !isTerminal(current.run().status()) && !current.run().cancelRequested()) {
                cancelFlagged = store.isCancelRequested(tenantId, current.run().runId());
            }
        } catch (RuntimeException e) {
            throw storeUnavailable(READ_FAILED_MESSAGE, e);
        }
        if (current == null) {
            throw notFound();
        }
        return cancelFlagged ? withCancelRequested(current.run()) : current.run();
    }

    /**
     * Asks a live run to stop. For a running run this is cooperative: the
     * worker sees the flag before its next model call, so at most the call
     * already in flight still completes. A run that is still queued has
     * nothing in flight, so it is ended here and now - CANCELLED, lock freed,
     * its slot in the queue given back - instead of making the user wait for
     * a worker to come free just to discard it. A run that already ended is
     * left alone.
     */
    public LabCancelResponse cancel(UUID agentId, String runId, String subject) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        String tenantId = requireTenantId();
        LabRunDocument doc = lookup(tenantId, agentId, runId, subject);
        LabRunResponse run = doc.run();
        if (!isTerminal(run.status())) {
            try {
                store.requestCancel(tenantId, run.runId());
            } catch (RuntimeException e) {
                // Unlike a chat cancel, answering 202 here would be a lie
                // that keeps spending: the run would go on to its last call.
                throw storeUnavailable("The lab could not record the cancel request. Try again.", e);
            }
            if (RUN_QUEUED.equals(run.status())) {
                try {
                    // Flag first (above), so a worker that picks the run up
                    // at this very moment still stops before its first call.
                    endFromOutside(tenantId, doc, RUN_CANCELLED, null, true);
                } catch (RuntimeException e) {
                    // The flag is recorded, so the run still cancels - just
                    // not until a worker reaches it.
                    log.warn("Could not end queued lab run {} at once - its worker will cancel it", run.runId(), e);
                }
            }
        }
        return new LabCancelResponse(run.runId(), true);
    }

    // ------------------------------------------------------------------
    // Lookup, lock and ending a run from outside its worker
    // ------------------------------------------------------------------

    private LabRunDocument lookup(String tenantId, UUID agentId, String runId, String subject) {
        String requester = requireSubject(subject, TenantContext.current().orElse(null));
        // Run ids are UUIDs we minted. Anything else cannot name a run, and
        // must not reach the store as part of a key.
        String canonicalRunId;
        try {
            canonicalRunId = UUID.fromString(runId).toString();
        } catch (RuntimeException e) {
            throw notFound();
        }
        Optional<LabRunDocument> found;
        try {
            found = store.find(tenantId, canonicalRunId);
        } catch (RuntimeException e) {
            // Not a 404: the client forgets a run it is told does not exist.
            throw storeUnavailable(READ_FAILED_MESSAGE, e);
        }
        LabRunDocument doc = found.orElseThrow(AgentLabRunService::notFound);
        if (!agentId.toString().equals(doc.run().agentId()) || !requester.equals(doc.requestedBy())) {
            throw notFound();
        }
        return doc;
    }

    /**
     * Takes the lock, clearing a holder that can no longer be running. The
     * lock's TTL would free it eventually, but "eventually" is up to 25
     * minutes of 409 for a user whose run died with a restart and whose
     * browser no longer knows the run id to poll.
     *
     * <p>A holder with no document is left alone: that is exactly what a
     * concurrent {@link #start} looks like between taking the lock and
     * writing its QUEUED document.
     */
    private boolean acquireLock(String tenantId, String subject, String runId) {
        if (store.acquireLock(tenantId, subject, runId)) {
            return true;
        }
        Optional<String> holder = store.lockHolder(tenantId, subject);
        if (holder.isEmpty()) {
            return store.acquireLock(tenantId, subject, runId);
        }
        Optional<LabRunDocument> held = store.find(tenantId, holder.get());
        if (held.isEmpty()) {
            return false;
        }
        LabRunDocument current = settle(tenantId, held.get());
        if (current == null || !isTerminal(current.run().status())) {
            return false;
        }
        store.releaseLock(tenantId, subject, current.run().runId());
        return store.acquireLock(tenantId, subject, runId);
    }

    /**
     * Applies the two rules by which a live run is ended from outside its
     * worker, and returns the document as it stands afterwards.
     *
     * <ul>
     *   <li>Its liveness mark is gone: the JVM that held it died. FAILED.</li>
     *   <li>It is still QUEUED past the run deadline: the workers were busy
     *       for that long. It has spent nothing and would be failed by the
     *       deadline the moment a worker reached it; FAILED now, so the user
     *       is not left watching a run that can no longer do anything.</li>
     * </ul>
     *
     * <p>Time since the last write is deliberately not a rule: a queued run
     * writes nothing, and one model call can block for ten minutes.
     *
     * @return the unchanged document when neither rule applies; the terminal document when this
     *     call ended the run; the freshly read document when the run changed under this call (its
     *     worker just wrote); null when the document is gone
     */
    private LabRunDocument settle(String tenantId, LabRunDocument doc) {
        LabRunResponse run = doc.run();
        if (isTerminal(run.status())) {
            return doc;
        }
        String reason;
        if (!store.isAlive(tenantId, run.runId())) {
            // Flag first - see the class javadoc for why that order is what
            // keeps a worker that is in fact still there from resurrecting the run.
            store.requestCancel(tenantId, run.runId());
            reason = STALE_MESSAGE;
        } else if (RUN_QUEUED.equals(run.status())
            && clock.getAsLong() - run.createdAtMs() >= TimeUnit.MINUTES.toMillis(RUN_DEADLINE_MINUTES)) {
            reason = QUEUE_TIMEOUT_MESSAGE;
        } else {
            return doc;
        }
        LabRunDocument failed = endFromOutside(tenantId, doc, RUN_FAILED, reason, run.cancelRequested());
        if (failed != null) {
            log.warn("Lab run {} ended from outside its worker: {}", run.runId(), reason);
            return failed;
        }
        return store.find(tenantId, run.runId()).orElse(null);
    }

    /**
     * Ends a run on behalf of something that is not its worker: terminal
     * document (compare-and-set against {@code doc}), then the lock, then
     * this JVM's own hold on the run. Nothing but the document write can
     * lose a race, and losing it means the worker got there first - then
     * nothing else is touched.
     *
     * @return the terminal document, or null when the stored run is no longer the one in {@code doc}
     */
    private LabRunDocument endFromOutside(
        String tenantId, LabRunDocument doc, String status, String errorMessage, boolean cancelRequested
    ) {
        LabRunResponse run = doc.run();
        long now = clock.getAsLong();
        LabRunDocument ended = new LabRunDocument(
            new LabRunResponse(
                run.runId(), run.agentId(), run.kind(), status,
                run.plannedCalls(), run.completedCalls(), cancelRequested, errorMessage,
                run.createdAtMs(), now, now, run.cells(), run.similarity()),
            doc.requestedBy());
        if (!store.replaceIfUnchanged(tenantId, doc, ended)) {
            return null;
        }
        releaseLockQuietly(tenantId, doc.requestedBy(), run.runId());
        forget(run.runId());
        return ended;
    }

    private static LabRunResponse withCancelRequested(LabRunResponse run) {
        return new LabRunResponse(
            run.runId(), run.agentId(), run.kind(), run.status(),
            run.plannedCalls(), run.completedCalls(), true, run.errorMessage(),
            run.createdAtMs(), run.updatedAtMs(), run.finishedAtMs(), run.cells(), run.similarity());
    }

    private void releaseLockQuietly(String tenantId, String subject, String runId) {
        worker.releaseLockQuietly(tenantId, subject, runId);
    }

    private static String requireTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("The lab needs a tenant context."));
    }

    /** The principal's name; the JWT subject on the tenant context when the controller had none. */
    private static String requireSubject(String subject, TenantContext context) {
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        if (context != null && context.getSubject() != null && !context.getSubject().isBlank()) {
            return context.getSubject();
        }
        throw new IllegalStateException("The lab needs an authenticated user.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    private static ResponseStatusException storeUnavailable(String message, RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    /** See {@link LabRunPlanner#plannedCalls}. */
    static int plannedCalls(int cells, boolean judgeScoring, boolean judgeSimilarity) {
        return LabRunPlanner.plannedCalls(cells, judgeScoring, judgeSimilarity);
    }

}
