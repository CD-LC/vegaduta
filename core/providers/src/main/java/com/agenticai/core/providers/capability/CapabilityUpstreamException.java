package com.agenticai.core.providers.capability;

/**
 * Thrown when a fail-visible capability call (a playground/experiment
 * endpoint, never an internal hot path) reaches the provider but the upstream
 * call itself fails - mapped to a 502 by {@code GlobalExceptionHandler}, same
 * "our server made an outbound call to a third-party API that failed"
 * contract {@code SarvamApiException}/{@code E2bApiException} carry. Internal
 * decision paths stay fail-open to their local heuristics and never surface
 * upstream errors to user traffic.
 */
public class CapabilityUpstreamException extends RuntimeException {

    public CapabilityUpstreamException(String message) {
        super(message);
    }
}
