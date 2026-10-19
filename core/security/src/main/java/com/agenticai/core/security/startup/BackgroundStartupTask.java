package com.agenticai.core.security.startup;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a best-effort start-up task either inline (today's behaviour) or on a
 * short-lived daemon thread, so slow, non-essential start-up work can stop
 * holding up readiness without ever being able to fail it.
 *
 * <p>Why this exists (cold-start lane, 2026-10-04): {@code ApplicationReadyEvent}
 * listeners and {@code @PostConstruct} methods run on the {@code main} thread
 * and, in Spring Boot, readiness only flips to {@code ACCEPTING_TRAFFIC} after
 * every listener has returned. A warm-up that talks to the network (object
 * storage head/lifecycle calls, a Kafka topic check, the Telegram {@code getMe}
 * call) therefore adds its full latency - and, when the dependency is down,
 * its full connect/retry timeout - to every rolling deploy and every failover
 * restart. See {@code docs/plans/CORE-COLDSTART-PROFILE.md}.
 *
 * <p>Contract, deliberately narrow:
 * <ul>
 *   <li>{@code async == false} (the default everywhere this is used): the task
 *   runs inline on the caller's thread and any exception propagates exactly as
 *   it did before this helper existed - no behaviour change at all.</li>
 *   <li>{@code async == true}: the task runs on a new daemon thread named
 *   {@code startup-<name>}; every {@link Throwable} is caught and logged once,
 *   so a failing warm-up can never fail start-up. The calling thread returns
 *   immediately.</li>
 * </ul>
 *
 * <p>Only for work that is NOT a precondition of serving a request and does
 * NOT order-depend on finishing before the first request (a sweep that fails
 * "orphaned" rows must stay synchronous, otherwise it could fail a row created
 * after readiness). The thread starts with no {@code TenantContext}; a task
 * that needs one must set it itself (the tenant iterators already do).
 */
public final class BackgroundStartupTask {

    private static final Logger log = LoggerFactory.getLogger(BackgroundStartupTask.class);

    private BackgroundStartupTask() {
    }

    /**
     * @param async whether to hop to a background daemon thread
     * @param name  short stable label, used for the thread name and log lines
     * @param task  the work; must be safe to run after readiness has flipped
     * @return the started thread when {@code async} is true, otherwise {@code null}
     *         (the task has already completed inline) - tests join on it
     */
    public static Thread run(boolean async, String name, Runnable task) {
        if (!async) {
            task.run();
            return null;
        }
        return TenantAwareExecutors.startThread("startup-" + name, true, () -> {
            long started = System.nanoTime();
            try {
                task.run();
                log.info("Background start-up task '{}' finished in {} ms", name, (System.nanoTime() - started) / 1_000_000L);
            } catch (Throwable t) {
                // Never rethrow: the whole point is that this cannot fail start-up. Throwable (not just
                // Exception) so a NoClassDefFoundError from an optional dependency is contained too.
                log.warn("Background start-up task '{}' failed after {} ms - continuing without it: {}",
                    name, (System.nanoTime() - started) / 1_000_000L, t.toString(), t);
            }
        });
    }
}
