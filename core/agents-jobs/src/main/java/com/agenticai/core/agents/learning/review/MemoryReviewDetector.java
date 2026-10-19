package com.agenticai.core.agents.learning.review;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Finds what is worth tidying in a person's remembered facts, with cheap word-overlap similarity and no model
 * call: near-duplicates (keep the more informative one), contradictions (keep the newer one) and facts nobody
 * has used for a long time (forget). Pure and deterministic. It only ever compares facts inside one tier (one
 * agent's private facts, or the shared facts), because merging across tiers would change who can recall them.
 */
public final class MemoryReviewDetector {

    public enum Type {
        DUPLICATE,
        CONTRADICTION,
        STALE
    }

    /**
     * One suggested tidy-up. For DUPLICATE and CONTRADICTION {@code keep} survives (its text may be reworded by
     * the person) and {@code dropped} go; for STALE {@code keep} is null and the single dropped fact goes.
     */
    public record Finding(Type type, MemoryFactRef keep, List<MemoryFactRef> dropped) {}

    public record Settings(
            double duplicateThreshold,
            double contradictionThreshold,
            int staleDays,
            int maxFindings,
            int maxStale) {
        public static Settings defaults() {
            return new Settings(0.8, 0.5, 60, 5, 3);
        }
    }

    /** Most facts compared inside one tier, so the pairwise pass stays small. */
    static final int MAX_FACTS_PER_TIER = 200;

    private static final int MAX_DROPPED_PER_FINDING = 5;
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}']+");
    private static final Set<String> NEGATIONS =
            Set.of("not", "no", "never", "cannot", "cant", "dont", "doesnt", "isnt", "wont", "without", "stopped", "nothing");
    private static final Set<String> STOP =
            Set.of("the", "and", "for", "that", "this", "with", "from", "are", "was", "were", "has", "have", "had",
                    "his", "her", "its", "their", "you", "your", "user", "they", "them", "but", "can", "will", "all");

    private MemoryReviewDetector() {}

    public static List<Finding> detect(List<MemoryFactRef> facts, Instant now, Settings s) {
        Map<String, List<MemoryFactRef>> tiers = new LinkedHashMap<>();
        for (MemoryFactRef f : facts) {
            if (f != null && f.content() != null && !f.content().isBlank()) {
                tiers.computeIfAbsent(f.tier(), k -> new ArrayList<>()).add(f);
            }
        }
        Set<UUID> used = new HashSet<>();
        List<Finding> out = new ArrayList<>();
        Instant staleBefore = now.minus(Duration.ofDays(Math.max(1, s.staleDays())));
        for (List<MemoryFactRef> tier : tiers.values()) {
            List<Sig> sigs = signatures(tier);
            duplicates(sigs, used, s, out);
            contradictions(sigs, used, s, out);
        }
        int stale = 0;
        for (List<MemoryFactRef> tier : tiers.values()) {
            for (MemoryFactRef f : tier) {
                if (stale < s.maxStale() && !used.contains(f.id()) && f.lastActivity() != null
                        && f.lastActivity().isBefore(staleBefore)) {
                    used.add(f.id());
                    out.add(new Finding(Type.STALE, null, List.of(f)));
                    stale++;
                }
            }
        }
        return out.size() > s.maxFindings() ? List.copyOf(out.subList(0, s.maxFindings())) : out;
    }

    // ---------------------------------------------------------------- passes

    private static void duplicates(List<Sig> sigs, Set<UUID> used, Settings s, List<Finding> out) {
        List<Sig> byInfo = new ArrayList<>(sigs);
        // Most informative first, so the longer wording is the one kept; newer first on a tie.
        byInfo.sort(
                Comparator.comparingInt((Sig x) -> x.fact.content().length())
                        .reversed()
                        .thenComparing(x -> x.fact.createdAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        for (Sig a : byInfo) {
            if (used.contains(a.fact.id())) {
                continue;
            }
            List<MemoryFactRef> drops = new ArrayList<>();
            for (Sig b : byInfo) {
                if (b != a && !used.contains(b.fact.id()) && isDuplicate(a, b, s)) {
                    drops.add(b.fact);
                    if (drops.size() == MAX_DROPPED_PER_FINDING) {
                        break;
                    }
                }
            }
            if (!drops.isEmpty()) {
                used.add(a.fact.id());
                drops.forEach(d -> used.add(d.id()));
                out.add(new Finding(Type.DUPLICATE, a.fact, drops));
            }
        }
    }

    private static void contradictions(List<Sig> sigs, Set<UUID> used, Settings s, List<Finding> out) {
        List<Sig> newestFirst = new ArrayList<>(sigs);
        newestFirst.sort(
                Comparator.comparing((Sig x) -> x.fact.createdAt(), Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                        .reversed());
        for (int i = 0; i < newestFirst.size(); i++) {
            Sig newer = newestFirst.get(i);
            if (used.contains(newer.fact.id())) {
                continue;
            }
            List<MemoryFactRef> drops = new ArrayList<>();
            for (int j = i + 1; j < newestFirst.size() && drops.size() < MAX_DROPPED_PER_FINDING; j++) {
                Sig older = newestFirst.get(j);
                if (!used.contains(older.fact.id()) && contradicts(newer, older, s)) {
                    drops.add(older.fact);
                }
            }
            if (!drops.isEmpty()) {
                used.add(newer.fact.id());
                drops.forEach(d -> used.add(d.id()));
                out.add(new Finding(Type.CONTRADICTION, newer.fact, drops));
            }
        }
    }

    static boolean isDuplicate(Sig a, Sig b, Settings s) {
        if ((a.negations % 2) != (b.negations % 2) || !a.numbers.equals(b.numbers)) {
            return false;
        }
        int min = Math.min(a.words.size(), b.words.size());
        if (min < 2) {
            return false;
        }
        return jaccard(a.words, b.words) >= s.duplicateThreshold()
                || (min >= 3 && containment(a.words, b.words) >= 0.9);
    }

    static boolean contradicts(Sig a, Sig b, Settings s) {
        if (Math.min(a.words.size(), b.words.size()) < 2 || jaccard(a.words, b.words) < s.contradictionThreshold()) {
            return false;
        }
        boolean negationFlips = (a.negations % 2) != (b.negations % 2);
        boolean numbersDiffer = !a.numbers.isEmpty() && !b.numbers.isEmpty() && !a.numbers.equals(b.numbers);
        return negationFlips || numbersDiffer;
    }

    // ------------------------------------------------------------- similarity

    /** A fact reduced to what the comparisons need. {@code words} excludes negations, numbers and stop words. */
    static final class Sig {
        final MemoryFactRef fact;
        final Set<String> words = new HashSet<>();
        final Set<String> numbers = new HashSet<>();
        int negations;

        Sig(MemoryFactRef fact) {
            this.fact = fact;
            String text = fact.content().toLowerCase(Locale.ROOT).replace('’', '\'');
            for (String raw : SPLIT.split(text)) {
                if (raw.isEmpty()) {
                    continue;
                }
                String t = raw.replace("'", "");
                if (raw.endsWith("n't") || NEGATIONS.contains(t)) {
                    negations++;
                } else if (t.chars().allMatch(Character::isDigit)) {
                    numbers.add(t);
                } else if (t.length() >= 3 && !STOP.contains(t)) {
                    words.add(t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") ? t.substring(0, t.length() - 1) : t);
                }
            }
        }
    }

    static Sig sig(MemoryFactRef f) {
        return new Sig(f);
    }

    private static List<Sig> signatures(List<MemoryFactRef> tier) {
        List<MemoryFactRef> capped = tier.size() > MAX_FACTS_PER_TIER ? tier.subList(0, MAX_FACTS_PER_TIER) : tier;
        List<Sig> sigs = new ArrayList<>();
        capped.forEach(f -> sigs.add(new Sig(f)));
        return sigs;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String w : a) {
            if (b.contains(w)) {
                inter++;
            }
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    private static double containment(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int inter = 0;
        for (String w : small) {
            if (large.contains(w)) {
                inter++;
            }
        }
        return small.isEmpty() ? 0 : (double) inter / small.size();
    }
}
