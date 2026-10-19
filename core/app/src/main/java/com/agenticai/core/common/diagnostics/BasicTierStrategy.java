package com.agenticai.core.common.diagnostics;

import org.springframework.stereotype.Component;

/**
 * {@code BASIC} tier: plain language, no jargon, for a non-technical end user.
 * Always a deterministic template, never an LLM call - see {@link
 * FailureExplanationService}'s javadoc / this package's design note for why: this
 * tier fires on every single failure path platform-wide, and it is deliberately
 * generic by design, so paying LLM latency/cost here would be pure waste for zero
 * benefit.
 *
 * <p>Pure template, no dependencies, trivially unit-testable - takes only the
 * already-masked {@link MaskedFailureContext}, never a raw exception.
 */
@Component
class BasicTierStrategy implements ExplanationTierStrategy {

    private static final String GUARDRAIL_VIOLATION = "GuardrailViolationException";
    private static final String UPSTREAM_PROVIDER_FAILURE = "UpstreamProviderException";

    @Override
    public String explain(MaskedFailureContext context) {
        return switch (context.exceptionSimpleName()) {
            case GUARDRAIL_VIOLATION ->
                "We blocked part of the response to help keep your data safe. Please try again.";
            case UPSTREAM_PROVIDER_FAILURE ->
                "Something went wrong generating your response - we're looking into it.";
            default ->
                "Something went wrong while processing your request. We're looking into it.";
        };
    }
}
