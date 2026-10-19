package com.agenticai.core.tenancy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, any-authenticated-user counterpart to {@code PlatformController}'s admin-only {@code
 * GET /api/platform/settings} - the idle-timeout policy has to be readable by every signed-in
 * consumer (not just a platform-admin), since {@code IdleLogout.tsx} enforces it on every session.
 * Direct feedback (2026-08-02): "system throwing out logged in user, without asking to extend the
 * session... should never force logout if he is not idle, there should be setting in admin
 * platform maintenance to set the default." The admin WRITE side is the existing generic {@code
 * PUT /api/platform/settings/{key}} ({@code session.idle-timeout-seconds}/{@code
 * session.idle-warning-seconds}, seeded by {@code V172__session_idle_timeout_settings.sql}) -
 * this controller is purely the narrow, non-admin-gated read every tenant member needs.
 *
 * <p>Deliberately its own two-field response rather than reusing {@code
 * PlatformController#listSettings}'s full {@code List<PlatformSettingResponse>} shape - that
 * endpoint is {@code ROLE_platform-admin}-gated and returns every operational knob (including
 * ones, like the per-user budget amount, that shouldn't be exposed to an ordinary tenant member);
 * this one is intentionally narrow to just what the client-side idle timer needs.
 *
 * <p>Falls under {@code SecurityConfig}'s default {@code anyRequest().authenticated()} rule - not
 * {@code /api/platform/**} or {@code /api/admin/**} - so no new security rule was needed for this
 * to be reachable by any authenticated user of any tenant.
 */
@RestController
@RequestMapping("/api/session")
public class SessionConfigController {

    private static final String IDLE_TIMEOUT_KEY = "session.idle-timeout-seconds";
    private static final String IDLE_WARNING_KEY = "session.idle-warning-seconds";
    // 8 hours, not the 10 minutes this shipped with. The original 600 was chosen
    // only to preserve a previously hardcoded cutoff (see V172's own comments),
    // never as a deliberate policy - and in practice it signed people out while
    // they were reading a page or away from the desk briefly, which is the
    // complaint that prompted this change. A working day is the intended
    // boundary; the "still there?" countdown handles the edge.
    //
    // 0 disables the idle logout entirely. That relies on the client reading
    // this as a real value rather than coercing it away - see IdleLogout's
    // readSeconds, which replaced a `Number(x) || DEFAULT` that turned an
    // admin's deliberate 0 straight back into the default.
    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 28800;
    private static final int DEFAULT_IDLE_WARNING_SECONDS = 60;

    private final PlatformSettingsService platformSettingsService;

    public SessionConfigController(PlatformSettingsService platformSettingsService) {
        this.platformSettingsService = platformSettingsService;
    }

    public record SessionConfigResponse(int idleTimeoutSeconds, int idleWarningSeconds) {
    }

    @GetMapping("/config")
    public SessionConfigResponse getConfig() {
        int idleTimeoutSeconds = platformSettingsService.getInt(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT_SECONDS);
        int idleWarningSeconds = platformSettingsService.getInt(IDLE_WARNING_KEY, DEFAULT_IDLE_WARNING_SECONDS);
        // Defensive clamp: an admin-misconfigured warning >= timeout would otherwise show the
        // warning immediately (or leave no quiet active period at all) - cap it at half the
        // timeout rather than trusting the stored value blindly.
        int clampedWarningSeconds = Math.min(idleWarningSeconds, Math.max(1, idleTimeoutSeconds / 2));
        return new SessionConfigResponse(idleTimeoutSeconds, clampedWarningSeconds);
    }
}
