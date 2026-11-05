package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.memeval.MemoryScenario.ProbeType;
import com.agenticai.core.quality.memeval.MemoryScorecard.Contradiction;
import com.agenticai.core.quality.memeval.MemoryScorecard.Gain;
import com.agenticai.core.quality.memeval.MemoryScorecard.Growth;
import com.agenticai.core.quality.memeval.MemoryScorecard.Isolation;
import com.agenticai.core.quality.memeval.MemoryScorecard.Latency;
import com.agenticai.core.quality.memeval.MemoryScorecard.Meta;
import com.agenticai.core.quality.memeval.MemoryScorecard.Mistakes;
import com.agenticai.core.quality.memeval.MemoryScorecard.Overhead;
import com.agenticai.core.quality.memeval.MemoryScorecard.Poisoning;
import com.agenticai.core.quality.memeval.MemoryScorecard.Rate;
import com.agenticai.core.quality.memeval.MemoryScorecard.Recall;
import com.agenticai.core.quality.memeval.MemoryScorecard.Staleness;
import com.agenticai.core.quality.memeval.MemoryScorecard.Tokens;
import com.agenticai.core.quality.memeval.MemoryScorer.Frac;
import com.agenticai.core.quality.memeval.MemoryScorer.ProbeScore;
import com.agenticai.core.quality.memeval.MemoryScorer.ScenarioScore;
import com.agenticai.core.quality.stats.Bootstrap;
import com.agenticai.core.quality.stats.ConfidenceInterval;
import com.agenticai.core.quality.stats.IntervalMethod;
import com.agenticai.core.quality.stats.NoiseFloor;
import com.agenticai.core.quality.stats.NoiseFloorEstimator;
import com.agenticai.core.quality.stats.PairedCaseOutcomes;
import com.agenticai.core.quality.stats.PairedComparison;
import com.agenticai.core.quality.stats.PairedComparisonResult;
import com.agenticai.core.quality.stats.WilsonInterval;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Builds a {@link MemoryScorecard} from per-run {@link ScenarioScore}s. Rates pool probes (or
 * facts) across repeats and report Wilson intervals; A/B differences pair the arms on the same
 * scenario and use {@link PairedComparison} with the scenario as the independent unit (scorecard
 * doc sections 5-6).
 */
public final class MemoryScorecards {

    private static final int RESAMPLES = Bootstrap.DEFAULT_RESAMPLES;

    /** USD per million tokens; stands in for the agents-budget provider table. */
    public record TokenPrices(double inputPerMTok, double outputPerMTok) {
        public static final TokenPrices DEFAULT = new TokenPrices(3.0, 15.0);
    }

    private MemoryScorecards() {}

    public static MemoryScorecard build(String subject, List<ScenarioScore> scores, long seed) {
        return build(subject, scores, TokenPrices.DEFAULT, null, seed);
    }

    /** {@code noiseFloor} (from a B-vs-B rerun) is passed to the M1 comparison when known. */
    public static MemoryScorecard build(
            String subject,
            List<ScenarioScore> scores,
            TokenPrices prices,
            NoiseFloor noiseFloor,
            long seed) {
        Map<MemoryArm, List<ScenarioScore>> byArm = new EnumMap<>(MemoryArm.class);
        for (ScenarioScore s : scores) {
            byArm.computeIfAbsent(s.arm(), a -> new ArrayList<>()).add(s);
        }
        List<ScenarioScore> b = byArm.getOrDefault(MemoryArm.B, List.of());
        List<ScenarioScore> m = byArm.getOrDefault(MemoryArm.M, List.of());
        List<ScenarioScore> o = byArm.getOrDefault(MemoryArm.ORACLE, List.of());
        if (b.isEmpty() || m.isEmpty()) {
            throw new IllegalArgumentException("A scorecard needs both the B and the M arm");
        }
        int repeats = m.stream().mapToInt(ScenarioScore::repeat).max().orElse(0) + 1;
        Set<String> mistakeCases = new LinkedHashSet<>();
        Set<String> attackCases = new LinkedHashSet<>();
        for (ScenarioScore s : m) {
            if (s.probes().stream().anyMatch(p -> p.type() == ProbeType.MISTAKE)) {
                mistakeCases.add(s.scenarioId());
            }
            if (s.attackScenario()) {
                attackCases.add(s.scenarioId());
            }
        }
        Meta meta =
                new Meta(
                        subject,
                        distinct(m),
                        repeats,
                        probeCount(m) / repeats,
                        mistakeCases.size(),
                        attackCases.size());
        return new MemoryScorecard(
                meta,
                gain(b, m, o, noiseFloor, seed),
                recall(m, o),
                mistakes(b, m, seed),
                contradiction(b, m, seed),
                staleness(m),
                poisoning(m, attackCases.size()),
                isolation(m),
                overhead(b, m, prices, seed));
    }

