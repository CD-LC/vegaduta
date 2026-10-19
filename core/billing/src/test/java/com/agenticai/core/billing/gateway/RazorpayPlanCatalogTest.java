package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRICING-MONETIZATION-AUDIT-2026-07-31 B1/B2/D4 - pure unit coverage of
 * {@link RazorpayPlanCatalog}'s resolution matrix and coupon lookup (no
 * Spring context, no live Razorpay - the catalog is a plain bound-properties
 * object, same test style as {@link PaymentGatewayRegistryTest}). The
 * fallback ORDER is the contract under test: exact (tier, currency, period)
 * -&gt; same-currency monthly (annual only) -&gt; legacy
 * plan-id-starter/business -&gt; loud failure naming the missing key.
 */
class RazorpayPlanCatalogTest {

    private RazorpayPlanCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new RazorpayPlanCatalog();
    }

    // ------------------------------------------------------------------
    // resolvePlanId - exact hits
    // ------------------------------------------------------------------

    @Test
    void exactCombinationWinsOverEverything() {
        catalog.getPlans().getPremium().getInr().setMonthly("plan_p_inr_m");
        catalog.getPlans().getPremium().getInr().setAnnual("plan_p_inr_a");
        catalog.setPlanIdStarter("plan_legacy_starter");

        assertEquals("plan_p_inr_a",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.ANNUAL));
        assertEquals("plan_p_inr_m",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.MONTHLY));
    }

    @Test
    void eachTierAndCurrencyResolvesItsOwnBlock() {
        catalog.getPlans().getPremium().getUsd().setMonthly("plan_p_usd_m");
        catalog.getPlans().getUnlimited().getInr().setMonthly("plan_u_inr_m");
        catalog.getPlans().getUnlimited().getUsd().setAnnual("plan_u_usd_a");

        assertEquals("plan_p_usd_m",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.USD, BillingPeriod.MONTHLY));
        assertEquals("plan_u_inr_m",
            catalog.resolvePlanId(PaidTier.UNLIMITED, BillingCurrency.INR, BillingPeriod.MONTHLY));
        assertEquals("plan_u_usd_a",
            catalog.resolvePlanId(PaidTier.UNLIMITED, BillingCurrency.USD, BillingPeriod.ANNUAL));
    }

    @Test
    void configuredIdsAreTrimmed() {
        catalog.getPlans().getPremium().getInr().setMonthly("  plan_p_inr_m  ");

        assertEquals("plan_p_inr_m",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.MONTHLY));
    }

    // ------------------------------------------------------------------
    // resolvePlanId - fallback chain
    // ------------------------------------------------------------------

    @Test
    void annualFallsBackToSameCurrencyMonthlyWhenAnnualIsUnset() {
        catalog.getPlans().getPremium().getUsd().setMonthly("plan_p_usd_m");

        assertEquals("plan_p_usd_m",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.USD, BillingPeriod.ANNUAL));
    }

    @Test
    void monthlyDoesNotFallBackAcrossCurrenciesOnlyToLegacy() {
        // INR monthly configured must NOT satisfy a USD request - that would
        // charge rupee pricing to a dollar customer. USD falls straight to
        // the legacy key.
        catalog.getPlans().getPremium().getInr().setMonthly("plan_p_inr_m");
        catalog.setPlanIdStarter("plan_legacy_starter");

        assertEquals("plan_legacy_starter",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.USD, BillingPeriod.MONTHLY));
    }

    @Test
    void legacyKeysAreTheFinalFallbackPerTier() {
        catalog.setPlanIdStarter("plan_legacy_starter");
        catalog.setPlanIdBusiness("plan_legacy_business");

        // A pre-B1 deployment (only the legacy pair configured) keeps
        // resolving every combination to its tier's one plan id.
        assertEquals("plan_legacy_starter",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.MONTHLY));
        assertEquals("plan_legacy_starter",
            catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.USD, BillingPeriod.ANNUAL));
        assertEquals("plan_legacy_business",
            catalog.resolvePlanId(PaidTier.UNLIMITED, BillingCurrency.INR, BillingPeriod.ANNUAL));
    }

    // ------------------------------------------------------------------
    // resolvePlanId - loud failure (audit §2.4: no more "" sent to Razorpay)
    // ------------------------------------------------------------------

    @Test
    void fullyUnconfiguredCombinationThrowsNamingThePropertyAndEnvVar() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> catalog.resolvePlanId(PaidTier.UNLIMITED, BillingCurrency.USD, BillingPeriod.ANNUAL)
        );
        assertTrue(thrown.getMessage().contains("app.billing.razorpay.plans.unlimited.usd.annual"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("RAZORPAY_PLAN_UNLIMITED_USD_ANNUAL"), thrown.getMessage());
        // Annual requests also point at the monthly fallback key...
        assertTrue(thrown.getMessage().contains("plans.unlimited.usd.monthly"), thrown.getMessage());
        // ...and every message names the tier's legacy escape hatch.
        assertTrue(thrown.getMessage().contains("plan-id-business"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("RAZORPAY_PLAN_ID_BUSINESS"), thrown.getMessage());
    }

    @Test
    void premiumFailureNamesTheStarterLegacyKey() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.MONTHLY)
        );
        assertTrue(thrown.getMessage().contains("app.billing.razorpay.plans.premium.inr.monthly"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("plan-id-starter"), thrown.getMessage());
    }

    @Test
    void blankConfigValuesCountAsUnset() {
        catalog.getPlans().getPremium().getInr().setMonthly("   ");
        catalog.setPlanIdStarter("");

        assertThrows(
            IllegalStateException.class,
            () -> catalog.resolvePlanId(PaidTier.PREMIUM, BillingCurrency.INR, BillingPeriod.MONTHLY)
        );
    }

    // ------------------------------------------------------------------
    // offerIdFor - D4 coupons
    // ------------------------------------------------------------------

    @Test
    void offerLookupIsCaseInsensitiveInBothDirections() {
        // Env relaxed binding (APP_BILLING_RAZORPAY_OFFERS_LAUNCH50=...)
        // delivers lower-cased keys; customers type whatever they like.
        catalog.getOffers().put("launch50", "offer_abc");
        catalog.getOffers().put("DIWALI", "offer_def");

        assertEquals(Optional.of("offer_abc"), catalog.offerIdFor("LAUNCH50"));
        assertEquals(Optional.of("offer_abc"), catalog.offerIdFor("Launch50"));
        assertEquals(Optional.of("offer_def"), catalog.offerIdFor("diwali"));
    }

    @Test
    void offerCodeIsTrimmedBeforeMatching() {
        catalog.getOffers().put("launch50", "offer_abc");

        assertEquals(Optional.of("offer_abc"), catalog.offerIdFor("  launch50  "));
    }

    @Test
    void unknownBlankOrValuelessCodesResolveEmpty() {
        catalog.getOffers().put("emptyvalue", "   ");

        assertEquals(Optional.empty(), catalog.offerIdFor("nope"));
        assertEquals(Optional.empty(), catalog.offerIdFor(""));
        assertEquals(Optional.empty(), catalog.offerIdFor(null));
        // A code whose configured Offer id is blank is as good as absent -
        // better to reject the coupon than send offer_id: "" to Razorpay.
        assertEquals(Optional.empty(), catalog.offerIdFor("emptyvalue"));
    }

    // --- what the console may OFFER ---------------------------------------------------------------------------

    @Test
    void usdIsNotOfferedUntilRealUsdPlansExistEvenIfTheLegacyIdsDo() {
        RazorpayPlanCatalog catalog = new RazorpayPlanCatalog();
        catalog.setPlanIdStarter("plan_legacy_starter");
        catalog.setPlanIdBusiness("plan_legacy_business");
        assertEquals(java.util.List.of(BillingCurrency.INR), catalog.availableCurrencies());
    }

    @Test
    void bothCurrenciesAreOfferedOnceBothTiersHaveDedicatedMonthlyPlansInEach() {
        RazorpayPlanCatalog catalog = new RazorpayPlanCatalog();
        catalog.getPlans().getPremium().getInr().setMonthly("p_i");
        catalog.getPlans().getUnlimited().getInr().setMonthly("u_i");
        assertEquals(java.util.List.of(BillingCurrency.INR), catalog.availableCurrencies());
        catalog.getPlans().getPremium().getUsd().setMonthly("p_u");
        assertEquals(java.util.List.of(BillingCurrency.INR), catalog.availableCurrencies(), "one USD tier is not enough");
        catalog.getPlans().getUnlimited().getUsd().setMonthly("u_u");
        assertEquals(java.util.List.of(BillingCurrency.INR, BillingCurrency.USD), catalog.availableCurrencies());
    }

    @Test
    void nothingIsOfferedWhenNothingIsConfigured() {
        assertEquals(java.util.List.of(), new RazorpayPlanCatalog().availableCurrencies());
    }

    @Test
    void annualIsOnlyAdvertisedForDedicatedYearlyPlans() {
        RazorpayPlanCatalog catalog = new RazorpayPlanCatalog();
        catalog.getPlans().getPremium().getInr().setMonthly("p_m");
        catalog.getPlans().getUnlimited().getInr().setMonthly("u_m");
        assertEquals(java.util.List.of(), catalog.currenciesWithDedicatedPlans(BillingPeriod.ANNUAL),
            "a monthly plan must never stand in for a per-year card");
        catalog.getPlans().getPremium().getInr().setAnnual("p_y");
        catalog.getPlans().getUnlimited().getInr().setAnnual("u_y");
        assertEquals(java.util.List.of(BillingCurrency.INR), catalog.currenciesWithDedicatedPlans(BillingPeriod.ANNUAL));
    }
}
