package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.reliability.SloSample.Hist;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * The arithmetic behind the SLO view, with no Spring, no clock and no I/O so
 * every rule is unit-tested: window differencing of cumulative counters,
 * merging replicas, percentiles from histogram buckets, the share of requests
 * over a latency target, and error-budget burn.
 *
 * <p>Honest-absence rule: every function that cannot answer returns empty or
 * null - never zero, never a guess. Callers turn that into an omitted series
 * plus a note.
 */
final class SloMath {

    /** One replica's view of the window: the first sample at or after the window start and its newest sample. */
    record InstanceWindow(String instanceId, SloSample base, SloSample latest) {

        long coveredMs() {
            return latest.t() - base.t();
        }
    }

    /** Every replica's counters for the window, summed. */
    record Delta(
        double req,
        double err5xx,
        Hist chat,
        Hist api,
        Hist ttft,
        Map<String, double[]> llm,
        double jobOk,
        double jobFailed,
        /** Replicas that contributed (a window of positive length). */
        int instances,
        long maxCoveredMs,
        long minCoveredMs,
        /** A histogram could not be differenced (counts went backwards) and was dropped for that replica. */
        boolean histogramDropped,
        /** Replicas published different bucket bounds; only the bounds they share were used. */
        boolean bucketMismatch
    ) {
    }

    /** The share of observations at or below {@code boundSeconds}, where the bound is a real bucket bound. */
    record Share(double shareAtOrBelow, double boundSeconds) {
    }

    /** Burn rate at or above which an error-budget verdict reads BREACH. */
    static final double BREACH_BURN = 6.0;
    /** Burn rate at or above which an error-budget verdict reads WARN (errors faster than the objective allows). */
    static final double WARN_BURN = 1.0;

    private static final Pattern CHAT_TURN_URI =
        Pattern.compile("^/api/(share/)?agents/\\{[^/}]+\\}/chat(/stream)?$");
    /**
     * Request templates whose duration is the length of a stream or of a long-running job, not a health signal:
     * SSE run/trace streams, ag-ui, A2A, voice, research, quick-task, brief planning, the demo studio, the
     * Responses API and the synchronous workflow run. Their durations would swamp the "other API" p95.
     * (Chat turns are matched by {@link #CHAT_TURN_URI} first and kept in their own class.)
     */
    private static final Pattern LONG_LIVED_URI = Pattern.compile(
        "^(/api/(voice|channels/voice|research|quick-task|a2a|demo/studio)(/.*)?"
            + "|/api/share/agents/\\{[^/}]+\\}/voice(/.*)?"
            + "|/api/agents/\\{[^/}]+\\}/(agui|a2a)(/.*)?"
            + "|/api/workflows/\\{[^/}]+\\}/run"
            + "|/api/briefs/plan"
            + "|/v1/responses"
            + "|.*/stream"
            + "|.*/runs/\\{[^/}]+\\}/events)$");
    private static final double EPS = 1e-9;

    private SloMath() {
    }

    /** True for the request templates that are long-lived streams or long-running calls (see {@link #LONG_LIVED_URI}). */
    static boolean isLongLived(String uriTemplate) {
        return uriTemplate != null && LONG_LIVED_URI.matcher(uriTemplate).matches();
    }

    // ------------------------------------------------------------------ classification

    /** True for the request templates that are a chat turn (agent chat, shared-agent chat, OpenAI-compatible chat). */
    static boolean isChatTurn(String uriTemplate) {
        if (uriTemplate == null) {
            return false;
        }
        return CHAT_TURN_URI.matcher(uriTemplate).matches() || "/v1/chat/completions".equals(uriTemplate);
    }

    /** Actuator requests (probes, scrapes) are not user traffic. */
    static boolean isActuator(String uriTemplate) {
        return uriTemplate != null && (uriTemplate.equals("/actuator") || uriTemplate.startsWith("/actuator/"));
    }

    /** A 5xx status tag. */
    static boolean isServerError(String statusTag) {
        return statusTag != null && statusTag.length() == 3 && statusTag.charAt(0) == '5';
    }

    // ------------------------------------------------------------------ window combine

