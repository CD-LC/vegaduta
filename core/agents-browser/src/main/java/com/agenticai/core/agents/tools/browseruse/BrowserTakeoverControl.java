package com.agenticai.core.agents.tools.browseruse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The takeover state of ONE browser session: who is driving it, since when, and the bookkeeping the
 * idle / max duration / token rules need. In memory only (the session itself is in memory and dies
 * with the JVM, so a persisted copy could only ever describe a browser that no longer exists).
 *
 * <p>Thread-safe by {@code synchronized}: the agent's tool thread, the user's HTTP threads, the
 * stream pump and the reaper all touch it. Every transition goes through {@link #moveTo} and the
 * named methods below, which refuse an edge {@link BrowserTakeoverState#canMoveTo} does not allow.
 * Times are passed in so the rules are unit-testable without sleeping.
 *
 * <p>What it deliberately never holds: the agent's free-text reason beyond a capped,
 * control-character-stripped display string, and anything the user typed other than {@link
 * #typedSecrets} - candidate values kept only so they can be scrubbed from the page text the agent
 * reads after hand-back; they are never exposed by any getter that feeds a result, a log line or an
 * audit entry.
 */
final class BrowserTakeoverControl {

    /** Who asked for the page. */
    enum Origin {
        AGENT,
        USER
    }

    private static final int MAX_TYPED_CANDIDATES = 24;
    private static final int MAX_BUFFER_CHARS = 512;

    private final String sessionKey;
    private final String tenantId;
    private final String ownerUserId;
    private final UUID agentId;
    private final UUID takeoverId = UUID.randomUUID();
    private final Instant createdAt;

    private BrowserTakeoverState state = BrowserTakeoverState.AGENT_CONTROL;
    private int epoch;
    private Origin origin;
    private String kind;
    private String reason;
    private String host;
    private Instant pausedAt;
    private Instant controlStartedAt;
    private Instant lastInputAt;
    private String resumeNote;
    private String endedBy;
    private int inputEvents;

    private final StringBuilder typedBuffer = new StringBuilder();
    private final Set<String> typedSecrets = new LinkedHashSet<>();
    private final RateLimiter limiter;

    BrowserTakeoverControl(
            String sessionKey,
            String tenantId,
            String ownerUserId,
            UUID agentId,
            Instant now,
            int perSecond,
            int burst) {
        this.sessionKey = sessionKey;
        this.tenantId = tenantId;
        this.ownerUserId = ownerUserId;
        this.agentId = agentId;
        this.createdAt = now;
        this.limiter = new RateLimiter(perSecond, burst, now);
    }

    String sessionKey() {
        return sessionKey;
    }

    String tenantId() {
        return tenantId;
    }

    /**
     * The user the session belongs to (the subject that created it), or null when no interactive
     * user did.
     */
    String ownerUserId() {
        return ownerUserId;
    }

    UUID agentId() {
        return agentId;
    }

    UUID takeoverId() {
        return takeoverId;
    }

    Instant createdAt() {
        return createdAt;
    }

    synchronized BrowserTakeoverState state() {
        return state;
    }

    /**
     * Bumped every time the person takes the page, so a token minted for an earlier episode never
     * works again.
     */
    synchronized int epoch() {
        return epoch;
    }

    synchronized Origin origin() {
        return origin;
    }

    synchronized String kind() {
        return kind;
    }

    synchronized String reason() {
        return reason;
    }

    /** The site's host at the moment the page was handed over (never a path or query). */
    synchronized String host() {
        return host;
    }

    synchronized Instant pausedAt() {
        return pausedAt;
    }

    synchronized Instant controlStartedAt() {
        return controlStartedAt;
    }

    synchronized Instant lastInputAt() {
        return lastInputAt;
    }

    synchronized String endedBy() {
        return endedBy;
    }

    synchronized int inputEvents() {
        return inputEvents;
    }

    /**
     * True when {@code tenant} and {@code user} are exactly this session's owner. A null owner
     * matches nobody.
     */
    boolean isOwnedBy(String tenant, String user) {
        return ownerUserId != null
                && user != null
                && tenant != null
                && ownerUserId.equals(user)
                && tenantId.equals(tenant);
    }

    // ---- transitions
    // ---------------------------------------------------------------------------------------

    /** AGENT_CONTROL -> PAUSED_FOR_USER. */
    synchronized void pause(Origin by, String kind, String reason, String host, Instant now) {
        moveTo(BrowserTakeoverState.PAUSED_FOR_USER);
        this.origin = by;
        this.kind = kind;
        this.reason = reason;
        this.host = host;
        this.pausedAt = now;
        this.controlStartedAt = null;
        this.lastInputAt = null;
        this.resumeNote = null;
        this.endedBy = null;
        this.inputEvents = 0;
        clearTyped();
    }

    /**
     * PAUSED_FOR_USER -> USER_CONTROL; starts the idle and max-duration clocks and a new token
     * epoch.
     */
    synchronized void beginUserControl(Instant now) {
        moveTo(BrowserTakeoverState.USER_CONTROL);
        this.epoch++;
        this.controlStartedAt = now;
        this.lastInputAt = now;
        limiter.reset(now);
    }

    /**
     * USER_CONTROL | PAUSED_FOR_USER -> RESUMING. {@code by} is a short label ("user", "idle") kept
     * for the audit entry.
     */
    synchronized void beginResume(String by) {
        moveTo(BrowserTakeoverState.RESUMING);
        this.endedBy = by;
    }

    /** RESUMING -> AGENT_CONTROL, storing the (already redacted) note the agent may fetch once. */
    synchronized void finishResume(String note) {
        moveTo(BrowserTakeoverState.AGENT_CONTROL);
        this.resumeNote = note;
        clearTyped();
    }

    /** Any non-terminal state -> EXPIRED. */
    synchronized void expire(String by) {
        moveTo(BrowserTakeoverState.EXPIRED);
        this.endedBy = by;
        clearTyped();
    }

    /** Any non-terminal state -> CANCELLED. */
    synchronized void cancel(String by) {
        moveTo(BrowserTakeoverState.CANCELLED);
        this.endedBy = by;
        clearTyped();
    }

    private void moveTo(BrowserTakeoverState next) {
        if (!state.canMoveTo(next)) {
            throw new IllegalStateException(
                    "illegal browser takeover transition " + state + " -> " + next);
        }
        this.state = next;
    }

    /** The note stored by {@link #finishResume}, handed out exactly once. */
    synchronized String consumeResumeNote() {
        String note = resumeNote;
        resumeNote = null;
        return note;
    }

    // ---- input accounting
    // ----------------------------------------------------------------------------------

    /**
     * Records that the person did something (resets the idle clock) and counts it. Returns false
     * when over the rate limit.
     */
    synchronized boolean admitInput(Instant now) {
        if (!limiter.tryAcquire(now)) {
            return false;
        }
        this.lastInputAt = now;
        this.inputEvents++;
        return true;
    }

    /**
     * Appends typed characters to the pending run (capped). The run is only ever used to build
     * scrub candidates.
     */
    synchronized void noteTyped(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (typedBuffer.length() + text.length() <= MAX_BUFFER_CHARS) {
            typedBuffer.append(text);
        }
    }

    synchronized void noteBackspace() {
        if (typedBuffer.length() > 0) {
            typedBuffer.setLength(typedBuffer.length() - 1);
        }
    }

    /**
     * A click / Enter / Tab ends the run: it becomes a scrub candidate when long enough to scrub
     * without mangling page text.
     */
    synchronized void endTypedRun() {
        if (typedBuffer.length() >= BrowserSecretPlaceholders.MIN_TYPEABLE_LENGTH
                && typedSecrets.size() < MAX_TYPED_CANDIDATES) {
            typedSecrets.add(typedBuffer.toString());
        }
        typedBuffer.setLength(0);
    }

    /**
     * Ends the current run and returns every candidate gathered since the episode began (a copy).
     */
    synchronized List<String> drainTypedCandidates() {
        endTypedRun();
        List<String> copy = new ArrayList<>(typedSecrets);
        typedSecrets.clear();
        return copy;
    }

    private void clearTyped() {
        typedBuffer.setLength(0);
        typedSecrets.clear();
    }

    // ---- clocks
    // --------------------------------------------------------------------------------------------

    /** Seconds until the idle timeout fires (0 when due or not in USER_CONTROL). */
    synchronized long idleRemainingSeconds(Instant now, long idleTimeoutSeconds) {
        if (state != BrowserTakeoverState.USER_CONTROL || lastInputAt == null) {
            return 0;
        }
        return Math.max(0, idleTimeoutSeconds - Duration.between(lastInputAt, now).getSeconds());
    }

    /** Seconds until the hard cap fires (0 when due or not in USER_CONTROL). */
    synchronized long maxRemainingSeconds(Instant now, long maxDurationSeconds) {
        if (state != BrowserTakeoverState.USER_CONTROL || controlStartedAt == null) {
            return 0;
        }
        return Math.max(
                0, maxDurationSeconds - Duration.between(controlStartedAt, now).getSeconds());
    }

    /**
     * Seconds the agent's request may still wait for the person to take over (PAUSED_FOR_USER
     * only).
     */
    synchronized long pausedRemainingSeconds(Instant now, long pausedWaitSeconds) {
        if (state != BrowserTakeoverState.PAUSED_FOR_USER || pausedAt == null) {
            return 0;
        }
        return Math.max(0, pausedWaitSeconds - Duration.between(pausedAt, now).getSeconds());
    }

    /**
     * Token bucket: {@code perSecond} sustained, {@code burst} peak. Not thread-safe by itself
     * (callers hold the control's lock).
     */
    static final class RateLimiter {
        private final double perSecond;
        private final double burst;
        private double tokens;
        private Instant last;

        RateLimiter(int perSecond, int burst, Instant now) {
            this.perSecond = perSecond;
            this.burst = burst;
            this.tokens = burst;
            this.last = now;
        }

        void reset(Instant now) {
            this.tokens = burst;
            this.last = now;
        }

        boolean tryAcquire(Instant now) {
            double elapsed = Math.max(0, Duration.between(last, now).toNanos() / 1_000_000_000.0);
            tokens = Math.min(burst, tokens + elapsed * perSecond);
            last = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
