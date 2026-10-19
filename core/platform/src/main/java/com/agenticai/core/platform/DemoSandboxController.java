package com.agenticai.core.platform;

import com.agenticai.core.security.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin controls and analytics for the anonymous "try it without
 * registering" demo sandbox - {@code /api/platform/demo-settings} and
 * {@code /api/platform/demo-metrics}.
 *
 * <p>Gated by the same {@code SecurityConfig} {@code
 * .requestMatchers("/api/platform/**").hasAuthority("ROLE_platform-admin")}
 * wildcard every other controller under this prefix inherits (see {@code
 * PlatformController}'s javadoc for that posture) - no separate authorization
 * check in this class.
 *
 * <p>Its own small controller rather than more methods on {@code
 * PlatformController}, for the reason {@link NewsletterController} states: that
 * class is long-lived and under concurrent edit, and a self-contained feature
 * with its own services gains nothing from living in it.
 */
@RestController
@RequestMapping("/api/platform")
public class DemoSandboxController {

    private final DemoSandboxSettingsService settingsService;
    private final DemoSandboxMetricsService metricsService;

    public DemoSandboxController(
        DemoSandboxSettingsService settingsService,
        DemoSandboxMetricsService metricsService
    ) {
        this.settingsService = settingsService;
        this.metricsService = metricsService;
    }

    /** Current effective knob values plus the code fallbacks - see {@link DemoSettingsResponse}. */
    @GetMapping("/demo-settings")
    public DemoSettingsResponse getDemoSettings() {
        return settingsService.get();
    }

    /**
     * Partial update; validated. Returns the full re-read state so the console
     * renders what the platform actually stored rather than what it hoped it
     * had sent.
     */
    @PutMapping("/demo-settings")
    public DemoSettingsResponse updateDemoSettings(@RequestBody DemoSettingsUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return settingsService.update(request, updatedBy);
    }

    /**
     * The demo funnel - created / live / converted / conversion rate, plus a
     * daily series and the recent-sandbox operator table.
     *
     * @param days length of the daily series. Clamped rather than rejected: a
     *     silly {@code ?days=100000} from a hand-edited URL should give the
     *     operator a chart, not a 400 - there is no correctness risk in a
     *     read-only window, unlike the settings above where clamping would
     *     silently store something the operator didn't ask for.
     * @param limit rows in the recent table, clamped the same way.
     */
    @GetMapping("/demo-metrics")
    public DemoMetricsResponse getDemoMetrics(
        @RequestParam(name = "days", defaultValue = "30") int days,
        @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return metricsService.metrics(clamp(days, 1, 90), clamp(limit, 1, 200));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