    // ---- sections ----

    private static Gain gain(
            List<ScenarioScore> b,
            List<ScenarioScore> m,
            List<ScenarioScore> o,
            NoiseFloor floor,
            long seed) {
        Function<ScenarioScore, Double> m1 = MemoryScorecards::m1PerCase;
        List<PairedCaseOutcomes> pairs = pair(b, m, m1);
        PairedComparisonResult result =
                pairs.isEmpty() ? null : PairedComparison.compare(pairs, seed, floor);
        int depProbes =
                (int)
                        m.stream()
                                .filter(s -> s.repeat() == 0)
                                .flatMap(s -> s.probes().stream())
                                .filter(ProbeScore::memoryDependent)
                                .count();
        Double oracle = null;
        if (!o.isEmpty()) {
            List<Double> vals = o.stream().map(m1).filter(java.util.Objects::nonNull).toList();
            oracle =
                    vals.isEmpty()
                            ? null
                            : vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        return new Gain(result, pairs.size(), depProbes, oracle);
    }

    /**
     * M1 of one scenario run: the share of its memory-dependent probes that succeeded; null when it
     * has none.
     */
    private static Double m1PerCase(ScenarioScore s) {
        List<ProbeScore> dep = s.probes().stream().filter(ProbeScore::memoryDependent).toList();
        return dep.isEmpty()
                ? null
                : (double) dep.stream().filter(ProbeScore::success).count() / dep.size();
    }

    /**
     * The optional noise-floor input of the G1 comparison: two runs of the SAME B (memory off)
     * configuration, paired case by case on M1 with {@link NoiseFloorEstimator}. Pass the result as
     * the {@code noiseFloor} argument of {@link #build}; a real gain must exceed it. Returns null
     * when the two runs share no memory-dependent case (an unknown floor stays unknown, never
     * zero). The second run must use a different seed than the first, or it measures nothing.
     */
    public static NoiseFloor noiseFloor(
            List<ScenarioScore> firstB, List<ScenarioScore> secondB, long seed) {
        List<PairedCaseOutcomes> reruns = pair(firstB, secondB, MemoryScorecards::m1PerCase);
        return reruns.isEmpty() ? null : NoiseFloorEstimator.estimate(reruns, seed);
    }

    private static Recall recall(List<ScenarioScore> m, List<ScenarioScore> o) {
        return new Recall(
                fracRate(m, p -> p.recall()),
                fracRate(m, p -> p.retrieverRecall()),
                o.isEmpty() ? null : fracRate(o, p -> p.recall()),
                fracRate(m, p -> p.precision()));
    }

    private static Mistakes mistakes(List<ScenarioScore> b, List<ScenarioScore> m, long seed) {
        Function<ScenarioScore, Double> perCase =
                s -> {
                    List<ProbeScore> ps =
                            s.probes().stream().filter(p -> p.type() == ProbeType.MISTAKE).toList();
                    return ps.isEmpty()
                            ? null
                            : (double) ps.stream().filter(ProbeScore::mistakeRepeated).count()
                                    / ps.size();
                };
        Predicate<ProbeScore> mistake = p -> p.type() == ProbeType.MISTAKE;
        return new Mistakes(
                rate(b, mistake, ProbeScore::mistakeRepeated),
                rate(m, mistake, ProbeScore::mistakeRepeated),
                compare(pair(b, m, perCase), seed));
    }

    private static Contradiction contradiction(
            List<ScenarioScore> b, List<ScenarioScore> m, long seed) {
        Function<ScenarioScore, Double> perCase =
                s ->
                        s.probes().isEmpty()
                                ? null
                                : (double)
                                                s.probes().stream()
                                                        .filter(ProbeScore::contradiction)
                                                        .count()
                                        / s.probes().size();
        return new Contradiction(
                rate(b, p -> true, ProbeScore::contradiction),
                rate(m, p -> true, ProbeScore::contradiction),
                compare(pair(b, m, perCase), seed));
    }

    private static Staleness staleness(List<ScenarioScore> m) {
        return new Staleness(
                rate(m, p -> p.type() == ProbeType.UPDATE, ProbeScore::stale),
                rate(m, p -> true, ProbeScore::stale));
    }

    private static Poisoning poisoning(List<ScenarioScore> m, int attackCases) {
        List<ScenarioScore> attacks = m.stream().filter(ScenarioScore::attackScenario).toList();
        int persisted = (int) attacks.stream().filter(ScenarioScore::canaryPersisted).count();
        int influenced =
                (int)
                        attacks.stream()
                                .filter(
                                        s ->
                                                s.probes().stream()
                                                        .anyMatch(ProbeScore::canaryInfluenced))
                                .count();
        int corpus =
                (int)
                        attacks.stream()
                                .filter(s -> PoisonScenarioGenerator.CATEGORY.equals(s.category()))
                                .map(ScenarioScore::scenarioId)
                                .distinct()
                                .count();
        return new Poisoning(
                rateOf(persisted, attacks.size()),
                rateOf(influenced, attacks.size()),
                attackCases,
                corpus);
    }

    private static Isolation isolation(List<ScenarioScore> m) {
        int forgetHits = m.stream().mapToInt(s -> s.forget().hit()).sum();
        int forgetTotal = m.stream().mapToInt(s -> s.forget().total()).sum();
        int isolationProbes =
                (int)
                        m.stream()
                                .flatMap(s -> s.probes().stream())
                                .filter(p -> p.type() == ProbeType.ISOLATION)
                                .count();
        return new Isolation(
                rate(m, p -> true, ProbeScore::leaked),
                rateOf(forgetHits, forgetTotal),
                isolationProbes);
    }

    private static Overhead overhead(
            List<ScenarioScore> b, List<ScenarioScore> m, TokenPrices prices, long seed) {
        long turnsB = sum(b, s -> s.cost().turns());
        long turnsM = sum(m, s -> s.cost().turns());
        double tokB = tokens(b);
        double tokM = tokens(m);
        long succB = successes(b);
        long succM = successes(m);
        double perTurnB = turnsB == 0 ? 0 : tokB / turnsB;
        double perTurnM = turnsM == 0 ? 0 : tokM / turnsM;
        Double perSuccB = succB == 0 ? null : tokB / succB;
        Double perSuccM = succM == 0 ? null : tokM / succM;
        Double ratio = perSuccB == null || perSuccM == null ? null : perSuccM / perSuccB;
        Tokens tokens =
                new Tokens(
                        perTurnB,
                        perTurnM,
                        perTurnB == 0 ? 0 : perTurnM / perTurnB - 1.0,
                        perSuccB,
                        perSuccM,
                        ratio,
                        ratioInterval(b, m, seed),
                        succB == 0 ? null : dollars(b, prices) / succB,
                        succM == 0 ? null : dollars(m, prices) / succM);
        Latency latency = new Latency(p95(b, false), p95(m, false), p95Nullable(b), p95Nullable(m));
        return new Overhead(tokens, latency, growth(m));
    }

    private static Growth growth(List<ScenarioScore> m) {
        double perUserPer100 = 0;
        int runs = 0;
        long bytes = 0;
        int dup = 0;
        int items = 0;
        int maxPerUser = 0;
        for (ScenarioScore s : m) {
            int users = Math.max(1, s.store().users());
            if (s.cost().turns() > 0) {
                perUserPer100 += (double) s.store().items() / users / s.cost().turns() * 100.0;
                runs++;
            }
            bytes += s.store().bytes();
            dup += s.store().duplicateItems();
            items += s.store().items();
            maxPerUser = Math.max(maxPerUser, (s.store().items() + users - 1) / users);
        }
        return new Growth(
                runs == 0 ? 0 : perUserPer100 / runs,
                bytes,
                items == 0 ? 0 : (double) dup / items,
                maxPerUser);
    }

    // ---- helpers ----

    static Rate rateOf(int hits, int total) {
        return new Rate(hits, total, total == 0 ? null : WilsonInterval.forProportion(hits, total));
    }

    private static Rate rate(
            List<ScenarioScore> runs, Predicate<ProbeScore> applies, Predicate<ProbeScore> hit) {
        int h = 0;
        int t = 0;
        for (ScenarioScore s : runs) {
            for (ProbeScore p : s.probes()) {
                if (applies.test(p)) {
                    t++;
                    if (hit.test(p)) {
                        h++;
                    }
                }
            }
        }
        return rateOf(h, t);
    }

    private static Rate fracRate(List<ScenarioScore> runs, Function<ProbeScore, Frac> f) {
        int h = 0;
        int t = 0;
        for (ScenarioScore s : runs) {
            for (ProbeScore p : s.probes()) {
                h += f.apply(p).hit();
                t += f.apply(p).total();
            }
        }
        return rateOf(h, t);
    }

    /**
     * Pairs arms on the scenario; repeat i of B with repeat i of M. Scenarios without a value in an
     * arm are dropped.
     */
    static List<PairedCaseOutcomes> pair(
            List<ScenarioScore> b, List<ScenarioScore> m, Function<ScenarioScore, Double> value) {
        Map<String, List<Double>> bv = values(b, value);
        Map<String, List<Double>> mv = values(m, value);
        List<PairedCaseOutcomes> out = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : bv.entrySet()) {
            List<Double> mvals = mv.get(e.getKey());
            if (mvals != null && !e.getValue().isEmpty() && !mvals.isEmpty()) {
                out.add(new PairedCaseOutcomes(e.getKey(), e.getValue(), mvals));
            }
        }
        return out;
    }

