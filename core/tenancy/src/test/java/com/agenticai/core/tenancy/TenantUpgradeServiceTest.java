package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.BillingCurrency;
import com.agenticai.core.billing.gateway.BillingPeriod;
import com.agenticai.core.billing.gateway.GatewaySubscription;
import com.agenticai.core.billing.gateway.PaidTier;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.billing.gateway.RazorpayPlanCatalog;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TenantUpgradeResponse;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * No test previously existed for {@link TenantUpgradeService} - the self-serve half of the #811
 * upgrade flow (create a real Razorpay subscription, mark the tenant pending). Combined with {@link
 * TenantTest} (the state machine) and {@link RazorpayWebhookServiceTest} (the confirmation half),
 * this closes the test gap on the revenue-loop path VEGADUTA-MASTER-ROADMAP-2026-07-19.md §1
 * incorrectly described as unbuilt.
 *
 * <p>PRICING-MONETIZATION-AUDIT-2026-07-31 B1/B2/D4 - the service now resolves Razorpay Plan ids
 * through {@link RazorpayPlanCatalog} (currency + billing period aware, legacy-key fallback) and
 * supports coupon codes. These tests configure the catalog with only the legacy pair by default -
 * proving that a pre-B1 deployment's config keeps behaving byte-for-byte identically - and layer
 * plans/offers on top per test. The catalog's own resolution-order matrix is covered in {@code
 * RazorpayPlanCatalogTest} (core/billing); here only the seams are exercised.
 */
class TenantUpgradeServiceTest {

    private static final String PREMIUM_PLAN_ID = "plan_starter_123";
    private static final String UNLIMITED_PLAN_ID = "plan_business_456";

    private TenantRepository tenantRepository;
    private AppUserRepository appUserRepository;
    private PaymentGatewayRegistry paymentGatewayRegistry;
    private PaymentGatewayClient paymentGatewayClient;
    private RazorpayPlanCatalog planCatalog;
    private FreeModeService freeModeService;
    private TenantUpgradeService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        paymentGatewayRegistry = mock(PaymentGatewayRegistry.class);
        paymentGatewayClient = mock(PaymentGatewayClient.class);
        // #825 Multi-gateway follow-up - the registry resolves to this mock for every gatewayName
        // these existing tests pass (null, via the single-arg initiateUpgrade overload, unless a
        // test explicitly overrides this stub) - matches PaymentGatewayRegistry's real "null/blank
        // -> configured default" contract. Its plan/coupon/currency methods mirror
        // RazorpayGatewayClient's overrides: every currency and period, coupons on, all backed by
        // the catalog below.
        when(paymentGatewayRegistry.resolve(any())).thenReturn(paymentGatewayClient);
        when(paymentGatewayClient.gatewayName()).thenReturn("razorpay");
        // Post-freemium-hardening precondition: the upgrade path refuses to
        // call an unconfigured gateway - the mock must opt in explicitly.
        when(paymentGatewayClient.isConfigured()).thenReturn(true);
        // Free mode defaults OFF (Mockito boolean default) - existing tests
        // exercise normal billing; the dedicated free-mode test flips it on.
        freeModeService = mock(FreeModeService.class);
        // Legacy-only config: exactly what every deployment configured before B1/B2 had.
        planCatalog = new RazorpayPlanCatalog();
        planCatalog.setPlanIdStarter(PREMIUM_PLAN_ID);
        planCatalog.setPlanIdBusiness(UNLIMITED_PLAN_ID);
        when(paymentGatewayClient.supportedCurrencies())
                .thenReturn(EnumSet.allOf(BillingCurrency.class));
        when(paymentGatewayClient.supportedPeriods())
                .thenReturn(EnumSet.allOf(BillingPeriod.class));
        when(paymentGatewayClient.supportsCoupons()).thenReturn(true);
        when(paymentGatewayClient.resolvePlanId(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                planCatalog.resolvePlanId(
                                        inv.getArgument(0, PaidTier.class),
                                        inv.getArgument(1, BillingCurrency.class),
                                        inv.getArgument(2, BillingPeriod.class)));
        when(paymentGatewayClient.offerIdFor(any()))
                .thenAnswer(inv -> planCatalog.offerIdFor(inv.getArgument(0)));
        when(paymentGatewayClient.offeredCurrencies())
                .thenAnswer(inv -> planCatalog.availableCurrencies());
        when(paymentGatewayClient.annualCurrencies())
                .thenAnswer(inv -> planCatalog.currenciesWithDedicatedPlans(BillingPeriod.ANNUAL));
        service =
                new TenantUpgradeService(
                        tenantRepository,
                        appUserRepository,
                        paymentGatewayRegistry,
                        freeModeService);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static Tenant freeTenant() {
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        // Tenant#id is @GeneratedValue(UUID) with no public setter - Hibernate only ever
        // populates it on persist. These tests never go through a real EntityManager, so
        // without this, tenant.getId() stays null and
        // TenantUpgradeService#initiateUpgrade NPEs at tenant.getId().toString().
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private void setContext(String schema, String subject) {
        TenantContext.set(TenantContext.of(schema, subject, Set.of()));
    }

    /** Stubs the happy-path tenant + admin-user lookups most upgrade tests need. */
    private Tenant stubFreeAcmeTenantWithAdmin() {
        Tenant tenant = freeTenant();
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));
        AppUser adminUser = mock(AppUser.class);
        when(adminUser.getEmail()).thenReturn("admin@acme.test");
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1"))
                .thenReturn(Optional.of(adminUser));
        return tenant;
    }

