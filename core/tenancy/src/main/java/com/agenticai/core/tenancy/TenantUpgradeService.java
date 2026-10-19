package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.BillingCurrency;
import com.agenticai.core.billing.gateway.BillingPeriod;
import com.agenticai.core.billing.gateway.GatewaySubscription;
import com.agenticai.core.billing.gateway.PaidTier;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TenantUpgradeOptionsResponse;
import com.agenticai.core.tenancy.dto.TenantUpgradeResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * #811 - "Wire tenant-console 'Upgrade your plan' text to a real flow." Until now the only real
 * write path to {@link Tenant#getPlan()} was the platform super-admin override ({@code
 * PlatformTenantPlanService}, web-admin's Subscription Plans page) - a tenant-admin hitting the 403
 * from {@code TenantLimitExceededException} ("upgrade your plan") had nowhere to actually act on
 * that. This service is the missing self-serve half: create a real Razorpay subscription for the
 * plan they picked, same integration path {@link TenantProvisioningService#signup} already uses at
 * signup.
 *
 * <p><b>Plan-id resolution (PRICING-MONETIZATION-AUDIT-2026-07-31 B1/B2, replacing the old "plan-id
 * reuse" note that used to live here):</b> the Razorpay Plan id now comes from {@code
 * RazorpayPlanCatalog} - one dashboard Plan per (tier, currency, period) under {@code
 * app.billing.razorpay.plans.*}, falling back per-combination to the legacy single-per-tier {@code
 * plan-id-starter}/{@code plan-id-business} keys (created when the paid tiers were named
 * STARTER/BUSINESS; a deployment with only those set behaves exactly as before B1: every upgrade
 * bills the one configured INR monthly plan). A combination whose whole fallback chain is
 * unconfigured now fails loudly naming the missing key instead of sending {@code "plan_id": ""} to
 * Razorpay (audit §2.4).
 *
 * <p><b>Coupons (audit D4):</b> an optional coupon code resolves through {@code
 * app.billing.razorpay.offers.{code}} to a Razorpay Offer id passed on subscription create. Unknown
 * codes are a 400 - a customer who typed a coupon and got silently charged full price is the one
 * outcome this flow must never produce.
 *
 * <p><b>No trial on an upgrade</b> (unlike signup's {@code trialDays} parameter) - a trial is a
 * new-customer acquisition incentive; someone already running on this platform upgrading from FREE
 * has already had their free run. {@code trialDays=0} means Razorpay's {@code start_at} is
 * effectively "now," so authorization happens immediately, same as any other "upgrade now" SaaS
 * flow.
 */
@Service
@Profile("security")
public class TenantUpgradeService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final FreeModeService freeModeService;

    public TenantUpgradeService(
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository,
            PaymentGatewayRegistry paymentGatewayRegistry,
            FreeModeService freeModeService) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.freeModeService = freeModeService;
    }

    /**
     * Overload defaulting to {@link PaymentGatewayRegistry}'s configured default gateway (Razorpay)
     * - unchanged behavior for every existing caller.
     */
    public TenantUpgradeResponse initiateUpgrade(TenantPlan targetPlan) {
        return initiateUpgrade(targetPlan, null);
    }

    /**
     * #825 Multi-gateway overload - INR/MONTHLY/no-coupon defaults, unchanged behavior for every
     * pre-B1/B2/D4 caller.
     */
    public TenantUpgradeResponse initiateUpgrade(TenantPlan targetPlan, String gatewayName) {
        return initiateUpgrade(targetPlan, gatewayName, null, null, null);
    }

    /**
     * The full B1/B2/D4 contract behind {@code POST /api/tenant/upgrade}.
     *
     * <p>#825 Multi-gateway - {@code gatewayName} lets a tenant pick which gateway to pay through
     * (e.g. {@code "phonepe"}); {@code null}/blank uses {@link PaymentGatewayRegistry}'s configured
     * default. Currency/period/coupon selection is limited to what the gateway declares ({@link
     * PaymentGatewayClient#supportedCurrencies()}, {@link PaymentGatewayClient#supportedPeriods()},
     * {@link PaymentGatewayClient#supportsCoupons()}; today only Razorpay has dashboard Plans and
     * Offers); anything outside that is rejected up front rather than silently billing something
     * other than what was asked for (PhonePe's plan config is INR-monthly by construction - see
     * {@code PhonePeGatewayClient#parsePlanConfig}).
     *
     * @param rawCurrency "INR"|"USD" (case-insensitive), null/blank = INR
     * @param rawPeriod "MONTHLY"|"ANNUAL" (case-insensitive), null/blank = MONTHLY
     * @param couponCode optional; must match a configured {@code app.billing.razorpay.offers.*}
     *     code (case-insensitive) or the request fails with a 400
     */
    /**
     * The currencies/periods the console may offer right now, as the gateway declares them ({@link
     * PaymentGatewayClient#offeredCurrencies()}, {@link PaymentGatewayClient#annualCurrencies()}).
     * Razorpay: exactly the currencies whose plans are configured (a USD price card is only shown
     * once USD plans exist, so an international customer is never sent into a checkout that cannot
     * start). Any other gateway today bills INR monthly only (see initiateUpgrade). An unconfigured
     * gateway offers nothing; an unknown gateway name falls back to the default gateway.
     */
    public TenantUpgradeOptionsResponse upgradeOptions(String gatewayName) {
        PaymentGatewayClient client;
        try {
            client = paymentGatewayRegistry.resolve(gatewayName);
        } catch (IllegalArgumentException e) {
            client = paymentGatewayRegistry.resolve(null);
        }
        boolean billingEnabled = !freeModeService.isEnabled();
        if (!client.isConfigured()) {
            return new TenantUpgradeOptionsResponse(
                    billingEnabled, client.gatewayName(), List.of(), List.of());
        }
        return new TenantUpgradeOptionsResponse(
                billingEnabled,
                client.gatewayName(),
                client.offeredCurrencies().stream().map(Enum::name).toList(),
                client.annualCurrencies().stream().map(Enum::name).toList());
    }

    public TenantUpgradeResponse initiateUpgrade(
            TenantPlan targetPlan,
            String gatewayName,
            String rawCurrency,
            String rawPeriod,
            String couponCode) {
        if (targetPlan == TenantPlan.FREE) {
            throw new TenantUpgradeException(
                    "FREE isn't a plan you upgrade to - contact support to downgrade instead.");
        }

        // Free mode makes every entitlement resolve UNLIMITED for every
        // tenant - there is nothing to upgrade TO, and charging a card for
        // a plan that changes nothing would be the one billing bug worse
        // than a missing gate. The checkout becomes meaningful again the
        // moment the switch flips off.
        if (freeModeService.isEnabled()) {
            throw new TenantUpgradeException(
                    "Billing is not enabled on this deployment right now - every plan feature is already "
                            + "unlocked while free mode is on. Upgrades open when billing is switched on.");
        }

        BillingCurrency currency =
                BillingCurrency.fromWire(rawCurrency)
                        .orElseThrow(
                                () ->
                                        new TenantUpgradeException(
                                                "Unknown currency '"
                                                        + rawCurrency
                                                        + "' - supported values are INR and USD."));
        BillingPeriod period =
                BillingPeriod.fromWire(rawPeriod)
                        .orElseThrow(
                                () ->
                                        new TenantUpgradeException(
                                                "Unknown billing period '"
                                                        + rawPeriod
                                                        + "' - supported values are MONTHLY and ANNUAL."));

        String schemaName =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated /api/tenant/upgrade request - SecurityConfig should have blocked this"));
        Tenant tenant =
                tenantRepository
                        .findBySchemaName(schemaName)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No Tenant row for schema '" + schemaName + "'"));

        if (tenant.getPlan() == targetPlan) {
            throw new TenantUpgradeException(
                    "This workspace is already on the " + targetPlan + " plan.");
        }

        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        AppUser adminUser =
                appUserRepository
                        .findByKeycloakUserId(subject)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No AppUser row for Keycloak user '"
                                                        + subject
                                                        + "'"));

        PaymentGatewayClient paymentGatewayClient = paymentGatewayRegistry.resolve(gatewayName);
        // Fail loudly BEFORE asking the gateway for a subscription when its
        // credentials aren't configured (the "Razorpay/Stripe details
        // pending" deployment state): otherwise the tenant hits an opaque
        // downstream 401 and a pending_upgrade row is never even written.
        if (!paymentGatewayClient.isConfigured()) {
            throw new TenantUpgradeException(
                    "The '"
                            + paymentGatewayClient.gatewayName()
                            + "' payment gateway is not configured on this "
                            + "deployment yet - paid upgrades are unavailable until its credentials are set. Your "
                            + "workspace keeps its current plan.");
        }
        String gateway = paymentGatewayClient.gatewayName();
        boolean hasCoupon = couponCode != null && !couponCode.isBlank();
        if (!paymentGatewayClient.supportedCurrencies().contains(currency)
                || !paymentGatewayClient.supportedPeriods().contains(period)) {
            throw new TenantUpgradeException(
                    "Paying "
                            + currency
                            + "/"
                            + period
                            + " is not available through "
                            + gateway
                            + " - omit the currency and billing period, or pick another payment method.");
        }
        if (hasCoupon && !paymentGatewayClient.supportsCoupons()) {
            throw new TenantUpgradeException(
                    "Coupon codes are not available for " + gateway + " payments.");
        }

        String offerId = null;
        if (hasCoupon) {
            offerId =
                    paymentGatewayClient
                            .offerIdFor(couponCode)
                            .orElseThrow(
                                    () ->
                                            new TenantUpgradeException(
                                                    "Unknown coupon code '"
                                                            + couponCode.trim()
                                                            + "' - check the code and try again."));
        }

        PaidTier tier = targetPlan == TenantPlan.PREMIUM ? PaidTier.PREMIUM : PaidTier.UNLIMITED;
        String planId = paymentGatewayClient.resolvePlanId(tier, currency, period);
        GatewaySubscription subscription =
                offerId == null
                        ? paymentGatewayClient.createSubscriptionWithTrial(
                                tenant.getId().toString(),
                                planId,
                                0,
                                adminUser.getEmail(),
                                tenant.getName())
                        : paymentGatewayClient.createSubscriptionWithTrial(
                                tenant.getId().toString(),
                                planId,
                                0,
                                adminUser.getEmail(),
                                tenant.getName(),
                                offerId);

        tenant.beginUpgrade(targetPlan, subscription.id());
        tenantRepository.save(tenant);

        return new TenantUpgradeResponse(
                targetPlan,
                subscription.id(),
                paymentGatewayClient.publicKeyId(),
                subscription.checkoutUrl());
    }
}
