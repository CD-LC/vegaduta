package com.agenticai.core.agents.learning.routines;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The pure heuristics behind "routines from habits": which requests are the same intent, and whether the moments
 * the person made them form a rhythm (daily, every weekday, or weekly on one day at about one time). No Spring, no
 * database, no clock of its own, so every rule is unit-testable.
 *
 * <p>No embedding port is used. Requests are normalised to a set of content words (lower-cased, stop words and
 * time words dropped, a crude plural stem) and grouped by Jaccard similarity, which is robust enough for "summarise
 * my unread emails" against "Please summarise the unread emails from today" and costs nothing per request.
 */
public final class RoutineDetector {

    /** The cadences the loop can propose; each maps onto a {@code ScheduledTaskSchedule.Kind}. */
    public enum Cadence {
        DAILY("daily"),
        WEEKDAYS("weekdays"),
        WEEKLY("weekly");

        private final String frequency;

        Cadence(String frequency) {
            this.frequency = frequency;
        }

        /** The {@code frequency} value the scheduled-tasks API takes. */
        public String frequency() {
            return frequency;
        }
    }

    /** One remembered request. */
    public record Obs(UUID agentId, String source, Set<String> tokens, String text, Instant at) {}

    public record Settings(
            double similarity,
            int minOccurrences,
            int minDistinctDays,
            int maxTimeSpreadMinutes,
            ZoneId zone) {}

    /**
     * A detected routine.
     *
     * @param time {@code HH:mm} (24h) in {@code timezone}
     * @param day {@code MON}..{@code SUN} for WEEKLY, else null
     */
    public record Routine(
            String prompt,
            UUID agentId,
            Cadence cadence,
            String time,
            String day,
            String timezone,
            int occurrences,
            int distinctDays,
            Instant firstSeen,
            Instant lastSeen,
            Set<String> tokens) {

        /** The normalised 6-field cron the scheduled-tasks service would store for this schedule. */
        public String cron() {
            String[] hm = time.split(":");
            String minute = String.valueOf(Integer.parseInt(hm[1]));
            String hour = String.valueOf(Integer.parseInt(hm[0]));
            String dow =
                    switch (cadence) {
                        case DAILY -> "*";
                        case WEEKDAYS -> "MON-FRI";
                        case WEEKLY -> day;
                    };
            return "0 " + minute + " " + hour + " * * " + dow;
        }

        /** Plain words, e.g. "Every Monday at 09:00 (Asia/Kolkata)". */
        public String scheduleInWords() {
            return ScheduledTaskSchedule.describe(cron(), timezone);
        }
    }

    private static final Set<String> STOP =
            Set.of(
                    "a", "an", "the", "and", "or", "of", "to", "for", "in", "on", "at", "by", "with", "from",
                    "my", "me", "i", "you", "your", "our", "we", "us", "it", "its", "is", "are", "was", "be",
                    "do", "does", "can", "could", "would", "will", "should", "please", "pls", "kindly", "hey",
                    "hi", "hello", "thanks", "thank", "again", "just", "some", "any", "all", "that", "this",
                    "these", "those", "there", "here", "what", "whats", "give", "get", "show", "tell", "let",
                    "need", "want", "like", "also", "now", "then", "as", "so", "if", "about", "into", "up",
                    "out", "over", "new", "latest", "recent", "last", "next", "past", "today", "todays",
                    "tomorrow", "yesterday", "tonight", "morning", "afternoon", "evening", "night", "week",
                    "weekly", "daily", "day", "month", "year", "monday", "tuesday", "wednesday", "thursday",
                    "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june",
                    "july", "august", "september", "october", "november", "december", "am", "pm");

    private static final int MIN_TOKENS = 2;
    private static final int MIN_TEXT_CHARS = 12;
    private static final int MAX_TEXT_CHARS = 500;
    /** Daily and weekday routines must have been seen within this many days; weekly ones within {@code WEEKLY_FRESH}. */
    static final int DAILY_FRESH_DAYS = 4;

    static final int WEEKLY_FRESH_DAYS = 10;

    private RoutineDetector() {}

    // ---------------------------------------------------------------- normalisation

    /** True when a request is worth remembering at all: a sentence, not a command, a greeting or a paste. */
    public static boolean eligible(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.length() >= MIN_TEXT_CHARS
                && t.length() <= MAX_TEXT_CHARS
                && !t.startsWith("/")
                && tokens(t).size() >= MIN_TOKENS;
    }

