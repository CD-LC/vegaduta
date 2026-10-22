package com.agenticai.core.platform.fleet;

import com.agenticai.core.platform.fleet.FleetHealthService.FleetHealthResponse;
import com.agenticai.core.platform.fleet.FleetHealthService.FleetSlo;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fleet supervisor surface - {@code GET /api/platform/fleet/health}, gated by the blanket
 * {@code /api/platform/** -> ROLE_platform-admin} rule.
 *
 * <p>One call returns every agent in every ACTIVE non-demo tenant with its windowed
 * traffic/error/spend stats, finished-job success rate, eval-score drift, derived alerts and a
 * single {@code health} verdict (OK / WARN / BREACH / STOPPED) - the fleet-level answer "which
 * agents need an operator right now". The SLO thresholds are query parameters so an on-call
 * operator can widen or tighten the bar without a redeploy.
 *
 * <p>Escalation is deliberately not re-implemented here: a row's {@code tenantId} + {@code
 * tenantEmergencyStopped} pair drives the console's existing per-tenant emergency-stop/resume
 * buttons ({@code POST /api/platform/tenants/{tenantId}/emergency-stop|resume}).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/fleet")
@Profile("security")
public class FleetHealthController {

    private final FleetHealthService fleetHealthService;

    public FleetHealthController(FleetHealthService fleetHealthService) {
        this.fleetHealthService = fleetHealthService;
    }

    @GetMapping("/health")
    public FleetHealthResponse health(
            @RequestParam(defaultValue = "24") int windowHours,
            @RequestParam(defaultValue = "0.10") double maxErrorRate,
            @RequestParam(defaultValue = "0.90") double minJobSuccessRate,
            @RequestParam(defaultValue = "0.05") double evalDriftDrop,
            @RequestParam(defaultValue = "3.0") double spendSpikeRatio,
            @RequestParam(defaultValue = "5") long minCallsForSlo,
            @RequestParam(defaultValue = "1.0") double minSpendUsd) {
        if (windowHours <= 0 || windowHours > 24 * 30) {
            throw new IllegalArgumentException("windowHours must be 1..720");
        }
        FleetSlo slo =
                new FleetSlo(
                        windowHours,
                        maxErrorRate,
                        minJobSuccessRate,
                        evalDriftDrop,
                        spendSpikeRatio,
                        minCallsForSlo,
                        minSpendUsd);
        return fleetHealthService.health(slo);
    }
}
