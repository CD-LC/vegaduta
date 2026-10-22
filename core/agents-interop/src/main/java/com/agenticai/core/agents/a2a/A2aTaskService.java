package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.AgentChatStreamResponder;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Maps an A2A task onto ONE {@link AgentChatService#chat} turn - the exact same chokepoint the web
 * console, every omnichannel webhook and the MCP {@code call_agent} tool go through, so an
 * A2A-triggered turn is governed identically (tenant kill-switch, guardrails, tool logging, budget
 * metering, session memory) with zero parallel code path. The A2A {@code contextId} IS the chat
 * {@code sessionId}: a peer that keeps sending messages with the same {@code contextId} continues
 * the same short-term-memory conversation, which is precisely the semantics the spec assigns to a
 * context.
 *
 * <p>Three ways a turn is consumed, all built on {@link #run}:
 *
 * <ul>
 *   <li><b>blocking</b> (default) - the request thread parks on the task's completion future
 *       ({@link #awaitBlocking}), bounded by {@link A2aProperties#getBlockingTimeout()}. Same
 *       block-on-the-request- thread posture {@code McpCallAgentToolCallback} documents; acceptable
 *       for this surface, and the ONLY posture under which {@code TenantContext} (a ThreadLocal) is
 *       trivially correct.
 *   <li><b>non-blocking</b> ({@code returnImmediately:true} / {@code blocking:false}) - {@link
 *       #runAsync} re-binds the caller's {@code TenantContext} on a virtual thread and returns the
 *       SUBMITTED task at once; the caller polls {@code GetTask}. Bounded by a semaphore so one
 *       burst cannot fan out unbounded LLM calls.
 *   <li><b>streaming</b> - {@link A2aJsonRpcController} passes an {@code onDelta} consumer and
 *       drains it into SSE frames on the request thread.
 * </ul>
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Service
public class A2aTaskService {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskService.class);
    static final String AUDIT_ACTION = "a2a.task.send";
    static final String ANONYMOUS_ACTOR = "a2a-anonymous";

    private final AgentChatService agentChatService;
    private final A2aTaskStore store;
    private final AuditLogService auditLogService;
    private final A2aProperties properties;

    /**
     * Virtual threads, same choice {@code AgentChatService#preTurnExecutor} makes: an async A2A
     * turn is almost entirely waiting on the LLM, and the semaphore below - not the pool - is what
     * bounds concurrency.
     */
    private final ExecutorService asyncExecutor =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("a2a-task-service-");

    /** Stop accepting async A2A work when the context closes (JAVA-026). */
    @jakarta.annotation.PreDestroy
    void shutdownAsyncExecutor() {
        asyncExecutor.shutdown();
    }

    private final Semaphore asyncSlots;

    public A2aTaskService(
            AgentChatService agentChatService,
            A2aTaskStore store,
            AuditLogService auditLogService,
            A2aProperties properties) {
        this.agentChatService = agentChatService;
        this.store = store;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.asyncSlots = new Semaphore(Math.max(1, properties.getMaxConcurrentAsyncTasks()));
    }

    /**
     * Mints and registers a SUBMITTED task owned by the current caller. The owner is the bound
     * {@link TenantContext} (tenant + subject) - what the store's {@link A2aTaskStore#find} will
     * later demand back - falling back to the servlet {@link Principal} for the subject when
     * security is on but no TenantContext was bound, and to null under the "insecure" profile.
     */
    public A2aTask create(
            UUID agentId,
            String contextId,
            String userMessageId,
            String userText,
            Principal principal) {
        Optional<TenantContext> tenant = TenantContext.current();
        String tenantId = tenant.map(TenantContext::getTenantId).orElse(null);
        String subject =
                tenant.map(TenantContext::getSubject)
                        .filter(StringUtils::hasText)
                        .orElseGet(
                                () ->
                                        principal != null
                                                        && StringUtils.hasText(principal.getName())
                                                ? principal.getName()
                                                : null);
        A2aTask task =
                new A2aTask(
                        UUID.randomUUID().toString(),
                        contextId,
                        agentId,
                        tenantId,
                        subject,
                        userMessageId,
                        userText);
        store.put(task);
        // Redis mode: mirror the terminal snapshot (every terminal path completes this future);
        // no-op in memory mode.
        // The completing thread is often a Reactor/Netty one, so in redis mode the blocking Redis
        // GET+SET is
        // moved to a virtual thread; memory mode keeps the direct (no-op) call.
        task.completion()
                .whenComplete(
                        (ignored, err) -> {
                            if (store.isRedisBacked()) {
                                asyncExecutor.execute(() -> store.save(task));
                            } else {
                                store.save(task);
                            }
                        });
        return task;
    }

    /**
     * Starts the chat turn for {@code task} on the CURRENT thread (so {@link
     * AgentChatService#chat}'s eager {@code TenantContext} capture sees the caller's context) and
     * subscribes to its token stream. Returns the task's completion future; it completes normally
     * on COMPLETED, FAILED and CANCELED alike - the outcome is in {@link A2aTask#snapshot()}, never
     * in the future, so a waiter never has to unwrap an {@link ExecutionException}.
     *
     * @param onDelta invoked on the reactor thread for each token, AFTER it was appended to the
     *     task; null when nobody streams.
     */
    public CompletableFuture<Void> run(A2aTask task, Consumer<String> onDelta) {
        if (!task.markWorking()) {
            // Canceled between create() and run() - nothing to start.
            return task.completion();
        }
        store.save(task);
        // Long-term-memory subject: the caller's identity when known, else the
        // context itself (memory then behaves session-scoped only - the same
        // fallback AgentController#chat takes when no principal exists).
        String subjectKey = task.subject() != null ? task.subject() : task.contextId();
        Flux<String> tokens;
        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    task.agentId(), task.contextId(), subjectKey, task.userText())
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            tokens = agentChatService.chat(turnContext, chatTurn);
        } catch (RuntimeException setupFailure) {
            // chat() validates eagerly (kill switch, budget, access) and throws
            // synchronously for those; a synchronous throw must land in the
            // task the same way an asynchronous onError would.
            fail(task, setupFailure);
            return task.completion();
        }
        // The terminal callbacks below run on whichever reactor/HTTP thread ends the stream, and
        // write the tenant's audit log: re-apply the tenant this run started under (captured on the
        // caller's thread), or the audit row fails against a schema-less thread and is silently
        // lost.
        TenantScope tenant = TenantScope.capture();
        Disposable subscription =
                tokens
                        // The cancel/abort seam: A2aTask#cancel emits on this sink, which
                        // completes the stream here before the disposal even lands.
                        .takeUntilOther(task.cancelSignal().asMono())
                        .subscribe(
                                delta -> {
                                    task.appendDelta(delta);
                                    if (onDelta != null) {
                                        onDelta.accept(delta);
                                    }
                                },
                                error -> tenant.run(() -> fail(task, error)),
                                () ->
                                        tenant.run(
                                                () -> {
                                                    if (task.markCompleted()) {
                                                        audit(
                                                                task,
                                                                AuditOutcome.SUCCESS,
                                                                "completed");
                                                    }
                                                }));
        task.attach(subscription);
        return task.completion();
    }

    /**
     * Non-blocking variant: re-binds the caller's {@link TenantContext} on a virtual thread and
     * calls {@link #run} there. The context is captured HERE, on the request thread, because by the
     * time the virtual thread starts the servlet filter that bound it may already have cleared it.
     */
    public void runAsync(A2aTask task) {
        if (!asyncSlots.tryAcquire()) {
            task.abort(
                    "Too many non-blocking tasks in flight on this server; retry, or send without"
                            + " returnImmediately.");
            throw A2aError.internal(
                    "too many non-blocking tasks in flight; retry later or use a blocking send");
        }
        Optional<TenantContext> captured = TenantContext.current();
        asyncExecutor.execute(
                () -> {
                    captured.ifPresent(TenantContext::set);
                    try {
                        run(task, null).whenComplete((ignored, err) -> asyncSlots.release());
                    } catch (RuntimeException e) {
                        asyncSlots.release();
                        fail(task, e);
                    } finally {
                        TenantContext.clear();
                    }
                });
    }

    /**
     * Parks until the task is terminal or {@link A2aProperties#getBlockingTimeout()} elapses - a
     * timeout ABORTS the turn (FAILED, stream cut) rather than leaving an orphan running for a
     * reply nobody will collect.
     */
    public void awaitBlocking(A2aTask task) {
        Duration timeout = properties.getBlockingTimeout();
        try {
            task.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(
                    "A2A task {} (agent {}) exceeded the blocking timeout of {} - aborting",
                    task.id(),
                    task.agentId(),
                    timeout);
            task.abort("The agent did not finish within " + timeout.toSeconds() + " seconds.");
            audit(task, AuditOutcome.FAILURE, "timed out after " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.abort("The request was interrupted before the agent finished.");
        } catch (ExecutionException e) {
            // Unreachable by construction (the future only ever completes
            // normally) - kept so a future refactor that changes that fails loudly here.
            throw new IllegalStateException(
                    "A2A task completion future failed unexpectedly", e.getCause());
        }
    }

    /**
     * @throws A2aError {@code -32001} when unknown, expired, or not owned by the caller.
     */
    public A2aTask get(String taskId, Principal principal) {
        if (taskId != null && taskId.startsWith(A2aTask.OUTBOUND_ID_PREFIX)) {
            // Outbound-call bookkeeping (A2aClientService) is not part of the inbound surface.
            throw A2aError.taskNotFound(taskId);
        }
        return store.find(taskId, currentTenantId(), currentSubject(principal))
                .orElseThrow(() -> A2aError.taskNotFound(taskId));
    }

    /**
     * @throws A2aError {@code -32001} when not found; {@code -32002} when the task already reached
     *     a terminal state (the spec's TaskNotCancelable).
     */
    public A2aTask cancel(String taskId, Principal principal) {
        A2aTask task = get(taskId, principal);
        if (!task.cancel()) {
            throw A2aError.taskNotCancelable(taskId, task.snapshot().state());
        }
        store.save(task);
        audit(task, AuditOutcome.SUCCESS, "canceled by caller");
        return task;
    }

    private void fail(A2aTask task, Throwable error) {
        // The user-facing half only: getMessage() on an arbitrary upstream
        // exception is raw internal detail (class names, provider JSON) and this
        // string lands in a peer agent's transcript. Same helper, same reason,
        // as AgentController's own mid-stream failure path.
        String message = AgentChatStreamResponder.midStreamFailureMessage(error);
        if (task.markFailed(message)) {
            log.warn("A2A task {} (agent {}) failed", task.id(), task.agentId(), error);
            audit(task, AuditOutcome.FAILURE, "failed: " + message);
        }
    }

    /**
     * Best-effort audit row, same "never let logging break the real call" posture {@code
     * McpCallAgentToolCallback#recordAudit} takes - a failure writing the row must never change
     * what the caller was about to receive.
     */
    private void audit(A2aTask task, AuditOutcome outcome, String detail) {
        String actor = task.subject() != null ? task.subject() : ANONYMOUS_ACTOR;
        try {
            auditLogService.record(
                    actor,
                    AUDIT_ACTION,
                    "Agent",
                    task.agentId().toString(),
                    outcome,
                    "A2A task "
                            + task.id()
                            + " (context "
                            + task.contextId()
                            + ") "
                            + detail
                            + ".");
        } catch (RuntimeException auditFailure) {
            log.warn("Failed to record audit log entry for A2A task {}", task.id(), auditFailure);
        }
    }

    static String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    static String currentSubject(Principal principal) {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(StringUtils::hasText)
                .orElseGet(
                        () ->
                                principal != null && StringUtils.hasText(principal.getName())
                                        ? principal.getName()
                                        : null);
    }

    /**
     * Exposed for the controller's stream drain loop; see {@link
     * A2aProperties#getBlockingTimeout()}.
     */
    Duration blockingTimeout() {
        return properties.getBlockingTimeout();
    }
}
