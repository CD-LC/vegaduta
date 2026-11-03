package com.agenticai.core.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A Redis-backed spend control could not evaluate its limit and the operator chose fail-closed
 * ({@code app.governance.spend-controls.fail-mode=closed}): the call is refused rather than run unmetered.
 * Maps to HTTP 503 so clients and load balancers treat it as a retryable outage, not a quota decision.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SpendControlsUnavailableException extends RuntimeException {

    private final String control;

    public SpendControlsUnavailableException(String control, Throwable cause) {
        super("Spend control '" + control + "' is temporarily unavailable and the platform is configured to refuse "
            + "metered requests while it is (app.governance.spend-controls.fail-mode=closed). Retry shortly.", cause);
        this.control = control;
    }

    public String control() {
        return control;
    }
}
