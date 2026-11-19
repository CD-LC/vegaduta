package com.agenticai.core.platform;

import com.agenticai.core.platform.PlatformTenantDetailService.TenantDetail;
import com.agenticai.core.platform.PlatformTenantDetailService.TenantRef;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-tenant control page backend - {@code /api/platform/tenant-detail/**}, gated by the
 * blanket {@code /api/platform/** -> ROLE_platform-admin} rule. All logic lives in {@link
 * PlatformTenantDetailService}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/tenant-detail")
@Profile("security")
public class PlatformTenantDetailController {

    private final PlatformTenantDetailService service;

    public PlatformTenantDetailController(PlatformTenantDetailService service) {
        this.service = service;
    }

    /**
     * Picker roster: every non-demo tenant INCLUDING lifecycle states the main roster hides
     * (SUSPENDED, PROVISIONING, PROVISIONING_FAILED). ERASED tenants are omitted. No per-tenant
     * counts here - that is the detail call.
     */
    @GetMapping
    public List<TenantRef> list() {
        return service.list();
    }

    @GetMapping("/{tenantId}")
    public TenantDetail get(@PathVariable("tenantId") UUID tenantId) {
        return service.get(tenantId);
    }
}
