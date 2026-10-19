package com.agenticai.core.security;

import java.time.Instant;

/**
 * A2-PR3 (narrowed scope, see PR prompt) - the FREE-tier edit-lock-specific
 * flavor of {@link TenantLimitExceededException}, thrown by {@code
 * AgentService#assertEditable}/{@code WorkflowService#assertEditable} when
 * {@link EditLockPolicy#isLocked} says no.
 *
 * <p><b>Why a subclass rather than a standalone type.</b> Same reasoning as
 * {@code PerUserBudgetExhaustedException} (see its javadoc): every existing
 * catch-site / test that treats an edit-lock rejection as a plain {@link
 * TenantLimitExceededException} keeps working unchanged, while {@code
 * GlobalExceptionHandler} gets a strictly more specific type to bind a
 * dedicated handler to. Spring's {@code @ExceptionHandler} resolution picks
 * the most specific declared type, so unrelated {@link
 * TenantLimitExceededException} causes (seat ceilings, agent-count ceilings,
 * etc.) keep their existing flat HTTP 403 "upgrade your plan" mapping,
 * while THIS type maps to HTTP 423 (Locked) with a structured {@code
 * lockedReason}/{@code editableUntil} body a frontend can bind to without
 * parsing the English message.
 *
 * <p>{@code editableUntil} is {@code null} when the object was never
 * editable at all under this plan+age combination in a way that has a
 * meaningful "unlock" instant to report (defensive - in practice {@link
 * EditLockPolicy#isLocked} only returns true when {@code createdAt} is
 * non-null, so callers should always be able to compute one).
 */
public class EditLockedException extends TenantLimitExceededException {

    private final String lockedReason;
    private final Instant editableUntil;

    /**
     * @param message        human-readable message, unchanged from the existing
     *                       {@code TenantLimitExceededException} copy callers already throw.
     * @param lockedReason   short, client-actionable reason string (distinct from
     *                       {@code message}, which stays the full sentence for logs/legacy
     *                       consumers) - e.g. {@code "free_tier_grace_window_expired"}.
     * @param editableUntil  the instant the grace window closed ({@code createdAt +
     *                       EditLockPolicy.FREE_TIER_GRACE_WINDOW}), ISO-8601 on the wire.
     */
    public EditLockedException(String message, String lockedReason, Instant editableUntil) {
        super(message);
        this.lockedReason = lockedReason;
        this.editableUntil = editableUntil;
    }

    public String getLockedReason() {
        return lockedReason;
    }

    /** The instant this object's FREE-tier grace window closed; see {@link EditLockPolicy}. */
    public Instant getEditableUntil() {
        return editableUntil;
    }
}
