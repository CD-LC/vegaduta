package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.job.schedule.AgentCronSchedules;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;

/**
 * The pure schedule maths behind scheduled tasks: friendly schedule ("every weekday at 08:00") to a normalized
 * 6-field Spring cron, validation (including the minimum interval), next-run computation and the plain-language
 * description the confirmation card and the console show. No Spring context, no database, no clock of its own -
 * every method takes the instant it should reason from, which is what makes the DST / month-end / minimum-interval
 * behaviour unit-testable.
 *
 * <p>Next-run arithmetic is delegated to {@link CronExpression} through {@link AgentCronSchedules} (the schedule
 * zone is applied there), exactly like {@code agent_schedule}: DST is therefore the cron library's job, not
 * arithmetic done here. A wall-clock time that does not exist on a spring-forward day is shifted by the library to
 * the next real instant; a time that occurs twice on a fall-back day fires once.
 *
 * <p>Month ends: a MONTHLY schedule accepts day 1-28 or {@code LAST}. 29, 30 and 31 are rejected on purpose -
 * as a plain cron day-of-month they would silently skip every shorter month ("monthly on the 31st" firing seven
 * times a year), and the honest way to say "end of the month" is {@code LAST}, which maps to the cron {@code L}
 * (the last day of whichever month it is, including a leap February).
 */
public final class ScheduledTaskSchedule {

