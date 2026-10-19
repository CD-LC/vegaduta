package com.agenticai.core.platform;

import com.agenticai.core.security.DataResidencyRegion;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - body for
 * {@code PUT /api/platform/tenants/{tenantId}/data-residency}. {@code
 * region} may be {@code null} to clear back to unspecified - same "just the
 * target value, tenant id comes from the path" shape {@link
 * PlatformTenantPlanUpdateRequest} already uses.
 */
public record PlatformTenantDataResidencyUpdateRequest(DataResidencyRegion region) {
}
