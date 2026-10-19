package com.agenticai.core.approval.dto;

/**
 * Body for POST /api/approvals/{id}/approve and .../reject.
 *
 * <p><b>{@code decidedBy} is ignored and must stay ignored.</b> It was once
 * read from this body and persisted verbatim as the decision's audit
 * attribution, which let any caller approve an action and record someone
 * else's name against it - a forged audit trail on the platform's primary
 * human-in-the-loop control. {@code ApprovalController} now derives the
 * decider from the authenticated principal
 * ({@code ApprovalController#callerSubject}) and never consults this field.
 *
 * <p>The field is retained purely for wire compatibility: existing clients
 * (and the {@code web} console) still send it, and removing it from the
 * record would make Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} default
 * turn those requests into 400s. It is inert - a value sent here has no
 * effect anywhere. Do not reintroduce a read of it.
 *
 * <p>{@code reason} is caller-supplied by design and only meaningful for a
 * rejection - it is free-text explanation, not an identity claim.
 */
public record ApprovalDecisionRequest(String decidedBy, String reason, java.util.Map<String, String> formValues) {

    /** {@code formValues}: answers to the fields a workflow approval node asked for; ignored everywhere else. */
    public ApprovalDecisionRequest(String decidedBy, String reason) {
        this(decidedBy, reason, null);
    }
}