    /** The sorted content words of a request; the unit of comparison. Empty when there is too little to compare. */
    public static Set<String> tokens(String text) {
        Set<String> out = new TreeSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) {
                continue;
            }
            String w = raw;
            if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) {
                w = w.substring(0, w.length() - 1);
            }
            out.add(w);
        }
        return out;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String s : a) {
            if (b.contains(s)) {
                shared++;
            }
        }
        return (double) shared / (a.size() + b.size() - shared);
    }

    // ---------------------------------------------------------------- detection

    /** Every routine found in {@code all}, most-observed first. {@code now} decides whether a habit is still alive. */
    public static List<Routine> detect(List<Obs> all, Settings s, Instant now) {
        List<Obs> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(Obs::at));
        List<Routine> found = new ArrayList<>();
        for (List<Obs> cluster : cluster(sorted, s.similarity())) {
            Routine r = analyse(cluster, s, now);
            if (r != null) {
                found.add(r);
            }
        }
        found.sort(Comparator.comparingInt(Routine::occurrences).reversed());
        return found;
    }

    /** Single-link grouping: a request joins the cluster holding its most similar member, if similar enough. */
    static List<List<Obs>> cluster(List<Obs> sortedByTime, double threshold) {
        List<List<Obs>> clusters = new ArrayList<>();
        for (Obs o : sortedByTime) {
            List<Obs> best = null;
            double bestSim = 0;
            for (List<Obs> c : clusters) {
                double sim = 0;
                for (Obs m : c) {
                    sim = Math.max(sim, jaccard(m.tokens(), o.tokens()));
                }
                if (sim >= threshold && sim > bestSim) {
                    best = c;
                    bestSim = sim;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                clusters.add(best);
            }
            best.add(o);
        }
        return clusters;
    }

    static Routine analyse(List<Obs> cluster, Settings s, Instant now) {
        if (cluster.size() < s.minOccurrences()) {
            return null;
        }
        // The first request of each local day: one day counts once however many times the person asked.
        TreeMap<LocalDate, Obs> byDay = new TreeMap<>();
        for (Obs o : cluster) {
            byDay.merge(o.at().atZone(s.zone()).toLocalDate(), o, (a, b) -> a.at().isBefore(b.at()) ? a : b);
        }
        if (byDay.size() < s.minDistinctDays()) {
            return null;
        }
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        List<Integer> minutes = new ArrayList<>();
        for (Obs o : byDay.values()) {
            var local = o.at().atZone(s.zone());
            minutes.add(local.getHour() * 60 + local.getMinute());
        }
        List<Integer> sortedMinutes = new ArrayList<>(minutes);
        sortedMinutes.sort(Integer::compare);
        int spread = sortedMinutes.get(sortedMinutes.size() - 1) - sortedMinutes.get(0);
        if (spread > s.maxTimeSpreadMinutes()) {
            return null; // asked at all hours: a topic the person likes, not a rhythm
        }
        int median = sortedMinutes.get(sortedMinutes.size() / 2);
        int rounded = Math.min(23 * 60 + 55, Math.round(median / 5f) * 5);
        String time = String.format(Locale.ROOT, "%02d:%02d", rounded / 60, rounded % 60);

        Cadence cadence = cadenceOf(days);
        if (cadence == null) {
            return null;
        }
        long sinceLast = ChronoUnit.DAYS.between(days.get(days.size() - 1), now.atZone(s.zone()).toLocalDate());
        int fresh = cadence == Cadence.WEEKLY ? WEEKLY_FRESH_DAYS : DAILY_FRESH_DAYS;
        if (sinceLast > fresh) {
            return null; // the habit has lapsed
        }
        String day = null;
        if (cadence == Cadence.WEEKLY) {
            day = days.get(0).getDayOfWeek().name().substring(0, 3);
        }
        Obs rep = representative(cluster);
        Obs latest = cluster.get(cluster.size() - 1);
        return new Routine(
                rep.text(),
                latest.agentId(),
                cadence,
                time,
                day,
                s.zone().getId(),
                cluster.size(),
                days.size(),
                cluster.get(0).at(),
                latest.at(),
                rep.tokens());
    }

    /** DAILY, WEEKDAYS or WEEKLY for these distinct local days (ascending), or null when they follow no rhythm. */
    static Cadence cadenceOf(List<LocalDate> days) {
        if (days.size() < 2) {
            return null;
        }
        DayOfWeek first = days.get(0).getDayOfWeek();
        boolean sameWeekday = true;
        boolean weeklyGaps = true;
        boolean anyWeekend = false;
        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            sameWeekday &= d.getDayOfWeek() == first;
            anyWeekend |= isWeekend(d);
            if (i > 0) {
                long gap = ChronoUnit.DAYS.between(days.get(i - 1), d);
                weeklyGaps &= gap % 7 == 0 && gap <= 28;
            }
        }
        if (sameWeekday && weeklyGaps) {
            return Cadence.WEEKLY;
        }
        int consecutive = 0;
        for (int i = 1; i < days.size(); i++) {
            LocalDate a = days.get(i - 1);
            LocalDate b = days.get(i);
            long gap = anyWeekend ? ChronoUnit.DAYS.between(a, b) : weekdaysBetween(a, b);
            if (gap > 2) {
                return null; // a hole of more than one missed day: not a daily habit
            }
            if (gap == 1) {
                consecutive++;
            }
        }
        // Most steps must be "the next day", so Mon/Wed/Fri or a burst with holes is not read as daily.
        if (consecutive * 10 < (days.size() - 1) * 6) {
            return null;
        }
        return anyWeekend ? Cadence.DAILY : Cadence.WEEKDAYS;
    }

    /** Weekdays in (a, b]: Friday to Monday is 1. */
    static long weekdaysBetween(LocalDate a, LocalDate b) {
        long n = 0;
        for (LocalDate d = a.plusDays(1); !d.isAfter(b); d = d.plusDays(1)) {
            if (!isWeekend(d)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** The member most like all the others (ties: the earliest), so the prompt is the person's own typical wording. */
    static Obs representative(List<Obs> cluster) {
        Obs best = cluster.get(0);
        double bestScore = -1;
        for (Obs a : cluster) {
            double score = 0;
            for (Obs b : cluster) {
                score += jaccard(a.tokens(), b.tokens());
            }
            if (score > bestScore) {
                best = a;
                bestScore = score;
            }
        }
        return best;
    }

    /** Wording-insensitive key for "is this the same request?", e.g. against an existing scheduled task. */
    public static boolean sameIntent(Set<String> a, String otherText, double threshold) {
        return jaccard(a, tokens(otherText)) >= threshold;
    }
}
