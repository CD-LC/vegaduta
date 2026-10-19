package com.agenticai.core.billing.gateway;

/**
 * A {@link PaymentGatewayClient} call to the underlying gateway's API failed
 * - network error, gateway-side rejection, bad credentials, etc. Same
 * "our server made an outbound call that failed" class of exception as
 * {@code SidecarCommunicationException}/{@code KeycloakAdminException}
 * elsewhere in this codebase (mapped to 502 by {@code GlobalExceptionHandler}
 * once Stage 3+ wires a handler for it - not yet, since nothing calls this
 * client from a request path until then).
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
