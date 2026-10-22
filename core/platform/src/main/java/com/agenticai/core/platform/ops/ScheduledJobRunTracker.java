package com.agenticai.core.platform.ops;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

/**
 * Last-run bookkeeping for every {@code @Scheduled} method, fed by Spring's own scheduled-task
 * observations ({@code tasks.scheduled.execution}): Spring Boot's {@code
 * ScheduledTasksObservabilityAutoConfiguration} hands the {@code ObservationRegistry} to the task
 * registrar, every run of a {@code @Scheduled} method is wrapped in an observation, and Boot
 * registers this {@link ObservationHandler} bean with that registry. No proxies and no aspect:
 * nothing about how any job is invoked changes.
 *
 * <p><b>Scope: this replica, in memory.</b> State is lost on restart and each replica only knows
 * the runs it executed itself (a job skipped because another replica held its advisory lock still
 * counts as a SUCCESS run here - the method returned normally). That is the documented contract of
 * {@code GET /api/platform/jobs}.
 *
 * <p>Keyed by {@code declaringClass.getName() + "." + method.getName()} - the same string Spring's
 * {@code ScheduledMethodRunnable#toString()} produces, which is how {@link
 * ScheduledJobRegistryService} joins runs to registered tasks.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class ScheduledJobRunTracker implements ObservationHandler<ScheduledTaskObservationContext> {

    static final int MAX_ERROR_CHARS = 500;

    /**
     * Immutable view of one job's most recent run. {@code status} is SUCCESS, FAILED or RUNNING.
     */
    public record RunState(
            Instant lastStartedAt,
            Instant lastFinishedAt,
            String lastStatus,
            String lastError,
            Long lastDurationMs) {}

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public ScheduledJobRunTracker() {
        this(Clock.systemUTC());
    }

    ScheduledJobRunTracker(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        String key = key(context.getMethod());
        if (key == null) {
            return;
        }
        Instant now = clock.instant();
        runs.compute(
                key,
                (k, prev) ->
                        new RunState(
                                now,
                                prev == null ? null : prev.lastFinishedAt(),
                                "RUNNING",
                                null,
                                prev == null ? null : prev.lastDurationMs()));
    }

    @Override
    public void onError(ScheduledTaskObservationContext context) {
        String key = key(context.getMethod());
        if (key == null) {
            return;
        }
        String error = describe(context.getError());
        runs.computeIfPresent(
                key,
                (k, prev) ->
                        new RunState(
                                prev.lastStartedAt(),
                                prev.lastFinishedAt(),
                                "FAILED",
                                error,
                                prev.lastDurationMs()));
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        String key = key(context.getMethod());
        if (key == null) {
            return;
        }
        Instant now = clock.instant();
        runs.compute(
                key,
                (k, prev) -> {
                    Instant started = prev == null ? null : prev.lastStartedAt();
                    boolean failed =
                            context.getError() != null
                                    || (prev != null && "FAILED".equals(prev.lastStatus()));
                    Long duration =
                            started == null
                                    ? null
                                    : Math.max(0L, Duration.between(started, now).toMillis());
                    return new RunState(
                            started,
                            now,
                            failed ? "FAILED" : "SUCCESS",
                            failed
                                    ? (prev != null && prev.lastError() != null
                                            ? prev.lastError()
                                            : describe(context.getError()))
                                    : null,
                            duration);
                });
    }

    /**
     * Most recent run of the job with this key, or null when it has not run on this replica since
     * boot.
     */
    public RunState get(String key) {
        return runs.get(key);
    }

    static String key(Method method) {
        return method == null
                ? null
                : method.getDeclaringClass().getName() + "." + method.getName();
    }

    /** Exception class and first line of its message, bounded. Stack traces stay in the logs. */
    static String describe(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable root = t;
        if (root instanceof java.lang.reflect.UndeclaredThrowableException
                && root.getCause() != null) {
            root = root.getCause();
        }
        String message =
                root.getMessage() == null ? "" : root.getMessage().lines().findFirst().orElse("");
        String out = root.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message);
        return out.length() > MAX_ERROR_CHARS ? out.substring(0, MAX_ERROR_CHARS) : out;
    }
}