    @Test
    void initiateUpgradeToFreeIsRejected() {
        setContext("acme", "user-1");

        TenantUpgradeException thrown =
                assertThrows(
                        TenantUpgradeException.class,
                        () -> service.initiateUpgrade(TenantPlan.FREE));
        assertEquals(
                "FREE isn't a plan you upgrade to - contact support to downgrade instead.",
                thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void initiateUpgradeToAlreadyCurrentPremiumPlanIsRejected() {
        setContext("acme", "user-1");
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.PREMIUM,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        TenantUpgradeException thrown =
                assertThrows(
                        TenantUpgradeException.class,
                        () -> service.initiateUpgrade(TenantPlan.PREMIUM));
        assertEquals("This workspace is already on the PREMIUM plan.", thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void initiateUpgradeToPremiumCreatesARealSubscriptionAndMarksTenantPending() {
        setContext("acme", "keycloak-user-1");
        Tenant tenant = stubFreeAcmeTenantWithAdmin();
        GatewaySubscription subscription =
                new GatewaySubscription(
                        "sub_new_1", "https://razorpay.test/checkout/sub_new_1", "created");
        when(paymentGatewayClient.createSubscriptionWithTrial(
                        eq(tenant.getId().toString()),
                        eq(PREMIUM_PLAN_ID),
                        eq(0),
                        eq("admin@acme.test"),
                        eq("Acme")))
                .thenReturn(subscription);
        when(paymentGatewayClient.publicKeyId()).thenReturn("rzp_test_key");

        TenantUpgradeResponse response = service.initiateUpgrade(TenantPlan.PREMIUM);

        assertEquals(TenantPlan.PREMIUM, response.targetPlan());
        assertEquals("sub_new_1", response.gatewaySubscriptionId());
        assertEquals("rzp_test_key", response.gatewayPublicKey());
        assertEquals("https://razorpay.test/checkout/sub_new_1", response.checkoutUrl());
        // Tenant is marked pending, NOT yet upgraded - confirmation only
        // happens via the webhook (RazorpayWebhookServiceTest).
        assertEquals(TenantPlan.PREMIUM, tenant.getPendingPlan());
        assertEquals("sub_new_1", tenant.getPendingGatewaySubscriptionId());
        assertEquals(TenantPlan.FREE, tenant.getPlan());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void initiateUpgradeToUnlimitedUsesTheBusinessPlanId() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        when(paymentGatewayClient.createSubscriptionWithTrial(
                        any(), eq(UNLIMITED_PLAN_ID), eq(0), any(), any()))
                .thenReturn(
                        new GatewaySubscription(
                                "sub_unlimited",
                                "https://razorpay.test/checkout/sub_unlimited",
                                "created"));

        service.initiateUpgrade(TenantPlan.UNLIMITED);

        verify(paymentGatewayClient)
                .createSubscriptionWithTrial(any(), eq(UNLIMITED_PLAN_ID), eq(0), any(), any());
    }

    // ------------------------------------------------------------------
    // B1/B2 - currency + billing period
    // ------------------------------------------------------------------

    @Test
    void annualInrUpgradeUsesTheConfiguredAnnualPlanId() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        planCatalog.getPlans().getPremium().getInr().setAnnual("plan_prem_inr_annual");
        when(paymentGatewayClient.createSubscriptionWithTrial(
                        any(), eq("plan_prem_inr_annual"), eq(0), any(), any()))
                .thenReturn(
                        new GatewaySubscription("sub_a1", "https://razorpay.test/a1", "created"));

        // Case-insensitive wire values on purpose - the DTO passes raw strings through.
        service.initiateUpgrade(TenantPlan.PREMIUM, null, "inr", "annual", null);

        verify(paymentGatewayClient)
                .createSubscriptionWithTrial(
                        any(), eq("plan_prem_inr_annual"), eq(0), any(), any());
    }

    @Test
    void usdMonthlyUpgradeUsesTheConfiguredUsdPlanId() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        planCatalog.getPlans().getUnlimited().getUsd().setMonthly("plan_unl_usd_monthly");
        when(paymentGatewayClient.createSubscriptionWithTrial(
                        any(), eq("plan_unl_usd_monthly"), eq(0), any(), any()))
                .thenReturn(
                        new GatewaySubscription("sub_u1", "https://razorpay.test/u1", "created"));

        service.initiateUpgrade(TenantPlan.UNLIMITED, null, "USD", "MONTHLY", null);

        verify(paymentGatewayClient)
                .createSubscriptionWithTrial(
                        any(), eq("plan_unl_usd_monthly"), eq(0), any(), any());
    }

    @Test
    void unknownCurrencyIsA400StyleUpgradeException() {
        setContext("acme", "keycloak-user-1");

        TenantUpgradeException thrown =
                assertThrows(
                        TenantUpgradeException.class,
                        () -> service.initiateUpgrade(TenantPlan.PREMIUM, null, "EUR", null, null));
        assertTrue(thrown.getMessage().contains("EUR"), thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void unknownPeriodIsA400StyleUpgradeException() {
        setContext("acme", "keycloak-user-1");

        TenantUpgradeException thrown =
                assertThrows(
                        TenantUpgradeException.class,
                        () ->
                                service.initiateUpgrade(
                                        TenantPlan.PREMIUM, null, null, "WEEKLY", null));
        assertTrue(thrown.getMessage().contains("WEEKLY"), thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void unconfiguredCombinationFailsNamingTheMissingConfigKeyInsteadOfSendingAnEmptyPlanId() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        // Wipe even the legacy fallback - the true "nothing configured" state
        // that used to send "plan_id": "" to Razorpay (audit §2.4).
        planCatalog.setPlanIdStarter("");
        planCatalog.setPlanIdBusiness("");

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                service.initiateUpgrade(
                                        TenantPlan.PREMIUM, null, "USD", "MONTHLY", null));
        assertTrue(
                thrown.getMessage().contains("app.billing.razorpay.plans.premium.usd.monthly"),
                thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    // ------------------------------------------------------------------
    // D4 - coupons
    // ------------------------------------------------------------------

    @Test
    void knownCouponCodeResolvesCaseInsensitivelyAndPassesTheOfferIdToTheGateway() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        planCatalog.getOffers().put("launch50", "offer_abc123");
        when(paymentGatewayClient.createSubscriptionWithTrial(
                        any(), eq(PREMIUM_PLAN_ID), eq(0), any(), any(), eq("offer_abc123")))
                .thenReturn(
                        new GatewaySubscription("sub_c1", "https://razorpay.test/c1", "created"));

        service.initiateUpgrade(TenantPlan.PREMIUM, null, null, null, "LAUNCH50");

        verify(paymentGatewayClient)
                .createSubscriptionWithTrial(
                        any(), eq(PREMIUM_PLAN_ID), eq(0), any(), any(), eq("offer_abc123"));
        // The plain (no-offer) overload must NOT have been used.
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void unknownCouponCodeIsRejectedNotSilentlyIgnored() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();

        TenantUpgradeException thrown =
                assertThrows(
                        TenantUpgradeException.class,
                        () ->
                                service.initiateUpgrade(
                                        TenantPlan.PREMIUM, null, null, null, "NOPE99"));
        assertTrue(thrown.getMessage().contains("NOPE99"), thrown.getMessage());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Multi-gateway seams
    // ------------------------------------------------------------------

    @Test
    void
            initiateUpgradeToAnExplicitlyChosenNonRazorpayGatewayUsesTheBareTenantPlanNameAsThePlanId() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        PaymentGatewayClient phonePeClient =
                mock(PaymentGatewayClient.class, Answers.CALLS_REAL_METHODS);
        when(phonePeClient.gatewayName()).thenReturn("phonepe");
        when(phonePeClient.isConfigured()).thenReturn(true);
        when(paymentGatewayRegistry.resolve("phonepe")).thenReturn(phonePeClient);
        when(phonePeClient.createSubscriptionWithTrial(any(), eq("PREMIUM"), eq(0), any(), any()))
                .thenReturn(
                        new GatewaySubscription(
                                "sub_pp_1", "https://phonepe.test/redirect/sub_pp_1", "PENDING"));

        service.initiateUpgrade(TenantPlan.PREMIUM, "phonepe");

        // Razorpay's own dashboard-specific Plan id constant must NOT leak into a different
        // gateway's request - PhonePe gets the bare TenantPlan name instead (see
        // TenantUpgradeService#resolvePlanId's javadoc).
        verify(phonePeClient)
                .createSubscriptionWithTrial(any(), eq("PREMIUM"), eq(0), any(), any());
        verify(paymentGatewayClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void nonDefaultCurrencyOrPeriodOnANonRazorpayGatewayIsRejected() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        PaymentGatewayClient phonePeClient =
                mock(PaymentGatewayClient.class, Answers.CALLS_REAL_METHODS);
        when(phonePeClient.gatewayName()).thenReturn("phonepe");
        when(paymentGatewayRegistry.resolve("phonepe")).thenReturn(phonePeClient);

        // Silently billing a monthly INR PhonePe mandate to someone who asked for annual USD is
        // the failure mode this guard exists for.
        assertThrows(
                TenantUpgradeException.class,
                () -> service.initiateUpgrade(TenantPlan.PREMIUM, "phonepe", "USD", null, null));
        assertThrows(
                TenantUpgradeException.class,
                () -> service.initiateUpgrade(TenantPlan.PREMIUM, "phonepe", null, "ANNUAL", null));
        verify(phonePeClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
    }

    @Test
    void couponOnANonRazorpayGatewayIsRejected() {
        setContext("acme", "keycloak-user-1");
        stubFreeAcmeTenantWithAdmin();
        planCatalog.getOffers().put("launch50", "offer_abc123");
        PaymentGatewayClient phonePeClient =
                mock(PaymentGatewayClient.class, Answers.CALLS_REAL_METHODS);
        when(phonePeClient.gatewayName()).thenReturn("phonepe");
        when(paymentGatewayRegistry.resolve("phonepe")).thenReturn(phonePeClient);

        assertThrows(
                TenantUpgradeException.class,
                () ->
                        service.initiateUpgrade(
                                TenantPlan.PREMIUM, "phonepe", null, null, "launch50"));
        verify(phonePeClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any());
        verify(phonePeClient, never())
                .createSubscriptionWithTrial(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void noTenantContextThrowsIllegalState() {
        assertThrows(
                IllegalStateException.class, () -> service.initiateUpgrade(TenantPlan.PREMIUM));
    }

    @Test
    void unknownTenantSchemaThrowsIllegalState() {
        setContext("ghost", "user-1");
        when(tenantRepository.findBySchemaName("ghost")).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class, () -> service.initiateUpgrade(TenantPlan.PREMIUM));
    }

    @Test
    void noAppUserRowForTheAuthenticatedSubjectThrowsIllegalState() {
        setContext("acme", "keycloak-user-1");
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(freeTenant()));
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1"))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class, () -> service.initiateUpgrade(TenantPlan.PREMIUM));
    }

    // --- upgradeOptions: what the console may offer
    // ----------------------------------------------------------

    @Test
    void optionsOfferOnlyTheCurrenciesThatAreActuallyConfigured() {
        // Legacy-only config (setUp): INR, no USD, no yearly.
        var legacy = service.upgradeOptions(null);
        assertEquals(java.util.List.of("INR"), legacy.currencies());
        assertEquals(java.util.List.of(), legacy.annualCurrencies());
        assertEquals("razorpay", legacy.gateway());
        org.junit.jupiter.api.Assertions.assertTrue(legacy.billingEnabled());

        planCatalog.getPlans().getPremium().getInr().setMonthly("p_i");
        planCatalog.getPlans().getUnlimited().getInr().setMonthly("u_i");
        planCatalog.getPlans().getPremium().getInr().setAnnual("p_iy");
        planCatalog.getPlans().getUnlimited().getInr().setAnnual("u_iy");
        planCatalog.getPlans().getPremium().getUsd().setMonthly("p_u");
        planCatalog.getPlans().getUnlimited().getUsd().setMonthly("u_u");
        var full = service.upgradeOptions(null);
        assertEquals(java.util.List.of("INR", "USD"), full.currencies());
        assertEquals(
                java.util.List.of("INR"),
                full.annualCurrencies(),
                "USD has no dedicated yearly plan yet");
    }

    @Test
    void optionsAreEmptyForAnUnconfiguredGatewayAndFlagFreeMode() {
        when(paymentGatewayClient.isConfigured()).thenReturn(false);
        when(freeModeService.isEnabled()).thenReturn(true);
        var options = service.upgradeOptions("razorpay");
        assertEquals(java.util.List.of(), options.currencies());
        org.junit.jupiter.api.Assertions.assertFalse(options.billingEnabled());
    }

    @Test
    void anotherGatewayOffersInrMonthlyOnly() {
        PaymentGatewayClient phonePeClient =
                mock(PaymentGatewayClient.class, Answers.CALLS_REAL_METHODS);
        when(phonePeClient.gatewayName()).thenReturn("phonepe");
        when(phonePeClient.isConfigured()).thenReturn(true);
        when(paymentGatewayRegistry.resolve("phonepe")).thenReturn(phonePeClient);
        var options = service.upgradeOptions("phonepe");
        assertEquals(java.util.List.of("INR"), options.currencies());
        assertEquals(java.util.List.of(), options.annualCurrencies());
    }

    @Test
    void anUnknownGatewayNameFallsBackToTheDefault() {
        when(paymentGatewayRegistry.resolve("nope"))
                .thenThrow(new IllegalArgumentException("unknown"));
        when(paymentGatewayRegistry.resolve((String) null)).thenReturn(paymentGatewayClient);
        assertEquals("razorpay", service.upgradeOptions("nope").gateway());
    }
}
