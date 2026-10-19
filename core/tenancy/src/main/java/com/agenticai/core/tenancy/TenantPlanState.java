package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * The plan and monetization half of {@link Tenant}: the current {@link TenantPlan}, the paid
 * gateway subscription (signup, self-serve upgrade, cancellation, past-due grace), complimentary
 * grants, the one-per-tenant event trial and the referral bookkeeping.
 *
 * <p>A {@link MappedSuperclass}, not an embeddable: every column still maps onto the same {@code
 * control_plane.tenant} row and every attribute keeps its name on the {@code Tenant} entity, so
 * JPQL such as {@code t.compPlanExpiresAt} and every caller of {@code tenant.getPlan()} are
 * unchanged. Split out only to keep the entity class readable; {@link Tenant} is the only
 * subclass.
 */
@MappedSuperclass
@Access(AccessType.FIELD)
public abstract class TenantPlanState {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantPlan plan;

    // Billing columns (migration V11) - all six nullable, all
    // unset until the signup->checkout flow starts populating them.
    // See V11__tenant_billing_columns.sql's header comment for why
    // gatewayCustomerId lags gatewaySubscriptionId (Razorpay has no
    // pre-checkout customer-creation step - PaymentGatewayClient's javadoc
    // explains the same thing from the gateway-client side) and why
    // subscriptionStatus stores the gateway's raw status string rather than
    // a parallel app-level enum (Stage 4's webhook handler is the one place
    // that needs to reason about every possible Razorpay status; forcing a
    // second enum here would just be a second thing to keep in sync with
    // BillingEventType).
    @Column(name = "payment_gateway", length = 32)
    private String paymentGateway;

    @Column(name = "gateway_customer_id", length = 64)
    private String gatewayCustomerId;

    @Column(name = "gateway_subscription_id", length = 64)
    private String gatewaySubscriptionId;

    @Column(name = "subscription_status", length = 32)
    private String subscriptionStatus;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    // PRICING-MONETIZATION-AUDIT-2026-07-31 D5 (migration V161) - the paid
    // subscription's own end-of-life bookkeeping, both meaningless (and
    // null/false) unless this tenant is on a paid plan with a live gateway
    // subscription:
    //   past_due_since   - when Razorpay first reported subscription.halted
    //     (every retry of a failing charge exhausted). Non-null == the
    //     grace window is open: the tenant keeps its paid plan while the
    //     customer fixes their payment method; a later successful charge
    //     clears it (#clearPastDue), a cancelled/completed event ends it
    //     via #downgradeToFree. Deliberately NOT an immediate downgrade -
    //     one failed card should not vaporize a paying customer's workspace.
    //   cancel_scheduled - the tenant-admin asked to cancel (POST
    //     /api/tenant/subscription/cancel, always cancel_at_cycle_end) and
    //     the gateway accepted; the plan stays paid until Razorpay emits
    //     subscription.cancelled at cycle end, which #downgradeToFree then
    //     applies. Purely "intent recorded" - the gateway remains the
    //     source of truth for when it actually happens.
    @Column(name = "past_due_since")
    private Instant pastDueSince;

    @Column(name = "cancel_scheduled", nullable = false)
    private boolean cancelScheduled = false;

    // #811 (migration V71) - an in-flight self-serve upgrade. See
    // #beginUpgrade/#confirmUpgrade/#cancelPendingUpgrade below; both null
    // whenever no upgrade is pending, which is true for almost every tenant.
    @Enumerated(EnumType.STRING)
    @Column(name = "pending_plan", length = 32)
    private TenantPlan pendingPlan;

    @Column(name = "pending_gateway_subscription_id", length = 64)
    private String pendingGatewaySubscriptionId;

    // V471 - WHEN beginUpgrade created the pending subscription above. Until
    // this column existed an abandoned checkout (customer closed the gateway
    // page without paying) left pending_* populated forever with nothing to
    // age it by; StalePendingUpgradeCleanup reads this to clear upgrades
    // whose checkout window has long closed. Null whenever no upgrade is
    // pending.
    @Column(name = "pending_upgrade_started_at")
    private Instant pendingUpgradeStartedAt;

