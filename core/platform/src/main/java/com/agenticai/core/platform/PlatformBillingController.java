package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only platform billing view - {@code /api/platform/billing/**}, gated by
 * the blanket {@code /api/platform/** -> ROLE_platform-admin} rule in
 * {@code SecurityConfig}. GET endpoints only: no refunds, charges, cancels or
 * plan changes live here (plan overrides and the site-wide promo stay in
 * {@code PlatformController}). See {@link PlatformBillingService} for where the
 * data comes from and what is deliberately not available.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/billing")
@Profile("security")
public class PlatformBillingController {

    private final PlatformBillingService service;

    public PlatformBillingController(PlatformBillingService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public PlatformBillingService.BillingSummary summary() {
        return service.summary();
    }

    @GetMapping("/subscriptions")
    public PlatformBillingService.SubscriptionList subscriptions(
        @RequestParam(name = "plan", required = false) String plan,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.subscriptions(plan, state, q, limit);
    }

    @GetMapping("/comp-grants")
    public PlatformBillingService.CompGrantList compGrants(
        @RequestParam(name = "limit", defaultValue = "200") int limit) {
        return service.compGrants(limit);
    }

    @GetMapping("/invoices")
    public PlatformBillingService.InvoiceList invoices(
        @RequestParam(name = "tenantId", required = false) String tenantId,
        @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.invoices(tenantId, limit);
    }
}
