package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import java.time.Instant;

/**
 * D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - {@code GET
 * /api/tenant/subscription}'s response, also returned by {@code POST
 * /api/tenant/subscription/cancel} so the console can re-render the
 * subscription card from the cancel call's own response without a second
 * round trip.
 *
 * @param plan               the tenant's current {@link TenantPlan} - during a
 *     scheduled cancellation or past-due grace window this is still the paid
 *     plan; it only becomes FREE once the gateway's cancelled/completed
 *     webhook lands
 * @param subscriptionStatus the gateway's raw status/event string as last
 *     recorded on the tenant (see V11's header for why this is never an
 *     app-level enum) - null for a tenant with no gateway subscription at all
 * @param cancelScheduled    the tenant-admin cancelled and the subscription
 *     ends at the current billing cycle's end
 * @param pastDue            the failed-payment grace window is open (Razorpay
 *     reported {@code subscription.halted}); {@code pastDueSince} says since
 *     when, null whenever {@code pastDue} is false
 * @param compPlanReason     free-text provenance of an active complimentary
 *     plan grant (see {@code Tenant#compPlanReason}) - e.g. a scholarship,
 *     event trial, referral reward, or site-wide promo. Null whenever there
 *     is no live comp grant; the web console uses this (matched against a
 *     known promo reason string) to decide whether to show a promo banner,
 *     not to render the raw text.
 * @param compPlanExpiresAt  when the active comp grant (if any) auto-reverts;
 *     null whenever {@code compPlanReason} is null
 */
public record TenantSubscriptionStatusResponse(
    TenantPlan plan,
    String subscriptionStatus,
    boolean cancelScheduled,
    boolean pastDue,
    Instant pastDueSince,
    String compPlanReason,
    Instant compPlanExpiresAt
) {
}
