package com.agenticai.core.tenancy;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class in the Stage 5 signup path allowed to hold a database
 * transaction open - every actual DB write during provisioning goes through
 * one of these three short, focused methods. {@link TenantProvisioningService}
 * itself is deliberately NOT {@code @Transactional}, for the exact reason
 * {@code HumanApprovalGateService} isn't (see its javadoc from the earlier
 * design-review pass): the Keycloak Admin API call and the schema-creation/
 * Flyway-migration step in between {@link #createProvisioningTenant} and
 * {@link #activate} are slow, external, and failure-prone - none of that
 * should happen while holding open a database connection/transaction.
 */
@Component
class TenantProvisioningRecorder {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;

    TenantProvisioningRecorder(TenantRepository tenantRepository, AppUserRepository appUserRepository) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
    }

    /**
     * Admin-tunable signup trial (platform_setting rows, V473). Field-injected and optional so the two-argument
     * constructor the tests rely on is unchanged: absent (plain unit tests) = the built-in defaults below, i.e. the
     * behaviour from before the settings existed.
     */
    @Autowired(required = false)
    private PlatformSettingsService platformSettings;

    static final String KEY_TRIAL_ENABLED = "platform.signup-trial.enabled";
    static final String KEY_TRIAL_MONTHS = "platform.signup-trial.months";
    static final String KEY_TRIAL_PLAN = "platform.signup-trial.plan";
    static final String KEY_TRIAL_FREE_UNTIL = "platform.signup-trial.free-until";
    static final int MAX_TRIAL_MONTHS = 36;

    /** The comp grant a brand-new signup receives. */
    record SignupGrant(TenantPlan plan, Instant expiresAt) {
    }

    /**
     * What a signup at {@code grantedAt} gets, from the platform settings: nothing when disabled or 0 months, else
     * the configured plan (UNLIMITED or PREMIUM) until {@code months} calendar months later - or until the optional
     * fixed date when that is later (a launch-promotion floor). Without a settings service (unit tests) it is the
     * original hard-coded behaviour. Never throws on a bad setting: an unreadable value falls back to the default.
     */
    Optional<SignupGrant> signupGrant(Instant grantedAt) {
        if (platformSettings == null) {
            return Optional.of(new SignupGrant(SIGNUP_TRIAL_PLAN, signupTrialExpiry(grantedAt)));
        }
        if (!platformSettings.getBoolean(KEY_TRIAL_ENABLED, true)) {
            return Optional.empty();
        }
        int months = Math.max(0, Math.min(MAX_TRIAL_MONTHS, platformSettings.getInt(KEY_TRIAL_MONTHS, SIGNUP_TRIAL_MONTHS)));
        if (months == 0) {
            return Optional.empty();
        }
        TenantPlan plan = SIGNUP_TRIAL_PLAN;
        String rawPlan = platformSettings.getString(KEY_TRIAL_PLAN, SIGNUP_TRIAL_PLAN.name());
        if (rawPlan != null && rawPlan.trim().equalsIgnoreCase(TenantPlan.PREMIUM.name())) {
            plan = TenantPlan.PREMIUM;
        }
        Instant expiry = grantedAt.atOffset(java.time.ZoneOffset.UTC).plusMonths(months).toInstant();
        Instant floor = parseInstant(platformSettings.getString(KEY_TRIAL_FREE_UNTIL, ""));
        if (floor != null && floor.isAfter(expiry)) {
            expiry = floor;
        }
        return Optional.of(new SignupGrant(plan, expiry));
    }

    /** ISO instant or ISO date (read as UTC midnight); blank or unparseable = null (no floor). */
    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // fall through to date forms
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * {@code saveAndFlush}, not {@code save} - forces the INSERT (and, with
     * it, migration V9's unique-name constraint check) to run synchronously
     * inside this method's transaction, not deferred to commit time outside
     * it. That matters here specifically because the caller needs to catch
     * a losing race against a concurrent identical-name signup as a clean
     * {@link DuplicateTenantException}, which only works if the constraint
     * violation surfaces before this method returns.
     */
    @Transactional
    Tenant createProvisioningTenant(
        String name, TenantPlan plan, String schemaName, String keycloakRealm,
        AccountType accountType, ExperienceMode experienceMode, boolean newsOptIn
    ) {
        try {
            Tenant tenant = new Tenant(name, plan, schemaName, keycloakRealm, accountType, experienceMode);
            // Signup consent (migration V123) - the OPTIONAL news/product-updates
            // opt-in, captured in the same write as everything else the signup
            // form collected. Not a constructor parameter (that would ripple
            // through every existing `new Tenant(...)` test call site for a
            // field that, unlike the others there, is genuinely optional/
            // defaultable) - a plain setter before the one saveAndFlush below.
            tenant.setNewsOptIn(newsOptIn);
            return tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTenantException("An organization named '" + name + "' is already registered.");
        }
    }

    /** New-signup UNLIMITED trial, per direct product decision (2026-09-01,
     * ahead of the public launch): every brand-new self-service tenant gets
     * every feature before landing on FREE, not FREE from day one. Rides the
     * same comp-grant primitive/expiry-revert machinery
     * {@link TrialGrantService}'s event-triggered 7-day Premium trial
     * already uses ({@link Tenant#grantComplimentaryPlan} /
     * {@link CompGrantExpiryReverter}) - deliberately a separate constant
     * pair rather than reusing TrialGrantService's own 7-day/PREMIUM
     * constants, since that service's growth-loop trial (claimed later, at a
     * moment of intent) is a distinct, already-tuned mechanic this must not
     * disturb.
     *
     * <p><b>30 days -> 3 months (2026-09-12)</b>, direct instruction from
     * the maintainer ("everything free, for first 3 months of every consumer").
     *
     * <p><b>Calendar months, not 90 days.</b> "3 months" is a promise made to
     * a human in the signup copy and in {@code CompGrantBanner}'s "until
     * {date}" line, so it has to mean what a human means - signing up on
     * 31 January must expire on 30 April, not on a date arithmetic picked.
     * {@link Instant} cannot add month units at all ({@code Instant.plus} with
     * a month-based amount throws {@code UnsupportedTemporalTypeException}),
     * which is why this is an {@code int} of months resolved through
     * {@link java.time.ZoneOffset#UTC} at grant time rather than a
     * {@link Duration} - the same convention {@code CompGrantService} already
     * uses for its Academy scholarship grants.
     *
     * <p><b>This is a bonus on top of FREE, not a replacement for it.</b> FREE
     * remains a real, permanent, card-free plan; when this grant expires
     * {@link CompGrantExpiryReverter} reverts to {@code plan_before_comp_grant}
     * (FREE for a self-service signup, captured by
     * {@link Tenant#grantComplimentaryPlan}), so the tenant lands on a working
     * free plan rather than a wall. That is what keeps the public "Free is a
     * real plan, not a trial - no card, no clock" copy true, and it is load
     * bearing: the paid path (Razorpay) is not proven against a live account
     * yet, so an expiry that demanded payment would have nowhere to send them.
     *
     * <p><b>Cost note.</b> UNLIMITED lifts {@code FreeTierUsageLimiterService}'s
     * hosted-LLM caps by the unlimited multiplier for the whole window, and
     * that is the platform's own model spend (unlike WhatsApp, which is
     * bring-your-own-credential and costs us nothing - see V225's header).
     * Lengthening this window multiplies that exposure; the caps themselves
     * are admin-tunable via {@code PlatformSettingsService}. */
    static final int SIGNUP_TRIAL_MONTHS = 3;
    static final TenantPlan SIGNUP_TRIAL_PLAN = TenantPlan.UNLIMITED;
    static final String SIGNUP_TRIAL_REASON = "signup-trial";

    /**
     * Launch promotion, direct product decision from the maintainer (2026-09-17):
     * "till 1st Jan 2027 ... everything, all plans is free for all users who
     * gets registered". Every self-service signup is UNLIMITED until this
     * instant, not just for {@link #SIGNUP_TRIAL_MONTHS} months.
     *
     * <p>UTC midnight, matching {@link #signupTrialExpiry}'s existing UTC
     * convention, so the promise lands on the same calendar day everywhere
     * rather than shifting by timezone.
     */
    static final Instant SIGNUP_FREE_UNTIL = Instant.parse("2027-01-01T00:00:00Z");

    /**
     * Grant expiry for a signup at {@code grantedAt}: the LATER of the launch
     * promotion ({@link #SIGNUP_FREE_UNTIL}) and the standing
     * {@link #SIGNUP_TRIAL_MONTHS}-month grant.
     *
     * <p><b>Why the later of the two, rather than just the promotion date.</b>
     * A flat "free until 1 Jan 2027" would QUIETLY SHORTEN the grant for anyone
     * signing up near that date - a 1 November signup would get two months
     * where today it gets three, and a 15 December signup barely a fortnight.
     * That is a regression dressed as a promotion. Taking the maximum means the
     * three-month floor still holds for late joiners, and it also makes this
     * function safe AFTER the promotion lapses: once the fixed date is in the
     * past it stops binding and the original behaviour resumes with no code
     * change and no cliff where signups suddenly get an already-expired grant.
     *
     * <p>Calendar months, not 90 days - see {@link #SIGNUP_TRIAL_MONTHS} for
     * why that distinction is load-bearing for the copy.
     *
     * <p><b>Cost.</b> The cost note on {@link #SIGNUP_TRIAL_MONTHS} applies with
     * more force here: this lifts the hosted-LLM caps for every new tenant until
     * 2027, which is the platform's own model spend. The caps themselves remain
     * admin-tunable via {@code PlatformSettingsService}, and that is the lever
     * to reach for if the exposure bites - not silently shortening the window
     * people were promised.
     */
    static Instant signupTrialExpiry(Instant grantedAt) {
        Instant standingGrant =
            grantedAt.atOffset(java.time.ZoneOffset.UTC).plusMonths(SIGNUP_TRIAL_MONTHS).toInstant();
        return standingGrant.isAfter(SIGNUP_FREE_UNTIL) ? standingGrant : SIGNUP_FREE_UNTIL;
    }

    /**
     * Final step of a successful signup - flips the tenant to {@code ACTIVE},
     * records the gateway-subscription fields,
     * creates its first {@link AppUser} row, and (for a FREE self-service/
     * social signup - {@code paymentGateway == null}, both real callers of
     * this method) grants the new-signup UNLIMITED trial above - all in the
     * same transaction, so no reader can ever observe an {@code ACTIVE}
     * tenant with zero users, a missing subscription reference, or (for a
     * signup that should have one) a not-yet-applied trial grant.
     */
    @Transactional
    void activate(
        UUID tenantId, String keycloakUserId, String email,
        String paymentGateway, String gatewaySubscriptionId, String subscriptionStatus, Instant trialEndsAt
    ) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " vanished mid-provisioning"));
        tenant.markActive();
        tenant.recordGatewaySubscription(paymentGateway, gatewaySubscriptionId, subscriptionStatus, trialEndsAt);
        if (paymentGateway == null) {
            Instant grantedAt = Instant.now();
            signupGrant(grantedAt).ifPresent(grant ->
                tenant.grantComplimentaryPlan(grant.plan(), grantedAt, grant.expiresAt(), SIGNUP_TRIAL_REASON));
        }
        appUserRepository.save(new AppUser(tenantId, keycloakUserId, email, UserRole.TENANT_ADMIN));
    }

    @Transactional
    void markFailed(UUID tenantId) {
        // B4: a failed provisioning attempt must not keep a user row either (retries and
        // GET /api/account would otherwise trip over a half-set-up member).
        appUserRepository.findByTenantId(tenantId).forEach(appUserRepository::delete);
        tenantRepository.findById(tenantId).ifPresent(Tenant::markProvisioningFailed);
    }

    /**
     * Removes the leftover of a FAILED signup attempt (B4) so the same organization name / email can sign up
     * again: its user rows and the tenant row itself. Only ever acts on a {@code PROVISIONING_FAILED} tenant -
     * a live or in-flight tenant is never touched.
     */
    @Transactional
    boolean discardFailedTenant(UUID tenantId) {
        Optional<Tenant> tenant = tenantRepository.findById(tenantId);
        if (tenant.isEmpty() || tenant.get().getStatus() != TenantStatus.PROVISIONING_FAILED) {
            return false;
        }
        appUserRepository.findByTenantId(tenantId).forEach(appUserRepository::delete);
        tenantRepository.delete(tenant.get());
        tenantRepository.flush();
        return true;
    }

    /**
     * Undoes {@link #activate}'s app-side bookkeeping for a tenant whose
     * Razorpay authorization was never completed within
     * {@code AbandonedSignupCleanupJob}'s window (Stage 3) - deletes the
     * {@link AppUser} row {@link #activate} created (never created in the
     * ordinary provisioning-failure path, since that fails before
     * {@link #activate} ever runs, so it needs its own explicit cleanup
     * here) and marks the tenant {@code PROVISIONING_FAILED}, same terminal
     * state the ordinary failure path uses.
     */
    @Transactional
    void rollbackActivated(UUID tenantId) {
        appUserRepository.findByTenantId(tenantId).forEach(appUserRepository::delete);
        tenantRepository.findById(tenantId).ifPresent(Tenant::markProvisioningFailed);
    }
}
