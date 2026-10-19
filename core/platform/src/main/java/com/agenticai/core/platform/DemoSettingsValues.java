package com.agenticai.core.platform;

/**
 * One complete set of demo-sandbox knob values - used twice in {@link
 * DemoSettingsResponse}: once for the EFFECTIVE values (what the running demo
 * code would compute right now, i.e. the {@code control_plane.platform_setting}
 * row where one exists) and once for the code FALLBACKS (what those same
 * getters would return if the row were missing).
 *
 * <p>Both are returned deliberately: three of these knobs are seeded by
 * {@code V129__demo_sandbox_admin_controls.sql} to values that differ from the
 * code fallback (per-IP cooldown, per-IP live cap, creations per cooldown - see
 * that migration's header for why), and an operator staring at "1" needs to be
 * able to see that the code would say "2" without a redeploy conversation.
 *
 * @param enabled {@code demo.enabled} - master kill switch for the whole
 *     no-signup surface.
 * @param maxLiveSessionsPerIp {@code demo.max-live-sessions-per-ip} - standing
 *     footprint cap: how many unexpired sandboxes one IP may hold at once.
 * @param cooldownMinutes {@code demo.rate-limit.window-minutes} - the per-IP
 *     creation cooldown window.
 * @param maxSessionsPerCooldown {@code demo.rate-limit.max-attempts} - how many
 *     sandboxes one IP may create inside that window. Together with {@code
 *     cooldownMinutes} this is the "one demo, then wait an hour" control.
 * @param ttlDays {@code demo.lifetime-days} - sandbox TTL.
 * @param messageLimit {@code demo.message-limit} - chat turns per sandbox.
 * @param reminderHoursBeforeExpiry {@code demo.reminder-hours-before-expiry}.
 * @param imageEnabled {@code demo.image.enabled}.
 * @param imagePerSessionLimit {@code demo.image.per-session-limit}.
 * @param telegramEnabled {@code demo.telegram.enabled} - stored and editable,
 *     but see {@link DemoSettingsResponse#notes()}: no code reads it yet.
 * @param magicEnabled {@code demo.magic.enabled} - kill switch for Magic
 *     Studio (the landing "say it, watch it build" flow AND its public
 *     {@code /w/<shareId>} run pages).
 * @param magicRunCap {@code demo.magic.run-cap} - distinct people allowed to
 *     run one published workflow link, for the link's whole life.
 * @param magicPerIpRuns {@code demo.magic.per-ip-runs} - runs one IP gets on
 *     one link (the "once per person" rule when 1).
 * @param magicPerSessionLimit {@code demo.magic.per-session-limit} - magic
 *     workflows one sandbox may create.
 * @param inactivityTimeoutHours {@code demo.inactivity-timeout-hours}
 *     (migration V134, added 2026-07-26) - how long a sandbox with NO
 *     activity since creation survives before {@code DemoSessionExpiryJob}
 *     retires it. Deliberately shorter than {@code ttlDays}: a never-touched
 *     sandbox gets only this short grace window at creation; real activity
 *     (a chat turn, an image) extends the deadline out to the full
 *     {@code ttlDays} instead (see {@code DemoSession#touchActivity}).
 * @param editableHours {@code demo.editable-hours} (migration V146 era,
 *     added 2026-07-28) - how long after creation a sandbox stays EDITABLE
 *     (chat/image/video). Past this window it flips to view-only until
 *     {@code expiresAt}: the transcript stays reviewable via
 *     {@code GET /api/demo/history}, mutating endpoints refuse softly with a
 *     register nudge (see {@code DemoSessionService#isReadOnly}).
 * @param exemptIps {@code demo.rate-limit.exempt-ips} (added 2026-07-28) -
 *     comma-separated exact IP addresses that bypass BOTH the creation-rate
 *     window and the standing live-sandboxes-per-IP cap (the operator's own
 *     address, QA runners). Read by {@code DemoRateLimitExemptions}; exact
 *     match only, no CIDR - see that class's javadoc for why.
 */
public record DemoSettingsValues(
    boolean enabled,
    int maxLiveSessionsPerIp,
    int cooldownMinutes,
    int maxSessionsPerCooldown,
    int ttlDays,
    int messageLimit,
    int reminderHoursBeforeExpiry,
    boolean imageEnabled,
    int imagePerSessionLimit,
    boolean telegramEnabled,
    boolean magicEnabled,
    int magicRunCap,
    int magicPerIpRuns,
    int magicPerSessionLimit,
    int inactivityTimeoutHours,
    int editableHours,
    String exemptIps
) {
}
