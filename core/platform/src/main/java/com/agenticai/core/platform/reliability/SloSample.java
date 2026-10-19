package com.agenticai.core.platform.reliability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One point-in-time reading of a replica's CUMULATIVE reliability counters
 * (counts since that process started, exactly as Micrometer holds them). A
 * window figure is always the difference of two samples from the same replica,
 * never a value read off one sample.
 *
 * <p>Serialised as JSON into Redis when cross-replica aggregation is on, so it
 * must tolerate a rolling deploy: unknown fields are ignored (a newer replica
 * may add some) and every collection/series may be absent (an older replica
 * may not write it). {@code v} is the schema version of the writer.
 *
 * @param v schema version of the writer
 * @param t epoch milliseconds the reading was taken
 * @param req server requests (all statuses), actuator excluded
 * @param err5xx server requests that ended 5xx
 * @param chat request-duration histogram of chat-turn requests; null when absent
 * @param api request-duration histogram of every other request; null when absent
 * @param ttft time-to-first-token histogram; null when absent
 * @param llm provider name to {calls, errors} cumulative LLM call counters
 * @param jobOk agent jobs that finished SUCCEEDED
 * @param jobFailed agent jobs that finished FAILED
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SloSample(
    int v,
    long t,
    double req,
    double err5xx,
    Hist chat,
    Hist api,
    Hist ttft,
    Map<String, double[]> llm,
    double jobOk,
    double jobFailed
) {

    public static final int CURRENT_VERSION = 1;

    /**
     * A cumulative histogram: {@code count} observations in total, and for each
     * ascending upper bound (seconds) the number of observations at or below it.
     * {@code bounds} is empty when the timer publishes no buckets - then only
     * {@code count} is known and no percentile can be derived.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hist(double count, double[] bounds, double[] cum) {

        public boolean hasBuckets() {
            return bounds != null && cum != null && bounds.length > 0 && bounds.length == cum.length;
        }
    }
}
