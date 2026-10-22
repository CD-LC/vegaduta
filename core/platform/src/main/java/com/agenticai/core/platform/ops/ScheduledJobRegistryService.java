package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.ops.ScheduledJobRunTracker.RunState;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.beans.Introspector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.OneTimeTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.config.TaskExecutionOutcome;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registry of every {@code @Scheduled} method registered on THIS replica, read from Spring's own
 * {@link ScheduledTaskHolder}s (the scheduled-annotation post processor, including core's {@code
 * RoleFilteringScheduledAnnotationBeanPostProcessor}: a job another deployment shape owns is simply
 * not registered here, so it is not listed), joined with {@link ScheduledJobRunTracker}'s last-run
 * bookkeeping.
 *
 * <p><b>Per replica, in memory.</b> Last-run fields describe runs on the replica that answered the
 * request, since its boot. With several replicas the console can show a job as never run while
 * another replica ran it. When the tracker has no record yet (observations not wired), Spring's own
 * {@link Task#getLastExecutionOutcome()} supplies start time and status.
 *
 * <p><b>Run now</b> ({@link #runNow}) is allowed only for the jobs in {@code
 * app.platform.jobs.run-now-allowlist} ({@code FQCN#method} entries). The default list holds only
 * jobs verified idempotent AND guarded by {@code PgAdvisoryJobLock}, so a manual run can neither
 * double-apply nor race the scheduled run on another replica: the LLM usage rollup (idempotent
 * upsert of yesterday), demo-session expiry (stamp-guarded), stale pending-upgrade cleanup (clears
 * stale state) and the model-catalog refresh (replace-not-merge per provider). The run invokes
 * Spring's own scheduled runnable, so it goes through the bean proxy ({@code @Transactional}
 * applies) and is tracked like a scheduled run.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class ScheduledJobRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRegistryService.class);

    static final String DEFAULT_ALLOWLIST =
            "com.agenticai.core.platform.LlmUsageRollupScheduler#rollupYesterday,"
                    + "com.agenticai.core.tenancy.demo.DemoSessionExpiryJob#tick,"
                    + "com.agenticai.core.tenancy.StalePendingUpgradeCleanup#clearStalePendingUpgrades,"
                    + "com.agenticai.core.providers.ProviderModelCatalogService#scheduledRefresh";

    /** {@code GET /api/platform/jobs} row. {@code id} is the stable {@code beanName#method}. */
    public record JobView(
            String id,
            String bean,
            String method,
            String schedule,
            Instant lastStartedAt,
            Instant lastFinishedAt,
            String lastStatus,
            String lastError,
            Long lastDurationMs,
            boolean runNowAllowed) {}

    /** One registered task, resolved. */
    record Entry(String id, String bean, String className, String method, ScheduledTask task) {}

    private final ObjectProvider<ScheduledTaskHolder> holders;
    private final Function<String, String> beanNameResolver;
    private final ScheduledJobRunTracker tracker;
    private final Set<String> allowlist;
    private final ExecutorService executor;
    private final Map<String, String> beanNames = new ConcurrentHashMap<>();

    @Autowired
    public ScheduledJobRegistryService(
            ObjectProvider<ScheduledTaskHolder> holders,
            ListableBeanFactory beanFactory,
            ScheduledJobRunTracker tracker,
            @Value("${app.platform.jobs.run-now-allowlist:" + DEFAULT_ALLOWLIST + "}")
                    String allowlist) {
        this(
                holders,
                className -> resolveBeanName(beanFactory, className),
                tracker,
                allowlist,
                TenantAwareExecutors.newVirtualThreadPerTaskExecutor(
                        "scheduled-job-registry-service-"));
    }

    ScheduledJobRegistryService(
            ObjectProvider<ScheduledTaskHolder> holders,
            Function<String, String> beanNameResolver,
            ScheduledJobRunTracker tracker,
            String allowlist,
            ExecutorService executor) {
        this.holders = holders;
        this.beanNameResolver = beanNameResolver;
        this.tracker = tracker;
        this.allowlist = parseAllowlist(allowlist);
        this.executor = executor;
    }

    public List<JobView> list() {
        List<JobView> out = new ArrayList<>();
        for (Entry e : entries()) {
            out.add(view(e));
        }
        return out;
    }

    /**
     * Starts the job asynchronously.
     *
     * @throws ResponseStatusException 404 unknown id, 403 not on the run-now allowlist, 409 already
     *     running on this replica
     */
    public void runNow(String id, String actor) {
        Entry entry =
                entries().stream()
                        .filter(e -> e.id().equals(id))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No scheduled job with id '"
                                                        + id
                                                        + "' is registered on this replica."));
        if (!allowlist.contains(entry.className() + "#" + entry.method())) {
            log.info(
                    "platform-ops-audit actor={} action=platform.job.run_now outcome=DENIED job={}",
                    actor,
                    id);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Run-now is not allowed for this job (only idempotent, advisory-locked jobs are allowlisted).");
        }
        RunState state = tracker.get(trackerKey(entry));
        if (state != null && "RUNNING".equals(state.lastStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This job is already running on this replica.");
        }
        log.info(
                "platform-ops-audit actor={} action=platform.job.run_now outcome=STARTED job={}",
                actor,
                id);
        Runnable runnable = entry.task().getTask().getRunnable();
        executor.submit(
                () -> {
                    try {
                        runnable.run();
                    } catch (RuntimeException | Error e) {
                        log.warn(
                                "Manual run of scheduled job {} failed: {}",
                                id,
                                ScheduledJobRunTracker.describe(e));
                    }
                });
    }

    // ---------------------------------------------------------------- internals

    List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Set<ScheduledTask> tasks = new LinkedHashSet<>();
        holders.orderedStream().forEach(h -> tasks.addAll(h.getScheduledTasks()));
        for (ScheduledTask task : tasks) {
            String ref = String.valueOf(task.getTask().getRunnable());
            int dot = ref.lastIndexOf('.');
            if (dot <= 0 || dot == ref.length() - 1) {
                continue; // not a @Scheduled method runnable (programmatic task) - nothing stable
                // to show
            }
            String className = stripProxySuffix(ref.substring(0, dot));
            String method = ref.substring(dot + 1);
            String bean = beanNames.computeIfAbsent(className, this::beanNameOrFallback);
            String id = bean + "#" + method;
            int n = seen.merge(id, 1, Integer::sum);
            if (n > 1) {
                id = id + "#" + n; // the same method scheduled twice (@Schedules) - keep ids unique
            }
            entries.add(new Entry(id, bean, className, method, task));
        }
        entries.sort(Comparator.comparing(Entry::id));
        return entries;
    }

    private JobView view(Entry e) {
        RunState state = tracker.get(trackerKey(e));
        Instant started = null;
        Instant finished = null;
        String status = null;
        String error = null;
        Long duration = null;
        if (state != null) {
            started = state.lastStartedAt();
            finished = state.lastFinishedAt();
            status = state.lastStatus();
            error = state.lastError();
            duration = state.lastDurationMs();
        } else {
            TaskExecutionOutcome outcome = e.task().getTask().getLastExecutionOutcome();
            if (outcome != null && outcome.status() != null) {
                switch (outcome.status()) {
                    case STARTED -> status = "RUNNING";
                    case SUCCESS -> status = "SUCCESS";
                    case ERROR -> {
                        status = "FAILED";
                        error = ScheduledJobRunTracker.describe(outcome.throwable());
                    }
                    default -> status = null;
                }
                started = status == null ? null : outcome.executionTime();
            }
        }
        return new JobView(
                e.id(),
                e.bean(),
                e.method(),
                describeSchedule(e.task().getTask()),
                started,
                finished,
                status,
                error,
                duration,
                allowlist.contains(e.className() + "#" + e.method()));
    }

    private static String trackerKey(Entry e) {
        return e.className() + "." + e.method();
    }

    private String beanNameOrFallback(String className) {
        String resolved = null;
        try {
            resolved = beanNameResolver.apply(className);
        } catch (RuntimeException ex) {
            log.debug("Could not resolve bean name for {}: {}", className, ex.toString());
        }
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return Introspector.decapitalize(simple);
    }

    static String resolveBeanName(ListableBeanFactory beanFactory, String className) {
        try {
            Class<?> type =
                    ClassUtils.forName(
                            className, ScheduledJobRegistryService.class.getClassLoader());
            String[] names = beanFactory.getBeanNamesForType(type, true, false);
            return names.length == 0 ? null : names[0];
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static String stripProxySuffix(String className) {
        int marker = className.indexOf("$$");
        return marker > 0 ? className.substring(0, marker) : className;
    }

    static Set<String> parseAllowlist(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Human-readable schedule: {@code cron 0 20 0 * * *}, {@code fixedRate 5m}, {@code fixedDelay
     * 30s}, {@code once}.
     */
    static String describeSchedule(Task task) {
        if (task instanceof CronTask cron) {
            return "cron " + cron.getExpression();
        }
        if (task instanceof FixedRateTask rate) {
            return "fixedRate " + human(rate.getIntervalDuration());
        }
        if (task instanceof FixedDelayTask delay) {
            return "fixedDelay " + human(delay.getIntervalDuration());
        }
        if (task instanceof IntervalTask interval) {
            return "interval " + human(interval.getIntervalDuration());
        }
        if (task instanceof OneTimeTask) {
            return "once";
        }
        return Optional.ofNullable(task).map(t -> t.getClass().getSimpleName()).orElse("unknown");
    }

    static String human(Duration d) {
        if (d == null) {
            return "?";
        }
        long ms = d.toMillis();
        if (ms > 0 && ms % 3_600_000 == 0) {
            return (ms / 3_600_000) + "h";
        }
        if (ms > 0 && ms % 60_000 == 0) {
            return (ms / 60_000) + "m";
        }
        if (ms > 0 && ms % 1000 == 0) {
            return (ms / 1000) + "s";
        }
        return ms + "ms";
    }
}
