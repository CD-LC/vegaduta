package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskDtos.APPROVED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.AWAITING_APPROVAL;
import static com.agenticai.core.agents.coding.CodingTaskDtos.CANCELLED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.FAILED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.QUEUED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.ROLLED_BACK;
import static com.agenticai.core.agents.coding.CodingTaskDtos.isTerminal;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import com.agenticai.core.agents.coding.CodingTaskDtos.CancelResponse;
import com.agenticai.core.agents.coding.CodingTaskDtos.Capabilities;
import com.agenticai.core.agents.coding.CodingTaskDtos.DiffResponse;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.sdlc.harness.CodingCommandPolicy;
import com.agenticai.core.sdlc.harness.CodingHarnessLoop;
import com.agenticai.core.sdlc.harness.CodingSandbox;
import com.agenticai.core.sdlc.harness.CodingSandboxFactory;
import com.agenticai.core.sdlc.harness.GitUrlPolicy;
import com.agenticai.core.sdlc.harness.GitWorkspace;
import com.agenticai.core.sdlc.harness.HarnessApprovalGate;
import com.agenticai.core.sdlc.harness.RepoArchive;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coding tasks: starts a {@link CodingHarnessLoop} run in an isolated sandbox against a repository
 * the caller supplied (an uploaded archive or a public https git URL), reports its progress, and
 * holds the human approval gate.
 *
 * <h2>What is enforced where</h2>
 * <ul>
 *   <li><b>Flag</b>: {@code app.coding-harness.enabled} off (the default) = every route but
 *       {@code /capabilities} answers 404 and the {@code coding_task} tool is never offered.</li>
 *   <li><b>Access</b>: RUNNER on the agent (its model is what writes the code), the same bar as
 *       chatting with it; the plan gate reads the STORED plan like the Agent Lab.</li>
 *   <li><b>Isolation</b>: nothing runs on the core host. The sandbox ({@link CodingSandbox}) is E2B by
 *       default; its test handle only accepts allowlisted runner commands.</li>
 *   <li><b>Inputs</b>: archives are re-packed with zip-slip, link, size and entry caps
 *       ({@link RepoArchive}); git URLs go through {@link GitUrlPolicy}; the test command through
 *       {@link CodingCommandPolicy}; the budget is clamped to server caps.</li>
 *   <li><b>Approval</b>: the harness blocks on a {@link HarnessApprovalGate} that only a decision
 *       written by {@link #approve} - bound to the hash of the exact diff the user saw - can open. A
 *       missing decision, a timeout, a cancel, or any error is a denial. There is no setting, request
 *       field or tool argument that skips it. Only the task's own requester can decide.</li>
 *   <li><b>Tenancy</b>: every key is tenant-first and every lookup also checks the requester; another
 *       user's or tenant's task is a plain 404.</li>
 * </ul>
 *
 * <p>Only {@link #approve}/{@link #reject} can answer the gate; the {@code coding_task} agent tool can
 * start a task and read its diff but has no way to decide it.
 */
@Service
public class CodingTaskService {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskService.class);

    static final String MSG_OFF = "Coding tasks are not available on this deployment.";
    static final String MSG_PLAN = "Coding tasks are a paid-plan feature - upgrade your plan to use them.";
    static final String MSG_NOT_FOUND = "Coding task not found or expired.";
    static final String MSG_CONFLICT = "A coding task is already in progress. Wait for it to finish or cancel it.";
    static final String MSG_BUSY = "Coding tasks are busy right now. Try again in a minute.";
    static final String MSG_SAVE_FAILED = "The coding task could not be saved. Try again in a minute.";
    static final String MSG_READ_FAILED = "The coding task could not be read. Try again in a minute.";
    static final String MSG_STALE = "The task stopped responding (the server may have restarted). Start it again.";
    static final String MSG_TENANT_STOPPED = "This workspace is not usable right now, so the task was stopped.";
    static final String MSG_INTERNAL = "The task stopped because of an internal error.";
    static final String MSG_NOT_WAITING = "This task is not waiting for approval.";
    static final String MSG_DIFF_CHANGED = "The change shown to you is no longer the current one. Reload the task.";
    static final String MSG_TOO_LARGE = "The change is too large to review (over %d characters), so it was not applied.";

    /** A diff longer than this cannot be meaningfully reviewed by a person; the task fails instead of asking. */
    static final int MAX_DIFF_CHARS = 300_000;
    static final int MAX_EVENTS = 200;
    static final int HEARTBEAT_SECONDS = 30;

    /** What the controller (or the tool) hands over, already parsed. {@code archive} is raw bytes, not yet sanitised. */
    public record StartInput(
        UUID agentId, String instruction, String testCommand, String repoUrl, byte[] archive,
        Integer maxSteps, Integer maxWallSeconds, Integer maxTokens, Integer maxFixIterations
    ) {}

    /** Seam: how a model call is made for a task (production: the agent's own model via the Lab completion service). */
    @FunctionalInterface
    interface ModelFactory {
        CodingModel create(UUID agentId, String subject, String taskId);
    }

    /** Seam: how a sandbox is opened (production: {@link CodingSandboxFactory}). */
    interface SandboxOpener {
        CodingSandbox open(int ttlSeconds);

        String notReadyReason();

        CodingCommandPolicy policy();

        String runnerName();
    }

    record Plan(
        String taskId, String tenantId, String subject, UUID agentId, String instruction, String testCommand,
        String sourceKind, String repoUrl, byte[] zip, Budget budget, long createdAtMs
    ) {}

    private record LiveTask(String tenantId, Runnable task) {}

    private record LiveSession(String tenantId, CodingSandbox sandbox, GitWorkspace workspace, String baseline) {}

    private final CodingTaskSettings settings;
    private final CodingTaskStore store;
    private final CodingTaskExecutor executor;
    private final AgentService agentService;
    private final TenantLimits tenantLimits;
    private final TenantStatusGuard tenantStatusGuard;
    private final SandboxOpener sandboxes;
    private final ModelFactory models;
    private final GitUrlPolicy gitPolicy;
    private final LongSupplier clock;
    private final long pollMillis;
    private final CodingTaskWorker worker;

    private final Map<String, LiveTask> liveTasks = new ConcurrentHashMap<>();
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Autowired
    public CodingTaskService(
        CodingTaskSettings settings,
        CodingTaskStore store,
        CodingTaskExecutor executor,
        AgentService agentService,
        TenantLimits tenantLimits,
        TenantStatusGuard tenantStatusGuard,
        CodingSandboxFactory sandboxFactory,
        AgentLabCompletionService completionService
    ) {
        this(settings, store, executor, agentService, tenantLimits, tenantStatusGuard,
            CodingTaskSeams.sandboxOpener(sandboxFactory),
            (agentId, subject, taskId) -> new CodingTaskSeams.LabCompletionModel(completionService, agentId, subject, taskId),
            new GitUrlPolicy(settings.gitAllowedHosts()), System::currentTimeMillis, 1000L);
    }

    /** Test seam: every collaborator that touches the outside world is replaceable. */
    CodingTaskService(
        CodingTaskSettings settings, CodingTaskStore store, CodingTaskExecutor executor, AgentService agentService,
        TenantLimits tenantLimits, TenantStatusGuard tenantStatusGuard, SandboxOpener sandboxes, ModelFactory models,
        GitUrlPolicy gitPolicy, LongSupplier clock, long pollMillis
    ) {
        this.settings = settings;
        this.store = store;
        this.executor = executor;
        this.agentService = agentService;
        this.tenantLimits = tenantLimits;
        this.tenantStatusGuard = tenantStatusGuard;
        this.sandboxes = sandboxes;
        this.models = models;
        this.gitPolicy = gitPolicy;
        this.clock = clock;
        this.pollMillis = Math.max(5, pollMillis);
        this.worker = new CodingTaskWorker(settings, store, tenantStatusGuard, sandboxes, models, clock, this.pollMillis,
            liveTasks::remove, (taskId, tenantId, sandbox, workspace, baseline) -> {
                sessions.put(taskId, new LiveSession(tenantId, sandbox, workspace, baseline));
                scheduleReaper(taskId);
            });
    }

    // ------------------------------------------------------------------ lifecycle

    @PostConstruct
    public synchronized void startScheduler() {
        // Flag off (the default): no thread at all - this bean must be inert.
        if (scheduler != null || !settings.isEnabled()) {
            return;
        }
        scheduler = TenantAwareExecutors.newSingleThreadScheduledExecutor("coding-task-heartbeat", true);
        scheduler.scheduleWithFixedDelay(this::refreshLiveness, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        for (LiveSession session : sessions.values()) {
            session.sandbox().close();
        }
        sessions.clear();
    }

    /** Re-marks every task this JVM holds as alive. Must never throw (see the Agent Lab's identical note). */
    void refreshLiveness() {
        for (Map.Entry<String, LiveTask> held : liveTasks.entrySet()) {
            try {
                store.markAlive(held.getValue().tenantId(), held.getKey());
            } catch (RuntimeException e) {
                log.warn("Could not refresh the liveness mark of coding task {}", held.getKey(), e);
            }
        }
    }

    int liveTaskCount() {
        return liveTasks.size();
    }

    int sessionCount() {
        return sessions.size();
    }

    // ------------------------------------------------------------------ request-thread API

    /** Always answers for a signed-in caller: this is how the page learns the feature is off. */
    public Capabilities capabilities() {
        boolean on = settings.isEnabled();
        String reason = null;
        boolean allowed = on;
        if (on) {
            String tenantId = requireTenantId();
            if (tenantLimits.storedPlan(tenantId) == TenantPlan.FREE && !settings.isFreePlanEnabled()) {
                allowed = false;
                reason = "PLAN";
            } else if (sandboxes.notReadyReason() != null) {
                allowed = false;
                reason = "SETUP";
            }
        }
        return new Capabilities(on, allowed, reason, settings.runner(), List.copyOf(sandboxes.policy().allowedRunners().stream().sorted().toList()),
            settings.defaults(), settings.caps(), settings.maxArchiveBytes(), settings.approvalTimeoutSeconds(),
            settings.rollbackWindowSeconds(), settings.networkEnabled());
    }

    public boolean isEnabled() {
        return settings.isEnabled();
    }

    /**
     * Validates, takes the caller's one-live-task lock, stores the QUEUED task and hands it to the worker pool.
     *
     * @throws ResponseStatusException 404 flag off, 409 a live task exists, 412 not set up, 429 pool full
     * @throws TenantLimitExceededException the plan gate (403)
     * @throws IllegalArgumentException a request the validation rules reject (400)
     */
    public TaskView start(StartInput in, String subject) {
        requireEnabled();
        if (in == null || in.agentId() == null) {
            throw new IllegalArgumentException("An agent is required: its model writes the code.");
        }
        agentService.requireAccess(in.agentId(), PermissionLevel.RUNNER);
        TenantContext context = TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("A coding task needs a tenant context."));
        String tenantId = context.getTenantId();
        String requester = requireSubject(subject, context);
        if (tenantLimits.storedPlan(tenantId) == TenantPlan.FREE && !settings.isFreePlanEnabled()) {
            throw new TenantLimitExceededException(MSG_PLAN);
        }
        if (!tenantStatusGuard.isUsable(tenantId)) {
            throw new TenantLimitExceededException(MSG_TENANT_STOPPED);
        }
        String notReady = sandboxes.notReadyReason();
        if (notReady != null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, notReady);
        }

        String taskId = UUID.randomUUID().toString();
        Plan plan = CodingTaskSeams.validate(in, taskId, tenantId, requester, settings, sandboxes, gitPolicy, clock);

        boolean locked;
        try {
            locked = acquireLock(tenantId, requester, taskId);
        } catch (RuntimeException e) {
            throw unavailable(MSG_SAVE_FAILED, e);
        }
        if (!locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_CONFLICT);
        }

        CodingTaskState state = new CodingTaskState(plan, sandboxes.runnerName());
        TaskDocument queued = new TaskDocument(state.snapshot(plan.createdAtMs()), requester);
        try {
            store.markAlive(tenantId, taskId);
            store.save(tenantId, queued);
            store.index(tenantId, requester, taskId);
        } catch (RuntimeException e) {
            releaseLockQuietly(tenantId, requester, taskId);
            throw unavailable(MSG_SAVE_FAILED, e);
        }

        TenantContext snapshot = TenantContext.of(tenantId, context.getSubject(), context.getRoles());
        Runnable task = () -> worker.run(plan, snapshot);
        liveTasks.put(taskId, new LiveTask(tenantId, task));
        if (!executor.trySubmit(task)) {
            liveTasks.remove(taskId);
            try {
                store.delete(tenantId, taskId);
            } catch (RuntimeException e) {
                log.warn("Could not delete rejected coding task {} - it will expire", taskId, e);
            }
            releaseLockQuietly(tenantId, requester, taskId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, MSG_BUSY);
        }
        return queued.task();
    }

    public List<TaskView> list(String subject) {
        return list(subject, 0, CodingTaskListing.DEFAULT_LIMIT).tasks();
    }

    public CodingTaskListing.Page list(String subject, int offset, int limit) {
        requireEnabled();
        String tenantId = requireTenantId();
        String requester = requireSubject(subject, TenantContext.current().orElse(null));
        return CodingTaskListing.page(store, tenantId, requester, offset, limit, doc -> settle(tenantId, doc).task());
    }

    public TaskView get(String taskId, String subject) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = lookup(tenantId, taskId, subject);
        return settle(tenantId, doc).task();
    }

    /** The diff the human is asked to approve (and, after approval, the patch). */
    public DiffResponse diff(String taskId, String subject) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = lookup(tenantId, taskId, subject);
        TaskView t = doc.task();
        if (!t.diffAvailable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no change to show for this task.");
        }
        String diff;
        try {
            diff = store.findDiff(tenantId, t.taskId()).orElse(null);
        } catch (RuntimeException e) {
            throw unavailable(MSG_READ_FAILED, e);
        }
        if (diff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The change is no longer available.");
        }
        return new DiffResponse(t.taskId(), diff, t.diffHash(), false);
    }

    /**
     * Records the user's approval of exactly the diff they were shown. 409 unless the task is waiting
     * for approval and {@code diffHash} matches the current diff.
     */
    public TaskView approve(String taskId, String subject, String diffHash) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = settle(tenantId, lookup(tenantId, taskId, subject));
        TaskView t = doc.task();
        if (!AWAITING_APPROVAL.equals(t.status()) || !t.diffAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_NOT_WAITING);
        }
        if (diffHash == null || t.diffHash() == null || !constantTimeEquals(t.diffHash(), diffHash.strip())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_DIFF_CHANGED);
        }
        try {
            store.setDecision(tenantId, t.taskId(), "APPROVE:" + t.diffHash());
        } catch (RuntimeException e) {
            throw unavailable("The decision could not be recorded. Try again.", e);
        }
        return t;
    }

    public TaskView reject(String taskId, String subject) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = settle(tenantId, lookup(tenantId, taskId, subject));
        TaskView t = doc.task();
        if (!AWAITING_APPROVAL.equals(t.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_NOT_WAITING);
        }
        try {
            store.setDecision(tenantId, t.taskId(), "REJECT");
        } catch (RuntimeException e) {
            throw unavailable("The decision could not be recorded. Try again.", e);
        }
        return t;
    }

    /** Asks a live task to stop; a queued one is ended here and now. A finished task is left alone. */
    public CancelResponse cancel(String taskId, String subject) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = lookup(tenantId, taskId, subject);
        TaskView t = doc.task();
        if (!isTerminal(t.status())) {
            try {
                store.requestCancel(tenantId, t.taskId());
            } catch (RuntimeException e) {
                throw unavailable("The cancel request could not be recorded. Try again.", e);
            }
            if (QUEUED.equals(t.status())) {
                try {
                    LiveTask held = liveTasks.remove(t.taskId());
                    if (held != null) {
                        executor.remove(held.task());
                    }
                    store.save(tenantId, new TaskDocument(copyWith(t, CANCELLED, null, "Cancelled before it started."), doc.requestedBy()));
                    releaseLockQuietly(tenantId, doc.requestedBy(), t.taskId());
                } catch (RuntimeException e) {
                    log.warn("Could not end queued coding task {} at once - its worker will cancel it", t.taskId(), e);
                }
            }
        }
        return new CancelResponse(t.taskId(), true);
    }

    /**
     * After approval: resets the sandbox workspace to the baseline and drops the patch. Works inside
     * {@code app.coding-harness.rollback-window-seconds}; later the sandbox is gone, so only the
     * stored patch is discarded.
     */
    public TaskView rollback(String taskId, String subject) {
        requireEnabled();
        String tenantId = requireTenantId();
        TaskDocument doc = lookup(tenantId, taskId, subject);
        TaskView t = doc.task();
        if (!APPROVED.equals(t.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an approved task can be rolled back.");
        }
        String message;
        LiveSession session = sessions.remove(t.taskId());
        if (session != null && session.tenantId().equals(tenantId)) {
            try {
                session.workspace().rollbackTo(session.baseline());
                message = "Rolled back: the sandbox workspace was reset and the patch was discarded.";
            } catch (RuntimeException e) {
                log.warn("Rollback of coding task {} failed", t.taskId(), e);
                message = "The patch was discarded. The sandbox workspace could not be reset, but it is deleted anyway.";
            } finally {
                session.sandbox().close();
            }
        } else {
            message = "The patch was discarded. The sandbox had already been released.";
        }
        try {
            store.deleteDiff(tenantId, t.taskId());
            TaskView rolled = copyWith(t, ROLLED_BACK, t.outcome(), message);
            rolled = withDiff(rolled, false);
            store.save(tenantId, new TaskDocument(rolled, doc.requestedBy()));
            return rolled;
        } catch (RuntimeException e) {
            throw unavailable(MSG_SAVE_FAILED, e);
        }
    }

    /**
     * For the {@code coding_task} tool: polls until the task is waiting for approval, ended, or
     * {@code waitSeconds} passed. Never decides anything.
     */
    public TaskView awaitForTool(String taskId, String subject, int waitSeconds) {
        long deadline = clock.getAsLong() + Math.max(1, waitSeconds) * 1000L;
        TaskView view = get(taskId, subject);
        while (!AWAITING_APPROVAL.equals(view.status()) && !isTerminal(view.status()) && clock.getAsLong() < deadline) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            view = get(taskId, subject);
        }
        return view;
    }

    public int toolWaitSeconds() {
        return settings.toolWaitSeconds();
    }

    // ------------------------------------------------------------------ lookup / settle / lock

    private TaskDocument lookup(String tenantId, String taskId, String subject) {
        String requester = requireSubject(subject, TenantContext.current().orElse(null));
        String canonical;
        try {
            canonical = UUID.fromString(taskId).toString();
        } catch (RuntimeException e) {
            throw notFound();
        }
        Optional<TaskDocument> found;
        try {
            found = store.find(tenantId, canonical);
        } catch (RuntimeException e) {
            throw unavailable(MSG_READ_FAILED, e);
        }
        TaskDocument doc = found.orElseThrow(CodingTaskService::notFound);
        if (!requester.equals(doc.requestedBy())) {
            throw notFound();
        }
        return doc;
    }

    /** A live task whose liveness mark lapsed lost its JVM: fail it and free the user's lock. */
    private TaskDocument settle(String tenantId, TaskDocument doc) {
        TaskView t = doc.task();
        if (isTerminal(t.status()) || liveTasks.containsKey(t.taskId())) {
            return doc;
        }
        try {
            if (store.isAlive(tenantId, t.taskId())) {
                return doc;
            }
            TaskView failed = copyWith(t, FAILED, "ERROR", MSG_STALE);
            TaskDocument ended = new TaskDocument(failed, doc.requestedBy());
            store.requestCancel(tenantId, t.taskId());
            store.save(tenantId, ended);
            store.releaseLock(tenantId, doc.requestedBy(), t.taskId());
            return ended;
        } catch (RuntimeException e) {
            throw unavailable(MSG_READ_FAILED, e);
        }
    }

    private boolean acquireLock(String tenantId, String subject, String taskId) {
        if (store.acquireLock(tenantId, subject, taskId)) {
            return true;
        }
        Optional<String> holder = store.lockHolder(tenantId, subject);
        if (holder.isEmpty()) {
            return store.acquireLock(tenantId, subject, taskId);
        }
        Optional<TaskDocument> held = store.find(tenantId, holder.get());
        if (held.isEmpty()) {
            return false;
        }
        TaskDocument current = settle(tenantId, held.get());
        if (!isTerminal(current.task().status())) {
            return false;
        }
        store.releaseLock(tenantId, subject, current.task().taskId());
        return store.acquireLock(tenantId, subject, taskId);
    }

    private void releaseLockQuietly(String tenantId, String subject, String taskId) {
        worker.releaseLockQuietly(tenantId, subject, taskId);
    }

    void requireEnabled() {
        if (!settings.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MSG_OFF);
        }
    }

    private static String requireTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("Coding tasks need a tenant context."));
    }

    private static String requireSubject(String subject, TenantContext context) {
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        if (context != null && context.getSubject() != null && !context.getSubject().isBlank()) {
            return context.getSubject();
        }
        throw new IllegalStateException("Coding tasks need an authenticated user.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
    }

    private static ResponseStatusException unavailable(String message, RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ view helpers

    private static TaskView copyWith(TaskView t, String status, String outcome, String message) {
        return new TaskView(t.taskId(), t.agentId(), status, outcome, status, t.instruction(), t.testCommand(),
            t.sourceKind(), t.repoUrl(), t.runner(), t.budget(), t.usage(), t.events(), t.tests(), t.review(),
            t.diffAvailable(), t.diffHash(), t.diffFiles(), t.testFilesTouched(),
            message == null ? t.message() : message, t.cancelRequested(), t.createdAtMs(), System.currentTimeMillis());
    }

    private static TaskView withDiff(TaskView t, boolean available) {
        return new TaskView(t.taskId(), t.agentId(), t.status(), t.outcome(), t.phase(), t.instruction(),
            t.testCommand(), t.sourceKind(), t.repoUrl(), t.runner(), t.budget(), t.usage(), t.events(), t.tests(),
            t.review(), available, available ? t.diffHash() : null, available ? t.diffFiles() : 0,
            t.testFilesTouched(), t.message(), t.cancelRequested(), t.createdAtMs(), System.currentTimeMillis());
    }

    private void scheduleReaper(String taskId) {
        ScheduledExecutorService s = scheduler;
        if (s == null) {
            return;
        }
        s.schedule(() -> {
            LiveSession session = sessions.remove(taskId);
            if (session != null) {
                session.sandbox().close();
            }
        }, settings.rollbackWindowSeconds(), TimeUnit.SECONDS);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
