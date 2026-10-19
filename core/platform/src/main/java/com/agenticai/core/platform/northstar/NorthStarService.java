package com.agenticai.core.platform.northstar;

import com.agenticai.core.platform.northstar.NorthStarAggregator.FirstRun;
import com.agenticai.core.platform.northstar.NorthStarAggregator.PassKRow;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunBucket;
import com.agenticai.core.platform.northstar.NorthStarDtos.ChannelPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.CohortPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.Coverage;
import com.agenticai.core.platform.northstar.NorthStarDtos.LanguagePoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.NorthStarResponse;
import com.agenticai.core.platform.northstar.NorthStarDtos.PassK;
import com.agenticai.core.platform.northstar.NorthStarDtos.WeekPoint;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Credible, measurable "number one" metrics for the platform owner: weekly
 * active users who completed an agent run, run volume and success rate, how
 * much work happens without a human present, time to a first successful run,
 * channel-delivered runs and eval reliability (pass^k). Aggregates only - see
 * {@link NorthStarAggregator} for the 5-user suppression rule.
 *
 * <p><b>What counts as a run.</b> Only runs recorded against a user identity
 * in a durable table: terminal {@code agent_job} rows and terminal {@code
 * quick_task_run} rows. Interactive chat turns are NOT counted - no table
 * records which user sent a chat turn ({@code llm_call_log} has an agent and a
 * session but no user) - and the response says so in {@code notes} rather than
 * estimating them.
 *
 * <p><b>How it reads.</b> Same discipline as {@code FleetHealthService}: walk
 * every ACTIVE non-demo tenant, bind that tenant's schema, run a few bounded
 * grouped queries (each with a JDBC timeout), and merge. A query that fails in
 * one tenant (timeout, missing table, error) is counted per source and
 * reported in {@code notes}; it never turns into a silent zero and never
 * aborts the sweep. A total time budget stops the sweep early and says so.
 *
 * <p><b>Cost control.</b> The finished response is cached in memory (a single
 * entry, TTL {@code app.platform.north-star.cache-ttl-seconds}); calls are
 * serialised, so a stampede of dashboard loads runs the sweep once.
 */
@Service
public class NorthStarService {

    private static final Logger log = LoggerFactory.getLogger(NorthStarService.class);
    private static final String SYSTEM_ACTOR = "system:north-star-metrics";

    public static final int DEFAULT_WEEKS = 12;
    public static final int MIN_WEEKS = 1;
    public static final int MAX_WEEKS = 52;
    static final int MAX_TENANTS = 500;

    /** The tenant-table sources a sweep reads; used to attribute failures in the notes. */
    enum Source {
        JOBS("agent_job", "runs, weekly active users and success rate"),
        QUICK_TASKS("quick_task_run", "channel-delivered runs, runs and weekly active users"),
        FIRST_RUNS("control_plane.app_user + run tables", "time to first successful run"),
        PASS_K("eval_run / eval_run_case_result", "pass^k reliability");

        final String table;
        final String metrics;

        Source(String table, String metrics) {
            this.table = table;
            this.metrics = metrics;
        }
    }

    /** Failure counts for one source across the sweep. */
    record SourceHealth(Source source, int missing, int timedOut, int errored) {
        int total() {
            return missing + timedOut + errored;
        }
    }

    private record CacheEntry(int weeks, Instant computedAt, NorthStarResponse response) {
    }

    private final TenantRepository tenantRepository;
    private final NorthStarQueries queries;
    private final boolean enabled;
    private final long cacheTtlSeconds;
    private final long totalBudgetSeconds;

    /** Package-private and non-final so tests can pin time; production uses the system UTC clock. */
    Clock clock = Clock.systemUTC();

    private CacheEntry cache;

    public NorthStarService(
        TenantRepository tenantRepository,
        NorthStarQueries queries,
        @Value("${app.platform.north-star.enabled:true}") boolean enabled,
        @Value("${app.platform.north-star.cache-ttl-seconds:3600}") long cacheTtlSeconds,
        @Value("${app.platform.north-star.total-budget-seconds:90}") long totalBudgetSeconds
    ) {
        this.tenantRepository = tenantRepository;
        this.queries = queries;
        this.enabled = enabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.totalBudgetSeconds = Math.max(5, totalBudgetSeconds);
    }

