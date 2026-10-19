package com.agenticai.core.common.diagnostics;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@code INTERMEDIATE} tier: a bit more detail than {@code BASIC}, still
 * consumer-friendly - no HTTP status codes, exception class names, or raw provider
 * errors. Always a deterministic template, never an LLM call - same reasoning as
 * {@link BasicTierStrategy}.
 */
@Component
class IntermediateTierStrategy implements ExplanationTierStrategy {

    private static final String GUARDRAIL_VIOLATION = "GuardrailViolationException";
    private static final String UPSTREAM_PROVIDER_FAILURE = "UpstreamProviderException";

    @Override
    public String explain(MaskedFailureContext context) {
        return switch (context.exceptionSimpleName()) {
            case GUARDRAIL_VIOLATION -> "Our safety system blocked a tool's output before it reached "
                + "the AI because it matched a pattern we don't allow through. This is expected "
                + "protective behavior, not something wrong with your request.";
            case UPSTREAM_PROVIDER_FAILURE -> "Our connection to " + providerPhrase(context)
                + " was rejected or failed. This is on our end, not something you did - please try "
                + "again shortly.";
            default -> "An unexpected error occurred while handling your request. Our team has "
                + "been notified and is looking into it.";
        };
    }

    private static String providerPhrase(MaskedFailureContext context) {
        return StringUtils.hasText(context.maskedToolOrProviderName())
            ? "the configured AI service"
            : "one of our AI services";
    }
}
