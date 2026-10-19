package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.dto.TenantSubscriptionStatusResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - the tenant-console side
 * of subscription lifecycle, sibling of {@link TenantUpgradeController}.
 *
 * <p>Auth mirrors that controller's posture exactly: {@code POST
 * /api/tenant/subscription/cancel} is gated by {@code SecurityConfig}'s
 * {@code ROLE_tenant-admin} rule (ending the workspace's paid subscription
 * is at least as consequential as starting one); the {@code GET} status view
 * falls through to plain {@code anyRequest().authenticated()} - any tenant
 * member may see the plan state, same as {@code GET /api/tenant/settings}.
 */
@RestController
@RequestMapping("/api/tenant/subscription")
@Profile("security")
public class TenantSubscriptionController {

    private final TenantSubscriptionService tenantSubscriptionService;

    public TenantSubscriptionController(TenantSubscriptionService tenantSubscriptionService) {
        this.tenantSubscriptionService = tenantSubscriptionService;
    }

    @GetMapping
    public TenantSubscriptionStatusResponse status() {
        return tenantSubscriptionService.status();
    }

    @PostMapping("/cancel")
    public TenantSubscriptionStatusResponse cancel() {
        return tenantSubscriptionService.cancel();
    }
}
