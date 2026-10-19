package com.agenticai.core.billing.gateway;

import java.util.UUID;

/**
 * D5 (dunning) - published by {@link SubscriptionPendingHandler} once a
 * tenant's subscription payment has failed {@code
 * app.billing.dunning.max-failed-attempts} times (default 3): the signal to
 * auto-downgrade the tenant to FREE.
 *
 * <p>A Spring application event rather than a direct call, on purpose: the
 * downgrade itself must go through the EXISTING plan-transition path
 * ({@code Tenant#downgradeToFree} + {@code TenantRepository#save} in
 * core/tenancy - the same path {@code RazorpayWebhookService} uses for
 * cancelled/completed subscriptions), and billing cannot depend on tenancy
 * (tenancy already depends on billing - the same cycle this handler's
 * JdbcTemplate posture exists to avoid). Consumed by {@code
 * SubscriptionPaymentExhaustedListener} in core/tenancy.
 *
 * @param failedAttempts the attempt count that crossed the threshold, as
 *     derived from the tenant row's {@code subscription_status} counter (see
 *     {@link SubscriptionPendingHandler})
 */
public record SubscriptionPaymentExhaustedEvent(
    UUID tenantId,
    String tenantName,
    String gatewaySubscriptionId,
    int failedAttempts
) {
}
