package com.agenticai.core.platform;

import com.agenticai.core.security.TenantPlan;

/**
 * Body for {@code PUT /api/platform/tenants/{tenantId}/plan} - the
 * Subscription Plan management page's (web-admin) one write action. Just the
 * target plan; the tenant id itself comes from the path, matching every
 * other {@code PUT .../{id}} shape already in {@code PlatformController}
 * (e.g. {@code hosted-llm-providers/{id}}).
 */
public record PlatformTenantPlanUpdateRequest(TenantPlan plan) {
}
