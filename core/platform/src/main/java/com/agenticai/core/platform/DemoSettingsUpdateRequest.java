package com.agenticai.core.platform;

/**
 * {@code PUT /api/platform/demo-settings} body - a PARTIAL update: every field
 * is a boxed type and {@code null} means "leave this knob alone", so the admin
 * console can save one card without having to round-trip and re-send every
 * other value it happens to be displaying (and so two operators editing
 * different controls don't clobber each other).
 *
 * <p>Ranges are validated in {@link DemoSandboxSettingsService#update} and a
 * bad value is rejected with a 400 naming the field and the allowed range -
 * these knobs gate an unauthenticated, schema-provisioning endpoint, so a
 * fat-fingered {@code 10000} must not silently become the live cap.
 */
public record DemoSettingsUpdateRequest(
    Boolean enabled,
    Integer maxLiveSessionsPerIp,
    Integer cooldownMinutes,
    Integer maxSessionsPerCooldown,
    Integer ttlDays,
    Integer messageLimit,
    Integer reminderHoursBeforeExpiry,
    Boolean imageEnabled,
    Integer imagePerSessionLimit,
    Boolean telegramEnabled,
    Boolean magicEnabled,
    Integer magicRunCap,
    Integer magicPerIpRuns,
    Integer magicPerSessionLimit,
    Integer inactivityTimeoutHours,
    Integer editableHours,
    /**
     * Comma-separated exact-IP allowlist for the demo rate/cap exemptions.
     * Unlike the numeric knobs above, an EMPTY string is a meaningful value
     * ("clear the allowlist"), so only {@code null} means "leave alone".
     */
    String exemptIps
) {
}
