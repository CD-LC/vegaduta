package com.agenticai.core.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A captured {@link TenantContext} that can be re-applied on another thread. {@code TenantContext}
 * is a ThreadLocal, so any hop off the request thread (executor, reactive scheduler, virtual
 * thread) must capture it first and re-apply it explicitly - otherwise the schema resolver silently
 * falls back to the public schema. This is the one place that does the save / set / always-restore
 * dance.
 *
 * <p>Semantics (identical to the hand-rolled blocks this replaced): with a non-null captured
 * context, the executing thread's previous context is saved, the captured one is set, and the
 * previous value is restored (or cleared if there was none) in {@code finally}, even on exception -
 * so a pooled thread never leaks tenant scope and an outer scope is never clobbered. The same goes
 * for the {@link AgentVersionPin}: a scope made by {@link #capture()} carries the capturing
 * thread's pin (none included) and applies it on the executing thread, and EVERY non-empty scope
 * restores the executing thread's previous pin afterwards, so a pin set inside the work (or left
 * stale on a pooled/scheduler thread) can never stamp a wrong {@code agent_version_id} on later,
 * unrelated work. With no captured context the work simply runs untouched: an outer scope on the
 * executing thread stays in force and is neither replaced nor cleared.
 */
public final class TenantScope {

    private static final TenantScope NONE = new TenantScope(null, false, null);

    private final TenantContext captured;
    private final boolean pinCaptured;
    private final UUID capturedPin;

    private TenantScope(TenantContext captured, boolean pinCaptured, UUID capturedPin) {
        this.captured = captured;
        this.pinCaptured = pinCaptured;
        this.capturedPin = capturedPin;
    }

    /**
     * Captures the calling thread's current context and agent-version pin (an empty scope if there
     * is no context).
     */
    public static TenantScope capture() {
        TenantContext context = TenantContext.current().orElse(null);
        return context == null ? NONE : new TenantScope(context, true, AgentVersionPin.current());
    }

    /**
     * Wraps an already-captured context; {@code null} yields an empty (pass-through) scope. The pin
     * is left as the executing thread has it for the duration of the work, but is restored
     * afterwards.
     */
    public static TenantScope of(TenantContext context) {
        return context == null ? NONE : new TenantScope(context, false, null);
    }

    /**
     * Wraps an already-captured context together with an agent-version pin, exactly as {@link
     * #capture()} would have captured both on a thread holding them: {@code null} context yields
     * an empty (pass-through) scope; otherwise {@code agentVersionPin} (null included) is applied on
     * the executing thread for the duration of the work. For a caller that holds the tenant
     * explicitly (ADR-0022 {@code TurnContext}) but still has to carry the request thread's pin.
     */
    public static TenantScope of(TenantContext context, UUID agentVersionPin) {
        return context == null ? NONE : new TenantScope(context, true, agentVersionPin);
    }

    public static TenantScope of(Optional<TenantContext> context) {
        return of(context == null ? null : context.orElse(null));
    }

    public boolean isEmpty() {
        return captured == null;
    }

    public Optional<TenantContext> context() {
        return Optional.ofNullable(captured);
    }

    public void run(Runnable work) {
        if (captured == null) {
            work.run();
            return;
        }
        Saved saved = apply();
        try {
            work.run();
        } finally {
            restore(saved);
        }
    }

    public <T> T get(Supplier<T> work) {
        if (captured == null) {
            return work.get();
        }
        Saved saved = apply();
        try {
            return work.get();
        } finally {
            restore(saved);
        }
    }

    public <T> T call(Callable<T> work) throws Exception {
        if (captured == null) {
            return work.call();
        }
        Saved saved = apply();
        try {
            return work.call();
        } finally {
            restore(saved);
        }
    }

    private record Saved(Optional<TenantContext> context, UUID pin) {}

    private Saved apply() {
        Saved saved = new Saved(TenantContext.current(), AgentVersionPin.current());
        TenantContext.set(captured);
        if (pinCaptured) {
            AgentVersionPin.set(capturedPin);
        }
        return saved;
    }

    private static void restore(Saved saved) {
        saved.context().ifPresentOrElse(TenantContext::set, TenantContext::clear);
        AgentVersionPin.set(saved.pin());
    }

    public Runnable wrapRunnable(Runnable work) {
        return () -> run(work);
    }

    public <T> Supplier<T> wrapSupplier(Supplier<T> work) {
        return () -> get(work);
    }

    public <T> Callable<T> wrapCallable(Callable<T> work) {
        return () -> call(work);
    }

    public <A, R> Function<A, R> wrapFunction(Function<A, R> work) {
        return a -> get(() -> work.apply(a));
    }
}