    /**
     * Sums, over every replica, (newest sample - window-start sample). A replica whose two samples are the
     * same instant (or inverted) contributes nothing. Counters are clamped at zero; a histogram whose bucket
     * counts went backwards is dropped for that replica and flagged.
     */
    static Delta combine(List<InstanceWindow> windows) {
        double req = 0;
        double err = 0;
        double jobOk = 0;
        double jobFailed = 0;
        Hist chat = null;
        Hist api = null;
        Hist ttft = null;
        Map<String, double[]> llm = new HashMap<>();
        int instances = 0;
        long maxCovered = 0;
        long minCovered = Long.MAX_VALUE;
        boolean dropped = false;
        boolean[] mismatch = {false};

        for (InstanceWindow w : windows) {
            if (w.base() == null || w.latest() == null || w.coveredMs() <= 0) {
                continue;
            }
            instances++;
            maxCovered = Math.max(maxCovered, w.coveredMs());
            minCovered = Math.min(minCovered, w.coveredMs());
            SloSample b = w.base();
            SloSample l = w.latest();
            req += nonNegative(l.req() - b.req());
            err += nonNegative(l.err5xx() - b.err5xx());
            jobOk += nonNegative(l.jobOk() - b.jobOk());
            jobFailed += nonNegative(l.jobFailed() - b.jobFailed());

            Hist dChat = subtract(l.chat(), b.chat());
            Hist dApi = subtract(l.api(), b.api());
            Hist dTtft = subtract(l.ttft(), b.ttft());
            dropped |= (l.chat() != null && dChat == null) || (l.api() != null && dApi == null)
                || (l.ttft() != null && dTtft == null);
            chat = add(chat, dChat, mismatch);
            api = add(api, dApi, mismatch);
            ttft = add(ttft, dTtft, mismatch);

            if (l.llm() != null) {
                for (Map.Entry<String, double[]> e : l.llm().entrySet()) {
                    double[] now = e.getValue();
                    double[] before = b.llm() == null ? null : b.llm().get(e.getKey());
                    if (now == null || now.length < 2) {
                        continue;
                    }
                    double calls = nonNegative(now[0] - (before == null || before.length < 2 ? 0 : before[0]));
                    double errors = nonNegative(now[1] - (before == null || before.length < 2 ? 0 : before[1]));
                    double[] acc = llm.computeIfAbsent(e.getKey(), k -> new double[2]);
                    acc[0] += calls;
                    acc[1] += errors;
                }
            }
        }
        return new Delta(req, err, chat, api, ttft, llm, jobOk, jobFailed, instances,
            maxCovered, instances == 0 ? 0 : minCovered, dropped, mismatch[0]);
    }

    private static double nonNegative(double v) {
        return v < 0 ? 0 : v;
    }

    // ------------------------------------------------------------------ histogram algebra

    /**
     * {@code cur - base} bucket by bucket. A null {@code base} means "no earlier reading": the series did not
     * exist yet, so everything in {@code cur} happened inside the window. Returns null when {@code cur} is null
     * or the result would be negative anywhere (the counts are not cumulative, or the series was reset).
     */
    static Hist subtract(Hist cur, Hist base) {
        if (cur == null) {
            return null;
        }
        double baseCount = base == null ? 0 : base.count();
        double count = cur.count() - baseCount;
        if (count < -EPS) {
            return null;
        }
        count = nonNegative(count);
        if (!cur.hasBuckets()) {
            return new Hist(count, new double[0], new double[0]);
        }
        double[] cum = new double[cur.bounds().length];
        for (int i = 0; i < cum.length; i++) {
            double before;
            if (base == null || (!base.hasBuckets() && base.count() == 0)) {
                before = 0;
            } else if (base.hasBuckets()) {
                int j = indexOf(base.bounds(), cur.bounds()[i]);
                if (j < 0) {
                    return null;
                }
                before = base.cum()[j];
            } else {
                return null;
            }
            double d = cur.cum()[i] - before;
            if (d < -EPS) {
                return null;
            }
            cum[i] = nonNegative(d);
        }
        return new Hist(count, cur.bounds().clone(), cum);
    }

