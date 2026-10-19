package com.agenticai.core.security.concurrent;

import com.agenticai.core.security.AgentVersionPin;
import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * The thread-bound state a task carries from the thread that submitted it to the thread that runs
 * it: the {@link TenantContext}, the {@link AgentVersionPin} and the SLF4J {@link MDC}. Captured
 * on the submitting thread; applied around the task on the worker and always restored afterwards,
 * so a pooled thread never keeps state from one task into the next.
 *
 * <p>An empty capture (a platform task: submitted from a thread with no tenant) is applied as
 * "no tenant", not "leave whatever the worker has": the worker's tenant, pin and MDC are cleared for
 * the duration of the task and restored after. A task that needs a tenant it was not submitted
 * with must bind it itself ({@code TenantScope.of(..)}, the tenant iterators).
 */
record ContextSnapshot(TenantContext tenant, UUID pin, Map<String, String> mdc) {

    /** No tenant, no pin, no MDC: what a platform task runs with. */
    static final ContextSnapshot EMPTY = new ContextSnapshot(null, null, null);

    static ContextSnapshot capture() {
        return new ContextSnapshot(
                TenantContext.current().orElse(null),
                AgentVersionPin.current(),
                MDC.getCopyOfContextMap());
    }

    void run(Runnable task) {
        ContextSnapshot previous = apply();
        try {
            task.run();
        } finally {
            previous.apply();
        }
    }

    <T> T call(Callable<T> task) throws Exception {
        ContextSnapshot previous = apply();
        try {
            return task.call();
        } finally {
            previous.apply();
        }
    }

    /** Puts this snapshot on the current thread and returns what was there before. */
    private ContextSnapshot apply() {
        ContextSnapshot previous = capture();
        if (tenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenant);
        }
        AgentVersionPin.set(pin);
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
        return previous;
    }
}