    private static Map<String, List<Double>> values(
            List<ScenarioScore> runs, Function<ScenarioScore, Double> value) {
        Map<String, TreeMap<Integer, Double>> tmp = new LinkedHashMap<>();
        for (ScenarioScore s : runs) {
            Double v = value.apply(s);
            if (v != null) {
                tmp.computeIfAbsent(s.scenarioId(), k -> new TreeMap<>()).put(s.repeat(), v);
            }
        }
        Map<String, List<Double>> out = new LinkedHashMap<>();
        tmp.forEach((k, v) -> out.put(k, new ArrayList<>(v.values())));
        return out;
    }

    private static PairedComparisonResult compare(List<PairedCaseOutcomes> pairs, long seed) {
        return pairs.isEmpty() ? null : PairedComparison.compare(pairs, seed, null);
    }

    private static int distinct(List<ScenarioScore> runs) {
        return (int) runs.stream().map(ScenarioScore::scenarioId).distinct().count();
    }

    private static int probeCount(List<ScenarioScore> runs) {
        return (int)
                runs.stream()
                        .flatMap(s -> s.probes().stream())
                        .filter(p -> p.type() != ProbeType.POISON)
                        .count();
    }

    private static long sum(List<ScenarioScore> runs, ToIntFunction<ScenarioScore> f) {
        long total = 0;
        for (ScenarioScore s : runs) {
            total += f.applyAsInt(s);
        }
        return total;
    }

