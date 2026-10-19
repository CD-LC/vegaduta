package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.CapabilityUpstreamException;

/**
 * Thrown when a playground call reaches TypeSafe but the upstream call itself
 * fails - mapped to a 502 by {@code GlobalExceptionHandler}, same "our server
 * made an outbound call to a third-party API that failed" contract {@code
 * SarvamApiException}/{@code E2bApiException} carry. A subtype of the generic
 * {@link CapabilityUpstreamException}. Deliberately NOT used by the internal
 * decision paths (classifier/selector) - those stay fail-open to their
 * heuristics and never surface upstream errors to user traffic.
 */
public class JevUpstreamException extends CapabilityUpstreamException {

    public JevUpstreamException(String message) {
        super(message);
    }
}
