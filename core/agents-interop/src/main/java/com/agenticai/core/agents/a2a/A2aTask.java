package com.agenticai.core.agents.a2a;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * One A2A task = one agent chat turn, held in {@link A2aTaskStore} for the
 * lifetime of its TTL. Mutable, guarded by {@code this}: the reactor thread
 * appending deltas, a second HTTP request canceling it, and the store's TTL
 * sweep can all touch one instance concurrently. Every read of the mutable
 * state goes through {@link #snapshot()} so a renderer never sees a
 * half-applied transition (state COMPLETED with the last delta missing).
 *
 * <p>Ownership ({@code tenantId}/{@code subject}) is recorded at creation and
 * is what {@link A2aTaskStore#find} matches on - a task id alone never
 * authorizes reading the reply it produced.
 */
public final class A2aTask {

    /**
     * Id prefix of bookkeeping records for OUTBOUND calls ({@link A2aClientService}). They share the
     * store with inbound tasks but live in their own id namespace: the inbound GetTask/CancelTask
     * surface refuses this prefix, so a remote caller can never read or cancel them.
     */
    public static final String OUTBOUND_ID_PREFIX = "a2a-out-";

    private final String id;
    private final String contextId;
    private final UUID agentId;
    private final String tenantId;
    private final String subject;
    private final String userMessageId;
    private final String userText;
    private final String artifactId;
    private final Instant createdAt;
    private final Sinks.Empty<Void> cancelSignal = Sinks.empty();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private A2aTaskState state = A2aTaskState.SUBMITTED;
    private Instant stateTimestamp;
    private final StringBuilder reply = new StringBuilder();
    private String failureMessage;
    private Instant lastTouched;
    private Disposable subscription;
    /** Where the request that created this task sits in an agent-call chain; null when unknown. */
    private volatile A2aCallContext callContext;

    A2aTask(
        String id, String contextId, UUID agentId, String tenantId, String subject,
        String userMessageId, String userText
    ) {
        this(id, contextId, agentId, tenantId, subject, userMessageId, userText,
            UUID.randomUUID().toString(), Instant.now());
    }

    private A2aTask(
        String id, String contextId, UUID agentId, String tenantId, String subject,
        String userMessageId, String userText, String artifactId, Instant createdAt
    ) {
        this.id = id;
        this.contextId = contextId;
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.subject = subject;
        this.userMessageId = userMessageId;
        this.userText = userText;
        this.artifactId = artifactId;
        this.createdAt = createdAt;
        this.stateTimestamp = createdAt;
        this.lastTouched = createdAt;
    }

    /**
     * Rebuilds a DETACHED task from a record another replica mirrored to Redis
     * ({@link A2aTaskStore} in {@code redis} mode). It carries state and reply
     * for {@code GetTask} rendering but has no subscription: canceling it flips
     * the record, it cannot cut a turn that is running on the replica that
     * owns it. A terminal restored task has its completion future completed so
     * a waiter never parks on it.
     */
    static A2aTask restore(
        String id, String contextId, UUID agentId, String tenantId, String subject,
        String userMessageId, String userText, String artifactId, Instant createdAt,
        A2aTaskState state, Instant stateTimestamp, String reply, String failureMessage
    ) {
        A2aTask task = new A2aTask(id, contextId, agentId, tenantId, subject, userMessageId, userText, artifactId, createdAt);
        task.state = state;
        task.stateTimestamp = stateTimestamp;
        task.lastTouched = Instant.now();
        if (reply != null) {
            task.reply.append(reply);
        }
        task.failureMessage = failureMessage;
        if (state.isTerminal()) {
            task.completion.complete(null);
        }
        return task;
    }

    public String id() {
        return id;
    }

    /** The hop/trace context of the inbound request this task serves, or null. */
    A2aCallContext callContext() {
        return callContext;
    }

    void callContext(A2aCallContext callContext) {
        this.callContext = callContext;
    }

    public String contextId() {
        return contextId;
    }

    public UUID agentId() {
        return agentId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String subject() {
        return subject;
    }

    public String userMessageId() {
        return userMessageId;
    }

    public String userText() {
        return userText;
    }

    /** Stable for the task's whole life so streamed chunks and the final Task agree on which artifact they built. */
    public String artifactId() {
        return artifactId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Completes (never exceptionally) once the turn reached a terminal state, whichever one. */
    public CompletableFuture<Void> completion() {
        return completion;
    }

    Sinks.Empty<Void> cancelSignal() {
        return cancelSignal;
    }

    synchronized void attach(Disposable subscription) {
        this.subscription = subscription;
        // A cancel that raced ahead of the subscribe - the flux is already cut by
        // takeUntilOther, but dispose anyway so nothing upstream keeps running.
        if (state == A2aTaskState.CANCELED) {
            subscription.dispose();
        }
    }

    synchronized void touch() {
        this.lastTouched = Instant.now();
    }

    synchronized Instant lastTouched() {
        return lastTouched;
    }

    synchronized boolean markWorking() {
        if (state != A2aTaskState.SUBMITTED) {
            return false;
        }
        transition(A2aTaskState.WORKING);
        return true;
    }

    synchronized void appendDelta(String delta) {
        if (delta != null && !state.isTerminal()) {
            reply.append(delta);
            lastTouched = Instant.now();
        }
    }

    /** No-op once terminal, so a completion signal arriving after a cancel cannot resurrect the task. */
    synchronized boolean markCompleted() {
        if (state.isTerminal()) {
            return false;
        }
        transition(A2aTaskState.COMPLETED);
        completion.complete(null);
        return true;
    }

    synchronized boolean markFailed(String message) {
        if (state.isTerminal()) {
            return false;
        }
        this.failureMessage = message;
        transition(A2aTaskState.FAILED);
        completion.complete(null);
        return true;
    }

    /**
     * @return false when already terminal (the caller maps that to
     *     {@code -32002 TaskNotCancelable}); true when this call performed the
     *     cancel - it cuts the token stream via {@link #cancelSignal()} and
     *     disposes the subscription, so the upstream LLM call stops too.
     */
    synchronized boolean cancel() {
        if (state.isTerminal()) {
            return false;
        }
        transition(A2aTaskState.CANCELED);
        cancelSignal.tryEmitEmpty();
        if (subscription != null) {
            subscription.dispose();
        }
        completion.complete(null);
        return true;
    }

    /**
     * A server-side abort (blocking timeout, client disconnect mid-stream):
     * FAILED rather than CANCELED, because the CALLER did not ask for it, and
     * the token stream is cut exactly as {@link #cancel()} cuts it so the
     * upstream LLM call does not keep running for a reply nobody will read.
     */
    synchronized boolean abort(String message) {
        if (!markFailed(message)) {
            return false;
        }
        cancelSignal.tryEmitEmpty();
        if (subscription != null) {
            subscription.dispose();
        }
        return true;
    }

    private void transition(A2aTaskState next) {
        this.state = next;
        this.stateTimestamp = Instant.now();
        this.lastTouched = stateTimestamp;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, stateTimestamp, reply.toString(), failureMessage);
    }

    public synchronized boolean isTerminal() {
        return state.isTerminal();
    }

    /** An immutable view of the mutable half, taken under the lock. */
    public record Snapshot(A2aTaskState state, Instant timestamp, String reply, String failureMessage) {
    }
}
