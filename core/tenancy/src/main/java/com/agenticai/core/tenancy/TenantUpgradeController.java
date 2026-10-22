package com.agenticai.core.tenancy;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.dto.TenantUpgradeRequest;
import com.agenticai.core.tenancy.dto.TenantUpgradeOptionsResponse;
import com.agenticai.core.tenancy.dto.TenantUpgradeResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #811 - the tenant-console side of the real upgrade flow (see {@link
 * TenantUpgradeService}'s javadoc). Gated by {@code SecurityConfig}'s {@code
 * POST /api/tenant/upgrade -> ROLE_tenant-admin} rule, same posture as
 * {@link TenantSettingsController} - only a tenant-admin can commit their
 * workspace to a paid subscription, not any member.
 */
@RestController
@RequestMapping("/api/tenant/upgrade")
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class TenantUpgradeController {

    private final TenantUpgradeService tenantUpgradeService;

    public TenantUpgradeController(TenantUpgradeService tenantUpgradeService) {
        this.tenantUpgradeService = tenantUpgradeService;
    }

    /** What the upgrade dialog may offer (currencies/periods actually billable here). Readable by any signed-in member. */
    @GetMapping("/options")
    public TenantUpgradeOptionsResponse options(@RequestParam(required = false) String gateway) {
        return tenantUpgradeService.upgradeOptions(gateway);
    }

    @PostMapping
    public TenantUpgradeResponse upgrade(@Valid @RequestBody TenantUpgradeRequest request) {
        return tenantUpgradeService.initiateUpgrade(
            request.targetPlan(), request.gatewayName(), request.currency(), request.period(), request.couponCode()
        );
    }
}
