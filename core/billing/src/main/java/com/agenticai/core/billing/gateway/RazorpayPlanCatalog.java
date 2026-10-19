package com.agenticai.core.billing.gateway;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PRICING-MONETIZATION-AUDIT-2026-07-31 B1 (multi-currency), B2 (annual plans), D4 (coupons via
 * Razorpay {@code offer_id}) - the config-backed catalog of Razorpay dashboard Plan ids per (tier,
 * currency, period), plus the couponCode -&gt; Offer id map.
 *
 * <p><b>Config shape</b> (all default blank; see {@code application.yml}'s {@code
 * app.billing.razorpay} block and {@code deploy/.env.example}):
 *
 * <pre>
 * app.billing.razorpay.plans.premium.inr.monthly     = plan_...
 * app.billing.razorpay.plans.premium.inr.annual      = plan_...
 * app.billing.razorpay.plans.premium.usd.monthly     = plan_...
 * app.billing.razorpay.plans.premium.usd.annual      = plan_...
 * app.billing.razorpay.plans.unlimited.{inr|usd}.{monthly|annual} likewise
 * app.billing.razorpay.offers.{couponCode}           = offer_...
 * </pre>
 *
 * <p><b>Resolution order</b> ({@link #resolvePlanId}): exact (tier, currency, period) -&gt;
 * same-currency {@code monthly} (only when {@code ANNUAL} was asked for) -&gt; the legacy
 * single-per-tier {@code plan-id-starter}/{@code plan-id-business} keys (bound here too, so an
 * existing deployment that has only those set keeps working untouched). If the whole chain is blank
 * this throws an {@link IllegalStateException} naming the exact missing property + env var - before
 * B1, the blank id was silently sent to Razorpay as {@code "plan_id": ""} and failed opaquely on
 * their side (audit finding §2.4).
 *
 * <p><b>Coupon lookup</b> ({@link #offerIdFor}) is case-insensitive on the code - required in
 * practice because the supported way to add offers in a deployed environment is Spring's relaxed
 * env binding ({@code APP_BILLING_RAZORPAY_OFFERS_LAUNCH50=offer_...}), where the map key arrives
 * lower-cased as {@code launch50} regardless of what casing users type into the coupon field.
 *
 * <p>Registered via {@link RazorpayBillingConfiguration}'s {@code @EnableConfigurationProperties} -
 * same per-module pattern as {@code McpRegistryConfiguration}/{@code RlConfiguration} (there is no
 * application-wide {@code @ConfigurationPropertiesScan}). Deliberately NOT gated behind the {@code
 * security} profile: it holds no credentials, and an always-present bean keeps {@code
 * TenantUpgradeService}'s constructor injection simple.
 */
@ConfigurationProperties(prefix = "app.billing.razorpay")
public class RazorpayPlanCatalog {

    private final Plans plans = new Plans();
    private final Map<String, String> offers = new LinkedHashMap<>();

    // The legacy pre-B1 "one Plan id per paid tier" keys (created when the
    // paid tiers were still named STARTER/BUSINESS - see
    // TenantUpgradeService's javadoc). Kept bound as the final fallback so
    // existing deployments keep working with zero config changes.
    private String planIdStarter = "";
    private String planIdBusiness = "";

    public Plans getPlans() {
        return plans;
    }

    public Map<String, String> getOffers() {
        return offers;
    }

    public String getPlanIdStarter() {
        return planIdStarter;
    }

    public void setPlanIdStarter(String planIdStarter) {
        this.planIdStarter = planIdStarter;
    }

    public String getPlanIdBusiness() {
        return planIdBusiness;
    }

    public void setPlanIdBusiness(String planIdBusiness) {
        this.planIdBusiness = planIdBusiness;
    }

    /**
     * Non-throwing {@link #resolvePlanId}: the plan id to bill for (tier, currency, period), or
     * empty when the chain has nothing for it. Order: exact -> same-currency monthly (annual only)
     * -> the legacy single-per-tier ids.
     *
     * <p>Same chain as {@link #resolvePlanId} (the legacy single-per-tier ids answer any currency,
     * kept as the documented final fallback). What the CONSOLE may offer is stricter - see {@link
     * #availableCurrencies()}.
     */
    public Optional<String> findPlanId(
            PaidTier tier, BillingCurrency currency, BillingPeriod period) {
        PerCurrency perCurrency =
                tier == PaidTier.PREMIUM ? plans.getPremium() : plans.getUnlimited();
        PerPeriod perPeriod =
                currency == BillingCurrency.INR ? perCurrency.getInr() : perCurrency.getUsd();

        String exact =
                period == BillingPeriod.ANNUAL ? perPeriod.getAnnual() : perPeriod.getMonthly();
        if (hasText(exact)) {
            return Optional.of(exact.trim());
        }
        if (period == BillingPeriod.ANNUAL && hasText(perPeriod.getMonthly())) {
            return Optional.of(perPeriod.getMonthly().trim());
        }
        String legacy = tier == PaidTier.PREMIUM ? planIdStarter : planIdBusiness;
        if (hasText(legacy)) {
            return Optional.of(legacy.trim());
        }
        return Optional.empty();
    }

    /**
     * True when a plan was configured for exactly this (tier, currency, period) - no monthly or
     * legacy fallback. The console uses it for ANNUAL: a "per year" card must never be answered by
     * a monthly plan.
     */
    public boolean hasDedicatedPlan(PaidTier tier, BillingCurrency currency, BillingPeriod period) {
        PerCurrency perCurrency =
                tier == PaidTier.PREMIUM ? plans.getPremium() : plans.getUnlimited();
        PerPeriod perPeriod =
                currency == BillingCurrency.INR ? perCurrency.getInr() : perCurrency.getUsd();
        return hasText(
                period == BillingPeriod.ANNUAL ? perPeriod.getAnnual() : perPeriod.getMonthly());
    }

    /** Currencies in which BOTH paid tiers have a dedicated plan for {@code period}. */
    public java.util.List<BillingCurrency> currenciesWithDedicatedPlans(BillingPeriod period) {
        java.util.List<BillingCurrency> out = new java.util.ArrayList<>();
        for (BillingCurrency currency : BillingCurrency.values()) {
            boolean all = true;
            for (PaidTier tier : PaidTier.values()) {
                all &= hasDedicatedPlan(tier, currency, period);
            }
            if (all) {
                out.add(currency);
            }
        }
        return out;
    }

    /**
     * Currencies the console may OFFER: both paid tiers have a dedicated monthly plan in that
     * currency, or - INR only - the legacy single-per-tier ids exist (they were created in rupees).
     * USD is therefore offered only once real USD plans are configured, so an international
     * customer is never shown a dollar price that the account would actually bill as a rupee plan.
     * Empty = nothing is wired.
     */
    public java.util.List<BillingCurrency> availableCurrencies() {
        java.util.List<BillingCurrency> out = new java.util.ArrayList<>();
        for (BillingCurrency currency : BillingCurrency.values()) {
            boolean all = true;
            for (PaidTier tier : PaidTier.values()) {
                boolean legacy =
                        currency == BillingCurrency.INR
                                && hasText(
                                        tier == PaidTier.PREMIUM ? planIdStarter : planIdBusiness);
                all &= hasDedicatedPlan(tier, currency, BillingPeriod.MONTHLY) || legacy;
            }
            if (all) {
                out.add(currency);
            }
        }
        return out;
    }

    /**
     * Resolves the Razorpay dashboard Plan id to bill for the given combination - see the class
     * javadoc for the exact fallback order.
     *
     * @throws IllegalStateException when nothing in the fallback chain is configured; the message
     *     names the exact property and env var to set. Server misconfiguration, not a client
     *     mistake - deliberately NOT a 400-mapped exception type.
     */
    public String resolvePlanId(PaidTier tier, BillingCurrency currency, BillingPeriod period) {
        Optional<String> found = findPlanId(tier, currency, period);
        if (found.isPresent()) {
            return found.get();
        }

        String tierKey = tier.name().toLowerCase(Locale.ROOT);
        String currencyKey = currency.name().toLowerCase(Locale.ROOT);
        String periodKey = period.name().toLowerCase(Locale.ROOT);
        String legacyProperty = tier == PaidTier.PREMIUM ? "plan-id-starter" : "plan-id-business";
        String legacyEnv =
                tier == PaidTier.PREMIUM ? "RAZORPAY_PLAN_ID_STARTER" : "RAZORPAY_PLAN_ID_BUSINESS";
        throw new IllegalStateException(
                "No Razorpay Plan id is configured for "
                        + tier
                        + " "
                        + currency
                        + "/"
                        + period
                        + " - create the plan in the Razorpay dashboard and set app.billing.razorpay.plans."
                        + tierKey
                        + "."
                        + currencyKey
                        + "."
                        + periodKey
                        + " (env RAZORPAY_PLAN_"
                        + tier.name()
                        + "_"
                        + currency.name()
                        + "_"
                        + period.name()
                        + ")"
                        + (period == BillingPeriod.ANNUAL
                                ? ", or its same-currency monthly fallback ...plans."
                                        + tierKey
                                        + "."
                                        + currencyKey
                                        + ".monthly"
                                : "")
                        + ", or the legacy app.billing.razorpay."
                        + legacyProperty
                        + " (env "
                        + legacyEnv
                        + ").");
    }

    /**
     * Case-insensitive couponCode -&gt; Razorpay Offer id lookup. Empty when the code is unknown
     * (callers reject with a 400 - never silently ignore a coupon the customer believes they
     * applied, audit D4) or when the code itself is null/blank (callers treat that as "no coupon"
     * before ever getting here).
     */
    public Optional<String> offerIdFor(String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            return Optional.empty();
        }
        String wanted = couponCode.trim();
        for (Map.Entry<String, String> entry : offers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().trim().equalsIgnoreCase(wanted)
                    && hasText(entry.getValue())) {
                return Optional.of(entry.getValue().trim());
            }
        }
        return Optional.empty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** {@code app.billing.razorpay.plans.*} - one {@link PerCurrency} block per paid tier. */
    public static class Plans {
        private final PerCurrency premium = new PerCurrency();
        private final PerCurrency unlimited = new PerCurrency();

        public PerCurrency getPremium() {
            return premium;
        }

        public PerCurrency getUnlimited() {
            return unlimited;
        }
    }

    /** {@code ...plans.{tier}.*} - one {@link PerPeriod} block per {@link BillingCurrency}. */
    public static class PerCurrency {
        private final PerPeriod inr = new PerPeriod();
        private final PerPeriod usd = new PerPeriod();

        public PerPeriod getInr() {
            return inr;
        }

        public PerPeriod getUsd() {
            return usd;
        }
    }

    /**
     * {@code ...plans.{tier}.{currency}.*} - the actual Plan ids, one per {@link BillingPeriod}.
     */
    public static class PerPeriod {
        private String monthly = "";
        private String annual = "";

        public String getMonthly() {
            return monthly;
        }

        public void setMonthly(String monthly) {
            this.monthly = monthly;
        }

        public String getAnnual() {
            return annual;
        }

        public void setAnnual(String annual) {
            this.annual = annual;
        }
    }
}