    // Academy Fast-Track Scholarship one-click fulfilment (migration V114, see
    // CORE-COMP-GRANT-CONTRACT.md) - a COMPLIMENTARY plan grant: the Academy
    // flips this tenant to a paid tier at no charge, with an expiry. Deliberately
    // distinct from both the platform-admin override (#changePlan, sets plan with
    // no expiry at all) and the paid self-serve upgrade (#beginUpgrade/
    // #confirmUpgrade, which always creates a real gateway subscription) - a comp
    // grant creates NO gateway subscription and touches NONE of the gateway_*
    // columns, exactly like #changePlan, but unlike it also records an expiry so
    // it can be reverted. All three null for every tenant that has never received
    // a comp grant. See #grantComplimentaryPlan / #revertComplimentaryGrant and
    // CompGrantExpiryReverter for the write/expire paths.
    @Column(name = "comp_plan_expires_at")
    private Instant compPlanExpiresAt;

    @Column(name = "comp_plan_granted_at")
    private Instant compPlanGrantedAt;

    @Column(name = "comp_plan_reason", length = 255)
    private String compPlanReason;

    // Comp-grant revert-target fix (migration V200) - the tenant's plan at the
    // moment its FIRST live comp grant was issued, so #revertComplimentaryGrant
    // can restore the true pre-grant plan instead of assuming FREE. Matters once
    // a comp grant can reach a tenant that wasn't already on FREE (e.g. a
    // site-wide promo landing on an existing paying PREMIUM customer) - reverting
    // them to FREE instead of back to PREMIUM would be a visible regression for
    // someone still being charged. Written once by #grantComplimentaryPlan (not
    // overwritten by a later re-grant/refresh while a grant is already active,
    // so a second grant can't clobber the true original plan), cleared by
    // #revertComplimentaryGrant. Null both before any grant and for any grant
    // already in flight before this column existed - the reverter falls back to
    // FREE when null, identical to today's behavior.
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_before_comp_grant", length = 32)
    private TenantPlan planBeforeCompGrant;

    // Expiry-reminder dedupe (migration V472): 0 = none sent, 1 = the ~14-day reminder sent, 2 =
    // the ~3-day
    // reminder sent. Reset by every (re-)grant and by the revert, so each grant gets its own
    // sequence.
    @Column(name = "comp_plan_reminder_stage", nullable = false)
    private int compPlanReminderStage;

    // Event-triggered 7-day Premium trial (migration V157, PRICING-MONETIZATION-
    // AUDIT-2026-07-31.md Â§5.4/B6) - the durable one-per-tenant claim record.
    // The trial's ACTIVE state lives in the comp_plan_* columns above (the grant
    // is issued through #grantComplimentaryPlan, so CompGrantExpiryReverter
    // reverts it to FREE at expiry exactly like an Academy comp), but the
    // reverter also NULLS those columns - these three are what remembers, forever,
    // that this tenant already used its one trial. Written once by
    // #claimEventTrial, never cleared. All three null == never claimed.
    @Column(name = "event_trial_claimed_at")
    private Instant eventTrialClaimedAt;

    @Column(name = "event_trial_expires_at")
    private Instant eventTrialExpiresAt;

    @Column(name = "event_trial_trigger", length = 64)
    private String eventTrialTrigger;

    // Referral program (migration V159, PRICING-MONETIZATION-AUDIT-2026-07-31.md
    // Â§8.5/D3) - give-1-month-get-1-month PREMIUM. Like the event trial above,
    // the reward itself is a comp grant (#grantComplimentaryPlan, reverted to
    // FREE by CompGrantExpiryReverter at expiry); these columns are the durable
    // referral bookkeeping that outlives the comp's expiry-time revert.
    //   referralCode: this tenant's own shareable code, lazily generated by
    //     ReferralService on first read, unique, never changed after.
    //   referredByTenantId/referralRedeemedAt: whose code this tenant redeemed
    //     and when - written once by #redeemReferral, never cleared (non-null
    //     referredByTenantId == this tenant has used its ONE redemption, ever).
    //     Also what the referrer's rolling-year 12-grant anti-abuse cap counts.
    @Column(name = "referral_code", length = 16)
    private String referralCode;

