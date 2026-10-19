package com.agenticai.core.knowledge.connector;

/**
 * Provider-side fetch failure for a connector sync - surfaced as the
 * connector row's {@code last_error} so the admin sees why a source went
 * stale, rather than the sync dying silently.
 */
public class ConnectorFetchException extends RuntimeException {

    public ConnectorFetchException(String message) {
        super(message);
    }

    public ConnectorFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
