package com.agenticai.core.templates;

/**
 * Thrown when a member tries to rate a template they submitted themselves.
 * Mapped to HTTP 409 in {@code core/app}'s {@code GlobalExceptionHandler} -
 * the template is APPROVED and publicly visible, so unlike {@link
 * TemplateNotFoundException} there is no existence secret to protect and the
 * message can say exactly why the write was refused.
 */
public class TemplateRatingForbiddenException extends RuntimeException {

    public TemplateRatingForbiddenException() {
        super("You cannot rate your own template.");
    }
}