    @Column(name = "referred_by_tenant_id")
    private UUID referredByTenantId;

    @Column(name = "referral_redeemed_at")
    private Instant referralRedeemedAt;

    protected TenantPlanState() {
        // JPA
    }

    protected TenantPlanState(TenantPlan plan) {
        this.plan = plan;
    }

    /**
     * Platform-admin plan override (Subscription Plan management page, {@code web-admin}) - a
     * super-admin directly setting this tenant's {@link TenantPlan}, independent of the gateway
     * subscription lifecycle ({@link #recordGatewaySubscription}). Deliberately does NOT touch
     * {@code gatewaySubscriptionId}/{@code subscriptionStatus}/{@code currentPeriodEnd} - those
     * describe what Razorpay's webhook last reported about a *paid* subscription, which this
     * override doesn't create or cancel. Every plan-gated read in this codebase ({@link
     * TenantLimitsServiceImpl#plan}, {@code AgentChatService}'s FREE-tier routing, etc.) reads
     * {@code Tenant.plan} fresh from the database on every call - no cache to invalidate, so this
     * takes effect on the tenant's very next request.
     */
    public void changePlan(TenantPlan plan) {
        this.plan = plan;
    }

    /**
     * Academy Fast-Track Scholarship one-click fulfilment (see this class's {@code
     * compPlanExpiresAt} field comment and {@code CompGrantService}) - flip this tenant to a
     * complimentary paid tier at no charge, recording when it was granted, when it lapses, and why.
     * Idempotent-friendly: re-granting simply overwrites the granted/expires/reason fields, so a
     * second grant refreshes/extends the expiry rather than stacking. Like {@link #changePlan},
     * deliberately leaves every {@code gateway*}/{@code subscriptionStatus}/ {@code
     * currentPeriodEnd} column untouched - a comp grant is NOT a paid subscription. Every
     * plan-gated read ({@code TenantLimitsServiceImpl#plan} etc.) reads {@code Tenant.plan} fresh
     * from the DB, so this takes effect on the tenant's very next request.
     *
     * <p>{@code planBeforeCompGrant} (see field comment) is captured ONLY when there is no grant
     * currently live ({@code compPlanExpiresAt == null}) - a refresh/extend of an already-active
     * grant must not clobber the true original plan with the currently-comped one, or {@link
     * #revertComplimentaryGrant} would restore the wrong value.
     */
    public void grantComplimentaryPlan(
            TenantPlan plan, Instant grantedAt, Instant expiresAt, String reason) {
        if (this.compPlanExpiresAt == null) {
            this.planBeforeCompGrant = this.plan;
        }
        this.plan = plan;
        this.compPlanGrantedAt = grantedAt;
        this.compPlanExpiresAt = expiresAt;
        this.compPlanReason = reason;
        this.compPlanReminderStage = 0;
    }

    /**
     * Event-triggered 7-day Premium trial (PRICING-MONETIZATION-AUDIT-2026-07-31.md Â§5.4/B6) -
     * records the one-per-tenant trial claim. Called by {@code TrialGrantService#claim} in the same
     * transaction as {@link #grantComplimentaryPlan} (which is what actually flips the plan); this
     * method only writes the durable "already claimed" record that outlives the comp grant's
     * expiry-time revert. Deliberately no unclaim/reset counterpart - one trial per tenant, ever.
     */
    public void claimEventTrial(String trigger, Instant claimedAt, Instant expiresAt) {
        this.eventTrialTrigger = trigger;
        this.eventTrialClaimedAt = claimedAt;
        this.eventTrialExpiresAt = expiresAt;
    }

    public Instant getEventTrialClaimedAt() {
        return eventTrialClaimedAt;
    }

    public Instant getEventTrialExpiresAt() {
        return eventTrialExpiresAt;
    }

    public String getEventTrialTrigger() {
        return eventTrialTrigger;
    }

