package com.agenticai.core.security.net;

/**
 * An outbound URL was refused by {@link SsrfGuard}. The message is written to
 * be safe to show to a tenant or feed back to a model: it names the rule, never
 * an internal host name or address.
 */
public class SsrfBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SsrfBlockedException(String reason) {
        super(reason);
    }
}
