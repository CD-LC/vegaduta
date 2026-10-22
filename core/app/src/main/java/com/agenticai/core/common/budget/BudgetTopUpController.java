package com.agenticai.core.common.budget;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 Phase P2 - the tenant-user-facing
 * "top up to continue" endpoints, the follow-through for the HTTP 402
 * {@code PerUserBudgetExhaustedException} that {@code GlobalExceptionHandler}
 * returns when a per-user dollar budget is exhausted.
 *
 * <p>Deliberately its OWN controller (not piled into {@code PlatformController})
 * and deliberately NOT under {@code /api/platform/**}: this is a normal
 * authenticated end-user action (paying to raise one's own budget), not a
 * cross-tenant platform-admin operation - so it lives under {@code /api/budget}
 * and is gated by the standard authenticated-user rule, not
 * {@code ROLE_platform-admin}. The admin-facing knobs for the budget itself
 * stay in {@code PlatformController} (GET/PUT {@code /per-user-budget}).
 *
 * <p>Identity mirrors the chat path exactly so the credit lands on the very
 * counter {@code checkBudget} reads: {@code tenantId} from
 * {@link TenantContext}, and {@code userKey} = the authenticated principal name
 * (the same {@code subjectKey} {@code AgentController#chat} computes).
 *
 * <p>{@code @Profile("security")} because it depends on the security-gated
 * billing beans, same as {@code RazorpayWebhookController}.
 */
@RestController
@RequestMapping("/api/budget/top-up")
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class BudgetTopUpController {

    private final BudgetTopUpService budgetTopUpService;

    public BudgetTopUpController(BudgetTopUpService budgetTopUpService) {
        this.budgetTopUpService = budgetTopUpService;
    }

    @GetMapping("/options")
    public BudgetTopUpOptionsResponse options() {
        return budgetTopUpService.options();
    }

    @PostMapping
    public BudgetTopUpPurchaseResponse purchase(Principal principal, @RequestBody BudgetTopUpPurchaseRequest request) {
        return budgetTopUpService.purchase(
            requireTenantId(), requireUserKey(principal), request.modality(), request.amountUsd()
        );
    }

    @PostMapping("/confirm")
    public BudgetTopUpConfirmResponse confirm(Principal principal, @RequestBody BudgetTopUpConfirmRequest request) {
        return budgetTopUpService.confirm(requireTenantId(), requireUserKey(principal), request);
    }

    private static String requireTenantId() {
        return TenantContext.current()
            .map(TenantContext::getTenantId)
            .filter(StringUtils::hasText)
            .orElseThrow(() -> new IllegalArgumentException("No tenant context for this request."));
    }

    private static String requireUserKey(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new IllegalArgumentException("An authenticated user is required to top up a budget.");
        }
        return principal.getName();
    }
}
