package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing (non-admin) counterpart to {@code PlatformController}'s
 * {@code GET /api/platform/feature-flags} - that surface is the ADMIN read
 * (list every flag, see who got bucketed which way); this is the one thing a
 * regular signed-in tenant member needs and couldn't get before: "what's my
 * own evaluated variant for flag X, right now."
 *
 * <p>Added for the "edge (on-device) option per AI feature" effort - the
 * first caller is the web client's Ask AI helper, deciding whether to
 * attempt an on-device draft before falling back to the hosted
 * {@code POST /api/assist/draft} call, gated by a flag such as
 * {@code edge.ask-ai}. Deliberately generic by key (not edge-specific) so
 * Quick Task/Diagnose/App Builder's own future edge flags reuse this same
 * endpoint rather than each growing their own.
 *
 * <p>No new decision logic here - delegates straight to {@link
 * FeatureFlagService#evaluate}, which already fails open to {@code
 * "control"} on a missing/disabled flag or any error (see that method's own
 * javadoc) - exactly the "a broken experiment must never break the real
 * feature it rides along on" contract every caller of this service gets for
 * free.
 *
 * <p>{@code /api/tenant/**} GET requests fall through to {@code
 * SecurityConfig}'s {@code anyRequest().authenticated()} rule (see {@code
 * TenantSettingsController}'s own javadoc for the same posture) - readable
 * by any authenticated tenant member, not just {@code ROLE_tenant-admin},
 * since this is a read-only check any signed-in user's own UI needs.
 */
@RestController
@RequestMapping("/api/tenant/feature-flags")
@Profile("security")
public class TenantFeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public TenantFeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    public record FeatureFlagEvaluationResponse(String key, String variant) {
    }

    @GetMapping("/{key}/evaluate")
    public FeatureFlagEvaluationResponse evaluate(@PathVariable String key) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/feature-flags request - SecurityConfig should have blocked this"));
        return new FeatureFlagEvaluationResponse(key, featureFlagService.evaluate(key, tenantId));
    }
}
