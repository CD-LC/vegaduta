package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The owner-facing control surface for the anonymous demo sandbox - a typed,
 * validated, one-screen view over the handful of {@code
 * control_plane.platform_setting} rows that the demo services already read at
 * runtime.
 *
 * <h2>Why a typed endpoint when a generic settings screen already exists</h2>
 * {@code PlatformController}'s {@code GET|PUT /api/platform/settings} can
 * already edit any of these keys - as free text, with no validation, in an
 * alphabetical list of every knob the platform has. That is fine for a knob an
 * operator touches once a year. It is not fine for the controls in front of an
 * UNAUTHENTICATED endpoint that provisions a Postgres schema per click: a
 * typo'd {@code demo.max-live-sessions-per-ip} of {@code 100} there is accepted
 * silently and is a free schema farm. Everything this class adds over the
 * generic surface is that guard rail - named fields, sane ranges, a 400 that
 * says which field and what range, and a single atomic-looking save.
 *
 * <h2>Keys are the demo code's OWN keys</h2>
 * Every constant below is a key that {@code DemoSessionService},
 * {@code DemoSessionRateLimiter}, {@code DemoSessionExpiryJob} or
 * {@code DemoImageService} already passes to {@link PlatformSettingsService}.
 * Nothing here invents a parallel config namespace, so a change made on this
 * screen takes effect on the very next demo request with no redeploy - that is
 * the whole point, and it is also why this class does NOT cache.
 *
 * <p>The one exception is {@link #KEY_TELEGRAM_ENABLED}, which is stored and
 * editable but not yet read by anything - reported as such in {@link
 * DemoSettingsResponse#notes()} rather than shipped as a toggle that lies.
 *
 * <h2>"One demo per IP, then wait an hour"</h2>
 * Modelled with the two knobs the running rate limiter already reads rather
 * than a new one: {@link #KEY_MAX_PER_COOLDOWN} creations per {@link
 * #KEY_COOLDOWN_MINUTES}-minute window (seeded 1 per 60 by
 * {@code V129__demo_sandbox_admin_controls.sql}), on top of {@link
 * #KEY_MAX_LIVE_PER_IP} standing live sandboxes (seeded 1). The {@code
 * defaults} half of the response reports the CODE fallbacks - which for those
 * three keys are deliberately different from the seeded values - so the
 * operator can always see both numbers.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class DemoSandboxSettingsService {

    static final String KEY_ENABLED = "demo.enabled";
    static final String KEY_MAX_LIVE_PER_IP = "demo.max-live-sessions-per-ip";
    static final String KEY_COOLDOWN_MINUTES = "demo.rate-limit.window-minutes";
    static final String KEY_MAX_PER_COOLDOWN = "demo.rate-limit.max-attempts";
    static final String KEY_TTL_DAYS = "demo.lifetime-days";
    static final String KEY_MESSAGE_LIMIT = "demo.message-limit";
    static final String KEY_REMINDER_HOURS = "demo.reminder-hours-before-expiry";
    static final String KEY_IMAGE_ENABLED = "demo.image.enabled";
    static final String KEY_IMAGE_PER_SESSION_LIMIT = "demo.image.per-session-limit";
    static final String KEY_TELEGRAM_ENABLED = "demo.telegram.enabled";
    // Magic Studio (core/agents/demo/magic/DemoMagicService reads these live).
    static final String KEY_MAGIC_ENABLED = "demo.magic.enabled";
    static final String KEY_MAGIC_RUN_CAP = "demo.magic.run-cap";
    static final String KEY_MAGIC_PER_IP_RUNS = "demo.magic.per-ip-runs";
    static final String KEY_MAGIC_PER_SESSION_LIMIT = "demo.magic.per-session-limit";
    // Activity-based expiry (migration V134, 2026-07-26) - DemoSessionService
    // reads this same key for the SHORT never-touched-yet grace window; real
    // activity extends a sandbox's deadline out to KEY_TTL_DAYS instead.
    static final String KEY_INACTIVITY_TIMEOUT_HOURS = "demo.inactivity-timeout-hours";
    // 24h-editable / view-only split (2026-07-28) - DemoSessionService reads
    // this for isReadOnly(); chat/image/video refuse softly past the window.
    static final String KEY_EDITABLE_HOURS = "demo.editable-hours";
    // IP allowlist (2026-07-28) - DemoRateLimitExemptions reads this same key
    // from both DemoSessionRateLimiter (creation rate) and DemoSessionService
    // (standing live-per-IP cap). Comma-separated exact addresses.
    static final String KEY_EXEMPT_IPS = "demo.rate-limit.exempt-ips";

    /**
     * Field name (as it appears in {@link DemoSettingsValues}) to the backing
     * setting key, so the console can show an operator exactly which row each
     * control writes.
     */
    private static final Map<String, String> SETTING_KEYS = buildSettingKeys();

    private static Map<String, String> buildSettingKeys() {
        // LinkedHashMap, not Map.ofEntries: this is serialised straight into
        // the API response and rendered as a list, and Map.of's iteration order
        // is deliberately randomised per JVM run - which would reshuffle the
        // console's "which key does this write" list on every core restart.
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("enabled", KEY_ENABLED);
        keys.put("maxLiveSessionsPerIp", KEY_MAX_LIVE_PER_IP);
        keys.put("cooldownMinutes", KEY_COOLDOWN_MINUTES);
        keys.put("maxSessionsPerCooldown", KEY_MAX_PER_COOLDOWN);
        keys.put("ttlDays", KEY_TTL_DAYS);
        keys.put("messageLimit", KEY_MESSAGE_LIMIT);
        keys.put("reminderHoursBeforeExpiry", KEY_REMINDER_HOURS);
        keys.put("imageEnabled", KEY_IMAGE_ENABLED);
        keys.put("imagePerSessionLimit", KEY_IMAGE_PER_SESSION_LIMIT);
        keys.put("telegramEnabled", KEY_TELEGRAM_ENABLED);
        keys.put("magicEnabled", KEY_MAGIC_ENABLED);
        keys.put("magicRunCap", KEY_MAGIC_RUN_CAP);
        keys.put("magicPerIpRuns", KEY_MAGIC_PER_IP_RUNS);
        keys.put("magicPerSessionLimit", KEY_MAGIC_PER_SESSION_LIMIT);
        keys.put("inactivityTimeoutHours", KEY_INACTIVITY_TIMEOUT_HOURS);
        keys.put("editableHours", KEY_EDITABLE_HOURS);
        keys.put("exemptIps", KEY_EXEMPT_IPS);
        return Collections.unmodifiableMap(keys);
    }

    // NOTE (corrected 2026-07-25): an earlier revision of this list warned that
    // demo.telegram.enabled was stored but unread. That was written against a
    // half-landed working tree and is NO LONGER TRUE - the gate exists in
    // DemoTelegramBot#isAvailable() (core/omnichannel/demo), which ANDs
    // platformSettings.getBoolean("demo.telegram.enabled", true) with the
    // bot-token/username checks, and DemoChannelController consults
    // isAvailable() on both the pair and status paths. Verified by reading
    // those call sites. Do not re-add the stale caveat without re-checking.
    private static final List<String> NOTES = List.of(
        "Every control on this screen is read live by the demo services on the next request - "
            + "no restart, no redeploy.",
        "demo.telegram.enabled only hides/disables the Telegram bridge. The bridge additionally "
            + "requires app.demo.telegram.bot-token to be configured; without a bot token the "
            + "affordance stays hidden regardless of this toggle.",
        "The Magic Studio controls gate the landing's 'say it, watch it build' flow and every "
            + "published /w/<shareId> run page: magicRunCap distinct people per link, "
            + "magicPerIpRuns runs each (1 = once per person), magicPerSessionLimit workflows "
            + "per sandbox. Each run additionally spends the owner sandbox's own message/image "
            + "allowance and the FREE-tier platform budgets - these caps sit on top, not instead.",
        "inactivityTimeoutHours (added 2026-07-26) is the grace window for a sandbox with NO "
            + "activity since it was created - a visitor who starts a sandbox and never sends a "
            + "message retires after this many hours. The FIRST real activity (a chat turn, an "
            + "image) extends that sandbox's deadline out to the full ttlDays instead, and every "
            + "activity after that keeps sliding it forward. 'Retire' means SUSPENDED, not "
            + "deleted - the schema and all its data stay on disk so a visitor who registers "
            + "later can still get it back. Reclaiming disk for long-suspended sandboxes is a "
            + "deliberately manual operator step, not automated, so a bug in this bookkeeping can "
            + "never itself delete a visitor's data.",
        "editableHours (added 2026-07-28) splits a sandbox's life in two: EDITABLE (chat, images, "
            + "video) for this many hours after creation, then VIEW-ONLY until it expires - the "
            + "visitor can still open /try and review their whole transcript (served durably from "
            + "control_plane.demo_chat_message), but every mutating action refuses with a "
            + "register-to-continue nudge. Set it equal to ttlDays*24 to effectively disable the "
            + "view-only phase."
    );

    private final PlatformSettingsService settings;

    // Fallbacks below MIRROR core/tenancy/demo's own @Value defaults exactly
    // (DemoSessionService, DemoSessionRateLimiter, DemoSessionExpiryJob,
    // DemoImageService) and are bound to the SAME app.demo.* property names, so
    // if an operator ever overrides one in application.yml both this screen and
    // the demo code move together. The "effective" half of the response must
    // never be able to disagree with what the demo code would actually compute,
    // which is exactly what a hand-copied literal here would eventually do.
    //
    // Two exceptions, both harmless: app.demo.enabled and
    // app.demo.telegram.enabled are not bound anywhere in core/tenancy/demo
    // (DemoSessionService hardcodes `true` as its getBoolean fallback, and
    // nothing reads the telegram key at all yet), so the `:true` defaults here
    // match those hardcoded fallbacks and the property is simply unused today.
    private final boolean defaultEnabled;
    private final int defaultMaxLivePerIp;
    private final int defaultCooldownMinutes;
    private final int defaultMaxPerCooldown;
    private final int defaultTtlDays;
    private final int defaultMessageLimit;
    private final int defaultReminderHours;
    private final boolean defaultImageEnabled;
    private final int defaultImagePerSessionLimit;
    private final boolean defaultTelegramEnabled;
    // Magic Studio fallbacks. DemoMagicService passes these same literals as
    // its getInt/getBoolean fallbacks (hardcoded there, bound to app.demo.magic.*
    // here) - if you change one side, change the other, or the "defaults" half
    // of this screen will report numbers the magic code doesn't actually use.
    private final boolean defaultMagicEnabled;
    private final int defaultMagicRunCap;
    private final int defaultMagicPerIpRuns;
    private final int defaultMagicPerSessionLimit;
    private final int defaultInactivityTimeoutHours;
    private final int defaultEditableHours;
    private final String defaultExemptIps;

    public DemoSandboxSettingsService(
        PlatformSettingsService settings,
        @Value("${app.demo.enabled:true}") boolean defaultEnabled,
        @Value("${app.demo.max-live-sessions-per-ip:2}") int defaultMaxLivePerIp,
        @Value("${app.demo.rate-limit.window-minutes:1440}") int defaultCooldownMinutes,
        @Value("${app.demo.rate-limit.max-attempts:3}") int defaultMaxPerCooldown,
        @Value("${app.demo.lifetime-days:5}") int defaultTtlDays,
        @Value("${app.demo.message-limit:20}") int defaultMessageLimit,
        @Value("${app.demo.reminder-hours-before-expiry:36}") int defaultReminderHours,
        @Value("${app.demo.image.enabled:true}") boolean defaultImageEnabled,
        @Value("${app.demo.image.per-session-limit:2}") int defaultImagePerSessionLimit,
        @Value("${app.demo.telegram.enabled:true}") boolean defaultTelegramEnabled,
        @Value("${app.demo.magic.enabled:true}") boolean defaultMagicEnabled,
        @Value("${app.demo.magic.run-cap:3}") int defaultMagicRunCap,
        @Value("${app.demo.magic.per-ip-runs:1}") int defaultMagicPerIpRuns,
        @Value("${app.demo.magic.per-session-limit:3}") int defaultMagicPerSessionLimit,
        @Value("${app.demo.inactivity-timeout-hours:24}") int defaultInactivityTimeoutHours,
        @Value("${app.demo.editable-hours:24}") int defaultEditableHours,
        @Value("${app.demo.rate-limit.exempt-ips:}") String defaultExemptIps
    ) {
        this.settings = settings;
        this.defaultEnabled = defaultEnabled;
        this.defaultMaxLivePerIp = defaultMaxLivePerIp;
        this.defaultCooldownMinutes = defaultCooldownMinutes;
        this.defaultMaxPerCooldown = defaultMaxPerCooldown;
        this.defaultTtlDays = defaultTtlDays;
        this.defaultMessageLimit = defaultMessageLimit;
        this.defaultReminderHours = defaultReminderHours;
        this.defaultImageEnabled = defaultImageEnabled;
        this.defaultImagePerSessionLimit = defaultImagePerSessionLimit;
        this.defaultTelegramEnabled = defaultTelegramEnabled;
        this.defaultMagicEnabled = defaultMagicEnabled;
        this.defaultMagicRunCap = defaultMagicRunCap;
        this.defaultMagicPerIpRuns = defaultMagicPerIpRuns;
        this.defaultMagicPerSessionLimit = defaultMagicPerSessionLimit;
        this.defaultInactivityTimeoutHours = defaultInactivityTimeoutHours;
        this.defaultEditableHours = defaultEditableHours;
        this.defaultExemptIps = defaultExemptIps == null ? "" : defaultExemptIps;
    }

    public DemoSettingsResponse get() {
        return new DemoSettingsResponse(effective(), defaults(), SETTING_KEYS, NOTES);
    }

    /**
     * Partial update - only non-null fields are written. Validates EVERY
     * supplied field before writing ANY of them, so a request that gets one
     * field wrong leaves the platform exactly as it found it instead of
     * half-applying (there is no transaction spanning ten independent
     * key-value rows, so validating up-front is the only way to get that
     * property).
     *
     * @throws IllegalArgumentException on an out-of-range value - mapped to a
     *     400 with the message intact by {@code GlobalExceptionHandler}.
     */
    public DemoSettingsResponse update(DemoSettingsUpdateRequest request, String updatedBy) {
        if (request == null) {
            throw new IllegalArgumentException("A demo-settings body is required.");
        }
        requireRange("maxLiveSessionsPerIp", request.maxLiveSessionsPerIp(), 1, 50,
            "how many live sandboxes one IP may hold");
        requireRange("cooldownMinutes", request.cooldownMinutes(), 1, 10080,
            "the per-IP creation cooldown window in minutes (10080 = one week)");
        requireRange("maxSessionsPerCooldown", request.maxSessionsPerCooldown(), 1, 100,
            "sandbox creations allowed per IP per cooldown window");
        requireRange("ttlDays", request.ttlDays(), 1, 30,
            "sandbox lifetime in days");
        requireRange("messageLimit", request.messageLimit(), 1, 500,
            "chat turns allowed per sandbox");
        requireRange("reminderHoursBeforeExpiry", request.reminderHoursBeforeExpiry(), 1, 720,
            "hours before expiry the reminder email is sent");
        requireRange("imagePerSessionLimit", request.imagePerSessionLimit(), 0, 100,
            "images one sandbox may generate (0 disables generation without flipping imageEnabled)");
        requireRange("magicRunCap", request.magicRunCap(), 1, 100,
            "distinct people allowed to run one published magic-workflow link");
        requireRange("magicPerIpRuns", request.magicPerIpRuns(), 1, 10,
            "runs one IP gets on one magic-workflow link (1 = once per person)");
        requireRange("magicPerSessionLimit", request.magicPerSessionLimit(), 1, 50,
            "magic workflows one sandbox may create");
        requireRange("inactivityTimeoutHours", request.inactivityTimeoutHours(), 1, 168,
            "hours a never-touched sandbox survives before it's retired (1 week max)");
        requireRange("editableHours", request.editableHours(), 1, 720,
            "hours after creation a sandbox stays editable before flipping to view-only (30 days max)");
        // The one string knob: reject an obviously-wrong paste (a URL, a
        // sentence) up front - a typo'd but IP-shaped entry merely never
        // matches (fails closed), see DemoRateLimitExemptions' javadoc.
        if (request.exemptIps() != null
                && !com.agenticai.core.tenancy.demo.DemoRateLimitExemptions.looksLikeIpList(request.exemptIps())) {
            throw new IllegalArgumentException(
                "exemptIps must be a comma-separated list of IP addresses (IPv4 or IPv6), e.g. "
                    + "\"122.172.81.185, 2401:4900:8813::1\" - got something that doesn't look like one."
            );
        }

        // A reminder that fires after the sandbox is already gone is a silently
        // dead feature, not a config choice - catch it here rather than let an
        // operator wonder why nobody gets the nudge.
        int effectiveTtlDays = request.ttlDays() != null ? request.ttlDays() : effectiveTtlDays();
        int effectiveReminderHours =
            request.reminderHoursBeforeExpiry() != null ? request.reminderHoursBeforeExpiry() : effectiveReminderHours();
        if (effectiveReminderHours >= effectiveTtlDays * 24) {
            throw new IllegalArgumentException(
                "reminderHoursBeforeExpiry (" + effectiveReminderHours + "h) must be less than the sandbox lifetime ("
                    + effectiveTtlDays + " days = " + (effectiveTtlDays * 24) + "h), otherwise the reminder would be due "
                    + "before the sandbox is even created and would never be sent."
            );
        }

        writeBoolean(KEY_ENABLED, request.enabled(), updatedBy);
        writeInt(KEY_MAX_LIVE_PER_IP, request.maxLiveSessionsPerIp(), updatedBy);
        writeInt(KEY_COOLDOWN_MINUTES, request.cooldownMinutes(), updatedBy);
        writeInt(KEY_MAX_PER_COOLDOWN, request.maxSessionsPerCooldown(), updatedBy);
        writeInt(KEY_TTL_DAYS, request.ttlDays(), updatedBy);
        writeInt(KEY_MESSAGE_LIMIT, request.messageLimit(), updatedBy);
        writeInt(KEY_REMINDER_HOURS, request.reminderHoursBeforeExpiry(), updatedBy);
        writeBoolean(KEY_IMAGE_ENABLED, request.imageEnabled(), updatedBy);
        writeInt(KEY_IMAGE_PER_SESSION_LIMIT, request.imagePerSessionLimit(), updatedBy);
        writeBoolean(KEY_TELEGRAM_ENABLED, request.telegramEnabled(), updatedBy);
        writeBoolean(KEY_MAGIC_ENABLED, request.magicEnabled(), updatedBy);
        writeInt(KEY_MAGIC_RUN_CAP, request.magicRunCap(), updatedBy);
        writeInt(KEY_MAGIC_PER_IP_RUNS, request.magicPerIpRuns(), updatedBy);
        writeInt(KEY_MAGIC_PER_SESSION_LIMIT, request.magicPerSessionLimit(), updatedBy);
        writeInt(KEY_INACTIVITY_TIMEOUT_HOURS, request.inactivityTimeoutHours(), updatedBy);
        writeInt(KEY_EDITABLE_HOURS, request.editableHours(), updatedBy);
        // Normalised (trimmed, deduped, sorted) so the stored row is always
        // canonical regardless of how the operator spaced the paste. Unlike
        // the numeric writes, empty string is a real value ("clear the
        // list") - only null means leave-alone, matching the record's javadoc.
        if (request.exemptIps() != null) {
            settings.update(KEY_EXEMPT_IPS,
                com.agenticai.core.tenancy.demo.DemoRateLimitExemptions.normalize(request.exemptIps()), updatedBy);
        }

        return get();
    }

    // ---------------------------------------------------------------------

    private DemoSettingsValues effective() {
        return new DemoSettingsValues(
            settings.getBoolean(KEY_ENABLED, defaultEnabled),
            settings.getInt(KEY_MAX_LIVE_PER_IP, defaultMaxLivePerIp),
            settings.getInt(KEY_COOLDOWN_MINUTES, defaultCooldownMinutes),
            settings.getInt(KEY_MAX_PER_COOLDOWN, defaultMaxPerCooldown),
            effectiveTtlDays(),
            settings.getInt(KEY_MESSAGE_LIMIT, defaultMessageLimit),
            effectiveReminderHours(),
            settings.getBoolean(KEY_IMAGE_ENABLED, defaultImageEnabled),
            settings.getInt(KEY_IMAGE_PER_SESSION_LIMIT, defaultImagePerSessionLimit),
            settings.getBoolean(KEY_TELEGRAM_ENABLED, defaultTelegramEnabled),
            settings.getBoolean(KEY_MAGIC_ENABLED, defaultMagicEnabled),
            settings.getInt(KEY_MAGIC_RUN_CAP, defaultMagicRunCap),
            settings.getInt(KEY_MAGIC_PER_IP_RUNS, defaultMagicPerIpRuns),
            settings.getInt(KEY_MAGIC_PER_SESSION_LIMIT, defaultMagicPerSessionLimit),
            settings.getInt(KEY_INACTIVITY_TIMEOUT_HOURS, defaultInactivityTimeoutHours),
            settings.getInt(KEY_EDITABLE_HOURS, defaultEditableHours),
            settings.getString(KEY_EXEMPT_IPS, defaultExemptIps)
        );
    }

    private DemoSettingsValues defaults() {
        return new DemoSettingsValues(
            defaultEnabled,
            defaultMaxLivePerIp,
            defaultCooldownMinutes,
            defaultMaxPerCooldown,
            defaultTtlDays,
            defaultMessageLimit,
            defaultReminderHours,
            defaultImageEnabled,
            defaultImagePerSessionLimit,
            defaultTelegramEnabled,
            defaultMagicEnabled,
            defaultMagicRunCap,
            defaultMagicPerIpRuns,
            defaultMagicPerSessionLimit,
            defaultInactivityTimeoutHours,
            defaultEditableHours,
            defaultExemptIps
        );
    }

    private int effectiveTtlDays() {
        return settings.getInt(KEY_TTL_DAYS, defaultTtlDays);
    }

    private int effectiveReminderHours() {
        return settings.getInt(KEY_REMINDER_HOURS, defaultReminderHours);
    }

    private void writeInt(String key, Integer value, String updatedBy) {
        if (value != null) {
            settings.update(key, Integer.toString(value), updatedBy);
        }
    }

    private void writeBoolean(String key, Boolean value, String updatedBy) {
        if (value != null) {
            settings.update(key, Boolean.toString(value), updatedBy);
        }
    }

    private static void requireRange(String field, Integer value, int min, int max, String meaning) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                field + " must be between " + min + " and " + max + " (" + meaning + ") - got " + value + "."
            );
        }
    }
}