    /**
     * Referral program (PRICING-MONETIZATION-AUDIT-2026-07-31.md Â§8.5/D3) - assigns this tenant's
     * own shareable referral code. Called once by {@code ReferralService} the first time the tenant
     * reads {@code GET /api/tenant/referral} (lazy generation); a no-op-guard here keeps a racing
     * second read from silently rotating an already-shared code.
     */
    public void assignReferralCode(String referralCode) {
        if (this.referralCode != null) {
            return; // Code already assigned (and possibly already shared) - never rotate it.
        }
        this.referralCode = referralCode;
    }

    /**
     * Referral program - records this tenant's one-and-only redemption of another tenant's code.
     * Called by {@code ReferralService#redeem} in the same transaction as {@link
     * #grantComplimentaryPlan} (which is what actually flips the plan); this only writes the
     * durable "already redeemed" record that outlives the comp grant's expiry-time revert.
     * Deliberately no un-redeem counterpart - one redemption per tenant, ever.
     */
    public void redeemReferral(UUID referredByTenantId, Instant redeemedAt) {
        this.referredByTenantId = referredByTenantId;
        this.referralRedeemedAt = redeemedAt;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public UUID getReferredByTenantId() {
        return referredByTenantId;
    }

    public Instant getReferralRedeemedAt() {
        return referralRedeemedAt;
    }

    /**
     * Called by {@code CompGrantExpiryReverter} once {@link #compPlanExpiresAt} has passed: restore
     * {@link #planBeforeCompGrant} (the plan captured when the grant was first issued) and clear
     * the comp-grant bookkeeping. Falls back to {@code FREE} when {@code planBeforeCompGrant} is
     * null - true both for the original Academy/trial/referral callers (which only ever grant to a
     * tenant already on FREE, so restoring FREE is correct either way) and for any grant already in
     * flight before {@code planBeforeCompGrant} existed. A tenant's paid {@code gateway*} state (a
     * real Razorpay subscription, separate from any comp grant) is untouched here regardless. A
     * no-op-safe call: if there is no live grant it still just re-lands the tenant on its current
     * plan with the comp fields already null.
     */
    public void revertComplimentaryGrant() {
        this.plan = planBeforeCompGrant != null ? planBeforeCompGrant : TenantPlan.FREE;
        this.planBeforeCompGrant = null;
        this.compPlanGrantedAt = null;
        this.compPlanExpiresAt = null;
        this.compPlanReason = null;
        this.compPlanReminderStage = 0;
    }

    /** Which expiry reminder was last sent for the live grant (see the field comment). */
    public int getCompPlanReminderStage() {
        return compPlanReminderStage;
    }

    /**
     * Records that the reminder for {@code stage} (1 = ~14 days, 2 = ~3 days) went out; never moves
     * backwards.
     */
    public void markCompReminderSent(int stage) {
        if (stage > this.compPlanReminderStage) {
            this.compPlanReminderStage = stage;
        }
    }

    /**
     * The plan the tenant returns to when the live grant lapses; null = FREE. Used to skip
     * "subscribe" nudges for payers.
     */
    public TenantPlan getPlanBeforeCompGrant() {
        return planBeforeCompGrant;
    }

    public Instant getCompPlanExpiresAt() {
        return compPlanExpiresAt;
    }

    public Instant getCompPlanGrantedAt() {
        return compPlanGrantedAt;
    }

    public String getCompPlanReason() {
        return compPlanReason;
    }

    /**
     * Records a newly-created gateway subscription at signup time (Stage 3) - {@code
     * gatewayCustomerId} is deliberately not a parameter here, see this class's field-level comment
     * above; it gets set later, directly, once Stage 4's webhook confirms the gateway has linked a
     * customer.
     */
    public void recordGatewaySubscription(
            String paymentGateway,
            String gatewaySubscriptionId,
            String subscriptionStatus,
            Instant trialEndsAt) {
        this.paymentGateway = paymentGateway;
        this.gatewaySubscriptionId = gatewaySubscriptionId;
        this.subscriptionStatus = subscriptionStatus;
        this.trialEndsAt = trialEndsAt;
    }

    /**
     * #811 - a tenant-admin's own self-serve upgrade, initiated by {@code TenantUpgradeService}: a
     * new Razorpay subscription has just been created for {@code targetPlan}, awaiting the
     * customer's authorization. Deliberately does NOT touch {@link #plan} yet - that only happens
     * once {@code RazorpayWebhookService} confirms the subscription actually activated (see {@link
     * #confirmUpgrade}). Overwrites any previous pending upgrade (e.g. the customer abandoned an
     * earlier attempt and tried again) - Razorpay's own {@code expire_by} eventually times out an
     * abandoned subscription on its side regardless.
     */
    public void beginUpgrade(TenantPlan targetPlan, String pendingGatewaySubscriptionId) {
        this.pendingPlan = targetPlan;
        this.pendingGatewaySubscriptionId = pendingGatewaySubscriptionId;
        this.pendingUpgradeStartedAt = Instant.now();
    }

    /**
     * #811 - called only from {@code RazorpayWebhookService} once Razorpay reports the pending
     * subscription above actually activated/charged. Promotes the pending subscription into this
     * tenant's real plan/ subscription state and clears the pending fields - after this call,
     * {@link #getGatewaySubscriptionId()} refers to the upgrade subscription that just activated,
     * not whatever the signup-time trial subscription was.
     */
    public void confirmUpgrade(String subscriptionStatus) {
        confirmUpgrade(subscriptionStatus, null);
    }

    /**
     * {@link #confirmUpgrade(String)} with the gateway name the confirming event arrived on. The
     * dedicated Razorpay webhook path has always left {@code paymentGateway} alone (it was written
     * at signup); the generic {@code /api/billing/webhook/{gateway}} path introduced with
     * multi-gateway support can carry an upgrade for ANY gateway, so the tenant's gateway column
     * must record which one it is - otherwise a Stripe-confirmed upgrade would leave the row
     * claiming (or nulling) Razorpay. A null gatewayName preserves the stored value.
     */
    public void confirmUpgrade(String subscriptionStatus, String gatewayName) {
        if (this.pendingPlan == null) {
            return; // Duplicate/late webhook delivery for an upgrade already confirmed (or never
            // initiated) - a no-op, not an error.
        }
        this.plan = this.pendingPlan;
        this.gatewaySubscriptionId = this.pendingGatewaySubscriptionId;
        this.subscriptionStatus = subscriptionStatus;
        if (gatewayName != null && !gatewayName.isBlank()) {
            this.paymentGateway = gatewayName;
        }
        this.pendingPlan = null;
        this.pendingGatewaySubscriptionId = null;
        this.pendingUpgradeStartedAt = null;
        // A paid upgrade supersedes any live comp grant: if the grant
        // bookkeeping were left live, CompGrantExpiryReverter would later
        // "revert" this freshly-PAID plan back to planBeforeCompGrant and a
        // paying customer would silently lose the tier they just bought.
        clearCompGrantBookkeeping();
    }

    /**
     * #811 - the pending subscription above was cancelled/halted before ever activating (customer
     * abandoned checkout, or a card was declined and never retried). Clears the pending fields
     * without touching {@link #plan} - the tenant simply stays on whatever plan it was already on.
     */
    public void cancelPendingUpgrade() {
        this.pendingPlan = null;
        this.pendingGatewaySubscriptionId = null;
        this.pendingUpgradeStartedAt = null;
    }

    /**
     * D5 - the tenant-admin asked to cancel their paid subscription and the gateway accepted a
     * cancel-at-cycle-end (see {@code TenantSubscriptionService#cancel}). Records intent only:
     * {@link #plan} stays paid - the customer keeps what they paid for until the cycle ends - and
     * the actual downgrade happens when the gateway's {@code subscription.cancelled} webhook
     * arrives ({@link #downgradeToFree}).
     *
     * @param subscriptionStatus the gateway's post-cancel status string (Razorpay reports the
     *     subscription still {@code "active"} until cycle end) - same raw-gateway-string posture
     *     this column has always had.
     */
    public void scheduleCancellation(String subscriptionStatus) {
        this.cancelScheduled = true;
        this.subscriptionStatus = subscriptionStatus;
    }

    /**
     * D5 - Razorpay reported {@code subscription.halted} on this tenant's LIVE paid subscription
     * (every charge retry failed): open the past-due grace window. Deliberately does NOT touch
     * {@link #plan} - an expired card should not vaporize a paying customer's workspace; {@link
     * #downgradeToFree} (driven by a later cancelled/completed event) is the only failed-payment
     * path that does. First halt wins: a re-delivered halted event never resets the grace clock.
     */
    public void markPastDue(String subscriptionStatus, Instant at) {
        if (this.pastDueSince == null) {
            this.pastDueSince = at;
        }
        this.subscriptionStatus = subscriptionStatus;
    }

    /**
     * D5 - the halted subscription above recovered (the customer fixed their payment method and
     * Razorpay reported activated/charged/resumed): close the grace window, keep the plan that
     * never stopped being paid.
     */
    public void clearPastDue(String subscriptionStatus) {
        this.pastDueSince = null;
        this.subscriptionStatus = subscriptionStatus;
    }

    /**
     * D5 - this tenant's LIVE paid subscription ended for good (Razorpay reported {@code
     * subscription.cancelled} - tenant-initiated via {@link #scheduleCancellation} or gateway-side
     * - or {@code subscription.completed}): drop the plan to FREE and close out the D5 bookkeeping.
     * {@code gatewaySubscriptionId}/{@code paymentGateway} are deliberately KEPT (not nulled) -
     * same support/debugging-reference posture as {@link Tenant#markErased}'s tombstone columns: they
     * record which subscription this tenant's paid era was, and a future re-upgrade overwrites them
     * via {@link #confirmUpgrade} anyway. Distinct from {@link #cancelPendingUpgrade} (a
     * never-activated checkout abandoned - plan untouched) and {@link #revertComplimentaryGrant} (a
     * comp grant lapsing - no gateway subscription involved).
     */
    public void downgradeToFree(String subscriptionStatus) {
        this.plan = TenantPlan.FREE;
        this.subscriptionStatus = subscriptionStatus;
        this.cancelScheduled = false;
        this.pastDueSince = null;
        // Same collision as #confirmUpgrade: a downgrade settles the plan at
        // FREE for good - leaving live comp-grant bookkeeping would let
        // CompGrantExpiryReverter later overwrite this landing plan with
        // planBeforeCompGrant (which could even be a paid tier).
        clearCompGrantBookkeeping();
    }

    /** Nulls the whole comp-grant block; callers decide when the grant era ends. */
    private void clearCompGrantBookkeeping() {
        this.compPlanExpiresAt = null;
        this.compPlanGrantedAt = null;
        this.compPlanReason = null;
        this.planBeforeCompGrant = null;
    }

    public boolean isCancelScheduled() {
        return cancelScheduled;
    }

    public Instant getPastDueSince() {
        return pastDueSince;
    }

    /** D5 - is the failed-payment grace window currently open? See {@link #markPastDue}. */
    public boolean isPastDue() {
        return pastDueSince != null;
    }

    public TenantPlan getPendingPlan() {
        return pendingPlan;
    }

    public String getPendingGatewaySubscriptionId() {
        return pendingGatewaySubscriptionId;
    }

    /** V471 - when the pending upgrade was initiated; see the field comment. */
    public Instant getPendingUpgradeStartedAt() {
        return pendingUpgradeStartedAt;
    }

    public TenantPlan getPlan() {
        return plan;
    }

    public String getPaymentGateway() {
        return paymentGateway;
    }

    public String getGatewayCustomerId() {
        return gatewayCustomerId;
    }

    public String getGatewaySubscriptionId() {
        return gatewaySubscriptionId;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    /**
     * Erasure completeness - the plan-side part of {@link Tenant#scrubPersonalData}: the referral
     * code and the operator's comp-grant note (which can quote a person).
     */
    protected void scrubPlanPersonalData() {
        this.referralCode = null;
        this.compPlanReason = null;
    }
}
