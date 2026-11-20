package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.dto.TenantSettingsResponse;
import com.agenticai.core.tenancy.dto.UpdateExperienceModeRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the "which experience do you want" control in Admin Overview and the account-type/limits
 * summary shown there - {@code accountType} itself is read-only here (see {@code
 * Tenant.accountType}'s javadoc for why it's a signup-time-only decision); only {@code
 * experienceMode} can change after the fact. Gated by {@code SecurityConfig}'s {@code
 * /api/tenant/**} -> {@code ROLE_tenant-admin} rule, same posture as {@code UserAdminController}.
 */
@RestController
@RequestMapping("/api/tenant/settings")
@Profile("security")
public class TenantSettingsController {

    private final TenantSettingsService settingsService;

    public TenantSettingsController(TenantSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public TenantSettingsResponse get() {
        return settingsService.get(tenantId());
    }

    @PutMapping
    public TenantSettingsResponse updateExperienceMode(
            @Valid @RequestBody UpdateExperienceModeRequest request) {
        return settingsService.updateExperienceMode(tenantId(), request);
    }

    private String tenantId() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No TenantContext for an authenticated /api/tenant/settings request - SecurityConfig should have blocked this"));
    }
}
