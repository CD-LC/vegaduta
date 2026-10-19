package com.agenticai.core.templates;

import java.util.Set;

/**
 * Lifecycle state of a {@link CommunityTemplate}. The legal transitions are
 * the single most security-relevant behaviour after the snapshot allow-list:
 * public visibility is gated on {@link #APPROVED}, and only an admin can move a
 * template into it. See {@link #canTransitionTo} for the authoritative graph.
 *
 * <pre>
 *   DRAFT --submit--&gt; PENDING --approve--&gt; APPROVED (public)
 *                       |                     |
 *                       +--reject--&gt; REJECTED +--withdraw--&gt; WITHDRAWN
 *   REJECTED --submit--&gt; PENDING            (editable + resubmittable)
 * </pre>
 */
public enum TemplateStatus {

    /** Owner is iterating; visible only to the owner. */
    DRAFT,

    /** Submitted; sitting in the moderation queue. Owner may still withdraw. */
    PENDING,

    /** Publicly listed and importable. Carries the approving admin + timestamp. */
    APPROVED,

    /** Rejected with a reason the submitter can see; editable and resubmittable. */
    REJECTED,

    /** Was public (or pending), pulled by owner or admin (e.g. after a report). */
    WITHDRAWN;

    /**
     * The authoritative legal-transition graph. Anything not listed here is an
     * {@link IllegalTemplateTransitionException} (HTTP 409). Kept as data on the
     * enum itself - not scattered across service methods - so "what can move
     * where" is auditable in exactly one place.
     */
    public boolean canTransitionTo(TemplateStatus target) {
        return switch (this) {
            // submit
            case DRAFT -> target == PENDING;
            // approve / reject / (owner) withdraw
            case PENDING -> Set.of(APPROVED, REJECTED, WITHDRAWN).contains(target);
            // (owner or admin) unpublish/report
            case APPROVED -> target == WITHDRAWN;
            // resubmit after fixing
            case REJECTED -> target == PENDING;
            // terminal-ish: a withdrawn template can be re-submitted by its owner
            case WITHDRAWN -> target == PENDING;
        };
    }
}