    /**
     * Sum of two histograms. When both carry buckets only the bounds they share are kept (cumulative counts
     * stay valid at any shared bound); {@code mismatch[0]} is set when that dropped anything.
     */
    static Hist add(Hist a, Hist b, boolean[] mismatch) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        double count = a.count() + b.count();
        if (!a.hasBuckets() && !b.hasBuckets()) {
            return new Hist(count, new double[0], new double[0]);
        }
        if (a.hasBuckets() != b.hasBuckets()) {
            mismatch[0] = true;
            return new Hist(count, new double[0], new double[0]);
        }
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i < a.bounds().length; i++) {
            int j = indexOf(b.bounds(), a.bounds()[i]);
            if (j >= 0) {
                kept.add(new double[] {a.bounds()[i], a.cum()[i] + b.cum()[j]});
            }
        }
        if (kept.size() != a.bounds().length || kept.size() != b.bounds().length) {
            mismatch[0] = true;
        }
        double[] bounds = new double[kept.size()];
        double[] cum = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            bounds[i] = kept.get(i)[0];
            cum[i] = kept.get(i)[1];
        }
        return new Hist(count, bounds, cum);
    }

    private static int indexOf(double[] bounds, double bound) {
        for (int i = 0; i < bounds.length; i++) {
            if (Math.abs(bounds[i] - bound) <= EPS * Math.max(1.0, Math.abs(bound))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The {@code q}-quantile (0 &lt; q &lt; 1) in seconds, by linear interpolation inside the bucket that holds
     * the rank (the same estimate Prometheus' {@code histogram_quantile} gives). Empty when there are no
     * observations or no buckets. When the rank lies above the last bound the last bound is returned: the
     * true value is at least that.
     */
    static OptionalDouble quantile(Hist h, double q) {
        return quantileEstimate(h, q).map(e -> OptionalDouble.of(e.seconds())).orElse(OptionalDouble.empty());
    }

    /** A quantile estimate; {@code aboveLastBound} means the value is only a floor (the rank lies above every bucket). */
    record Quantile(double seconds, boolean aboveLastBound) {
    }

    /** Like {@link #quantile} but says when the rank lies above every bucket, so the caller can avoid showing a floor as a figure. */
    static Optional<Quantile> quantileEstimate(Hist h, double q) {
        if (h == null || !h.hasBuckets() || h.count() <= 0 || q <= 0 || q >= 1) {
            return Optional.empty();
        }
        double rank = q * h.count();
        double[] bounds = h.bounds();
        double[] cum = h.cum();
        for (int i = 0; i < bounds.length; i++) {
            if (cum[i] >= rank) {
                double lowerBound = i == 0 ? 0 : bounds[i - 1];
                double lowerCount = i == 0 ? 0 : cum[i - 1];
                double inBucket = cum[i] - lowerCount;
                if (inBucket <= 0) {
                    return Optional.of(new Quantile(bounds[i], false));
                }
                return Optional.of(new Quantile(
                    lowerBound + (bounds[i] - lowerBound) * ((rank - lowerCount) / inBucket), false));
            }
        }
        return Optional.of(new Quantile(bounds[bounds.length - 1], true));
    }

    /**
     * The share of observations at or below the largest bucket bound that does not exceed {@code seconds}.
     * Using a bound at or under the target means the share of requests over the target is never understated.
     * Empty when there are no observations, no buckets, or the target is below the smallest bound.
     */
    static Optional<Share> shareAtOrBelow(Hist h, double seconds) {
        if (h == null || !h.hasBuckets() || h.count() <= 0) {
            return Optional.empty();
        }
        int best = -1;
        for (int i = 0; i < h.bounds().length; i++) {
            if (h.bounds()[i] <= seconds + EPS && (best < 0 || h.bounds()[i] > h.bounds()[best])) {
                best = i;
            }
        }
        if (best < 0) {
            return Optional.empty();
        }
        return Optional.of(new Share(Math.min(1.0, h.cum()[best] / h.count()), h.bounds()[best]));
    }

    // ------------------------------------------------------------------ error budget

    /** Non-bad share: {@code (total - bad) / total}; empty when nothing was observed. */
    static OptionalDouble availability(double total, double bad) {
        if (total <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.max(0.0, Math.min(1.0, (total - bad) / total)));
    }

    /**
     * Error-budget burn: the observed bad ratio divided by the budget the objective allows
     * ({@code 1 - objective}). 1.0 means errors arrive exactly as fast as the objective permits, 2.0 twice as fast.
     * Empty when nothing was observed or the objective leaves no budget (100% or more).
     */
    static OptionalDouble burnRate(double bad, double total, double objective) {
        if (total <= 0) {
            return OptionalDouble.empty();
        }
        double budget = 1.0 - objective;
        if (budget <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((bad / total) / budget);
    }

    /**
     * OK / WARN / BREACH from a burn rate, or INSUFFICIENT_DATA when there were fewer than {@code minRequests}
     * observations (one error in ten requests is not evidence) or no burn rate could be computed.
     */
    static String verdict(OptionalDouble burn, double observations, double minRequests) {
        if (burn.isEmpty() || observations < minRequests) {
            return "INSUFFICIENT_DATA";
        }
        if (burn.getAsDouble() >= BREACH_BURN) {
            return "BREACH";
        }
        if (burn.getAsDouble() >= WARN_BURN) {
            return "WARN";
        }
        return "OK";
    }
}
