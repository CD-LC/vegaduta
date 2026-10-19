package com.agenticai.core.templates;

/**
 * Thrown when a lifecycle transition is not legal from the template's current
 * status (see {@link TemplateStatus#canTransitionTo}) - e.g. approving a DRAFT
 * that was never submitted, or withdrawing something already withdrawn. Mapped
 * to HTTP 409 (Conflict) in {@code core/app}'s {@code GlobalExceptionHandler}:
 * the request is well-formed and the template exists (the caller could see it),
 * but its current state conflicts with the requested move.
 *
 * <p>Unlike {@link TemplateNotFoundException}, this is safe to be specific: it
 * is only ever thrown to a caller who already passed the visibility check for
 * this template, so naming the from/to states leaks nothing.
 */
public class IllegalTemplateTransitionException extends RuntimeException {

    public IllegalTemplateTransitionException(TemplateStatus from, TemplateStatus to) {
        super("A template in " + from + " cannot move to " + to + ".");
    }
}
