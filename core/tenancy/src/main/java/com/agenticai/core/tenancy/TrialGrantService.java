package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TrialClaimResponse;
import com.agenticai.core.tenancy.dto.TrialStatusResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event-triggered 7-day Premium trial (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §5.4 / approval item B6): a FREE tenant that has never paid and never trialled
 * gets ONE 7-day PREMIUM comp, claimed at a moment of highest intent (first
 * WhatsApp-connect attempt, first edit-lock collision) - no card, no gateway
 * subscription.
 *
 * <p><b>Rides the existing comp-grant machinery, deliberately:</b> the grant is
 * {@link Tenant#grantComplimentaryPlan} - the exact primitive {@link
 * CompGrantService} uses for Academy scholarships - so the plan flips with no
 * {@code gateway*} state, and {@link CompGrantExpiryReverter} reverts the tenant
 * to FREE at expiry with zero new expiry plumbing. {@code CompGrantService}
 * itself is not called: its contract is calendar-month durations
 * ({@code durationMonths}, service-token-authenticated, tenant-id-in-body) and
 * bending it to "7 days, JWT-authenticated, tenant from context" would distort
 * that contract more than sharing the underlying entity primitive does.
 *
 * <p><b>One per tenant, ever:</b> the reverter nulls the {@code comp_plan_*}
 * columns at expiry, so "already trialled" is recorded separately in the
 * V157 {@code event_trial_*} columns ({@link Tenant#claimEventTrial}), written in
 * the same transaction as the grant and never cleared. The claim read-check-write
 * runs under a {@code SELECT ... FOR UPDATE} row lock
 * ({@link TenantRepository#findBySchemaNameForUpdate}) so two concurrent claims
 * can't both pass the eligibility check.
 *
 * <p><b>An Academy comp and a trial can interleave</b> without corrupting each
 * other: both write the same comp_plan_* fields (latest grant wins/refreshes,
 * same semantics re-granting a scholarship always had), while the trial-claim
 * record stays independent. A tenant currently holding any comp (plan != FREE)
 * simply isn't trial-eligible.
 */
@Service
@Profile("security")
public class TrialGrantService {

    /** 7 days, per §5.4 - a taste of Premium, not a second free tier. */
    static final Duration TRIAL_DURATION = Duration.ofDays(7);
    static final TenantPlan TRIAL_PLAN = TenantPlan.PREMIUM;
    /** comp_plan_reason prefix - "event-trial:whatsapp-gate" etc., analytics-greppable. */
    static final String REASON_PREFIX = "event-trial:";

    private static final Logger log = LoggerFactory.getLogger(TrialGrantService.class);

    private final TenantRepository tenantRepository;

    public TrialGrantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Claim this tenant's one 7-day PREMIUM trial.
     *
     * @param tenantSchema the caller's tenant (JWT {@code tenant_id} claim / schema name)
     * @param trigger normalized trigger slug, e.g. {@code "whatsapp-gate"}
     * @throws TrialNotEligibleException (409 at the controller) with a
     *         user-readable reason when any eligibility rule fails
     */
    @Transactional
    public TrialClaimResponse claim(String tenantSchema, String trigger) {
        Tenant tenant = tenantRepository.findBySchemaNameForUpdate(tenantSchema)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantSchema + "' - TenantContext claims a tenant that doesn't exist"));

        String reason = ineligibilityReason(tenant);
        if (reason != null) {
            throw new TrialNotEligibleException(reason);
        }

        Instant claimedAt = Instant.now();
        Instant expiresAt = claimedAt.plus(TRIAL_DURATION);
        tenant.grantComplimentaryPlan(TRIAL_PLAN, claimedAt, expiresAt, REASON_PREFIX + trigger);
        tenant.claimEventTrial(trigger, claimedAt, expiresAt);
        tenantRepository.save(tenant);

        log.info("Event trial claimed: tenant={} trigger={} plan={} expiresAt={}",
            tenant.getId(), trigger, TRIAL_PLAN, expiresAt);

        return new TrialClaimResponse(true, TRIAL_PLAN, expiresAt);
    }

    /** What the web app reads to decide whether to offer the trial at a gate moment. */
    @Transactional(readOnly = true)
    public TrialStatusResponse status(String tenantSchema) {
        Tenant tenant = tenantRepository.findBySchemaName(tenantSchema)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantSchema + "' - TenantContext claims a tenant that doesn't exist"));

        boolean claimed = tenant.getEventTrialClaimedAt() != null;
        String reason = ineligibilityReason(tenant);
        return new TrialStatusResponse(
            reason == null,
            claimed,
            claimed ? tenant.getEventTrialExpiresAt() : null,
            reason
        );
    }

    /**
     * Null when eligible; otherwise the user-readable reason a claim would 409.
     * Ordered most-specific-first: "already used your trial" beats the generic
     * "not on FREE" for a tenant whose trial is currently live (plan == PREMIUM
     * via its own comp grant).
     */
    private String ineligibilityReason(Tenant tenant) {
        if (tenant.getEventTrialClaimedAt() != null) {
            return "This workspace has already used its one-time Premium trial.";
        }
        if (tenant.getPlan() != TenantPlan.FREE) {
            return "The Premium trial is only available on the FREE plan - this workspace is already on "
                + tenant.getPlan() + ".";
        }
        // "Has never had a paid subscription": any gateway subscription id or
        // gateway-reported status means a real (past or present) paid
        // subscription touched this tenant - a lapsed subscriber returning to
        // FREE is a win-back case, not a trial case.
        if (tenant.getGatewaySubscriptionId() != null || tenant.getSubscriptionStatus() != null) {
            return "This workspace has already had a paid subscription, so the one-time trial isn't available.";
        }
        // An upgrade mid-checkout: don't hand out a comp that would race the
        // paid subscription's webhook confirmation.
        if (tenant.getPendingPlan() != null || tenant.getPendingGatewaySubscriptionId() != null) {
            return "A plan upgrade is already in progress for this workspace - complete or cancel it first.";
        }
        return null;
    }
}
