package com.agenticai.core.mcp;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import java.time.Duration;
import java.time.Instant;

/**
 * The governance half of {@link McpServerRegistration}: the platform-staff review gate for
 * self-service STDIO registrations (MCP-STDIO-SANDBOXING), the bounded auto-retry backoff state
 * after a failed connect, and tool-definition pinning (V491).
 *
 * <p>A {@link MappedSuperclass}: every column still maps onto the same {@code
 * mcp_server_registration} row and keeps its attribute name on the entity, so queries and callers
 * are unchanged. {@link McpServerRegistration} is the only subclass; it owns {@code updatedAt}
 * and stamps it through {@link #touchUpdatedAt()}.
 */
@MappedSuperclass
@Access(AccessType.FIELD)
public abstract class McpRegistrationGovernance {

    // MCP-STDIO-SANDBOXING (2026-08-05) - the staff-review gate for
    // self-service STDIO registrations. See McpReviewStatus's own javadoc for
    // the full "what/why/when" - this entity only ever stores what {@link
    // McpRegistryService#register} decided, never computes provenance
    // itself (same "entity stays a dumb persistence shape" posture the rest
    // of this class already documents).
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private McpReviewStatus reviewStatus = McpReviewStatus.NOT_REQUIRED;

    @Column(name = "review_rejection_reason", columnDefinition = "TEXT")
    private String reviewRejectionReason;

