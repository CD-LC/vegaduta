package com.agenticai.core.billing.commerce;

/**
 * Lifecycle of a {@link CheckoutSession} - the same five states OpenAI's
 * Agentic Commerce Protocol uses for its {@code CheckoutSession.status}
 * (see this package's {@code package-info} for the "ACP-shaped, not
 * ACP-conformant" caveat), stored as the enum name in {@code
 * control_plane.checkout_session.status} and rendered in ACP's lowercase
 * form by {@link #wireValue()} for API responses.
 *
 * <p>Transitions this package actually performs:
 * <pre>
 *   NOT_READY_FOR_PAYMENT -> READY_FOR_PAYMENT   (gateway link created)
 *   NOT_READY_FOR_PAYMENT -> CANCELED            (gateway link creation failed)
 *   READY_FOR_PAYMENT     -> COMPLETED           (payment_link.paid / qr_code.credited webhook)
 *   READY_FOR_PAYMENT     -> EXPIRED             (lazy, on read, once expires_at has passed)
 *   EXPIRED               -> COMPLETED           (late webhook - the money did arrive)
 * </pre>
 * {@code COMPLETED} is terminal: a redelivered webhook never re-completes
 * a row (the UPDATE is state-guarded, see {@link CheckoutSessionRepository
 * #markCompleted}).
 */
public enum CheckoutSessionStatus {
    NOT_READY_FOR_PAYMENT,
    READY_FOR_PAYMENT,
    COMPLETED,
    CANCELED,
    EXPIRED;

    /** ACP's lowercase snake_case form ({@code ready_for_payment}), for API/tool responses. */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the customer can still pay against this session (no gateway link is ever re-issued). */
    public boolean isPayable() {
        return this == READY_FOR_PAYMENT;
    }
}