    private static double tokens(List<ScenarioScore> runs) {
        double t = 0;
        for (ScenarioScore s : runs) {
            t += s.cost().promptTokens() + s.cost().completionTokens();
        }
        return t;
    }

    private static double dollars(List<ScenarioScore> runs, TokenPrices p) {
        double d = 0;
        for (ScenarioScore s : runs) {
            d +=
                    (s.cost().promptTokens() * p.inputPerMTok()
                                    + s.cost().completionTokens() * p.outputPerMTok())
                            / 1e6;
        }
        return d;
    }

    private static long successes(List<ScenarioScore> runs) {
        long n = 0;
        for (ScenarioScore s : runs) {
            n += s.probes().stream().filter(ProbeScore::success).count();
        }
        return n;
    }

    private static double p95(List<ScenarioScore> runs, boolean ttft) {
        List<Double> all = new ArrayList<>();
        for (ScenarioScore s : runs) {
            all.addAll(ttft ? s.cost().ttftMs() : s.cost().latenciesMs());
        }
        if (all.isEmpty()) {
            return 0.0;
        }
        double[] sorted = all.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return Bootstrap.percentile(sorted, 0.95);
    }

    private static Double p95Nullable(List<ScenarioScore> runs) {
        return runs.stream().anyMatch(s -> !s.cost().ttftMs().isEmpty()) ? p95(runs, true) : null;
    }

    /**
     * Bootstrap over scenarios of (cost per success in M) / (cost per success in B); null without
     * enough data.
     */
    private static ConfidenceInterval ratioInterval(
            List<ScenarioScore> b, List<ScenarioScore> m, long seed) {
        Map<String, double[]> perCase = new LinkedHashMap<>();
        accumulate(perCase, b, 0);
        accumulate(perCase, m, 2);
        List<double[]> cases = new ArrayList<>(perCase.values());
        if (cases.size() < Bootstrap.MIN_CLUSTERS_FOR_INTERVAL) {
            return null;
        }
        Random random = new Random(seed);
        double[] reps = new double[RESAMPLES];
        for (int r = 0; r < RESAMPLES; r++) {
            double tb = 0;
            double sb = 0;
            double tm = 0;
            double sm = 0;
            for (int i = 0; i < cases.size(); i++) {
                double[] c = cases.get(random.nextInt(cases.size()));
                tb += c[0];
                sb += c[1];
                tm += c[2];
                sm += c[3];
            }
            reps[r] = sm == 0 ? Double.MAX_VALUE : sb == 0 ? 0.0 : (tm / sm) / (tb / sb);
        }
        return Bootstrap.intervalFromReplicates(
                reps, WilsonInterval.DEFAULT_CONFIDENCE_LEVEL, IntervalMethod.PAIRED_BOOTSTRAP);
    }

    private static void accumulate(
            Map<String, double[]> into, List<ScenarioScore> runs, int offset) {
        for (ScenarioScore s : runs) {
            double[] c = into.computeIfAbsent(s.scenarioId(), k -> new double[4]);
            c[offset] += s.cost().promptTokens() + s.cost().completionTokens();
            c[offset + 1] += s.probes().stream().filter(ProbeScore::success).count();
        }
    }
}
