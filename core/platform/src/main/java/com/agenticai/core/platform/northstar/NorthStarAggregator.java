package com.agenticai.core.platform.northstar;

import com.agenticai.core.platform.northstar.NorthStarDtos.ChannelPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.CohortPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.PassK;
import com.agenticai.core.platform.northstar.NorthStarDtos.PassKGroup;
import com.agenticai.core.platform.northstar.NorthStarDtos.WeekPoint;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pure half of the north-star metrics: everything that turns raw,
 * already-aggregated SQL rows into the published numbers, with NO database,
 * Spring or clock access so every rule below is unit-testable.
 *
 * <p><b>Privacy rule (applied here and nowhere else).</b> A published cell is
 * suppressed - its numbers become {@code null} and {@code suppressed} is set -
 * when it was contributed to by 1 to {@value #MIN_CONTRIBUTORS}-1 distinct
 * users. A cell with zero contributors is not suppressed: it discloses
 * nothing and "0" is the true answer. Where a total could be subtracted from
 * its parts to recover a suppressed cell (pass^k), the total is computed from
 * the un-suppressed parts only (complementary suppression).
 *
 * <p><b>User keys.</b> Rows carry {@code tenantSchema + ":" + md5(userId)}, so
 * the raw Keycloak subject never leaves the database and two tenants'
 * identically-named users are never merged. The keys exist only to count
 * distinct contributors and are never serialised.
 */
public final class NorthStarAggregator {

    /** A cell needs at least this many distinct contributing users to be published. */
    public static final int MIN_CONTRIBUTORS = 5;

    private NorthStarAggregator() {
    }

    /**
     * What caused a run. {@code INTERACTIVE} and {@code CHANNEL} runs are
     * user-initiated (a person pressed a button or typed a Quick Task);
     * {@code SCHEDULED} and {@code TRIGGERED} fire without anyone present, so
     * they never count towards weekly active users even though the schedule's
     * owner is recorded as the requester.
     */
    public enum RunKind {
        INTERACTIVE(true),
        SCHEDULED(false),
        TRIGGERED(false),
        CHANNEL(true);

        private final boolean userInitiated;

        RunKind(boolean userInitiated) {
            this.userInitiated = userInitiated;
        }

        public boolean userInitiated() {
            return userInitiated;
        }
    }

    /** One SQL row: {@code runs} terminal runs of one kind/outcome by one user in one ISO week. */
    public record RunBucket(
        LocalDate weekStart, RunKind kind, String channel, boolean success, String userKey, long runs
    ) {
    }

    /** A user's first successful user-initiated run, in minutes after their account was created. */
    public record FirstRun(LocalDate signupWeek, String userKey, double minutes) {
    }

    /** One SQL row of eval reliability: cases of runs with {@code k} repeats and how many passed all k. */
    public record PassKRow(String userKey, int k, long cases, long casesAllPassed) {
    }

    // ------------------------------------------------------------------ calendar

    /** Monday of the ISO week containing {@code today} (matches Postgres {@code date_trunc('week', ...)}). */
    public static LocalDate weekStartOf(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** {@code weeks} Monday dates, oldest first, ending with the week containing {@code today}. */
    public static List<LocalDate> weekStarts(LocalDate today, int weeks) {
        LocalDate current = weekStartOf(today);
        List<LocalDate> out = new ArrayList<>(weeks);
        for (int i = weeks - 1; i >= 0; i--) {
            out.add(current.minusWeeks(i));
        }
        return out;
    }

    // ------------------------------------------------------------- classification

    /** {@code agent_job.origin} to run kind; null for an origin this code does not know (never guessed). */
    public static RunKind kindForJobOrigin(String origin) {
        if (origin == null) {
            return null;
        }
        return switch (origin) {
            case "API" -> RunKind.INTERACTIVE;
            case "SCHEDULE" -> RunKind.SCHEDULED;
            // APPROVAL = a sentinel proposal a human approved; the run itself is automation-initiated.
            case "EVENT", "APPROVAL" -> RunKind.TRIGGERED;
            default -> null;
        };
    }

    /** {@code quick_task_run.channel} to run kind: LOG delivers nothing outside the app, the rest are channel-delivered. */
    public static RunKind kindForQuickTaskChannel(String channel) {
        if (channel == null) {
            return null;
        }
        return switch (channel) {
            case "LOG" -> RunKind.INTERACTIVE;
            case "WHATSAPP", "SMS", "CALL" -> RunKind.CHANNEL;
            default -> null;
        };
    }

    /** True for a cell that must be hidden: 1..MIN_CONTRIBUTORS-1 contributors. */
    public static boolean isSuppressed(long contributors) {
        return contributors > 0 && contributors < MIN_CONTRIBUTORS;
    }

    // ------------------------------------------------------------------- weekly

    private static final class WeekAcc {
        final Set<String> users = new HashSet<>();
        final Set<String> activeUsers = new HashSet<>();
        final EnumMap<RunKind, Long> byKind = new EnumMap<>(RunKind.class);
        long runs;
        long succeeded;
    }

    /**
     * One {@link WeekPoint} per entry of {@code weekStarts}, in the same order.
     * Rows outside {@code weekStarts} are ignored. {@code currentWeekStart}
     * flags the in-progress week as {@code partial}.
     */
    public static List<WeekPoint> weekly(List<RunBucket> buckets, List<LocalDate> weekStarts, LocalDate currentWeekStart) {
        Set<LocalDate> inWindow = new HashSet<>(weekStarts);
        Map<LocalDate, WeekAcc> byWeek = new HashMap<>();
        for (RunBucket b : buckets) {
            if (b.runs() <= 0 || !inWindow.contains(b.weekStart())) {
                continue;
            }
            WeekAcc acc = byWeek.computeIfAbsent(b.weekStart(), k -> new WeekAcc());
            acc.users.add(b.userKey());
            acc.runs += b.runs();
            acc.byKind.merge(b.kind(), b.runs(), Long::sum);
            if (b.success()) {
                acc.succeeded += b.runs();
                if (b.kind().userInitiated()) {
                    acc.activeUsers.add(b.userKey());
                }
            }
        }

        List<WeekPoint> out = new ArrayList<>(weekStarts.size());
        for (LocalDate week : weekStarts) {
            boolean partial = week.equals(currentWeekStart);
            WeekAcc acc = byWeek.get(week);
            if (acc == null) {
                out.add(new WeekPoint(week, partial, 0L, 0L, null, null, zeroKinds(), false));
                continue;
            }
            boolean runsHidden = isSuppressed(acc.users.size());
            boolean wauHidden = isSuppressed(acc.activeUsers.size());
            Long wau = wauHidden ? null : (long) acc.activeUsers.size();
            if (runsHidden) {
                out.add(new WeekPoint(week, partial, wau, null, null, null, null, true));
                continue;
            }
            long interactive = acc.byKind.getOrDefault(RunKind.INTERACTIVE, 0L);
            Double share = acc.runs > 0 ? round4((double) (acc.runs - interactive) / acc.runs) : null;
            Double success = acc.runs > 0 ? round4((double) acc.succeeded / acc.runs) : null;
            Map<String, Long> kinds = new LinkedHashMap<>();
            for (RunKind k : RunKind.values()) {
                kinds.put(k.name(), acc.byKind.getOrDefault(k, 0L));
            }
            out.add(new WeekPoint(week, partial, wau, acc.runs, share, success, kinds, wauHidden));
        }
        return out;
    }

    private static Map<String, Long> zeroKinds() {
        Map<String, Long> kinds = new LinkedHashMap<>();
        for (RunKind k : RunKind.values()) {
            kinds.put(k.name(), 0L);
        }
        return kinds;
    }

    // ------------------------------------------------------------------ channels

    /** Channel-delivered runs per delivery channel over the whole window, busiest first, suppressed last. */
    public static List<ChannelPoint> channels(List<RunBucket> buckets, List<LocalDate> weekStarts) {
        Set<LocalDate> inWindow = new HashSet<>(weekStarts);
        Map<String, Set<String>> users = new TreeMap<>();
        Map<String, long[]> counts = new HashMap<>(); // [runs, succeeded]
        for (RunBucket b : buckets) {
            if (b.kind() != RunKind.CHANNEL || b.channel() == null || b.runs() <= 0
                || !inWindow.contains(b.weekStart())) {
                continue;
            }
            String channel = b.channel().toUpperCase(Locale.ROOT);
            users.computeIfAbsent(channel, k -> new HashSet<>()).add(b.userKey());
            long[] c = counts.computeIfAbsent(channel, k -> new long[2]);
            c[0] += b.runs();
            if (b.success()) {
                c[1] += b.runs();
            }
        }
        List<ChannelPoint> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : users.entrySet()) {
            long[] c = counts.get(e.getKey());
            if (isSuppressed(e.getValue().size())) {
                out.add(new ChannelPoint(e.getKey(), null, null, true));
            } else {
                out.add(new ChannelPoint(e.getKey(), c[0], c[0] > 0 ? round4((double) c[1] / c[0]) : null, false));
            }
        }
        out.sort(Comparator
            .comparing(ChannelPoint::suppressed)
            .thenComparing((ChannelPoint p) -> p.runs() == null ? 0L : -p.runs())
            .thenComparing(ChannelPoint::channel));
        return out;
    }

    // ----------------------------------------------------------- time to first run

    /**
     * Per signup-week cohort: how many users signed up, how many reached a
     * first successful user-initiated run, and the median minutes it took.
     * {@code signupsByWeek} may be null when the signup count could not be
     * read; the signups cell is then null rather than a false zero.
     */
    public static List<CohortPoint> cohorts(
        List<FirstRun> firstRuns, Map<LocalDate, Long> signupsByWeek, List<LocalDate> weekStarts
    ) {
        Map<LocalDate, Set<String>> users = new HashMap<>();
        Map<LocalDate, List<Double>> minutes = new HashMap<>();
        for (FirstRun f : firstRuns) {
            if (!validMinutes(f.minutes())) {
                continue;
            }
            users.computeIfAbsent(f.signupWeek(), k -> new HashSet<>()).add(f.userKey());
            minutes.computeIfAbsent(f.signupWeek(), k -> new ArrayList<>()).add(f.minutes());
        }
        List<CohortPoint> out = new ArrayList<>(weekStarts.size());
        for (LocalDate week : weekStarts) {
            long activated = users.getOrDefault(week, Set.of()).size();
            Long signups = signupsByWeek == null ? null : signupsByWeek.getOrDefault(week, 0L);
            boolean hidden = isSuppressed(activated) || (signups != null && isSuppressed(signups));
            if (hidden) {
                out.add(new CohortPoint(week, null, null, null, true));
                continue;
            }
            Double median = activated > 0 ? median(minutes.get(week)) : null;
            out.add(new CohortPoint(week, signups, activated, median, false));
        }
        return out;
    }

    /**
     * Median minutes over every first run whose signup week is in the window,
     * or null when fewer than {@link #MIN_CONTRIBUTORS} distinct users
     * contributed (including zero: there is nothing to take a median of).
     */
    public static Double overallMedianMinutes(List<FirstRun> firstRuns, List<LocalDate> weekStarts) {
        Set<LocalDate> inWindow = new HashSet<>(weekStarts);
        Set<String> users = new HashSet<>();
        List<Double> minutes = new ArrayList<>();
        for (FirstRun f : firstRuns) {
            if (validMinutes(f.minutes()) && inWindow.contains(f.signupWeek())) {
                users.add(f.userKey());
                minutes.add(f.minutes());
            }
        }
        if (users.size() < MIN_CONTRIBUTORS) {
            return null;
        }
        return median(minutes);
    }

    private static boolean validMinutes(double m) {
        return Double.isFinite(m) && m >= 0;
    }

    /** Standard median (mean of the two middle values for an even count), rounded to one decimal. */
    public static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double m = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return Math.round(m * 10.0) / 10.0;
    }

    // -------------------------------------------------------------------- pass^k

    /**
     * Average pass^k over eval cases that were replayed k >= 2 times, per k and
     * overall. Null when there is no repeated-run data at all.
     */
    public static PassK passK(List<PassKRow> rows) {
        Map<Integer, Set<String>> users = new TreeMap<>();
        Map<Integer, long[]> counts = new HashMap<>(); // [cases, allPassed]
        for (PassKRow r : rows) {
            if (r.k() < 2 || r.cases() <= 0) {
                continue;
            }
            users.computeIfAbsent(r.k(), k -> new HashSet<>()).add(r.userKey());
            long[] c = counts.computeIfAbsent(r.k(), k -> new long[2]);
            c[0] += r.cases();
            c[1] += Math.min(r.casesAllPassed(), r.cases());
        }
        if (users.isEmpty()) {
            return null;
        }
        List<PassKGroup> groups = new ArrayList<>();
        long visibleCases = 0;
        long visiblePassed = 0;
        Set<String> visibleUsers = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> e : users.entrySet()) {
            long[] c = counts.get(e.getKey());
            if (isSuppressed(e.getValue().size())) {
                groups.add(new PassKGroup(e.getKey(), null, null, true));
            } else {
                groups.add(new PassKGroup(e.getKey(), c[0], round4((double) c[1] / c[0]), false));
                visibleCases += c[0];
                visiblePassed += c[1];
                visibleUsers.addAll(e.getValue());
            }
        }
        boolean anySuppressed = groups.stream().anyMatch(PassKGroup::suppressed);
        boolean averageHidden = visibleCases == 0 || visibleUsers.size() < MIN_CONTRIBUTORS;
        Double average = averageHidden ? null : round4((double) visiblePassed / visibleCases);
        return new PassK(average, averageHidden ? null : visibleCases, groups, anySuppressed || averageHidden);
    }

    // --------------------------------------------------------------- suppression

    /** How many published cells were hidden by the {@value #MIN_CONTRIBUTORS}-user rule, for the notes list. */
    public static int countSuppressed(
        List<WeekPoint> weeks, List<CohortPoint> cohorts, List<ChannelPoint> channels, PassK passK
    ) {
        int n = 0;
        for (WeekPoint w : weeks) {
            if (w.suppressed()) {
                n++;
            }
        }
        for (CohortPoint c : cohorts) {
            if (c.suppressed()) {
                n++;
            }
        }
        for (ChannelPoint c : channels) {
            if (c.suppressed()) {
                n++;
            }
        }
        if (passK != null) {
            for (PassKGroup g : passK.byK()) {
                if (g.suppressed()) {
                    n++;
                }
            }
        }
        return n;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