    /**
     * The north-star metrics for the trailing {@code weeks} ISO weeks (UTC,
     * including the current partial week). Serves the cached entry when it is
     * younger than the TTL and was computed for the same {@code weeks}.
     */
    public synchronized NorthStarResponse northStar(int weeks) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "North-star metrics are disabled.");
        }
        if (weeks < MIN_WEEKS || weeks > MAX_WEEKS) {
            throw new IllegalArgumentException("weeks must be " + MIN_WEEKS + ".." + MAX_WEEKS);
        }
        Instant now = clock.instant();
        if (cache != null && cache.weeks() == weeks && cacheTtlSeconds > 0
            && now.isBefore(cache.computedAt().plusSeconds(cacheTtlSeconds))) {
            return cache.response();
        }
        NorthStarResponse fresh = compute(weeks, now);
        cache = new CacheEntry(weeks, now, fresh);
        return fresh;
    }

    // ------------------------------------------------------------------- sweep

    private static final class Collected {
        final List<RunBucket> runs = new ArrayList<>();
        final List<FirstRun> firstRuns = new ArrayList<>();
        final List<PassKRow> passK = new ArrayList<>();
        final EnumMap<Source, int[]> failures = new EnumMap<>(Source.class); // [missing, timeout, error]

        void fail(Source source, NorthStarSqlFailure kind) {
            int[] f = failures.computeIfAbsent(source, k -> new int[3]);
            f[kind.ordinal()]++;
        }
    }

    private NorthStarResponse compute(int weeks, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<LocalDate> weekStarts = NorthStarAggregator.weekStarts(today, weeks);
        LocalDate currentWeek = NorthStarAggregator.weekStartOf(today);
        Instant since = weekStarts.get(0).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant deadline = now.plus(Duration.ofSeconds(totalBudgetSeconds));

        List<Tenant> all = tenantRepository.findActiveExcludingDemo();
        boolean capped = all.size() > MAX_TENANTS;
        List<Tenant> scope = capped ? all.subList(0, MAX_TENANTS) : all;

        Collected data = new Collected();
        int scanned = 0;
        for (Tenant tenant : scope) {
            if (clock.instant().isAfter(deadline)) {
                break;
            }
            scanTenant(tenant, since, data);
            scanned++;
        }
        int skipped = all.size() - scanned;

        Map<LocalDate, Long> signups = null;
        try {
            signups = queries.signupsByWeek(since);
        } catch (RuntimeException e) {
            log.warn("North-star: signup counts unavailable ({})", NorthStarSqlFailure.classify(e), e);
        }

        List<WeekPoint> weekPoints = NorthStarAggregator.weekly(data.runs, weekStarts, currentWeek);
        List<ChannelPoint> channels = NorthStarAggregator.channels(data.runs, weekStarts);
        List<CohortPoint> cohorts = NorthStarAggregator.cohorts(data.firstRuns, signups, weekStarts);
        Double median = NorthStarAggregator.overallMedianMinutes(data.firstRuns, weekStarts);
        PassK passK = NorthStarAggregator.passK(data.passK);
        List<LanguagePoint> languages = List.of();

        List<SourceHealth> health = new ArrayList<>();
        data.failures.forEach((source, f) -> health.add(new SourceHealth(source, f[0], f[1], f[2])));

        List<String> notes = buildNotes(
            health, all.size(), scanned, skipped, capped,
            NorthStarAggregator.countSuppressed(weekPoints, cohorts, channels, passK),
            median, data.firstRuns.isEmpty(), passK, signups != null);

        return new NorthStarResponse(now, weekPoints, median, cohorts, languages, channels, passK,
            new Coverage(all.size(), scanned, skipped, capped || skipped > 0), notes);
    }

    private void scanTenant(Tenant tenant, Instant since, Collected data) {
        String schema = tenant.getSchemaName();
        asTenant(schema, () -> {
            data.runs.addAll(guard(Source.JOBS, data, schema, () -> queries.jobRuns(schema, since)));
            data.runs.addAll(guard(Source.QUICK_TASKS, data, schema, () -> queries.quickTaskRuns(schema, since)));
            data.firstRuns.addAll(guard(Source.FIRST_RUNS, data, schema,
                () -> queries.firstRuns(schema, tenant.getId(), since)));
            data.passK.addAll(guard(Source.PASS_K, data, schema, () -> queries.passK(schema, since)));
            return null;
        });
    }

    private <T> List<T> guard(Source source, Collected data, String schema, Supplier<List<T>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            NorthStarSqlFailure kind = NorthStarSqlFailure.classify(e);
            data.fail(source, kind);
            log.warn("North-star: {} unreadable in schema '{}' ({})", source.table, schema, kind, e);
            return List.of();
        }
    }

    /** Binds the TARGET tenant schema for the call and restores the caller's context afterwards. */
    private <T> T asTenant(String schema, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    // ------------------------------------------------------------------- notes

    /**
     * Every honest caveat the numbers carry, as plain sentences. "unavailable"
     * means the metric could not be computed at all; "partially unavailable"
     * means it was computed over the tenants that could be read.
     */
    static List<String> buildNotes(
        List<SourceHealth> health, int tenantsTotal, int scanned, int skipped, boolean capped,
        int suppressedCells, Double medianMinutes, boolean noFirstRuns, PassK passK, boolean signupsAvailable
    ) {
        List<String> notes = new ArrayList<>();

        notes.add("Metric 'interactive chat turns' unavailable because chat turns are not recorded against a user "
            + "identity in any durable table (llm_call_log keeps the agent and session but no user), so runs and "
            + "weekly active users count agent jobs and Quick Task runs only.");
        notes.add("Metric 'conversations by language' unavailable because no language or locale is recorded on "
            + "conversations, runs or channel sessions.");
        notes.add("Weekly active users count only user-initiated runs (API-submitted agent jobs and Quick Tasks). "
            + "Scheduled and event-triggered runs fire without a person present, so they are counted as runs but "
            + "never as active users.");
        notes.add("Run success rate = succeeded / (succeeded + failed). Cancelled, queued, running and "
            + "awaiting-approval runs are excluded from both sides.");
        notes.add("Time to first run is measured from the user's account creation (control_plane.app_user.created_at) "
            + "to the start of their first successful user-initiated run, for users who signed up inside the window. "
            + "Users who never ran an agent are not in the median; the cohort table shows activated vs signed up.");

        if (skipped > 0 || capped) {
            notes.add("Partial sweep: " + scanned + " of " + tenantsTotal + " active tenants were scanned"
                + (capped ? " (tenant cap " + MAX_TENANTS + ")" : "")
                + (skipped > 0 && !capped ? " before the time budget ran out" : "")
                + "; every figure covers only those tenants.");
        }

        for (SourceHealth h : health) {
            if (h.total() == 0) {
                continue;
            }
            StringBuilder why = new StringBuilder();
            if (h.missing() > 0) {
                why.append(h.missing()).append(" without the source table/column");
            }
            if (h.timedOut() > 0) {
                why.append(why.length() > 0 ? ", " : "").append(h.timedOut()).append(" timed out");
            }
            if (h.errored() > 0) {
                why.append(why.length() > 0 ? ", " : "").append(h.errored()).append(" errored");
            }
            boolean everyone = scanned > 0 && h.total() >= scanned;
            notes.add("Metric '" + h.source().metrics + "' " + (everyone ? "unavailable" : "partially unavailable")
                + " because " + h.source().table + " could not be read for " + h.total() + " of " + scanned
                + " scanned tenants (" + why + ")"
                + (everyone ? "." : "; figures cover the remaining tenants."));
        }

        if (!signupsAvailable) {
            notes.add("Signup counts unavailable because control_plane.app_user could not be read; cohort "
                + "'signups' cells are omitted.");
        }
        if (medianMinutes == null) {
            notes.add(noFirstRuns
                ? "Metric 'time to first run' unavailable because no user who signed up inside the window has a "
                    + "successful run yet."
                : "Metric 'time to first run' withheld because fewer than " + NorthStarAggregator.MIN_CONTRIBUTORS
                    + " users in the window have a successful first run.");
        }
        if (passK == null) {
            notes.add("Metric 'pass^k' unavailable because no successful eval run in the window replayed its cases "
                + "more than once (pass^k needs repeats >= 2).");
        } else if (passK.average() == null) {
            notes.add("Metric 'pass^k' withheld because fewer than " + NorthStarAggregator.MIN_CONTRIBUTORS
                + " users contributed repeated eval runs.");
        }
        if (suppressedCells > 0) {
            notes.add(suppressedCells + " cell(s) suppressed because fewer than " + NorthStarAggregator.MIN_CONTRIBUTORS
                + " users contributed. A suppressed cell is hidden, not zero.");
        }
        return notes;
    }
}
