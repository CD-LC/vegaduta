package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.reliability.ReliabilityApi.AvailabilitySlo;
import com.agenticai.core.platform.reliability.ReliabilityApi.JobSlo;
import com.agenticai.core.platform.reliability.ReliabilityApi.LatencySlo;
import com.agenticai.core.platform.reliability.ReliabilityApi.Objectives;
import com.agenticai.core.platform.reliability.ReliabilityApi.ProviderErrorRate;
import com.agenticai.core.platform.reliability.ReliabilityApi.SloResponse;
import com.agenticai.core.platform.reliability.SloMath.Delta;
import com.agenticai.core.platform.reliability.SloMath.Share;
import com.agenticai.core.platform.reliability.SloSample.Hist;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/platform/slo?windowHours=24}: availability, latency, LLM
 * provider error rate and agent-job success over a window, plus the error
 * budget those figures burn against the configured objectives
 * ({@code app.slo.*}).
 *
 * <p>Every number is computed from counters the platform already keeps (see
 * {@link SloSampler}) by differencing two readings; the arithmetic lives in
 * {@link SloMath}. <b>Nothing is invented</b>: a series with no data, or with
 * no histogram buckets, is left out and the {@code notes} say why. The
 * {@code scope} field says whether the figures combine every replica or only
 * the one that answered.
 *
 * <p>Latency objectives apply to two classes: time to first token of chat
 * turns, and every other API request. The duration of a chat turn is reported
 * too but has no target - it is the length of the whole streamed answer, which
 * depends on the model, not on the platform's health.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class SloService {

    static final double LATENCY_QUANTILE = 0.95;
    /** The slow-share bucket bound must be at least this fraction of the target for a burn verdict to be derived from it. */
    static final double SHARE_BOUND_TOLERANCE = 0.8;

    private final SloSampler sampler;
    private final boolean enabled;
    private final double availabilityTarget;
    private final double apiP95TargetMs;
    private final double chatTtftP95TargetMs;
    private final double minRequests;

    /** Package-private and non-final so tests can pin time. */
    Clock clock = Clock.systemUTC();

    public SloService(
        SloSampler sampler,
        @Value("${app.platform.reliability.enabled:true}") boolean enabled,
        @Value("${app.slo.availability-target:0.995}") double availabilityTarget,
        @Value("${app.slo.api-latency-p95-ms:1000}") double apiP95TargetMs,
        @Value("${app.slo.chat-ttft-p95-ms:4000}") double chatTtftP95TargetMs,
        @Value("${app.slo.min-requests:100}") double minRequests
    ) {
        this.sampler = sampler;
        this.enabled = enabled;
        this.availabilityTarget = availabilityTarget;
        this.apiP95TargetMs = apiP95TargetMs;
        this.chatTtftP95TargetMs = chatTtftP95TargetMs;
        this.minRequests = minRequests;
    }

    /**
     * @throws ResponseStatusException 404 when {@code app.platform.reliability.enabled=false}
     * @throws IllegalArgumentException (answered 400) when the window is outside 1..retention hours
     */
    public SloResponse slo(int windowHours) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reliability endpoints are disabled.");
        }
        long maxHours = Math.max(1, sampler.retentionHours());
        if (windowHours < 1 || windowHours > maxHours) {
            throw new IllegalArgumentException("windowHours must be 1.." + maxHours);
        }
        Instant now = clock.instant();
        SloSampler.WindowRead read = sampler.read(Duration.ofHours(windowHours));
        return build(now, windowHours, read, sampler.intervalSeconds(), sampler.isAggregatingViaRedis());
    }

    /** Pure composition of the response from a window read; separated so it is testable without a sampler. */
    SloResponse build(Instant now, int windowHours, SloSampler.WindowRead read, long intervalSeconds, boolean viaRedis) {
        List<String> notes = new ArrayList<>(read.notes());
        Objectives objectives = new Objectives(availabilityTarget, apiP95TargetMs, chatTtftP95TargetMs, LATENCY_QUANTILE);
        Delta delta = SloMath.combine(read.windows());
        String scope = read.aggregated() ? "platform" : "replica";

        if (delta.instances() == 0) {
            notes.add("No samples cover this window yet. Counters are sampled every " + intervalSeconds
                + " s from the moment the replica starts, so try again in a minute.");
            return new SloResponse(now, windowHours, 0, scope, 0, objectives, null, List.of(), List.of(), null, notes);
        }

        long coveredSeconds = delta.maxCoveredMs() / 1000;
        long windowSeconds = windowHours * 3600L;
        if (coveredSeconds + 2 * intervalSeconds + 1 < windowSeconds) {
            notes.add("The samples span " + humanDuration(coveredSeconds) + " of the requested " + windowHours
                + " h: counters live in memory and start again at zero whenever a replica restarts, so the window is "
                + "shorter than asked. Every figure below covers only that span.");
        }
        if (delta.instances() > 1 && delta.maxCoveredMs() - delta.minCoveredMs() > 2 * intervalSeconds * 1000L + 1000L) {
            notes.add("Some replicas started inside the window; their share covers only the time since they started.");
        }
        if (!read.aggregated()) {
            notes.add("These figures come from the in-memory counters of the one replica that answered. Behind a load "
                + "balancer, each refresh can land on a different replica"
                + (viaRedis ? "" : "; set PLATFORM_RELIABILITY_SLO_REDIS=true to combine all replicas")
                + ".");
        } else {
            notes.add("Combined across " + delta.instances() + " replica(s) that publish samples. Replicas running an "
                + "image from before this feature, and a replica's last few minutes before it died, are not included.");
        }
        if (delta.histogramDropped()) {
            notes.add("A latency histogram was left out for at least one replica because its bucket counts were not "
                + "cumulative (the metrics registry in use does not publish cumulative buckets).");
        }
        if (delta.bucketMismatch()) {
            notes.add("Replicas publish different histogram bucket bounds (mixed configuration); percentiles use only "
                + "the bounds they share.");
        }

        AvailabilitySlo availability = availability(delta, notes);
        List<LatencySlo> latency = new ArrayList<>();
        addLatency(latency, notes, "chat_turn", "Chat turn (whole streamed answer)", delta.chat(), null);
        addLatency(latency, notes, "chat_ttft", "Chat time to first token", delta.ttft(), chatTtftP95TargetMs);
        addLatency(latency, notes, "other_api", "Other API requests", delta.api(), apiP95TargetMs);

        List<ProviderErrorRate> providers = providers(delta, notes);
        JobSlo jobs = jobs(delta, notes);

        notes.add("Availability is the share of server requests that did not end in a 5xx (actuator requests excluded). "
            + "A stream that starts with 200 and fails later still counts as a success.");
        notes.add("Latency percentiles are estimates interpolated from histogram buckets, not exact values.");
        return new SloResponse(now, windowHours, coveredSeconds, scope, delta.instances(), objectives,
            availability, latency, providers, jobs, notes);
    }

    private AvailabilitySlo availability(Delta delta, List<String> notes) {
        if (delta.req() <= 0) {
            notes.add("No server requests were recorded in this window, so availability is not shown.");
            return null;
        }
        OptionalDouble availability = SloMath.availability(delta.req(), delta.err5xx());
        OptionalDouble burn = SloMath.burnRate(delta.err5xx(), delta.req(), availabilityTarget);
        if (burn.isEmpty()) {
            notes.add("The availability objective leaves no error budget (it is 100% or more), so no burn rate is shown.");
        }
        return new AvailabilitySlo(
            delta.req(), delta.err5xx(),
            boxed(availability),
            delta.err5xx() / delta.req(),
            boxed(burn),
            burn.isPresent() ? Double.valueOf(1.0 - burn.getAsDouble()) : null,
            SloMath.verdict(burn, delta.req(), minRequests));
    }

    private void addLatency(
        List<LatencySlo> out, List<String> notes, String key, String label, Hist hist, Double targetMs
    ) {
        if (hist == null || hist.count() <= 0) {
            notes.add("No observations for \"" + label + "\" were recorded in this window.");
            return;
        }
        if (!hist.hasBuckets()) {
            notes.add("Latency for \"" + label + "\" is not shown: its timer publishes no histogram buckets. Activate "
                + "the latency-histograms Spring profile to enable percentiles.");
            return;
        }
        Optional<SloMath.Quantile> q50 = SloMath.quantileEstimate(hist, 0.50);
        Optional<SloMath.Quantile> q95 = SloMath.quantileEstimate(hist, 0.95);
        Optional<SloMath.Quantile> q99 = SloMath.quantileEstimate(hist, 0.99);
        Double p50 = quantileMs(q50, label, "p50", notes);
        Double p95 = quantileMs(q95, label, "p95", notes);
        Double p99 = quantileMs(q99, label, "p99", notes);
        Boolean meets = null;
        if (targetMs != null && q95.isPresent()) {
            SloMath.Quantile q = q95.get();
            // A p95 above the last bucket is only a floor: it still proves a miss when the floor is at or over the target.
            meets = q.aboveLastBound()
                ? (q.seconds() * 1000.0 >= targetMs ? Boolean.FALSE : null)
                : Boolean.valueOf(q.seconds() * 1000.0 <= targetMs);
        }
        Double slowShare = null;
        Double slowBoundMs = null;
        Double burnValue = null;
        String verdict = null;
        if (targetMs != null) {
            Optional<Share> share = SloMath.shareAtOrBelow(hist, targetMs / 1000.0);
            if (share.isPresent()) {
                slowShare = 1.0 - share.get().shareAtOrBelow();
                slowBoundMs = share.get().boundSeconds() * 1000.0;
                if (slowBoundMs >= targetMs * SHARE_BOUND_TOLERANCE) {
                    OptionalDouble burn = SloMath.burnRate(slowShare * hist.count(), hist.count(), LATENCY_QUANTILE);
                    burnValue = boxed(burn);
                    verdict = SloMath.verdict(burn, hist.count(), minRequests);
                } else {
                    // The nearest bucket bound at or under the target is far below it, so the share over the bound
                    // would overstate the share over the target and could contradict "meets target".
                    notes.add("No histogram bucket sits close to the " + Math.round(targetMs) + " ms target for \""
                        + label + "\" (nearest bound at or below it: " + Math.round(slowBoundMs) + " ms), so no "
                        + "burn rate or verdict is shown for it; the percentiles above are still estimated.");
                }
            } else {
                notes.add("The latency target for \"" + label + "\" is below the smallest histogram bucket, so the share "
                    + "of slow requests is not shown.");
            }
        }
        out.add(new LatencySlo(key, label, hist.count(), p50, p95, p99, targetMs, meets,
            slowShare, slowBoundMs, burnValue, verdict));
        if ("other_api".equals(key)) {
            notes.add("\"Other API requests\" leaves out known long-lived streams and long-running calls (run and trace "
                + "streams, ag-ui, A2A, voice, research, quick-task); they still count for availability. Replicas "
                + "running an image from before this change still include them, and an endpoint not on that list "
                + "that streams or runs long will still raise this percentile.");
        }
        if ("chat_turn".equals(key)) {
            notes.add("Chat-turn latency is the length of the whole streamed answer, so it has no target: it grows "
                + "with the model's answer length, not with platform health. Time to first token is the chat objective.");
        }
    }

    private List<ProviderErrorRate> providers(Delta delta, List<String> notes) {
        List<ProviderErrorRate> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : delta.llm().entrySet()) {
            double calls = e.getValue()[0];
            double errors = e.getValue()[1];
            if (calls > 0) {
                out.add(new ProviderErrorRate(e.getKey(), calls, errors, Math.min(1.0, errors / calls)));
            }
        }
        out.sort(Comparator.comparingDouble(ProviderErrorRate::calls).reversed());
        if (out.isEmpty()) {
            notes.add("No LLM calls were recorded in this window, so provider error rates are not shown.");
        } else {
            notes.add("LLM error rate counts failed provider calls; a call the user cancelled is not an error.");
        }
        return out;
    }

    private JobSlo jobs(Delta delta, List<String> notes) {
        double finished = delta.jobOk() + delta.jobFailed();
        if (finished <= 0) {
            notes.add("No agent jobs finished in this window on the replicas reporting, so job success is not shown "
                + "(job counters exist only where jobs run).");
            return null;
        }
        notes.add("Job success counts jobs that finished on a worker. Jobs the recovery sweeps fail (orphaned or "
            + "expired) are not counted here.");
        return new JobSlo(finished, delta.jobOk(), delta.jobFailed(), delta.jobOk() / finished);
    }

    private static Double boxed(OptionalDouble v) {
        return v.isPresent() ? Double.valueOf(v.getAsDouble()) : null;
    }

    /** Milliseconds for a quantile, or null (with a note) when it lies above the largest bucket and is only a floor. */
    private static Double quantileMs(Optional<SloMath.Quantile> q, String label, String name, List<String> notes) {
        if (q.isEmpty()) {
            return null;
        }
        if (q.get().aboveLastBound()) {
            notes.add("The " + name + " of \"" + label + "\" is above "
                + java.math.BigDecimal.valueOf(q.get().seconds()).stripTrailingZeros().toPlainString()
                + " s, the largest histogram bucket, so no figure is shown for it.");
            return null;
        }
        return Double.valueOf(q.get().seconds() * 1000.0);
    }

    static String humanDuration(long seconds) {
        if (seconds < 120) {
            return seconds + " s";
        }
        if (seconds < 7200) {
            return (seconds / 60) + " min";
        }
        return String.format(java.util.Locale.ROOT, "%.1f h", seconds / 3600.0);
    }
}
