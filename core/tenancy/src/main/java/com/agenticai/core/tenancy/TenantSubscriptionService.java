package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.dto.TenantSubscriptionStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - the missing OTHER half
 * of {@link TenantUpgradeService}: a tenant-admin ending their own paid
 * subscription, plus the status view the console's subscription card reads.
 * Until D5 the only way off a paid plan was contacting support for a
 * platform-admin override ({@code Tenant#changePlan}) - the exact asymmetry
 * ("self-serve in, support-ticket out") the audit flagged.
 *
 * <p><b>Cancel is always cancel-at-cycle-end.</b> The customer paid for the
 * current cycle; they keep it. This method only records the gateway-accepted
 * intent ({@link Tenant#scheduleCancellation}) - {@code Tenant.plan} is NOT
 * touched here. The actual FREE downgrade happens when Razorpay emits {@code
 * subscription.cancelled} at cycle end and {@link RazorpayWebhookService}
 * applies it ({@link Tenant#downgradeToFree}) - the same "the webhook is the
 * source of truth for plan flips" posture the upgrade flow has always had
 * ({@code beginUpgrade} vs {@code confirmUpgrade}).
 *
 * <p>Tenant resolution mirrors {@link TenantUpgradeService#initiateUpgrade}
 * exactly (schema from {@link TenantContext}, never a path variable), and
 * the gateway is resolved from {@code Tenant.paymentGateway} - the gateway
 * that actually holds this subscription - not from a caller-supplied name.
 */
@Service
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class TenantSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TenantSubscriptionService.class);

    private final TenantRepository tenantRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;

    public TenantSubscriptionService(
        TenantRepository tenantRepository, PaymentGatewayRegistry paymentGatewayRegistry
    ) {
        this.tenantRepository = tenantRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
    }

    /** {@code GET /api/tenant/subscription} - the console subscription card's read. */
    public TenantSubscriptionStatusResponse status() {
        return statusOf(currentTenant());
    }

    /**
     * {@code POST /api/tenant/subscription/cancel} - cancel the current paid
     * subscription at the end of the billing cycle. Throws {@link
     * TenantUpgradeException} (the billing flows' shared 400) when there is
     * nothing cancellable: a FREE plan, a complimentary/comp-grant plan (no
     * gateway subscription behind it - it lapses on its own expiry instead),
     * no recorded gateway subscription, or a cancellation already scheduled.
     */
    public TenantSubscriptionStatusResponse cancel() {
        Tenant tenant = currentTenant();

        if (tenant.getPlan() == TenantPlan.FREE) {
            throw new TenantUpgradeException("This workspace is on the FREE plan - there is no subscription to cancel.");
        }
        if (tenant.getCompPlanExpiresAt() != null) {
            // A live comp grant (Academy scholarship / event trial / referral
            // reward) is not gateway-backed - CompGrantExpiryReverter ends it
            // at its own expiry. Cancelling the dormant signup-time trial
            // subscription here would be both pointless and confusing.
            throw new TenantUpgradeException(
                "This workspace's current plan is a complimentary grant - it ends on its own expiry date, there is no paid subscription to cancel.");
        }
        if (tenant.getGatewaySubscriptionId() == null) {
            throw new TenantUpgradeException("This workspace has no payment subscription on record - contact support.");
        }
        if (tenant.isCancelScheduled()) {
            throw new TenantUpgradeException(
                "Cancellation is already scheduled - your plan stays active until the end of the current billing cycle.");
        }

        PaymentGatewayClient gateway = paymentGatewayRegistry.resolve(tenant.getPaymentGateway());
        // The gateway holding this subscription must actually be configured -
        // credentials can be rotated/removed after the subscription was
        // created, and a missing-config call fails as an opaque gateway 401
        // otherwise. Checked before touching any tenant state.
        if (!gateway.isConfigured()) {
            throw new TenantUpgradeException(
                "The '" + gateway.gatewayName() + "' payment gateway is not configured on this deployment - "
                    + "contact support to cancel, or retry once billing credentials are restored.");
        }
        // Always cancel_at_cycle_end - see this class's javadoc. The gateway
        // call happens BEFORE any tenant state is written: if Razorpay
        // rejects the cancel, nothing here should claim it happened.
        String gatewayStatus = gateway.cancelSubscription(tenant.getGatewaySubscriptionId(), true);

        tenant.scheduleCancellation(gatewayStatus);
        tenantRepository.save(tenant);
        log.info(
            "Tenant '{}' scheduled cancellation of subscription '{}' at cycle end (gateway status now '{}') - plan {} kept until the webhook confirms",
            tenant.getName(), tenant.getGatewaySubscriptionId(), gatewayStatus, tenant.getPlan()
        );
        return statusOf(tenant);
    }

    private Tenant currentTenant() {
        String schemaName = TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/subscription request - SecurityConfig should have blocked this"));
        return tenantRepository.findBySchemaName(schemaName)
            .orElseThrow(() -> new IllegalStateException("No Tenant row for schema '" + schemaName + "'"));
    }

    private static TenantSubscriptionStatusResponse statusOf(Tenant tenant) {
        return new TenantSubscriptionStatusResponse(
            tenant.getPlan(),
            tenant.getSubscriptionStatus(),
            tenant.isCancelScheduled(),
            tenant.isPastDue(),
            tenant.getPastDueSince(),
            tenant.getCompPlanReason(),
            tenant.getCompPlanExpiresAt()
        );
    }
}