    /**
     * Subject (Keycloak {@code sub}) who first created this registration - captured once, at
     * creation, for the review queue's "requester" column. Null for rows written before this column
     * existed, or with no ambient TenantContext.
     */
    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    // Bounded auto-retry with backoff (2026-08-05) - see markError's javadoc
    // for the schedule this drives. retryAttemptCount counts consecutive
    // failures since the last successful connect (markConnected resets all
    // three fields to zero/null/false); nextRetryAt is when the retry job
    // (DynamicMcpConnectionManager#retryErroredConnections) is next allowed
    // to try this registration again; retryExhausted stops that job from
    // ever picking this row up again once MAX_AUTO_RETRY_ATTEMPTS is reached
    // - a registration does not retry itself forever, it eventually asks for
    // a human ("Try again" button) instead.
    @Column(name = "retry_attempt_count", nullable = false)
    private int retryAttemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "retry_exhausted", nullable = false)
    private boolean retryExhausted;

    // --- Tool-definition pinning (V491; see McpToolPin / McpToolPinService) -----------------

    @Column(name = "tools_pinned_hash", length = 64)
    private String toolsPinnedHash;

    @Column(name = "tools_pinned_manifest", columnDefinition = "TEXT")
    private String toolsPinnedManifest;

    @Column(name = "tools_pinned_at")
    private Instant toolsPinnedAt;

    @Column(name = "tools_pinned_by")
    private String toolsPinnedBy;

    @Column(name = "tools_observed_hash", length = 64)
    private String toolsObservedHash;

    @Column(name = "tools_observed_manifest", columnDefinition = "TEXT")
    private String toolsObservedManifest;

    @Column(name = "tools_changed_at")
    private Instant toolsChangedAt;

    /** Stamps the owning registration's {@code updatedAt} - see {@link McpServerRegistration}. */
    protected abstract void touchUpdatedAt();

    /**
     * Every backoff step beyond this many consecutive failures gives up automatic retry - see
     * {@link McpServerRegistration#markError(String, McpConnectionPhase, String, String, String, String)}.
     */
    static final int MAX_AUTO_RETRY_ATTEMPTS = 8;

    /**
     * 5m, 15m, 1h, then a 6h steady state - see class-level retry field comments. Package-private +
     * static so it's directly unit-testable without a repository/service.
     */
    static Duration backoffFor(int attemptNumber) {
        if (attemptNumber <= 0) {
            return Duration.ofMinutes(5);
        }
        if (attemptNumber == 1) {
            return Duration.ofMinutes(15);
        }
        if (attemptNumber == 2) {
            return Duration.ofHours(1);
        }
        return Duration.ofHours(6);
    }

    /** Shared by every {@code markError} overload - see that method's javadoc for the schedule. */
    protected void scheduleNextRetryOrGiveUp() {
        Duration delay = backoffFor(this.retryAttemptCount);
        this.retryAttemptCount = this.retryAttemptCount + 1;
        if (this.retryAttemptCount >= MAX_AUTO_RETRY_ATTEMPTS) {
            this.retryExhausted = true;
            this.nextRetryAt = null;
        } else {
            this.nextRetryAt = Instant.now().plus(delay);
        }
    }

    /**
     * Success zeroes the auto-retry backoff state completely - a registration that just worked owes
     * nothing to whatever streak of failures preceded it (see the retry field comments above).
     */
    protected void resetRetryState() {
        this.retryAttemptCount = 0;
        this.nextRetryAt = null;
        this.retryExhausted = false;
    }

    // --- Review gate (MCP-STDIO-SANDBOXING) --------------------------------

    /**
     * This registration is exempt from staff review - remote, or catalog-sourced STDIO. See {@link
     * McpReviewStatus#NOT_REQUIRED}.
     */
    public void markReviewNotRequired() {
        this.reviewStatus = McpReviewStatus.NOT_REQUIRED;
        this.reviewRejectionReason = null;
        touchUpdatedAt();
    }

    /**
     * A self-service custom/registry-browsed STDIO registration awaiting platform-staff sign-off -
     * connect/test are refused until this changes.
     */
    public void markReviewPending() {
        this.reviewStatus = McpReviewStatus.PENDING;
        this.reviewRejectionReason = null;
        touchUpdatedAt();
    }

    /**
     * Platform staff approved this registration's current command/args/env - future connect/test
     * attempts proceed normally.
     */
    public void approveReview() {
        this.reviewStatus = McpReviewStatus.APPROVED;
        this.reviewRejectionReason = null;
        touchUpdatedAt();
    }

    /**
     * Platform staff rejected this registration - {@code reason} is shown back to the tenant via
     * connectionHint.
     */
    public void rejectReview(String reason) {
        this.reviewStatus = McpReviewStatus.REJECTED;
        this.reviewRejectionReason = reason;
        touchUpdatedAt();
    }

    /**
     * True when a connect/test attempt must be refused outright - the gate {@code
     * DynamicMcpConnectionManager} enforces before any other check.
     */
    public boolean isReviewBlocked() {
        return reviewStatus == McpReviewStatus.PENDING || reviewStatus == McpReviewStatus.REJECTED;
    }

    /**
     * Captured once, at creation - see field javadoc. Not part of any {@code markX}
     * state-transition method since it is set exactly once and never touched again.
     */
    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public McpReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getReviewRejectionReason() {
        return reviewRejectionReason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public int getRetryAttemptCount() {
        return retryAttemptCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public boolean isRetryExhausted() {
        return retryExhausted;
    }

    public String getToolsPinnedHash() {
        return toolsPinnedHash;
    }

    public String getToolsPinnedManifest() {
        return toolsPinnedManifest;
    }

    public Instant getToolsPinnedAt() {
        return toolsPinnedAt;
    }

    public String getToolsPinnedBy() {
        return toolsPinnedBy;
    }

    public String getToolsObservedHash() {
        return toolsObservedHash;
    }

    public String getToolsObservedManifest() {
        return toolsObservedManifest;
    }

    public Instant getToolsChangedAt() {
        return toolsChangedAt;
    }

    /**
     * True when the tool list last observed differs from the approved baseline. Never true for a
     * registration that has not been pinned yet.
     */
    public boolean isToolsChanged() {
        return toolsPinnedHash != null
                && toolsObservedHash != null
                && !toolsPinnedHash.equals(toolsObservedHash);
    }

    /**
     * Records the tool list just listed. The first observation pins it (trust on first use);
     * later ones only update the observed side. Returns true when this call newly flipped the
     * registration into the changed state.
     */
    public boolean observeTools(String hash, String manifestJson, String actor) {
        boolean wasChanged = isToolsChanged();
        this.toolsObservedHash = hash;
        this.toolsObservedManifest = manifestJson;
        if (toolsPinnedHash == null) {
            pinObservedTools(actor);
            return false;
        }
        boolean changed = isToolsChanged();
        if (changed && !wasChanged) {
            this.toolsChangedAt = Instant.now();
        } else if (!changed) {
            this.toolsChangedAt = null;
        }
        return changed && !wasChanged;
    }

    /** Accepts the last observed tool list as the new approved baseline. */
    public void pinObservedTools(String actor) {
        this.toolsPinnedHash = toolsObservedHash;
        this.toolsPinnedManifest = toolsObservedManifest;
        this.toolsPinnedAt = Instant.now();
        this.toolsPinnedBy = actor;
        this.toolsChangedAt = null;
    }
}
