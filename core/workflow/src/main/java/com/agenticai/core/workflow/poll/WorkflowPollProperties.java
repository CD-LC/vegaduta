package com.agenticai.core.workflow.poll;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.workflow.poll.*} - the HTTP polling trigger's limits. Registered
 * by {@code @Component} + {@code @ConfigurationProperties}, the same
 * convention {@code AgentJobProperties} documents (there is no
 * application-wide {@code @ConfigurationPropertiesScan}).
 */
@Component
@ConfigurationProperties(prefix = "app.workflow.poll")
public class WorkflowPollProperties {

    /** Master switch for the whole feature; off = the ticker still ticks but polls nothing. */
    private boolean enabled = true;

    /** How often the ticker wakes to look for due polls. */
    private Duration tick = Duration.ofSeconds(60);

    /** Smallest accepted intervalSeconds at configure time (below the tick it cannot fire anyway). */
    private int minIntervalSeconds = 60;

    /** Largest intervalSeconds accepted at configure time (a week). */
    private int maxIntervalSeconds = 604_800;

    /** New items fired per poll at most; the rest stay unseen and fire next poll. */
    private int maxItemsPerPoll = 25;

    /** Response bodies larger than this are a poll failure, not a truncated read. */
    private long maxResponseBytes = 1_048_576;

    /** Cap on the seen-id ring inside state_json. */
    private int seenIdsCap = 1_000;

    /** Failure backoff: next poll = interval * min(consecutiveFailures + 1, this). */
    private int failureBackoffCap = 8;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration responseTimeout = Duration.ofSeconds(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTick() {
        return tick;
    }

    public void setTick(Duration tick) {
        this.tick = tick;
    }

    public int getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(int minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public int getMaxIntervalSeconds() {
        return maxIntervalSeconds;
    }

    public void setMaxIntervalSeconds(int maxIntervalSeconds) {
        this.maxIntervalSeconds = maxIntervalSeconds;
    }

    public int getMaxItemsPerPoll() {
        return maxItemsPerPoll;
    }

    public void setMaxItemsPerPoll(int maxItemsPerPoll) {
        this.maxItemsPerPoll = maxItemsPerPoll;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getSeenIdsCap() {
        return seenIdsCap;
    }

    public void setSeenIdsCap(int seenIdsCap) {
        this.seenIdsCap = seenIdsCap;
    }

    public int getFailureBackoffCap() {
        return failureBackoffCap;
    }

    public void setFailureBackoffCap(int failureBackoffCap) {
        this.failureBackoffCap = failureBackoffCap;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
