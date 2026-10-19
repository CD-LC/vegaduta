package com.agenticai.core.agents.quota;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.chat.FimCompletionEntitlements;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one implementation of {@link FimCompletionEntitlements} - the bean whose absence makes {@code
 * POST /api/completions/fim} refuse every request.
 *
 * <p>It lives here, in {@code core/agents}, for the reason the port's own javadoc gives: the two
 * meters it delegates to ({@link FreeTierUsageLimiterService}, {@link PerUserBudgetService}) are in
 * this module, and this module already depends on {@code core/chat}, so the dependency can only
 * point this way without closing a Maven cycle.
 *
 * <p><b>This class contains no counting logic and must never grow any.</b> Every number it moves
 * comes from the same two services every hosted chat turn already charges, called in the same order
 * and with the same failure handling as {@code FreeTierChatClientService#buildChainForFreeTenant} -
 * an atomic token reservation, then an atomic dollar reservation, with both released together if
 * any later step throws.
 *
 * <p><b>Why this is metered at all, for every plan.</b> The FIM endpoint does not use a tenant's
 * own key: it resolves the cheapest row of the PLATFORM-hosted pool, so every completion spends the
 * platform's money regardless of the caller's plan. It sits in a keystroke loop, which is the
 * highest-frequency request shape this product has. Unmetered, one editor left open overnight is a
 * bill; metered wrong in the other direction, a paying user is refused mid-keystroke. Hence: no
 * plan check here, and the same meters as everything else.
 *
 * <p><b>Thread-safety, which the port requires and is not decorative.</b> The service can hand a
 * lease to an in-flight call and terminate it from a pool worker after the originating request has
 * returned - so the lease captures tenant, subject, plan and both reserved amounts at {@link
 * #reserve} time on the request thread, and never reads request-scoped state afterwards. Both
 * delegate services take every one of those as an explicit parameter and read neither {@code
 * TenantContext} nor {@code SecurityContextHolder} themselves (checked, not assumed), so a terminal
 * call on another thread charges the right account. An {@link AtomicBoolean} enforces the port's
 * exactly-once rule; a second terminal call is dropped rather than double-refunding.
 */
@Service
public class FimCompletionEntitlementsAdapter implements FimCompletionEntitlements {

    /**
     * Completions draw from the TEXT sub-cap, the same one chat draws from. Deliberately NOT a new
     * modality: a per-user dollar window that a person can exhaust twice over - once by chatting,
     * once by typing in an editor - is two budgets wearing one name.
     */
    private static final PerUserBudgetService.Modality MODALITY =
            PerUserBudgetService.Modality.TEXT;

    private final FreeTierUsageLimiterService usageLimiterService;
    private final PerUserBudgetService perUserBudgetService;
    private final TenantLimits tenantLimits;

    public FimCompletionEntitlementsAdapter(
            FreeTierUsageLimiterService usageLimiterService,
            PerUserBudgetService perUserBudgetService,
            TenantLimits tenantLimits) {
        this.usageLimiterService = usageLimiterService;
        this.perUserBudgetService = perUserBudgetService;
        this.tenantLimits = tenantLimits;
    }

    /** Monthly hosted spend cap (V477); optional, setter-injected. */
    private volatile com.agenticai.core.agents.spendcap.SpendCapService spendCapService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpendCapService(
            com.agenticai.core.agents.spendcap.SpendCapService spendCapService) {
        this.spendCapService = spendCapService;
    }

    @Override
    public Lease reserve(int maxTokens) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("llm-call");
        if (spendCapService != null) {
            spendCapService.assertWithinCap(tenantId);
        }
        String subjectKey = subjectKey();
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;

        long reservedTokens = 0;
        long reservedMicroCents = 0;
        boolean perUserBudgeted = tenantId != null && subjectKey != null;
        try {
            reservedTokens =
                    usageLimiterService.checkAndReserveTokenBudget(tenantId, plan, maxTokens);
            if (perUserBudgeted) {
                // Priced off the token reservation just taken, for the same
                // reason chat prices it that way: no model is chosen yet at
                // reservation time (see checkAndReserveBudget's javadoc).
                reservedMicroCents =
                        perUserBudgetService.checkAndReserveBudget(
                                tenantId, subjectKey, MODALITY, reservedTokens, plan);
            }
        } catch (RuntimeException e) {
            release(
                    tenantId,
                    subjectKey,
                    plan,
                    reservedTokens,
                    reservedMicroCents,
                    perUserBudgeted);
            throw e;
        }
        return new ReservationLease(
                tenantId, subjectKey, plan, reservedTokens, reservedMicroCents, perUserBudgeted);
    }

    /**
     * The authenticated principal when the security profile is on, else the TenantContext subject -
     * the same resolution {@code VisionSessionController#subjectKey} uses, and it must stay the
     * same: a different key here would split one person's dollar window into two disconnected
     * buckets, one for chat and one for their editor. Null only when neither is present, which
     * switches the per-user gate off rather than charging a bucket nobody owns.
     */
    private String subjectKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && StringUtils.hasText(auth.getName())) {
            return auth.getName();
        }
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    /**
     * The full give-it-all-back path, shared by the reserve-time catch above and {@link
     * Lease#abandon}.
     */
    private void release(
            String tenantId,
            String subjectKey,
            TenantPlan plan,
            long reservedTokens,
            long reservedMicroCents,
            boolean perUserBudgeted) {
        usageLimiterService.reconcileTokenReservation(tenantId, reservedTokens, 0);
        if (perUserBudgeted) {
            perUserBudgetService.releaseSpendReservation(
                    tenantId, subjectKey, MODALITY, reservedMicroCents, plan);
        }
    }

    /**
     * Immutable except for the once-only guard - see the class javadoc on why every field is
     * captured rather than looked up later.
     */
    private final class ReservationLease implements Lease {

        private final String tenantId;
        private final String subjectKey;
        private final TenantPlan plan;
        private final long reservedTokens;
        private final long reservedMicroCents;
        private final boolean perUserBudgeted;
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        private ReservationLease(
                String tenantId,
                String subjectKey,
                TenantPlan plan,
                long reservedTokens,
                long reservedMicroCents,
                boolean perUserBudgeted) {
            this.tenantId = tenantId;
            this.subjectKey = subjectKey;
            this.plan = plan;
            this.reservedTokens = reservedTokens;
            this.reservedMicroCents = reservedMicroCents;
            this.perUserBudgeted = perUserBudgeted;
        }

        @Override
        public void settle(String model, Long promptTokens, Long completionTokens) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            long actualTokens =
                    (promptTokens == null ? 0 : promptTokens)
                            + (completionTokens == null ? 0 : completionTokens);
            usageLimiterService.reconcileTokenReservation(tenantId, reservedTokens, actualTokens);
            if (perUserBudgeted) {
                // Handles both directions itself: a reservation is corrected
                // to the real cost, and a call with no reservation is priced
                // and recorded outright (reconcileSpendReservationForCall
                // falls through to recordSpendForCall when reserved <= 0), so
                // usage is never silently free.
                perUserBudgetService.reconcileSpendReservationForCall(
                        tenantId,
                        subjectKey,
                        MODALITY,
                        reservedMicroCents,
                        model,
                        promptTokens,
                        completionTokens,
                        plan);
            }
        }

        @Override
        public void abandon() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            release(
                    tenantId,
                    subjectKey,
                    plan,
                    reservedTokens,
                    reservedMicroCents,
                    perUserBudgeted);
        }
    }
}
