package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.reliability.ReliabilityApi.DependenciesResponse;
import com.agenticai.core.platform.reliability.ReliabilityApi.SloResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reliability console's two reads, both platform-admin only through the
 * blanket {@code SecurityConfig} rule {@code /api/platform/** ->
 * ROLE_platform-admin} (like every other platform controller there is
 * deliberately no second check here):
 *
 * <ul>
 *   <li>{@code GET /api/platform/health/dependencies} - per-dependency status,
 *       latency and detail ({@link DependencyHealthService}). Distinct from the
 *       older {@code GET /api/platform/health} passthrough, which stays as is.</li>
 *   <li>{@code GET /api/platform/slo?windowHours=24} - availability, latency,
 *       LLM error rate, job success and error-budget burn ({@link SloService}).</li>
 * </ul>
 *
 * <p>Full paths on the methods, no class-level {@code @RequestMapping}: the two
 * routes share no common base that another controller does not already own
 * ({@code /api/platform} is used by several), and a class-level base here would
 * either collide or hide the real URLs. Thin: bounds and the feature flag are
 * decided in the services ({@link IllegalArgumentException} answers 400, a
 * disabled feature 404). Same profile gate as the other platform controllers.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@Profile("security")
public class ReliabilityHealthController {

    private final DependencyHealthService dependencyHealthService;
    private final SloService sloService;

    public ReliabilityHealthController(DependencyHealthService dependencyHealthService, SloService sloService) {
        this.dependencyHealthService = dependencyHealthService;
        this.sloService = sloService;
    }

    @GetMapping("/api/platform/health/dependencies")
    public DependenciesResponse dependencies() {
        return dependencyHealthService.dependencies();
    }

    @GetMapping("/api/platform/slo")
    public SloResponse slo(@RequestParam(name = "windowHours", defaultValue = "24") int windowHours) {
        return sloService.slo(windowHours);
    }
}