    /** How the user described the cadence. */
    public enum Kind {
        DAILY, WEEKDAYS, WEEKLY, MONTHLY, CUSTOM;

        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                    "Invalid schedule: a frequency is required (daily, weekdays, weekly, monthly or custom).");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid schedule: frequency must be daily, weekdays, weekly, monthly or custom (got '" + raw + "').");
            }
        }
    }

    /**
     * The user's schedule as entered.
     *
     * @param kind   the cadence type
     * @param time   {@code HH:mm} (24h) for every kind but CUSTOM; ignored for CUSTOM
     * @param day    WEEKLY: MON..SUN; MONTHLY: 1..28 or LAST; ignored otherwise
     * @param cron   CUSTOM only: a 5-field unix cron (or Spring's 6-field form with a zero seconds field)
     */
    public record Spec(Kind kind, String time, String day, String cron) {
    }

    /** A validated schedule: the normalized spec fields plus the derived 6-field cron. */
    public record Compiled(Kind kind, String time, String day, String cron, String timezone) {
    }

    /** The scheduled_task.cron column is VARCHAR(64); the stored form carries a leading seconds field. */
    static final int MAX_CRON_CHARS = 64;

    /** How many upcoming occurrences the minimum-interval check looks across. */
    static final int INTERVAL_PROBE_RUNS = 12;

    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final List<String> DAY_NAMES = List.of(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private ScheduledTaskSchedule() {
    }

    /**
     * Validates a spec and derives its cron.
     *
     * @param now the instant the minimum-interval probe starts from
     * @throws IllegalArgumentException (mapped to HTTP 400) for anything invalid, with a message fit to show a user
     */
    public static Compiled compile(Spec spec, String timezone, int minIntervalMinutes, Instant now) {
        if (spec == null) {
            throw new IllegalArgumentException("Invalid schedule: a schedule is required.");
        }
        ZoneId zone = AgentCronSchedules.zoneOf(timezone);
        String zoneId = zone.getId();
        Kind kind = spec.kind();
        if (kind == null) {
            throw new IllegalArgumentException(
                "Invalid schedule: a frequency is required (daily, weekdays, weekly, monthly or custom).");
        }
        String cron;
        String time = null;
        String day = null;
        switch (kind) {
            case DAILY -> {
                time = normalizeTime(spec.time());
                cron = "0 " + minute(time) + " " + hour(time) + " * * *";
            }
            case WEEKDAYS -> {
                time = normalizeTime(spec.time());
                cron = "0 " + minute(time) + " " + hour(time) + " * * MON-FRI";
            }
            case WEEKLY -> {
                time = normalizeTime(spec.time());
                day = normalizeWeekday(spec.day());
                cron = "0 " + minute(time) + " " + hour(time) + " * * " + day;
            }
            case MONTHLY -> {
                time = normalizeTime(spec.time());
                day = normalizeMonthDay(spec.day());
                String dom = "LAST".equals(day) ? "L" : day;
                cron = "0 " + minute(time) + " " + hour(time) + " " + dom + " * *";
            }
            case CUSTOM -> cron = normalizeCustomCron(spec.cron());
            default -> throw new IllegalArgumentException("Invalid schedule: unsupported frequency " + kind + ".");
        }
        CronExpression parsed = parse(cron);
        List<Instant> probe = runs(parsed, zone, now, INTERVAL_PROBE_RUNS);
        if (probe.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid schedule: '" + cron + "' has no future occurrence, so it would never run.");
        }
        requireMinimumInterval(probe, minIntervalMinutes, cron);
        return new Compiled(kind, time, day, cron, zoneId);
    }

    /** The next {@code count} occurrences strictly after {@code from}, evaluated in {@code timezone}. */
    public static List<Instant> nextRuns(String cron, String timezone, Instant from, int count) {
        return runs(parse(cron), AgentCronSchedules.zoneOf(timezone), from, count);
    }

    /** The next occurrence strictly after {@code from}, or {@code null} when the cron can never fire again. */
    public static Instant nextAfter(String cron, String timezone, Instant from) {
        List<Instant> one = nextRuns(cron, timezone, from, 1);
        return one.isEmpty() ? null : one.get(0);
    }

    /**
     * The missed-run policy as a pure function: a due occurrence is fired AT MOST ONCE, and the NEXT slot is
     * computed from {@code now} - never from the missed slot - so any amount of downtime yields one firing (or
     * none, when it is older than the catch-up window), not a backlog.
     *
     * @return {@code true} when a due occurrence should still fire; {@code false} when it is too stale and should
     *     be recorded as missed (the schedule still advances either way)
     */
    public static boolean withinCatchUpWindow(Instant dueAt, Instant now, long catchUpWindowSeconds) {
        if (dueAt == null) {
            return true;
        }
        return now.getEpochSecond() - dueAt.getEpochSecond() <= catchUpWindowSeconds;
    }

    /**
     * The plain-language description of a cron (as stored: normalized 6-field) in {@code timezone}, e.g. "Every
     * weekday at 08:00 (Asia/Kolkata)". Recognises every shape {@link #compile} generates and falls back to the
     * cron itself for anything else (a CUSTOM expression the recogniser does not know).
     */
    public static String describe(String cron, String timezone) {
        String zone = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        String[] f = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (f.length == 6 && "0".equals(f[0]) && isInt(f[1], 0, 59) && isInt(f[2], 0, 23) && "*".equals(f[4])) {
            String at = String.format(Locale.ROOT, "%02d:%02d", Integer.parseInt(f[2]), Integer.parseInt(f[1]));
            String dom = f[3];
            String dow = f[5].toUpperCase(Locale.ROOT);
            if ("*".equals(dom)) {
                if ("*".equals(dow)) {
                    return "Every day at " + at + " (" + zone + ")";
                }
                if ("MON-FRI".equals(dow) || "1-5".equals(dow)) {
                    return "Every weekday (Monday to Friday) at " + at + " (" + zone + ")";
                }
                String name = weekdayName(dow);
                if (name != null) {
                    return "Every " + name + " at " + at + " (" + zone + ")";
                }
            } else if ("*".equals(dow) || "?".equals(dow)) {
                if ("L".equals(dom)) {
                    return "On the last day of every month at " + at + " (" + zone + ")";
                }
                if (isInt(dom, 1, 31)) {
                    return "On day " + Integer.parseInt(dom) + " of every month at " + at + " (" + zone + ")";
                }
            }
        }
        return "Custom schedule (cron " + (cron == null ? "" : cron.trim()) + ") in " + zone;
    }

    // ---------------------------------------------------------------- internals

    private static List<Instant> runs(CronExpression cron, ZoneId zone, Instant from, int count) {
        List<Instant> out = new ArrayList<>(Math.max(count, 0));
        ZonedDateTime cursor = from.atZone(zone);
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) {
                break;
            }
            out.add(next.toInstant());
            cursor = next;
        }
        return out;
    }

    private static CronExpression parse(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid schedule: '" + cron + "' is not a valid cron expression (" + e.getMessage() + ").");
        }
    }

    private static void requireMinimumInterval(List<Instant> probe, int minIntervalMinutes, String cron) {
        for (int i = 1; i < probe.size(); i++) {
            long gapSeconds = probe.get(i).getEpochSecond() - probe.get(i - 1).getEpochSecond();
            if (gapSeconds < minIntervalMinutes * 60L) {
                throw new IllegalArgumentException(
                    "Invalid schedule: '" + cron + "' would run more often than once every " + minIntervalMinutes
                        + " minutes. Scheduled tasks are meant for recurring work, not polling - choose a "
                        + "longer interval.");
            }
        }
    }

    private static String normalizeTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Invalid schedule: a time of day (HH:mm, 24-hour) is required.");
        }
        Matcher m = TIME.matcher(raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Invalid schedule: time must be HH:mm in 24-hour form, such as 08:00 or 17:30 (got '" + raw + "').");
        }
        return String.format(Locale.ROOT, "%02d:%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    private static int hour(String time) {
        return Integer.parseInt(time.substring(0, 2));
    }

    private static int minute(String time) {
        return Integer.parseInt(time.substring(3, 5));
    }

    private static String normalizeWeekday(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Invalid schedule: a weekday (Mon, Tue, ... Sun) is required for a weekly task.");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (key.length() >= 3) {
            String three = key.substring(0, 3);
            if (DAYS.contains(three) && DAY_NAMES.get(DAYS.indexOf(three)).toUpperCase(Locale.ROOT).startsWith(key)) {
                return three;
            }
        }
        throw new IllegalArgumentException(
            "Invalid schedule: weekday must be one of Mon, Tue, Wed, Thu, Fri, Sat, Sun (got '" + raw + "').");
    }

    private static String normalizeMonthDay(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                "Invalid schedule: a day of the month (1-28) or 'last' is required for a monthly task.");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if ("LAST".equals(key) || "L".equals(key)) {
            return "LAST";
        }
        if (isInt(key, 1, 28)) {
            return Integer.toString(Integer.parseInt(key));
        }
        throw new IllegalArgumentException(
            "Invalid schedule: day of month must be 1-28 or 'last' (got '" + raw + "'). Days 29-31 are not "
                + "accepted because shorter months would be skipped - use 'last' for the end of the month.");
    }

    private static String normalizeCustomCron(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Invalid schedule: a cron expression is required for a custom schedule.");
        }
        String normalized = AgentCronSchedules.normalize(raw);
        String[] fields = normalized.split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                "Invalid schedule: use a 5-field cron such as '0 8 * * 1-5' (minute hour day month weekday).");
        }
        if (!"0".equals(fields[0])) {
            throw new IllegalArgumentException(
                "Invalid schedule: second-level cron is not supported - the seconds field must be 0.");
        }
        if (normalized.length() > MAX_CRON_CHARS) {
            // The "0 " seconds prefix added by normalize() can push a 63-64 character cron past the column.
            throw new IllegalArgumentException(
                "Invalid schedule: that cron expression is too long - keep it to " + (MAX_CRON_CHARS - 2) + " characters.");
        }
        return normalized;
    }

    private static boolean isInt(String s, int min, int max) {
        if (s == null || s.isEmpty() || s.length() > 2) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        int v = Integer.parseInt(s);
        return v >= min && v <= max;
    }

    private static String weekdayName(String dow) {
        if (DAYS.contains(dow)) {
            return DAY_NAMES.get(DAYS.indexOf(dow));
        }
        if (isInt(dow, 0, 7)) {
            int v = Integer.parseInt(dow);
            // Spring: 1 = Monday .. 7 = Sunday; 0 is also accepted as Sunday.
            return DAY_NAMES.get(v == 0 ? 6 : v - 1);
        }
        return null;
    }
}
