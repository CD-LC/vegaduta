package com.agenticai.core.platform.abuse;

import com.agenticai.core.platform.PlatformListPaging;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase P5 (Fraud & Anomaly) - the read/operate surface for the abuse/anomaly
 * detector. Deliberately a NEW controller under {@code /api/platform/**} (so it
 * inherits the existing {@code ROLE_platform-admin} rule in {@code
 * SecurityConfig} - the correct tier for fleet-wide fraud monitoring) rather
 * than piling onto {@code PlatformController}, keeping that already-large class
 * focused. Same conventions as {@code PlatformController}: {@code
 * @Profile("security")}, constructor injection, bare DTOs, {@code updatedBy}
 * sourced from the validated JWT subject in {@link TenantContext}.
 *
 * <p>All GETs are side-effect-free (they only compute/read scores and read
 * anomaly/throttle state). The one mutating action here is the REVERSIBLE
 * throttle clear; suspension is reversed via the existing {@code
 * POST /api/platform/tenants/{tenantId}/emergency-resume}. Threshold config is
 * edited through the existing {@code PUT /api/platform/settings/{key}} surface
 * (keys seeded by migration V120), so this controller exposes thresholds
 * read-only via {@link #config()} rather than duplicating a write path.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/abuse-monitor")
@Profile("security")
public class AbuseMonitorController {

    private final AbuseSignalService abuseSignalService;

    public AbuseMonitorController(AbuseSignalService abuseSignalService) {
        this.abuseSignalService = abuseSignalService;
    }

    /** Current risk scores across every active tenant's recently-active principals (WATCH+ only), highest first. */
    @GetMapping("/scores")
    public ResponseEntity<List<AbuseRiskScore>> scores(
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset
    ) {
        return PlatformListPaging.window(abuseSignalService.currentScores(), limit, offset);
    }

    /** On-demand tenant-wide score (spend/token signals only; no single user in scope). */
    @GetMapping("/scores/{tenantId}")
    public AbuseRiskScore tenantScore(@PathVariable UUID tenantId) {
        return abuseSignalService.score(tenantId, null);
    }

    /** On-demand score for one (tenant, user) principal. */
    @GetMapping("/scores/{tenantId}/{userId}")
    public AbuseRiskScore principalScore(@PathVariable UUID tenantId, @PathVariable String userId) {
        return abuseSignalService.score(tenantId, userId);
    }

    /** Recent anomalies (fingerprinted {@code ABUSE_ANOMALY} issues) across all active tenants, newest first. */
    @GetMapping("/anomalies")
    public ResponseEntity<List<AbuseAnomalyEntry>> anomalies(
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset
    ) {
        return PlatformListPaging.window(abuseSignalService.recentAnomalies(), limit, offset);
    }

    /** Currently-throttled principals (self-expiring, TTL-bounded). */
    @GetMapping("/throttled")
    public ResponseEntity<List<AbuseThrottleService.ThrottleEntry>> throttled(
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset
    ) {
        return PlatformListPaging.window(abuseSignalService.currentThrottled(), limit, offset);
    }

    /** Reverse a throttle for one principal (the reversible, audited un-throttle). */
    @PostMapping("/throttle/{tenantId}/{userId}/clear")
    public void clearThrottle(@PathVariable UUID tenantId, @PathVariable String userId) {
        abuseSignalService.clearThrottle(tenantId, userId, actor());
    }

    /** Reverse a tenant-wide throttle (principal {@code tenantId:*}). */
    @PostMapping("/throttle/{tenantId}/clear")
    public void clearTenantThrottle(@PathVariable UUID tenantId) {
        abuseSignalService.clearThrottle(tenantId, null, actor());
    }

    /** Read-only view of the currently-effective thresholds/weights (edit via PUT /api/platform/settings/{key}). */
    @GetMapping("/config")
    public AbuseThresholds config() {
        return abuseSignalService.currentThresholds();
    }

    private String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }
}
