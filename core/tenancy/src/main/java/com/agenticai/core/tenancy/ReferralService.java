package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.ReferralRedeemResponse;
import com.agenticai.core.tenancy.dto.ReferralStatusResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Referral program (PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5 item D3):
 * give-1-month-get-1-month PREMIUM. Every tenant gets a stable shareable code
 * (lazily generated on first read of {@code GET /api/tenant/referral}); a NEW
 * tenant redeems a code once, and BOTH sides get a 1-calendar-month PREMIUM comp.
 *
 * <p><b>Rides the existing comp-grant machinery, deliberately</b> - the same
 * decision {@link TrialGrantService} already made and documented: the grant is
 * {@link Tenant#grantComplimentaryPlan} (no {@code gateway*} state touched,
 * {@link CompGrantExpiryReverter} reverts to FREE at expiry with zero new expiry
 * plumbing), while the durable referral record lives in the V159
 * {@code referral_*} columns, written in the same transaction and never cleared -
 * because the reverter nulls the {@code comp_plan_*} columns, "has this tenant
 * already redeemed?" cannot be answered from those. {@code CompGrantService}
 * itself is not called, for the exact contract-shape reasons TrialGrantService's
 * javadoc spells out.
 *
 * <p><b>Anti-abuse:</b> (1) redemption is for NEW tenants only - FREE plan, no
 * comp already active, created within {@link #NEW_TENANT_WINDOW}, never redeemed
 * before, not the referrer itself; (2) a referrer earns at most
 * {@link #MAX_REFERRER_GRANTS_PER_YEAR} grants per rolling 365 days (counted
 * from {@code referred_by_tenant_id}/{@code referral_redeemed_at}, so the count
 * survives comp expiry). Both sides of the redemption run under
 * {@code SELECT ... FOR UPDATE} row locks ({@link
 * TenantRepository#findBySchemaNameForUpdate} for the redeemer's
 * one-redemption-ever check, {@link TenantRepository#findByReferralCodeForUpdate}
 * for the referrer's cap count) so concurrent redemptions can't double-pass
 * either check.
 *
 * <p><b>The referrer's reward is skipped - not failed - when unsafe:</b>
 * {@code grantComplimentaryPlan} + the reverter would clobber a PAYING
 * referrer's plan (grant overwrites the plan now; revert lands them on FREE at
 * expiry, ignoring their live subscription). So the referrer only receives the
 * comp when they're on FREE or currently holding a comp (in which case the
 * re-grant refreshes the expiry to now+1mo - same overwrite-not-stack semantics
 * CompGrantService documents). A paying referrer's redemption still succeeds and
 * still counts toward their cap; only the redundant plan flip is skipped.
 */
@Service
@Profile("security")
public class ReferralService {

    /** Both sides get 1 calendar month of PREMIUM, per §8.5/D3 "give-1-month-get-1-month". */
    static final TenantPlan GRANT_PLAN = TenantPlan.PREMIUM;
    static final int GRANT_MONTHS = 1;
    /** Redeemer must be a NEW tenant: created within the last 30 days. */
    static final Duration NEW_TENANT_WINDOW = Duration.ofDays(30);
    /** A referrer earns at most this many referral grants per rolling 365 days. */
    static final int MAX_REFERRER_GRANTS_PER_YEAR = 12;
    static final Duration REFERRER_CAP_WINDOW = Duration.ofDays(365);
    /** comp_plan_reason prefixes - analytics-greppable, mirrors "event-trial:". */
    static final String REDEEMER_REASON_PREFIX = "referral-redeemed:";
    static final String REFERRER_REASON_PREFIX = "referral-reward:";

    /**
     * Crockford-ish uppercase alphabet - no I/L/O/0/1, so a code read aloud or
     * hand-typed from a WhatsApp message can't be mis-transcribed. 8 chars over
     * 31 symbols is ~40 bits: unguessable in practice, and the V159 unique
     * constraint backstops the (astronomically unlikely) generation collision.
     */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 8;
    private static final int MAX_GENERATION_ATTEMPTS = 10;

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private final TenantRepository tenantRepository;
    private final SecureRandom random = new SecureRandom();

    public ReferralService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * {@code GET /api/tenant/referral} - the tenant's own code (lazily generated
     * and persisted on first read), how many tenants redeemed it, and whether
     * this tenant can still redeem someone else's.
     */
    @Transactional
    public ReferralStatusResponse status(String tenantSchema) {
        Tenant tenant = tenantRepository.findBySchemaName(tenantSchema)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantSchema + "' - TenantContext claims a tenant that doesn't exist"));

        if (tenant.getReferralCode() == null) {
            // Re-fetch under a row lock so two concurrent first-reads can't both
            // generate: the second locker re-checks and sees the winner's code
            // (Tenant#assignReferralCode's own null-guard is the last line of
            // defense against rotating an already-shared code).
            tenant = tenantRepository.findBySchemaNameForUpdate(tenantSchema).orElseThrow();
            if (tenant.getReferralCode() == null) {
                tenant.assignReferralCode(generateUniqueCode());
                tenantRepository.save(tenant);
                log.info("Referral code assigned: tenant={} code={}", tenant.getId(), tenant.getReferralCode());
            }
        }

        return new ReferralStatusResponse(
            tenant.getReferralCode(),
            tenantRepository.countByReferredByTenantId(tenant.getId()),
            tenant.getReferralRedeemedAt() != null,
            redeemIneligibilityReason(tenant) == null
        );
    }

    /**
     * {@code POST /api/tenant/referral/redeem} - redeem another tenant's code;
     * on success BOTH tenants get the 1-month PREMIUM comp (referrer's side
     * skipped when unsafe - see class javadoc).
     *
     * @param tenantSchema the redeeming caller's tenant (JWT {@code tenant_id} claim)
     * @param normalizedCode trimmed/uppercased code ({@code ReferralRedeemRequest#normalizedCode})
     * @throws ReferralNotEligibleException (409 at the controller) with a
     *         user-readable reason when any rule fails
     */
    @Transactional
    public ReferralRedeemResponse redeem(String tenantSchema, String normalizedCode) {
        Tenant redeemer = tenantRepository.findBySchemaNameForUpdate(tenantSchema)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantSchema + "' - TenantContext claims a tenant that doesn't exist"));

        String reason = redeemIneligibilityReason(redeemer);
        if (reason != null) {
            throw new ReferralNotEligibleException(reason);
        }

        // Self-referral pre-check BEFORE the code lookup: locking one's own row
        // twice is harmless, but the message should name the real problem.
        if (normalizedCode.equals(redeemer.getReferralCode())) {
            throw new ReferralNotEligibleException("You can't redeem your own referral code.");
        }

        Tenant referrer = tenantRepository.findByReferralCodeForUpdate(normalizedCode)
            .orElseThrow(() -> new ReferralNotEligibleException(
                "That referral code isn't recognized - check it and try again."));
        if (referrer.getId().equals(redeemer.getId())) {
            throw new ReferralNotEligibleException("You can't redeem your own referral code.");
        }

        Instant now = Instant.now();
        long grantsThisYear = tenantRepository.countByReferredByTenantIdAndReferralRedeemedAtAfter(
            referrer.getId(), now.minus(REFERRER_CAP_WINDOW));
        if (grantsThisYear >= MAX_REFERRER_GRANTS_PER_YEAR) {
            throw new ReferralNotEligibleException(
                "This referral code has reached its limit for now - ask your friend for a different one.");
        }

        // Calendar month, not a flat 30 days - same Instant-has-no-month-arithmetic
        // reasoning as CompGrantService#grant.
        Instant expiresAt = now.atOffset(ZoneOffset.UTC).plusMonths(GRANT_MONTHS).toInstant();

        redeemer.grantComplimentaryPlan(GRANT_PLAN, now, expiresAt, REDEEMER_REASON_PREFIX + normalizedCode);
        redeemer.redeemReferral(referrer.getId(), now);
        tenantRepository.save(redeemer);

        boolean referrerRewarded = referrerRewardSafe(referrer);
        if (referrerRewarded) {
            referrer.grantComplimentaryPlan(GRANT_PLAN, now, expiresAt, REFERRER_REASON_PREFIX + normalizedCode);
            tenantRepository.save(referrer);
        }

        log.info("Referral redeemed: redeemer={} referrer={} code={} expiresAt={} referrerRewarded={}",
            redeemer.getId(), referrer.getId(), normalizedCode, expiresAt, referrerRewarded);

        return new ReferralRedeemResponse(true, GRANT_PLAN, expiresAt);
    }

    /**
     * Null when this tenant may redeem a code; otherwise the user-readable reason
     * a redemption would 409. Ordered most-specific-first, same convention as
     * {@code TrialGrantService#ineligibilityReason}: "already redeemed" beats
     * "not FREE" for a tenant whose referral comp is currently live.
     */
    private String redeemIneligibilityReason(Tenant tenant) {
        if (tenant.getReferralRedeemedAt() != null) {
            return "This workspace has already redeemed a referral code.";
        }
        if (tenant.getCompPlanExpiresAt() != null) {
            return "This workspace already has a complimentary plan active - a referral can't be redeemed on top of it.";
        }
        if (tenant.getPlan() != TenantPlan.FREE) {
            return "Referral codes can only be redeemed on the FREE plan - this workspace is already on "
                + tenant.getPlan() + ".";
        }
        if (tenant.getCreatedAt() == null
            || tenant.getCreatedAt().isBefore(Instant.now().minus(NEW_TENANT_WINDOW))) {
            return "Referral codes can only be redeemed within the first "
                + NEW_TENANT_WINDOW.toDays() + " days of a new workspace.";
        }
        return null;
    }

    /**
     * Whether flipping the referrer's plan via a comp grant is safe - see class
     * javadoc: a grant on a PAYING tenant would overwrite their paid plan now
     * and revert them to FREE at expiry, so only FREE referrers (fresh comp) or
     * referrers already holding a comp (expiry refresh) receive the reward.
     */
    private boolean referrerRewardSafe(Tenant referrer) {
        if (referrer.getGatewaySubscriptionId() != null || referrer.getPendingPlan() != null) {
            return false;
        }
        return referrer.getPlan() == TenantPlan.FREE || referrer.getCompPlanExpiresAt() != null;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!tenantRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        // ~40 bits of entropy: ten straight collisions means something is broken,
        // not unlucky. The V159 unique constraint would catch a race regardless.
        throw new IllegalStateException(
            "Could not generate a unique referral code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }
}
