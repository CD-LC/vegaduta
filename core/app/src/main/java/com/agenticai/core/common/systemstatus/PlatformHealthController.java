package com.agenticai.core.common.systemstatus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/platform/health} - the Ecosystem Maintenance console's
 * thin passthrough onto the exact same shared-infra health data {@code
 * SystemStatusController} already produces for {@code /api/system/status}.
 *
 * <p>Lives here, alongside {@link SystemStatusController} (same package,
 * same {@code app} module), rather than in {@code com.agenticai.core.platform.PlatformController}
 * - {@link SystemStatusController}/{@link SystemStatusResponse} live in
 * {@code app}, which depends on the {@code platform} module, not the other
 * way around; a compile-time reference from {@code platform} back onto
 * {@code app} would be a circular Maven dependency. Delegating to the exact
 * same injected {@link SystemStatusController} instance keeps this a true
 * passthrough - zero duplicated Kafka/DB/health-contributor logic - even
 * though the URL path (gated by {@code SecurityConfig}'s {@code
 * /api/platform/** -> ROLE_platform-admin} rule, same as every other
 * Ecosystem Maintenance endpoint) lives outside this controller's own module.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformHealthController {

    private final SystemStatusController systemStatusController;

    public PlatformHealthController(SystemStatusController systemStatusController) {
        this.systemStatusController = systemStatusController;
    }

    @GetMapping("/health")
    public SystemStatusResponse health() {
        return systemStatusController.get();
    }
}
