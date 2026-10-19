package com.agenticai.core.platform.northstar;

import com.agenticai.core.platform.northstar.NorthStarDtos.NorthStarResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/platform/metrics/north-star?weeks=12} - the platform owner's
 * north-star metrics. Gated by the blanket {@code SecurityConfig} rule
 * {@code /api/platform/** -> ROLE_platform-admin}; like the other platform
 * controllers there is deliberately no second authorization check here. Thin:
 * parameter bounds and the feature flag live in {@link NorthStarService}
 * ({@link IllegalArgumentException} answers 400, a disabled feature 404).
 * The result is aggregates only and is cached for an hour.
 */
@RestController
@RequestMapping("/api/platform/metrics/north-star")
@Profile("security")
public class NorthStarController {

    private final NorthStarService service;

    public NorthStarController(NorthStarService service) {
        this.service = service;
    }

    @GetMapping
    public NorthStarResponse northStar(@RequestParam(name = "weeks", defaultValue = "12") int weeks) {
        return service.northStar(weeks);
    }
}
